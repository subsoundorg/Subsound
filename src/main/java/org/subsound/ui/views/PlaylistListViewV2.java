package org.subsound.ui.views;

import org.gnome.adw.AlertDialog;
import org.gnome.adw.Clamp;
import org.gnome.gio.ListStore;
import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ColumnView;
import org.gnome.gtk.ColumnViewColumn;
import org.gnome.gtk.Entry;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListTabBehavior;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Popover;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Separator;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.SortListModel;
import org.gnome.gtk.Stack;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;
import org.javagi.gobject.types.Types;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.app.state.PlaylistsStore.GPlaylist;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ObjectIdentifier;
import org.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.ui.components.AdwDialogHelper;
import org.subsound.ui.components.AppNavigation;
import org.subsound.ui.components.AppNavigation.AppRoute;
import org.subsound.ui.components.Classes;
import org.subsound.ui.components.ClickLabel;
import org.subsound.ui.components.Icons;
import org.subsound.ui.components.ListItemPlayingIcon;
import org.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import org.subsound.ui.components.RoundedAlbumArt;
import org.subsound.ui.components.SongDownloadStatusIcon;
import org.subsound.ui.components.StarButton;
import org.subsound.ui.models.GSongInfo;
import org.subsound.utils.Utils;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.gnome.adw.ResponseAppearance.DEFAULT;
import static org.gnome.adw.ResponseAppearance.DESTRUCTIVE;
import static org.gnome.adw.ResponseAppearance.SUGGESTED;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.END;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.ui.components.AdwDialogHelper.CANCEL_LABEL_ID;
import static org.subsound.ui.views.AlbumInfoPage.infoLabel;

