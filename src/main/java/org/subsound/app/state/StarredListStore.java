package org.subsound.app.state;

import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.ui.models.GSongInfo;
import org.subsound.ui.models.GSongStore;
import org.subsound.utils.Utils;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class StarredListStore {
    private static final Logger log = LoggerFactory.getLogger(StarredListStore.class);

    private final Object lock = new Object();
    private final ListStore<GSongInfo> store;
    private final AppManager appManager;
    private final GSongStore songStore;
    private final ArrayList<String> backingIds = new ArrayList<>();
    private final AtomicBoolean isLoading = new AtomicBoolean(false);

    public StarredListStore(AppManager appManager) {
        this.appManager = appManager;
        this.songStore = appManager.getSongStore();
        this.store = new ListStore<>(GSongInfo.getType());
    }

    public void handleStarred(PlayerAction.Star2 star) {}
    public void handleStarred(PlayerAction.Star star) {}

    public void handleRefresh(PlayerAction.StarRefresh refresh) {
        refreshAsync();
    }

    public record Diff(int total, int removals, int insertions) {}
    // TODO: handle what should happen if we change server_id
    // We should probably expose a clear() function and do refreshAsync with the new server_id
    public CompletableFuture<Diff> refreshAsync() {
        return Utils.doAsync(() -> {
            if (!isLoading.compareAndSet(false, true)) {
                log.info("Ignoring refresh request while loading");
                // we are already loading
                return null;
            }

            try {
                var list = this.appManager.useClient(ServerClient::getStarred);
                synchronized (lock) {
                    var newSongs = list.songs();
                    var diff = mergeRefresh(backingIds, newSongs);

                    var removalIndices = diff.removalIndices();
                    var insertions = diff.insertions();

                    // Apply mutations on main thread
                    Utils.runOnMainThreadFuture(() -> {
                        // Remove backwards to preserve indices
                        for (int i = removalIndices.size() - 1; i >= 0; i--) {
                            store.removeAt(removalIndices.get(i));
                        }
                        // Insert forwards — positions account for previous insertions
                        for (var ins : insertions) {
                            var gSong = this.songStore.newInstance(ins.item());
                            store.insert(ins.position(), gSong);
                        }
                    }).join();


                    // 7. Update backing state
                    backingIds.clear();
                    backingIds.ensureCapacity(newSongs.size());
                    for (var song : newSongs) {
                        backingIds.add(song.id());
                    }
                    log.info("mergeRefresh: total={} removals={} insertions={} updated={}",
                            newSongs.size(),
                            removalIndices.size(),
                            insertions.size(),
                            newSongs.size() - insertions.size()
                    );

                    return new Diff(newSongs.size(), removalIndices.size(), insertions.size());
                }
            } finally {
                isLoading.set(false);
            }
        });
    }

    /**
     * Merges the API response into the ListStore with minimal mutations.
     * Preserves existing GSongInfo instances and their GTK bindings.
     * Must be called under synchronized(lock).
     */
    record Insertion(int position, SongInfo item) {}
    record Differences(
            ArrayList<Integer> removalIndices,
            ArrayList<Insertion> insertions
    ) {}
    private static Differences mergeRefresh(ArrayList<String> backingIds, List<SongInfo> newSongs) {
        var newIds = newSongs.stream().map(SongInfo::id).toList();
        var indexDiff = computeDiff(backingIds, newIds);

        // Get or create GSongInfo instances and update their underlying data
        final Map<String, SongInfo> resolved = new HashMap<>(newSongs.size());
        for (var song : newSongs) {
            resolved.put(song.id(), song);
        }

        var insertions = new ArrayList<Insertion>();
        for (var ins : indexDiff.insertions()) {
            insertions.add(new Insertion(ins.position(), resolved.get(newIds.get(ins.position()))));
        }

        return new Differences(indexDiff.removalIndices(), insertions);
    }

    /**
     * Pure diff computation on ID lists. Given the current ordered list of IDs and the
     * new ordered list, computes the minimal removal indices and insertion positions
     * needed to transform current into new.
     */
    record IndexInsertion(int position) {}
    record IndexDiff(
            ArrayList<Integer> removalIndices,
            ArrayList<IndexInsertion> insertions
    ) {}
    static IndexDiff computeDiff(List<String> currentIds, List<String> newIds) {
        var newIdSet = new HashSet<>(newIds);
        var currentIdSet = new HashSet<>(currentIds);

        // Compute removal indices — IDs in current but not in new
        var removalIndices = new ArrayList<Integer>();
        for (int i = 0; i < currentIds.size(); i++) {
            if (!newIdSet.contains(currentIds.get(i))) {
                removalIndices.add(i);
            }
        }

        // Compute the remaining ID set after removals
        var remainingIdSet = new HashSet<>(currentIdSet);
        for (int i : removalIndices) {
            remainingIdSet.remove(currentIds.get(i));
        }

        // Compute insertions — walk new list in order, record positions for new items
        var insertions = new ArrayList<IndexInsertion>();
        int cursor = 0;
        for (var id : newIds) {
            if (!remainingIdSet.contains(id)) {
                insertions.add(new IndexInsertion(cursor));
            }
            cursor++;
        }

        return new IndexDiff(removalIndices, insertions);
    }

    public void addStarred(SongInfo songInfo) {
        var gSong = songStore.newInstance(songInfo);
        synchronized (lock) {
            gSong.setStarredAt(gSong.getSongInfo().starred().or(() -> Optional.of(Instant.now())));
            backingIds.addFirst(songInfo.id());
            Utils.runOnMainThreadFuture(() -> store.insert(0, gSong)).join();
        }
    }

    public void removeStarred(SongInfo a) {
        synchronized (lock) {
            var indices = new ArrayList<Integer>();
            for (int i = 0; i < backingIds.size(); i++) {
                if (backingIds.get(i).equals(a.id())) {
                    indices.add(i);
                }
            }

            Utils.runOnMainThreadFuture(() -> {
                int count = 0;
                for (int pos : indices) {
                    int adjustedIndex = pos - count;
                    var song = this.store.get(adjustedIndex);
                    song.setStarredAt(Optional.empty());
                    this.store.removeAt(adjustedIndex);
                    count++;
                }
                log.info("removed songId={} from {} positions", a.id(), count);
            }).join();

            // Update backing list (backwards to preserve indices)
            for (int i = indices.size() - 1; i >= 0; i--) {
                backingIds.remove((int) indices.get(i));
            }
        }
    }

    public ListStore<GSongInfo> getStore() {
        return store;
    }

}