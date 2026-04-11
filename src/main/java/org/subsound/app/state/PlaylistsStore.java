package org.subsound.app.state;

import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.integration.ServerClient.PlaylistSimple;
import org.subsound.ui.models.GSongInfo;
import org.subsound.ui.models.GSongStore;
import org.subsound.utils.Utils;
import org.gnome.glib.Type;
import org.gnome.gio.ListStore;
import org.gnome.gobject.GObject;
import org.gnome.gobject.GObject.NotifyCallback;
import org.javagi.gobject.SignalConnection;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;
import org.slf4j.Logger;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class PlaylistsStore {
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(PlaylistsStore.class);

    public static final String STARRED_ID = "starred";
    public static final String DOWNLOADED_ID = "downloaded";

    private final AppManager appManager;
    // metaStore stores a list of playlist metadata, for listing all playlists that exist
    private final ListStore<GPlaylist> metaStore = new ListStore<>(GPlaylist.gtype);
    private final ArrayList<String> backingIds = new ArrayList<>();
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    private final Object lock = new Object();
    private final GSongStore songStore;
    private GPlaylist starredPlaylist;
    private GPlaylist downloadedPlaylist;

    public PlaylistsStore(AppManager appManager) {
        this.appManager = appManager;
        this.songStore = appManager.getSongStore();
    }

    public CompletableFuture<Void> refreshListAsync() {
        return Utils.doAsync(() -> {
            if (!isLoading.compareAndSet(false, true)) {
                log.info("Ignoring refresh request while loading");
                return null;
            }

            try {
                var task1 = Utils.doAsync(() -> this.appManager.useClient(ServerClient::getPlaylists));
                var task2 = Utils.doAsync(() -> this.appManager.useClient(ServerClient::getStarred));
                var serverPlaylists = task1.join();
                var starredList = task2.join();

                synchronized (lock) {
                    // Build the full playlist list with synthetic entries first
                    var allPlaylists = buildPlaylistList(serverPlaylists.playlists(), starredList.songs().size());
                    var diff = mergeRefresh(backingIds, allPlaylists);

                    var removalIndices = diff.removalIndices();
                    var insertions = diff.insertions();

                    // Build lookup map for updating existing items
                    var playlistById = new HashMap<String, PlaylistSimple>(allPlaylists.size());
                    for (var p : allPlaylists) {
                        playlistById.put(p.id(), p);
                    }

                    // Apply mutations on main thread
                    Utils.runOnMainThreadFuture(() -> {
                        // Update existing items with fresh data before removals
                        for (int i = 0; i < metaStore.getNItems(); i++) {
                            var gPlaylist = metaStore.getItem(i);
                            var fresh = playlistById.get(gPlaylist.getId());
                            if (fresh != null) {
                                gPlaylist.setValue(fresh);
                                metaStore.emitItemsChanged(i, 1, 1);
                            }
                        }

                        // Remove backwards to preserve indices
                        for (int i = removalIndices.size() - 1; i >= 0; i--) {
                            metaStore.removeAt(removalIndices.get(i));
                        }
                        // Insert forwards — positions account for previous insertions
                        for (var ins : insertions) {
                            metaStore.insert(ins.position(), ins.item());
                        }
                    }).join();

                    // Capture starred/downloaded playlist references on first load
                    if (starredPlaylist == null && metaStore.getNItems() > 0) {
                        starredPlaylist = metaStore.getItem(0);
                    }
                    if (downloadedPlaylist == null && metaStore.getNItems() > 1) {
                        downloadedPlaylist = metaStore.getItem(1);
                    }

                    // Update backing state
                    backingIds.clear();
                    backingIds.ensureCapacity(allPlaylists.size());
                    for (var p : allPlaylists) {
                        backingIds.add(p.id());
                    }
                    log.info("mergeRefresh: total={} removals={} insertions={}",
                            allPlaylists.size(),
                            removalIndices.size(),
                            insertions.size()
                    );

                    return null;
                }
            } finally {
                isLoading.set(false);
            }
        });
    }

    private List<PlaylistSimple> buildPlaylistList(List<PlaylistSimple> serverPlaylists, int starredCount) {
        var downloadCount = this.appManager.getDownloadQueue().size();

        var starred = new PlaylistSimple(
                STARRED_ID,
                "Starred",
                PlaylistKind.STARRED,
                Optional.empty(),
                starredCount,
                Instant.now(),
                Instant.now()
        );
        var downloaded = new PlaylistSimple(
                DOWNLOADED_ID,
                "Downloaded",
                PlaylistKind.DOWNLOADED,
                Optional.empty(),
                downloadCount,
                Instant.now(),
                Instant.now()
        );

        var result = new ArrayList<PlaylistSimple>(serverPlaylists.size() + 2);
        result.add(starred);
        result.add(downloaded);
        result.addAll(serverPlaylists);
        return result;
    }

    public void addNewPlaylist(PlaylistSimple createdPlaylist) {
        synchronized (lock) {
            Utils.runOnMainThread(() -> {
                // adds the new playlist to the "top" (right below our two fixed entries with Starred and Downloads)
                this.metaStore.insert(2, GPlaylist.newInstance(createdPlaylist));
                this.backingIds.addAll(2, List.of(createdPlaylist.id()));
            });
        }
    }

    public void removePlaylist(String playlistId) {
        synchronized (lock) {
            Utils.runOnMainThread(() -> {
                for (int i = 0; i < metaStore.getNItems(); i++) {
                    if (playlistId.equals(metaStore.getItem(i).getId())) {
                        metaStore.remove(i);
                        backingIds.remove(i);
                        break;
                    }
                }
                GPlaylist.instances.remove(playlistId);
            });
        }
    }

    public void refreshPlaylistAsync(String playlistId) {
        Utils.doAsync(() -> this.appManager.useClient(cl -> cl.getPlaylist(playlistId)))
                .thenAccept(playlist -> {
                    var simple = new PlaylistSimple(
                            playlist.id(),
                            playlist.name(),
                            playlist.kind(),
                            playlist.coverArtId(),
                            playlist.songCount(),
                            playlist.changedAt(),
                            playlist.created()
                    );

                    var gPlaylist = GPlaylist.newInstance(simple);
                    var gSongs = playlist.songs().stream().map(songStore::newInstance).toList();
                    Utils.runOnMainThread(() -> {
                        gPlaylist.setValue(simple);
                        gPlaylist.setSongs(gSongs);
                        // Signal downstream models (FilterListModel, SortListModel) to re-evaluate this item
                        for (int i = 0; i < metaStore.getNItems(); i++) {
                            if (playlistId.equals(metaStore.getItem(i).getId())) {
                                metaStore.emitItemsChanged(i, 1, 1);
                                break;
                            }
                        }
                    });
                });
    }

    public void renamePlaylist(String id, String newName) {
        synchronized (lock) {
            Utils.runOnMainThread(() -> {
                for (int i = 0; i < metaStore.getNItems(); i++) {
                    var gPlaylist = metaStore.getItem(i);
                    if (id.equals(gPlaylist.getId())) {
                        var old = gPlaylist.getPlaylist();
                        gPlaylist.setValue(new PlaylistSimple(
                                old.id(), newName, old.kind(), old.coverArtId(), old.songCount(), Instant.now(), old.created()
                        ));
                        metaStore.emitItemsChanged(i, 1, 1);
                        break;
                    }
                }
            });
        }
    }

    record Insertion(int position, GPlaylist item) {}
    record Differences(
            ArrayList<Integer> removalIndices,
            ArrayList<Insertion> insertions
    ) {}
    private static Differences mergeRefresh(ArrayList<String> backingIds, List<PlaylistSimple> newPlaylists) {
        var newIds = newPlaylists.stream().map(PlaylistSimple::id).toList();
        var indexDiff = StarredListStore.computeDiff(backingIds, newIds);

        final Map<String, GPlaylist> resolved = new HashMap<>(newPlaylists.size());
        for (var p : newPlaylists) {
            resolved.put(p.id(), GPlaylist.newInstance(p));
        }

        var insertions = new ArrayList<Insertion>();
        for (var ins : indexDiff.insertions()) {
            insertions.add(new Insertion(ins.position(), resolved.get(newIds.get(ins.position()))));
        }

        return new Differences(indexDiff.removalIndices(), insertions);
    }

    public void updateDownloadedCount(int count) {
        var sp = this.downloadedPlaylist;
        if (sp == null) {
            return;
        }
        var old = sp.getPlaylist();
        Utils.runOnMainThread(() -> {
            sp.setValue(new PlaylistSimple(
                    old.id(), old.name(), old.kind(), old.coverArtId(), count, old.changedAt(), old.created()
            ));
            for (int i = 0; i < metaStore.getNItems(); i++) {
                if (DOWNLOADED_ID.equals(metaStore.getItem(i).getId())) {
                    metaStore.emitItemsChanged(i, 1, 1);
                    break;
                }
            }
        });
    }

    public void updateStarredCount(int count) {
        var sp = this.starredPlaylist;
        if (sp == null) {
            return;
        }
        var old = sp.getPlaylist();
        Utils.runOnMainThread(() -> {
            sp.setValue(new PlaylistSimple(
                    old.id(), old.name(), old.kind(), old.coverArtId(), count, old.changedAt(), old.created()
            ));
            for (int i = 0; i < metaStore.getNItems(); i++) {
                if (STARRED_ID.equals(metaStore.getItem(i).getId())) {
                    metaStore.emitItemsChanged(i, 1, 1);
                    break;
                }
            }
        });
    }

    public ListStore<GPlaylist> playlistsListStore() {
        return this.metaStore;
    }

    public static class GPlaylist extends GObject {
        public static final Type gtype = Types.register(GPlaylist.class);
        public static final ConcurrentHashMap<String, GPlaylist> instances = new ConcurrentHashMap<>();

        private String id;
        private PlaylistSimple value;
        private List<GSongInfo> songs;

        public GPlaylist(MemorySegment address) {
            super(address);
        }

        public static Type getType() {
            return gtype;
        }

        @Property
        public String getId() {
            return id;
        }
        @Property
        public String getName() {
            return value.name();
        }
        public PlaylistSimple getPlaylist() {
            return value;
        }

        public void setValue(PlaylistSimple value) {
            this.value = value;
            this.notify("name");
        }
        public SignalConnection<NotifyCallback> onChanged(Consumer<GPlaylist> callback) {
            return this.onNotify("name", paramSpec -> {
                callback.accept(this);
            });
        }

        public static GPlaylist newInstance(PlaylistSimple value) {
            return instances.computeIfAbsent(value.id(), key -> {
                GPlaylist obj = GObject.newInstance(gtype);
                obj.id = value.id();
                obj.value = value;
                return obj;
            });
        }

        public void setSongs(List<GSongInfo> gSongs) {
            this.songs = gSongs;
            //this.notify("songs");
        }
    }
}