public class PlaylistListViewV2 extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(PlaylistListViewV2.class);

    private final AppManager appManager;
    private final ColumnView listView;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final ScrolledWindow scroll;
    private final Label titleLabel;
    private final Button reloadButton;
    private final MenuButton menuButton;
    private final AtomicReference<MiniState> prevState;
    private final Consumer<AppRoute> onNavigate;
    private final ListStore<GPlaylistEntry> listModel = new ListStore<>();
    private final SortListModel<GPlaylistEntry> sortModel;
    private final SingleSelection<GPlaylistEntry> selectionModel;
    private final ConcurrentHashMap<String, List<NowPlayingCell>> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<TitleArtistCell>> listenersTitle = new ConcurrentHashMap<>();
    private final AtomicReference<ServerClient.PlaylistSimple> currentPlaylist = new AtomicReference<>();
    private volatile boolean reloadNeeded = false;
    private volatile int lastKnownSongCount = 0;
    @Nullable
    private SignalConnection<?> playlistNotifySignal = null;

    /**
     * Wrapper GObject that pairs a GSongInfo with its original playlist position,
     * enabling a "revert to playlist order" sort without a side-table.
     */
    public static class GPlaylistEntry extends GObject {
        public static final Type gtype = Types.register(GPlaylistEntry.class);

        private GSongInfo gSong;
        private int position;
        private String queueItemId;

        public GPlaylistEntry(MemorySegment address) {
            super(address);
        }

        public static Type getType() {
            return gtype;
        }

        public static String makeQueueItemId(ObjectIdentifier identifier, String songId, int position) {
            return "%s||%s||%s".formatted(identifier.getId(), songId, position);
        }
        public static String makeQueueItemId(String playlistId, String songId, int position) {
            return "%s||%s||%s".formatted(playlistId, songId, position);
        }
        public static GPlaylistEntry of(String playlistId, GSongInfo gSong, int position) {
            var instance = (GPlaylistEntry) GObject.newInstance(getType());
            instance.gSong = gSong;
            instance.position = position;
            instance.queueItemId = makeQueueItemId(playlistId, gSong.getId(), position);
            return instance;
        }

        public @Nullable String getQueueItemId() {
            return queueItemId;
        }

        public GSongInfo gSong() {
            return gSong;
        }

        public SongInfo song() {
            return gSong.getSongInfo();
        }

        public int position() {
            return position;
        }

        public void setPosition(int p) {
            this.position = p;
        }
    }

    public PlaylistListViewV2(
            AppManager appManager,
            Consumer<AppRoute> onNavigate
    ) {
        super(VERTICAL, 0);
        this.appManager = appManager;
        this.onAction = appManager::handleAction;
        this.prevState = new AtomicReference<>(selectState(null, appManager.getState()));
        this.onNavigate = onNavigate;
        this.setHalign(CENTER);
        this.setValign(START);
        this.setHexpand(true);
        this.setVexpand(true);

        // 1. Build ColumnView without model (model set after columns + sorter are wired)
        this.listView = ColumnView.builder()
                .setShowRowSeparators(false)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setFocusOnClick(true)
                .setSingleClickActivate(false)
                .setTabBehavior(ListTabBehavior.ITEM)
                .build();

        // 2. Wire sorting: ColumnView aggregates per-column sorters into one composite sorter
        this.sortModel = new SortListModel<>(this.listModel, this.listView.getSorter());

        // 3. Selection wraps sort model
        this.selectionModel = new SingleSelection<>(this.sortModel);
        this.listView.setModel(this.selectionModel);

        // 4. Build columns with per-column factories and sorters, then append them

        // --- Now-playing / track-number column (60px, sortable by original position) ---
        var nowPlayingFactory = new SignalListItemFactory();
        nowPlayingFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var cell = new NowPlayingCell();
            listItem.setChild(cell);
        });
        nowPlayingFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof NowPlayingCell cell) {
                var playingItemId = appManager.getState().queue().playingItemId();
                cell.bind(entry, listItem, prevState.get(), playingItemId);
                var list = listeners.computeIfAbsent(entry.gSong.getId(), key -> new ArrayList<>());
                list.add(cell);
            }
        });
        nowPlayingFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            var child = listItem.getChild();
            if (child instanceof NowPlayingCell cell) {
                cell.unbind();
                if (entry != null) {
                    var list = listeners.get(entry.gSong.getId());
                    if (list != null) list.remove(cell);
                }
            }
        });
        nowPlayingFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            var child = listItem.getChild();
            if (child instanceof NowPlayingCell cell) {
            }
            listItem.setChild(null);
        });

        var orderSorter = new PlaylistEntrySorter(Comparator.comparingInt(GPlaylistEntry::position));
        var nowPlayingCol = new ColumnViewColumn("#", nowPlayingFactory);
        nowPlayingCol.setFixedWidth(60);
        nowPlayingCol.setSorter(orderSorter);
        this.listView.appendColumn(nowPlayingCol);

        // --- Album art column (44px, no sorter) ---
        var artFactory = new SignalListItemFactory();
        artFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(new AlbumArtCell(appManager));
        });
        artFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof AlbumArtCell cell) {
                cell.bind(entry.gSong());
            }
        });
        artFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });
        var artCol = new ColumnViewColumn("", artFactory);
        artCol.setFixedWidth(60);
        this.listView.appendColumn(artCol);

        // --- Title column (expand, sortable by title) ---
        var titleFactory = new SignalListItemFactory();
        titleFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var cell = new TitleArtistCell(this.onNavigate);
            listItem.setChild(cell);
        });
        titleFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof TitleArtistCell cell) {
                var playingItemId = appManager.getState().queue().playingItemId();
                cell.bind(entry, listItem, playingItemId);
                var list = listenersTitle.computeIfAbsent(entry.gSong.getId(), key -> new ArrayList<>());
                list.add(cell);
            }
        });
        titleFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            var child = listItem.getChild();
            if (child instanceof TitleArtistCell cell) {
                cell.unbind();
                if (entry != null) {
                    var list = listenersTitle.get(entry.gSong.getId());
                    if (list != null) list.remove(cell);
                }
            }
        });
        titleFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var titleSorter = new PlaylistEntrySorter((a, b) -> {
            return a.gSong().getTitle().compareToIgnoreCase(b.gSong().getTitle());
        });
        var titleCol = new ColumnViewColumn("Title", titleFactory);
        titleCol.setExpand(true);
        titleCol.setSorter(titleSorter);
        this.listView.appendColumn(titleCol);

        // --- Album column (200px, sortable by album) ---
        var albumFactory = new SignalListItemFactory();
        albumFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var cell = new AlbumCell(this.onNavigate);
            listItem.setChild(cell);
        });
        albumFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof AlbumCell cell) {
                cell.bind(listItem, entry);
            }
        });
        albumFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            if (listItem.getChild() instanceof AlbumCell cell) {
                cell.unbind();
            }
        });
        albumFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var albumSorter = new PlaylistEntrySorter((a, b) -> {
            var albumA = a.song().album();
            var albumB = b.song().album();
            albumA = albumA == null ? "" : albumA;
            albumB = albumB == null ? "" : albumB;
            return albumA.compareToIgnoreCase(albumB);
        });
        var albumCol = new ColumnViewColumn("Album", albumFactory);
        albumCol.setFixedWidth(200);
        albumCol.setSorter(albumSorter);
        this.listView.appendColumn(albumCol);

        // --- Duration column (80px, sortable by duration millis) ---
        var durationFactory = new SignalListItemFactory();
        durationFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            var label = infoLabel("", Classes.labelDim.add(Classes.labelNumeric));
            label.setHalign(END);
            label.setMarginEnd(8);
            listItem.setChild(label);
        });
        durationFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof Label label) {
                label.setLabel(Utils.formatDurationShort(entry.song().duration()));
            }
        });
        durationFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var durationSorter = new PlaylistEntrySorter(Comparator.comparingLong(a -> a.song().duration().toMillis()));
        var durationCol = new ColumnViewColumn("Duration", durationFactory);
        durationCol.setFixedWidth(80);
        durationCol.setSorter(durationSorter);
        this.listView.appendColumn(durationCol);

        // --- Actions column (star + menu button, sortable by starred presence) ---
        var starFactory = new SignalListItemFactory();
        starFactory.onSetup(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(new ActionCell(this.onAction));
        });
        starFactory.onBind(obj -> {
            var listItem = (ListItem) obj;
            var entry = (GPlaylistEntry) listItem.getItem();
            if (entry == null) {
                return;
            }
            var child = listItem.getChild();
            if (child instanceof ActionCell cell) {
                cell.bind(listItem, entry);
            }
        });
        starFactory.onUnbind(obj -> {
            var listItem = (ListItem) obj;
            var child = listItem.getChild();
            if (child instanceof ActionCell cell) {
                cell.unbind();
            }
        });
        starFactory.onTeardown(obj -> {
            var listItem = (ListItem) obj;
            listItem.setChild(null);
        });

        var starSorter = new PlaylistEntrySorter((a, b) -> {
            var sa = a.song().starred().isPresent();
            var sb = b.song().starred().isPresent();
            return Boolean.compare(sb, sa);
        });
        var starCol = new ColumnViewColumn("★", starFactory);
        starCol.setFixedWidth(120);
        starCol.setSorter(starSorter);
        this.listView.appendColumn(starCol);

        // Activate: play in sorted order so next/prev respect current sort.
        // Assign a UUID to every entry in the current sorted view so highlighting
        // survives re-sort and shuffle.
        var activateSignal = this.listView.onActivate(index -> {
            var playlist = this.currentPlaylist.get();
            if (playlist == null) {
                return;
            }
            var entry = this.sortModel.getItem(index);
            if (entry == null) {
                return;
            }
            log.info("listView.onActivate: {} {}", index, entry.song().title());
            int n = (int) this.sortModel.getNItems();
            var slots = new ArrayList<PlayerAction.QueueSlot>(n);
            for (int i = 0; i < n; i++) {
                var e = this.sortModel.getItem(i);
                slots.add(new PlayerAction.QueueSlot(e.getQueueItemId(), e.song()));
            }
            var playContext = new PlaylistIdentifier(playlist.id());
            this.onAction.apply(new PlayerAction.PlayAndReplaceQueue(playContext, slots, index));
        });

        var keyController = new EventControllerKey();
        keyController.setPropagationPhase(PropagationPhase.CAPTURE);
        keyController.onKeyPressed((keyval, keycode, state) -> {
            // GDK_KEY_space = 0x0020
            if (keyval == 0x20) {
                if (appManager.getState().player().state().isPlaying()) {
                    this.onAction.apply(new PlayerAction.Pause());
                } else {
                    this.onAction.apply(new PlayerAction.Play());
                }
                return true;
            }
            if (keyval != 0xFFFF) {
                return false; // GDK_KEY_Delete
            }
            var playlist = currentPlaylist.get();
            if (playlist == null || playlist.kind() != PlaylistKind.NORMAL) {
                return false;
            }
            var entry = selectionModel.getSelectedItem();
            if (entry == null) {
                return false;
            }
            confirmRemoveFromPlaylist(entry);
            return true;
        });
        this.listView.addController(keyController);

        var mapSignal = this.onMap(() -> {
            appManager.addOnStateChanged(this);

            var playlist = this.currentPlaylist.get();
            if (playlist == null) {
                return;
            }

            if (reloadNeeded && playlist.kind() == ServerClient.PlaylistKind.NORMAL) {
                reloadNeeded = false;
                this.refreshCurrentPlaylistAsync();
            }
        });
        var unmapSignal = this.onUnmap(() -> appManager.removeOnStateChanged(this));
        this.onDestroy(() -> {
            log.info("PlaylistListViewV2: onDestroy");
            appManager.removeOnStateChanged(this);
            if (playlistNotifySignal != null) {
                playlistNotifySignal.disconnect();
                playlistNotifySignal = null;
            }
            mapSignal.disconnect();
            unmapSignal.disconnect();
            activateSignal.disconnect();
        });

        this.scroll = ScrolledWindow.builder()
                .setVexpand(true)
                .setHexpand(true)
                .setHalign(Align.FILL)
                .setValign(Align.FILL)
                .setPropagateNaturalWidth(true)
                .setPropagateNaturalHeight(true)
                .build();
        this.scroll.setChild(this.listView);

        this.titleLabel = new Label();
        this.titleLabel.setLabel("");
        this.titleLabel.setHalign(START);
        this.titleLabel.setHexpand(true);
        this.titleLabel.setMaxWidthChars(50);
        this.titleLabel.setEllipsize(EllipsizeMode.END);
        this.titleLabel.setSingleLineMode(true);
        this.titleLabel.addCssClass("title-2");

        this.menuButton = MenuButton.builder()
                .setIconName(Icons.OpenMenu.getIconName())
                .setPopover(buildMenuPopover())
                .setValign(Align.CENTER)
                .setVisible(false)
                .build();
        this.menuButton.addCssClass("flat");
        this.menuButton.addCssClass("circular");
        this.reloadButton = new Button();
        this.reloadButton.setTooltipText("Refresh playlist");
        //this.reloadButton.setLabel("Sync library");
        this.reloadButton.setIconName("view-refresh-symbolic");
        this.reloadButton.addCssClass("flat");
        this.reloadButton.addCssClass("circular");
        this.reloadButton.onClicked(() -> {
            //this.appManager.handleAction(new PlayerAction.RefreshPlaylist());
            this.reloadNeeded = false;
            this.refreshCurrentPlaylistAsync();
        });

        var headerBox = new Box(HORIZONTAL, 0);
        headerBox.setMarginTop(12);
        headerBox.setMarginBottom(8);
        headerBox.setMarginStart(16);
        headerBox.setMarginEnd(8);
        headerBox.append(this.titleLabel);
        headerBox.append(this.reloadButton);
        headerBox.append(this.menuButton);

        this.append(headerBox);
        this.append(this.scroll);
    }

    private CompletableFuture<ServerClient.Playlist> refreshCurrentPlaylistAsync() {
        var songStore = appManager.getSongStore();
        return Utils.doAsync(() -> {
            var playlist = this.currentPlaylist.get();
            if (playlist == null) {
                return null;
            }
            var newPlaylist = appManager
                    .useClient(cl -> cl.getPlaylist(playlist.id()));

            // playlistid changed while loading:
            if (!this.currentPlaylist.get().id().equals(newPlaylist.id())) {
                return newPlaylist;
            }
            var songs = newPlaylist.songs().stream()
                    .map(songStore::newInstance)
                    .toList();
            setSongs(songs, playlist);
            return newPlaylist;
        });
    }

    public void setSongs(List<GSongInfo> songs, ServerClient.PlaylistSimple playlist) {
        this.currentPlaylist.set(playlist);
        this.lastKnownSongCount = playlist.songCount();
        this.reloadNeeded = false;
        var playlistId = playlist.id();
        var items = new GPlaylistEntry[songs.size()];
        for (int i = 0; i < songs.size(); i++) {
            var song = songs.get(i);
            items[i] = GPlaylistEntry.of(playlistId, song, i);
        }
        Utils.runOnMainThread(() -> {
            this.titleLabel.setLabel(playlist.name());
            this.menuButton.setVisible(playlist.kind() == ServerClient.PlaylistKind.NORMAL);
            this.listModel.removeAll();
            this.listModel.splice(0, 0, items);
            // Subscribe to GPlaylist metadata changes for the current playlist
            if (this.playlistNotifySignal != null) {
                this.playlistNotifySignal.disconnect();
                this.playlistNotifySignal = null;
            }

            if (playlist.kind() == ServerClient.PlaylistKind.NORMAL) {
                var store = appManager.getPlaylistsListStore();
                for (int i = 0; i < store.getNItems(); i++) {
                    var gp = store.getItem(i);
                    if (playlist.id().equals(gp.getId())) {
                        this.playlistNotifySignal = gp.onNotify("name", _ -> {
                            if (gp.getPlaylist().songCount() != this.lastKnownSongCount) {
                                this.reloadNeeded = true;
                            }
                        });
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var prev = prevState.get();
        var next = selectState(prev, state);
        if (next == prev) {
            return;
        }
        this.prevState.set(next);
        log.info("PlaylistListViewV2: onStateChanged: {} -> {}", prev, next);
        var prevSongId = prev.songInfo().map(SongInfo::id).orElse("");
        var nextSongId = next.songInfo().map(SongInfo::id).orElse("");
        int prevPos = prev.position().orElse(-1);
        int nextPos = next.position().orElse(-1);
        var playingItemId = state.queue().playingItemId();
        var playingState = next.nowPlayingState();

        if (!prevSongId.equals(nextSongId) || nextPos != prevPos) {
            var from = this.listeners.get(prevSongId);
            if (from != null) {
                for (var l : from) {
                    l.updateCellIsPlaying(false, NowPlayingState.NONE);
                }
            }
            var to = this.listeners.get(nextSongId);
            if (to != null) {
                for (var l : to) {
                    var qid = l.boundEntry != null ? l.boundEntry.getQueueItemId() : null;
                    boolean isPlaying = isPlayingEntry(qid, playingItemId, nextSongId);
                    l.updateCellIsPlaying(isPlaying, playingState);
                }
            }
            var from1 = this.listenersTitle.get(prevSongId);
            if (from1 != null) {
                for (var l : from1) {
                    l.updateRow(false);
                }
            }
            var to1 = this.listenersTitle.get(nextSongId);
            if (to1 != null) {
                for (var l : to1) {
                    var qid = l.boundEntry != null ? l.boundEntry.getQueueItemId() : null;
                    boolean isPlaying = isPlayingEntry(qid, playingItemId, nextSongId);
                    l.updateRow(isPlaying);
                }
            }
        }
        if (prev.nowPlayingState() != next.nowPlayingState()) {
            var to = this.listeners.get(nextSongId);
            if (to != null) {
                for (var l : to) {
                    var qid = l.boundEntry != null ? l.boundEntry.getQueueItemId() : null;
                    boolean isPlaying = isPlayingEntry(qid, playingItemId, nextSongId);
                    l.updateCellIsPlaying(isPlaying, playingState);
                }
            }
        }
    }

    private boolean isPlayingEntry(String entryQueueId, Optional<String> playingItemId, String nextSongId) {
        if (entryQueueId != null) {
            return playingItemId.map(entryQueueId::equals).orElse(false);
        }
        return false;
    }

    public record MiniState(
            Optional<SongInfo> songInfo,
            NowPlayingState nowPlayingState,
            Optional<ObjectIdentifier> playContext,
            Optional<Integer> position
    ){}

    private MiniState selectState(@Nullable MiniState prev, AppManager.AppState state) {
        var npSong = state.nowPlaying().map(AppManager.NowPlaying::song);
        var nowPlayingState = getNowPlayingState(state.player().state());
        if (prev == null) {
            return new MiniState(npSong, nowPlayingState, state.queue().playContext(), state.queue().position());
        }
        var prevSongId = prev.songInfo().map(SongInfo::id).orElse("");
        var nextSongId = npSong.map(SongInfo::id).orElse("");
        var playContext = state.queue().playContext();
        var pos = state.queue().position();
        if (prevSongId.equals(nextSongId) && prev.nowPlayingState() == nowPlayingState && prev.position() == pos) {
            return prev;
        }
        return new MiniState(npSong, nowPlayingState, playContext, pos);
    }

    private NowPlayingState getNowPlayingState(PlaybinPlayer.PlayerStates state) {
        return switch (state) {
            case INIT -> NowPlayingState.NONE;
            case BUFFERING -> NowPlayingState.LOADING;
            case READY -> NowPlayingState.PAUSED;
            case PAUSED -> NowPlayingState.PAUSED;
            case PLAYING -> NowPlayingState.PLAYING;
            case END_OF_STREAM -> NowPlayingState.NONE;
        };
    }

    // ---- Playlist menu ----

    private Popover buildMenuPopover() {
        var popoverBox = Box.builder()
                .setOrientation(VERTICAL)
                .setSpacing(0)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        var renameItem = menuItem("Rename\u2026");
        var downloadAllItem = menuItem("Download all");
        var deleteItem = menuItem("Delete Playlist\u2026");
        deleteItem.addCssClass("destructive-action");

        popoverBox.append(renameItem);
        popoverBox.append(downloadAllItem);
        popoverBox.append(deleteItem);

        var popover = Popover.builder().setChild(popoverBox).build();

        renameItem.onClicked(() -> {
            popover.popdown();
            showRenameDialog();
        });
        downloadAllItem.onClicked(() -> {
            popover.popdown();
            downloadAll();
        });
        deleteItem.onClicked(() -> {
            popover.popdown();
            showDeleteDialog();
        });

        return popover;
    }

    private void downloadAll() {
        int n = listModel.getNItems();
        var songs = new ArrayList<GSongInfo>(n);
        for (int i = 0; i < n; i++) {
            songs.add(listModel.getItem(i).gSong());
        }
        if (!songs.isEmpty()) {
            onAction.apply(new PlayerAction.AddManyToDownloadQueue(songs));
        }
    }

    private void showRenameDialog() {
        var playlist = currentPlaylist.get();
        if (playlist == null) {
            return;
        }

        var entry = Entry.builder()
                .setPlaceholderText("Playlist name")
                .setText(playlist.name())
                .setActivatesDefault(true)
                .build();

        var dialog = AlertDialog.builder()
                .setTitle("Rename Playlist")
                .setBody("Enter a new name for \"%s\"".formatted(playlist.name()))
                .build();
        dialog.addResponse("cancel", "_Cancel");
        dialog.addResponse("rename", "_Rename");
        dialog.setResponseAppearance("rename", SUGGESTED);
        dialog.setDefaultResponse("rename");
        dialog.setCloseResponse("cancel");
        dialog.setResponseEnabled("rename", false);
        dialog.setExtraChild(entry);

        var sig = entry.onChanged(() -> {
            var text = entry.getText();
            text = text == null ? "" : text.strip().trim();
            dialog.setResponseEnabled("rename", !text.isBlank() && !text.equals(playlist.name()));
        });

        dialog.onResponse(
                "", response -> {
                    try {
                        if ("rename".equals(response)) {
                            var name = entry.getText().strip().trim();
                            if (!name.isEmpty()) {
                                onAction.apply(new PlayerAction.RenamePlaylist(playlist.id(), name));
                                Utils.runOnMainThread(() -> this.titleLabel.setLabel(name));
                            }
                        }
                    } finally {
                        sig.disconnect();
                    }
                }
        );
        dialog.present(this);
    }

    private void showDeleteDialog() {
        var playlist = currentPlaylist.get();
        if (playlist == null) {
            return;
        }

        AdwDialogHelper.ofDialog(
                this,
                "Delete Playlist",
                "Delete \"%s\"? This cannot be undone.".formatted(playlist.name()),
                List.of(
                        new AdwDialogHelper.Response(CANCEL_LABEL_ID, "_Cancel", DEFAULT),
                        new AdwDialogHelper.Response("delete", "_Delete", DESTRUCTIVE)
                )
        ).thenAccept(result -> {
            if (!"delete".equals(result.label())) {
                return;
            }
            onAction.apply(new PlayerAction.DeletePlaylist(playlist.id()));
        });
    }

    private void confirmRemoveFromPlaylist(GPlaylistEntry entry) {
        var playlist = currentPlaylist.get();
        if (playlist == null || playlist.kind() != PlaylistKind.NORMAL) {
            return;
        }
        int deletedPos = entry.position();
        AdwDialogHelper.ofDialog(
                PlaylistListViewV2.this,
                "Remove from playlist",
                "Remove \"%s\" from \"%s\"?".formatted(entry.song().title(), playlist.name()),
                List.of(
                        new AdwDialogHelper.Response(CANCEL_LABEL_ID, "_Cancel", DEFAULT),
                        new AdwDialogHelper.Response("delete", "_Remove", DESTRUCTIVE)
                )
        ).thenAccept(result -> {
            if (!"delete".equals(result.label())) {
                return;
            }
            this.onAction.apply(new PlayerAction.RemoveFromPlaylist(
                    entry.song(),
                    deletedPos,
                    playlist.id(),
                    playlist.name()
            ));
            Utils.runOnMainThread(() -> {
                for (int i = 0; i < listModel.getNItems(); i++) {
                    if (listModel.getItem(i) == entry) {
                        listModel.remove(i);
                        break;
                    }
                }
                for (int i = 0; i < listModel.getNItems(); i++) {
                    var e = listModel.getItem(i);
                    if (e.position() > deletedPos) {
                        e.setPosition(e.position() - 1);
                    }
                }
            });
        });
    }

    private Popover buildRowContextMenu(GPlaylistEntry entry) {
        var stack = new Stack();

        var menuContent = Box.builder()
                .setOrientation(VERTICAL)
                .setSpacing(2)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        var menuPopover = new Popover();
        var menuClamp = new Clamp();
        menuClamp.setMaximumSize(220);
        menuClamp.setChild(stack);
        menuPopover.setChild(menuClamp);

        var playlist = currentPlaylist.get();
        var playlistId = playlist != null ? playlist.id() : null;
        var playlistIdentifier = playlistId != null ? new PlaylistIdentifier(playlistId) : null;

        var playMenuItem = menuItem("Play");
        playMenuItem.onClicked(() -> {
            menuPopover.popdown();
            if (playlistIdentifier == null) {
                return;
            }
            int n = sortModel.getNItems();
            var slots = new ArrayList<PlayerAction.QueueSlot>(n);
            for (int i = 0; i < n; i++) {
                var e = sortModel.getItem(i);
                slots.add(new PlayerAction.QueueSlot(e.getQueueItemId(), e.song()));
            }
            // find the index of this entry in the sorted model
            int idx = 0;
            for (int i = 0; i < n; i++) {
                if (sortModel.getItem(i) == entry) {
                    idx = i;
                    break;
                }
            }
            onAction.apply(new PlayerAction.PlayAndReplaceQueue(playlistIdentifier, slots, idx));
        });

        var playNextMenuItem = menuItem("Play Next");
        playNextMenuItem.onClicked(() -> {
            menuPopover.popdown();
            onAction.apply(new PlayerAction.Enqueue(entry.song()));
        });

        var addToQueueMenuItem = menuItem("Add to Queue");
        addToQueueMenuItem.onClicked(() -> {
            menuPopover.popdown();
            onAction.apply(new PlayerAction.EnqueueLast(entry.song()));
        });

        var addToPlaylistMenuItem = menuItem("Add to Playlist\u2026");
        addToPlaylistMenuItem.onClicked(() -> stack.setVisibleChildName("playlists"));

        var downloadMenuItem = menuItem("Download");
        downloadMenuItem.onClicked(() -> {
            menuPopover.popdown();
            onAction.apply(new PlayerAction.AddToDownloadQueue(entry.song()));
        });

        menuContent.append(playMenuItem);
        menuContent.append(playNextMenuItem);
        menuContent.append(addToQueueMenuItem);
        menuContent.append(addToPlaylistMenuItem);
        menuContent.append(downloadMenuItem);

        if (playlist != null && playlist.kind() == PlaylistKind.NORMAL) {
            var removeMenuItem = menuItem("Remove from Playlist");
            removeMenuItem.onClicked(() -> {
                menuPopover.popdown();
                confirmRemoveFromPlaylist(entry);
            });
            menuContent.append(removeMenuItem);
        }

        // Playlist submenu
        var playlistsView = Box.builder()
                .setOrientation(VERTICAL)
                .setSpacing(2)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        var backButton = menuItem("\u2190 Back");
        backButton.onClicked(() -> stack.setVisibleChildName("main"));
        playlistsView.append(backButton);
        playlistsView.append(new Separator(org.gnome.gtk.Orientation.HORIZONTAL));

        var playlists = appManager.getPlaylistsListStore();
        for (int i = 0; i < playlists.getNItems(); i++) {
            GPlaylist gPlaylist = playlists.getItem(i);
            if (gPlaylist.getPlaylist().kind() != PlaylistKind.NORMAL) {
                continue;
            }
            String pid = gPlaylist.getId();
            String pname = gPlaylist.getName();
            var btn = menuItem(pname);
            btn.onClicked(() -> {
                menuPopover.popdown();
                onAction.apply(new PlayerAction.AddToPlaylist(entry.song(), pid, pname));
            });
            playlistsView.append(btn);
        }

        var playlistsScroll = ScrolledWindow.builder()
                .setPropagateNaturalWidth(true)
                .setPropagateNaturalHeight(true)
                .setMaxContentHeight(300)
                .build();
        playlistsScroll.setChild(playlistsView);

        stack.addNamed(menuContent, "main");
        stack.addNamed(playlistsScroll, "playlists");
        stack.setVisibleChildName("main");

        menuPopover.onClosed(() -> stack.setVisibleChildName("main"));

        return menuPopover;
    }

    private static Button menuItem(String label) {
        var button = Button.builder().setLabel(label).build();
        button.addCssClass("flat");
        if (button.getChild() instanceof Label child) {
            child.setHalign(START);
            child.addCssClass("body");
            child.setSingleLineMode(true);
            child.setEllipsize(EllipsizeMode.END);
        }
        return button;
    }

    // ---- Inner Cell Classes ----
    private static class NowPlayingCell extends Box {
        private final Label trackNumberLabel;
        private final ListItemPlayingIcon playingIcon;
        private ListItem listItem;
        private GSongInfo gSong;
        private volatile NowPlayingState playingState = NowPlayingState.NONE;
        private SignalConnection<NotifyCallback> positionSignal;
        private SignalConnection<NotifyCallback> playingSignal;
        private boolean isCurrentlyPlaying;
        @Nullable volatile GPlaylistEntry boundEntry;

        NowPlayingCell() {
            super(HORIZONTAL, 0);
            this.setHalign(FILL);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);

            this.trackNumberLabel = infoLabel("", Classes.labelDim.add(Classes.labelNumeric));
            this.trackNumberLabel.setValign(CENTER);
            this.trackNumberLabel.setHalign(END);
            this.trackNumberLabel.setSingleLineMode(true);
            this.trackNumberLabel.setWidthChars(4);
            this.trackNumberLabel.setMaxWidthChars(4);

            this.playingIcon = new ListItemPlayingIcon(NowPlayingState.NONE, 32);
            this.playingIcon.setVisible(false);
            this.playingIcon.setHalign(CENTER);
            this.playingIcon.setValign(CENTER);

            var overlay = Overlay.builder().setChild(this.trackNumberLabel).build();
            overlay.setHexpand(true);
            overlay.setVexpand(true);
            overlay.addOverlay(this.playingIcon);
            this.append(overlay);
        }

        void bind(GPlaylistEntry entry, ListItem listItem, MiniState state, Optional<String> playingItemId) {
            this.boundEntry = entry;
            this.gSong = entry.gSong();
            this.listItem = listItem;
            this.trackNumberLabel.setLabel("%d".formatted(listItem.getPosition() + 1));
            this.playingSignal = this.gSong.onIsPlayingChanged((v) -> {

            });
            this.positionSignal = listItem.onNotify(
                    "position", _ -> {
                        int pos = listItem.getPosition();
                        Utils.runOnMainThread(() -> this.trackNumberLabel.setLabel("%d".formatted(pos + 1)));
                    }
            );
            String songId = entry.gSong().getSongInfo().id();
            boolean isPlaying = isPlayingEntry(entry.getQueueItemId(), playingItemId, songId);
            updateCellIsPlaying(isPlaying, state.nowPlayingState());
        }

        private static boolean isPlayingEntry(@Nullable String entryQueueId, Optional<String> playingItemId, String songId) {
            if (entryQueueId != null) {
                return playingItemId.map(entryQueueId::equals).orElse(false);
            }
            return false;
        }

        void unbind() {
            this.gSong = null;
            this.listItem = null;
            this.boundEntry = null;
            var sig = positionSignal;
            if (sig != null) {
                sig.disconnect();
            }
            var sig2 = this.playingSignal;
            if (sig2 != null) {
                sig2.disconnect();
            }
        }

        public void updateCellIsPlaying(boolean isPlayingNow, NowPlayingState playingState) {
            this.playingState = playingState;
            this.isCurrentlyPlaying = isPlayingNow;
            Utils.runOnMainThread(() -> {
                var isPlaying = this.isCurrentlyPlaying;
                switch (this.playingState) {
                    case LOADING, PAUSED, PLAYING -> {
                        this.trackNumberLabel.setVisible(!isPlaying);
                        this.playingIcon.setVisible(isPlaying);
                        this.playingIcon.setPlayingState(isPlaying ? this.playingState : NowPlayingState.NONE);
                    }
                    case NONE -> {
                        this.trackNumberLabel.setVisible(true);
                        this.playingIcon.setVisible(false);
                        this.playingIcon.setPlayingState(isPlaying ? this.playingState : NowPlayingState.NONE);
                    }
                }
            });
        }
    }

    private static class TitleArtistCell extends Box {
        private final Label titleLabel;
        private final Consumer<AppRoute> onNavigate;
        private final ClickLabel artistLabel;
        private GSongInfo gSong;
        private volatile NowPlayingState playingState = NowPlayingState.NONE;
        private ListItem listItem;
        private SignalConnection<NotifyCallback> positionSignal;
        private boolean itemAndPositionIsPlaying;
        @Nullable volatile GPlaylistEntry boundEntry;

        TitleArtistCell(Consumer<AppRoute> onNavigate) {
            super(VERTICAL, 2);
            this.onNavigate = onNavigate;
            this.setHalign(FILL);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);
            this.setMarginStart(4);
            this.setMarginEnd(4);

            this.titleLabel = new Label();
            this.titleLabel.setHalign(START);
            this.titleLabel.setEllipsize(EllipsizeMode.END);
            this.titleLabel.setSingleLineMode(true);
            this.titleLabel.setMaxWidthChars(36);

            this.artistLabel = new ClickLabel(
                    "", () -> {
                if (this.gSong == null) {
                    return;
                }
                var artistId = this.gSong.getSongInfo().artistId();
                if (artistId == null) {
                    return;
                }
                this.onNavigate.accept(new AppNavigation.AppRoute.RouteArtistInfo(artistId));
            }
            );
            this.artistLabel.addCssClass(Classes.labelDim.className());
            this.artistLabel.addCssClass(Classes.caption.className());
            this.artistLabel.setHalign(START);
            this.artistLabel.setSingleLineMode(true);
            this.artistLabel.setEllipsize(org.gnome.pango.EllipsizeMode.END);

            var inner = new Box(VERTICAL, 2);
            inner.setVexpand(true);
            inner.setValign(CENTER);
            inner.append(titleLabel);
            inner.append(artistLabel);
            this.append(inner);
        }

        void bind(GPlaylistEntry entry, ListItem listItem, Optional<String> playingItemId) {
            this.gSong = entry.gSong();
            this.listItem = listItem;
            this.boundEntry = entry;
            this.positionSignal = listItem.onNotify("position", (a) -> {
                // position changed; no action needed for title cell
            });
            var info = entry.gSong().getSongInfo();
            this.titleLabel.setLabel(info.title());
            this.artistLabel.setLabel(info.artist() != null ? info.artist() : "");
            boolean isPlaying = isPlayingEntry(entry.getQueueItemId(), playingItemId, info.id());
            updateRow(isPlaying);
        }

        private static boolean isPlayingEntry(@Nullable String entryQueueId, Optional<String> playingItemId, String songId) {
            if (entryQueueId != null) {
                return playingItemId.map(entryQueueId::equals).orElse(false);
            }
            return false;
        }

        void unbind() {
            this.gSong = null;
            this.listItem = null;
            this.boundEntry = null;
            if (this.positionSignal != null) {
                this.positionSignal.disconnect();
            }
            this.titleLabel.removeCssClass(Classes.colorAccent.className());
        }

        public void updateRow(boolean positionIsPlaying) {
            if (this.gSong == null) {
                return;
            }
            this.itemAndPositionIsPlaying = positionIsPlaying;
            Utils.runOnMainThread(() -> {
                if (this.itemAndPositionIsPlaying) {
                    this.titleLabel.addCssClass(Classes.colorAccent.className());
                } else {
                    this.titleLabel.removeCssClass(Classes.colorAccent.className());
                }
            });
        }
    }

    private static class AlbumCell extends Box {
        private final ClickLabel albumLabel;
        private final Consumer<AppRoute> onNavigate;
        @Nullable private GSongInfo gSong;
        @Nullable volatile GPlaylistEntry boundEntry;
        @Nullable volatile ListItem listItem;

        AlbumCell(Consumer<AppRoute> onNavigate) {
            super(HORIZONTAL, 0);
            this.onNavigate = onNavigate;
            this.setHalign(START);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);
            this.setMarginStart(4);
            this.setMarginEnd(4);

            this.albumLabel = new ClickLabel(
                    "", () -> {
                if (this.gSong == null) {
                    return;
                }
                var albumId = this.gSong.getSongInfo().albumId();
                if (albumId == null) {
                    return;
                }
                this.onNavigate.accept(new AppNavigation.AppRoute.RouteAlbumInfo(albumId));
            }
            );
            this.albumLabel.addCssClass(Classes.labelDim.className());
            this.albumLabel.addCssClass(Classes.caption.className());
            this.albumLabel.setSingleLineMode(true);
            this.albumLabel.setEllipsize(org.gnome.pango.EllipsizeMode.END);
            this.append(albumLabel);
        }

        void bind(ListItem listItem, GPlaylistEntry entry) {
            this.listItem = listItem;
            this.boundEntry = entry;
            this.gSong = entry.gSong();

            var album = gSong.getSongInfo().album();
            this.albumLabel.setLabel(album != null ? album : "");
        }

        public void unbind() {
            this.boundEntry = null;
            this.listItem = null;
            this.gSong = null;
            this.albumLabel.setLabel("");
        }
    }

    private class ActionCell extends Box {
        private final SongDownloadStatusIcon downloadIcon;
        private final StarButton starButton;
        private final Button menuButton;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;
        private GSongInfo gSong;
        private final AtomicReference<SignalConnection<?>> favoriteSignal = new AtomicReference<>();
        private final AtomicReference<SignalConnection<?>> downloadSignal = new AtomicReference<>();
        @Nullable volatile GPlaylistEntry boundEntry;
        @Nullable volatile ListItem listItem;

        ActionCell(Function<PlayerAction, CompletableFuture<Void>> onAction) {
            super(HORIZONTAL, 0);
            this.onAction = onAction;
            this.setHalign(FILL);
            this.setValign(FILL);
            this.setHexpand(true);
            this.setVexpand(true);

            this.downloadIcon = new SongDownloadStatusIcon();
            this.downloadIcon.setValign(CENTER);
            this.append(downloadIcon);

            this.starButton = new StarButton(
                    Optional.empty(),
                    newValue -> {
                        if (this.gSong == null) {
                            return CompletableFuture.completedFuture(null);
                        }
                        var action = newValue
                                     ? new PlayerAction.Star(this.gSong.getSongInfo())
                                     : new PlayerAction.Unstar(this.gSong.getSongInfo());
                        return this.onAction.apply(action);
                    }
            );
            this.starButton.setHalign(CENTER);
            this.append(starButton);

            this.menuButton = Button.builder()
                    .setIconName("view-more-symbolic")
                    .setCssClasses(new String[]{"flat", "circular"})
                    .setValign(CENTER)
                    .build();
            this.menuButton.onClicked(() -> {
                var item = this.listItem;
                if (item == null) { return; }
                var entry = this.boundEntry;
                if (entry == null) { return; }
                selectionModel.setSelected(item.getPosition());
                var popover = buildRowContextMenu(entry);
                popover.setParent(this.menuButton);
                popover.onClosed(() -> popover.unparent());
                popover.popup();
            });
            this.append(menuButton);
        }

        void bind(ListItem listItem, GPlaylistEntry entry) {
            this.listItem = listItem;
            this.boundEntry = entry;
            this.gSong = entry.gSong();
            this.starButton.setStarredAt(gSong.getSongInfo().starred());
            this.downloadIcon.updateDownloadState(gSong.getDownloadStateEnum());
            var conn = gSong.onNotify(
                    GSongInfo.Signal.IS_FAVORITE.getId(), _ -> {
                        this.starButton.setStarredAt(this.gSong.getSongInfo().starred());
                    }
            );
            var old = favoriteSignal.getAndSet(conn);
            if (old != null) {
                old.disconnect();
            }
            var dlConn = gSong.onNotify(
                    GSongInfo.Signal.DOWNLOAD_STATE.getId(), _ -> {
                        var g = this.gSong;
                        if (g != null) {
                            this.downloadIcon.updateDownloadState(g.getDownloadStateEnum());
                        }
                    }
            );
            var oldDl = downloadSignal.getAndSet(dlConn);
            if (oldDl != null) {
                oldDl.disconnect();
            }
        }

        void unbind() {
            var sig = favoriteSignal.getAndSet(null);
            if (sig != null) {
                sig.disconnect();
            }
            var dlSig = downloadSignal.getAndSet(null);
            if (dlSig != null) {
                dlSig.disconnect();
            }
            this.boundEntry = null;
            this.listItem = null;
            this.gSong = null;
        }
    }

    private static class AlbumArtCell extends Box {
        private static final int ART_SIZE = 48;
        private final RoundedAlbumArt albumArt;

        AlbumArtCell(AppManager appManager) {
            super(HORIZONTAL, 0);
            this.setHalign(CENTER);
            this.setValign(CENTER);
            this.albumArt = new RoundedAlbumArt(Optional.empty(), appManager, ART_SIZE);
            this.append(albumArt);
        }

        void bind(GSongInfo gSong) {
            this.albumArt.update(gSong.getSongInfo().coverArt());
        }
    }

}