package org.subsound.ui.components;

import org.gnome.adw.AlertDialog;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationSplitView;
import org.gnome.adw.ResponseAppearance;
import org.gnome.adw.StatusPage;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.Entry;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListTabBehavior;
import org.gnome.gtk.ListView;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.app.state.PlaylistsStore.GPlaylist;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.integration.ServerClient.PlaylistSimple;
import org.subsound.ui.models.GSongStore;
import org.subsound.ui.views.PlaylistListViewV2;
import org.subsound.ui.views.StarredListView;
import org.subsound.utils.Utils;

import java.util.List;
import java.util.Optional;

import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.FILL;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.utils.Utils.cssClasses;
import static org.subsound.utils.Utils.doAsync;

public class PlaylistsListView extends Box {
    private static final Logger log = LoggerFactory.getLogger(PlaylistsListView.class);

    private final AppManager appManager;
    private final ListStore<GPlaylist> listModel;
    private final ListView listView;
    private final NavigationSplitView view;
    private final NavigationPage initialPage;
    private final NavigationPage page1;
    private final StarredListView starredListView;
    private final NavigationPage starredListPage;
    private final PlaylistListViewV2 playlistListView;
    private final NavigationPage playlistPage;
    private final SingleSelection<GPlaylist> selectionModel;
    private final GSongStore songStore;
    private int currentIndex = 0;
    private String currentPlaylistId = null;

    public PlaylistsListView(AppManager appManager) {
        super(Orientation.VERTICAL, 0);
        this.appManager = appManager;
        this.songStore = appManager.getSongStore();
        this.listModel = appManager.getPlaylistsListStore();

        this.starredListView = new StarredListView(appManager.getStarredList(), appManager, appManager::navigateTo);
        this.starredListView.setHalign(Align.FILL);
        this.starredListView.setValign(Align.FILL);
        this.starredListPage = NavigationPage.builder()
                .setTag("starred")
                .setChild(this.starredListView)
                .setTitle("Starred")
                .setHexpand(true)
                .build();

        this.playlistListView = new PlaylistListViewV2(appManager, appManager::navigateTo);
        this.playlistListView.setHalign(Align.FILL);
        this.playlistListView.setValign(Align.FILL);
        this.playlistPage = NavigationPage.builder()
                .setTag("page-2")
                .setChild(this.playlistListView)
                .setTitle("")
                .setHexpand(true)
                .build();

        var b = Box.builder().setValign(Align.CENTER).setHalign(Align.CENTER).build();
        b.append(Label.builder().setLabel("Select a playlist to view").setCssClasses(cssClasses("title-1")).build());
        var statusPage = StatusPage.builder().setChild(b).build();
        this.initialPage = NavigationPage.builder().setTag("page-2-initial").setChild(statusPage).build();

        this.view = NavigationSplitView.builder()
                .setValign(Align.FILL)
                .setHalign(Align.FILL)
                .setHexpand(true)
                .setVexpand(true)
                .build();

        var factory = new SignalListItemFactory();
        factory.onSetup(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setActivatable(true);
            var row = new PlaylistRowWidget(appManager);
            listitem.setChild(row);
        });

        factory.onBind(object -> {
            ListItem listitem = (ListItem) object;
            var item = (GPlaylist) listitem.getItem();
            if (item == null) {
                return;
            }
            var child = listitem.getChild();
            if (child instanceof PlaylistRowWidget row) {
                row.bind(item);
            }
        });

        factory.onUnbind(object -> {
            ListItem listitem = (ListItem) object;
            var child = listitem.getChild();
            if (child instanceof PlaylistRowWidget row) {
                row.unbind();
            }
        });

        factory.onTeardown(object -> {
            ListItem listitem = (ListItem) object;
            listitem.setChild(null);
        });

        this.selectionModel = new SingleSelection<>(this.listModel);
        this.selectionModel.setAutoselect(true);
        this.selectionModel.setCanUnselect(false);

        this.listView = ListView.builder()
                .setShowSeparators(false)
                .setOrientation(VERTICAL)
                .setHexpand(true)
                .setVexpand(true)
                .setHalign(FILL)
                .setValign(FILL)
                .setFocusOnClick(false)
                .setSingleClickActivate(true)
                .setFactory(factory)
                .setModel(selectionModel)
                .setTabBehavior(ListTabBehavior.ITEM)
                .build();

        var activateSignal = this.listView.onActivate(index -> {
            var gPlaylist = this.listModel.getItem(index);
            if (gPlaylist == null) {
                return;
            }
            this.currentIndex = index;
            this.currentPlaylistId = gPlaylist.getId();
            this.selectionModel.setSelected(index);
            var playlist = gPlaylist.getPlaylist();
            log.info("listView.onActivate: {} {}", index, playlist.name());
            this.setSelectedPlaylist(playlist);
        });

        var motionController = new EventControllerMotion();
        motionController.onLeave(() -> {
            this.selectionModel.setSelected(this.currentIndex);
        });
        this.listView.addController(motionController);

        this.listModel.onItemsChanged((position, nRemoved, nAdded) -> {
            // Only act when the net item count decreases (real deletion).
            // nRemoved == nAdded is an in-place update (e.g. song-count refresh) — ignore it.
            if (nRemoved <= nAdded) {
                return;
            }
            int total = this.listModel.getNItems();
            if (total == 0) {
                return;
            }
            // Find where the currently displayed playlist is now (by ID, not index).
            var id = this.currentPlaylistId;
            if (id != null) {
                for (int i = 0; i < total; i++) {
                    var item = this.listModel.getItem(i);
                    if (item != null && id.equals(item.getId())) {
                        // Still present — just keep currentIndex in sync.
                        this.currentIndex = i;
                        return;
                    }
                }
            }
            // Current playlist was removed: select next, or last if at end.
            int newIndex = Math.min((int) position, total - 1);
            this.currentIndex = newIndex;
            this.selectionModel.setSelected(newIndex);
            var gPlaylist = this.listModel.getItem(newIndex);
            if (gPlaylist != null) {
                this.currentPlaylistId = gPlaylist.getId();
                this.setSelectedPlaylist(gPlaylist.getPlaylist());
            }
        });

        this.onDestroy(() -> {
            activateSignal.disconnect();
        });

        var playlistScrollView = ScrolledWindow.builder()
                .setChild(this.listView)
                .setHexpand(true)
                .setVexpand(true)
                .build();

        var headerLabel = Label.builder()
                .setLabel("Library")
                .setHalign(START)
                .setHexpand(true)
                .setCssClasses(cssClasses("title-3"))
                .build();

        var newPlaylistButton = Button.builder()
                .setIconName(Icons.ListAdd.getIconName())
                .setTooltipText("New playlist")
                .setValign(CENTER)
                .build();
        newPlaylistButton.addCssClass("flat");
        newPlaylistButton.onClicked(() -> showNewPlaylistDialog());

        var headerBox = new Box(HORIZONTAL, 0);
        headerBox.setMarginTop(8);
        headerBox.setMarginBottom(4);
        headerBox.setMarginStart(12);
        headerBox.setMarginEnd(4);
        headerBox.append(headerLabel);
        headerBox.append(newPlaylistButton);

        var sidebarBox = new Box(VERTICAL, 0);
        sidebarBox.append(headerBox);
        sidebarBox.append(playlistScrollView);
        sidebarBox.addCssClass(Classes.background.className());
        sidebarBox.addCssClass("library-sidebar");


        this.page1 = NavigationPage.builder()
                .setTag("page-1")
                .setChild(sidebarBox)
                .setTitle("Playlists")
                .build();
        this.view.setSidebar(this.page1);
        this.view.setMaxSidebarWidth(300);
        this.view.setShowContent(true);
        this.view.setHexpand(true);
        this.view.setVexpand(true);
        this.view.setHalign(Align.FILL);
        this.view.setValign(Align.BASELINE_FILL);
        this.view.setContent(this.starredListPage);
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(view);
    }

    public void preselect(String playlistId) {
        for (int i = 0; i < this.listModel.getNItems(); i++) {
            var gPlaylist = this.listModel.getItem(i);
            if (gPlaylist != null && playlistId.equals(gPlaylist.getId())) {
                this.currentIndex = i;
                this.currentPlaylistId = gPlaylist.getId();
                this.selectionModel.setSelected(i);
                this.setSelectedPlaylist(gPlaylist.getPlaylist());
                return;
            }
        }
        log.info("preselect: playlistId={} not found in listModel (size={})", playlistId, this.listModel.getNItems());
    }

    private void setSelectedPlaylist(PlaylistSimple playlist) {
        doAsync(() -> switch (playlist.kind()) {
            case NORMAL -> Optional.of(this.appManager.useClient(cl -> cl.getPlaylist(playlist.id())).songs());
            case STARRED -> Optional.<List<ServerClient.SongInfo>>empty();
            case DOWNLOADED -> {
                var downloads = this.appManager.getDownloadQueue();
                var futures = downloads.stream()
                        .map(d -> Utils.doAsync(() -> this.appManager.useClient(cl -> cl.getSong(d.songId()))))
                        .toList();
                var list = futures.stream().map(java.util.concurrent.CompletableFuture::join).toList();
                yield Optional.of(list);
            }
        }).thenApply(data -> {
            if (playlist.kind() == PlaylistKind.STARRED) {
                var currentPage = this.view.getContent();
                if (currentPage == this.starredListPage) {
                    return data;
                }
                Utils.runOnMainThread(() -> this.view.setContent(this.starredListPage));
                return data;
            }

            if (data.isEmpty()) {
                return Optional.<List<ServerClient.SongInfo>>empty();
            }

            var songs = data.get().stream().map(songStore::newInstance).toList();
            this.playlistListView.setSongs(songs, playlist);
            Utils.runOnMainThread(() -> {
                this.playlistPage.setTitle(playlist.name());
                this.view.setContent(this.playlistPage);
            });
            return data;
        });
    }

    private void showNewPlaylistDialog() {
        var entry = Entry.builder()
                .setPlaceholderText("Playlist name")
                .setActivatesDefault(true)
                .build();

        var dialog = AlertDialog.builder()
                .setTitle("Create Playlist")
                .setBody("Enter a name for the new playlist")
                .build();
        dialog.addResponse("cancel", "_Cancel");
        dialog.addResponse("create", "_Create");
        dialog.setResponseAppearance("create", ResponseAppearance.SUGGESTED);
        dialog.setDefaultResponse("create");
        dialog.setCloseResponse("cancel");
        dialog.setResponseEnabled("create", false);
        dialog.setExtraChild(entry);
        var sig = entry.onChanged(() -> {
            var text = entry.getText();
            text = text == null ? "" : text.strip().trim();
            dialog.setResponseEnabled("create", !text.isBlank());
        });

        dialog.onResponse("", response -> {
            try {
                if ("create".equals(response)) {
                    var name = entry.getText().strip().trim();
                    if (!name.isEmpty()) {
                        appManager.handleAction(new PlayerAction.CreatePlaylist(name, List.of()));
                    }
                }
            } finally {
                sig.disconnect();
            }
        });
        dialog.present(this);
    }

    private static class PlaylistRowWidget extends Box {
        private static final int ICON_SIZE = 48;

        private final AppManager appManager;
        private final Box prefixBox;
        private final RoundedAlbumArt prefixArt;
        private final Image prefixIconDownload;
        private final Image prefixIconStar;
        private final Label titleLabel;
        private final Label subtitleLabel;
        private GPlaylist gPlaylist;
        private SignalConnection<NotifyCallback> notifySignal;

        public PlaylistRowWidget(AppManager appManager) {
            super(HORIZONTAL, 12);
            this.appManager = appManager;

            this.setMarginTop(8);
            this.setMarginBottom(8);
            this.setMarginStart(12);
            this.setMarginEnd(12);

            // Prefix box for icon/cover art
            this.prefixBox = new Box(HORIZONTAL, 0);
            this.prefixBox.setHalign(CENTER);
            this.prefixBox.setValign(CENTER);
            this.prefixBox.setSizeRequest(ICON_SIZE, ICON_SIZE);

            // Create all three prefix widgets once; toggle visibility
            this.prefixArt = new RoundedAlbumArt(Optional.empty(), appManager, ICON_SIZE);
            this.prefixArt.setVisible(false);
            this.prefixArt.setHalign(CENTER);
            this.prefixArt.setValign(CENTER);

            this.prefixIconDownload = Image.fromIconName(Icons.FolderDownload.getIconName());
            this.prefixIconDownload.setPixelSize(24);
            this.prefixIconDownload.setHalign(CENTER);
            this.prefixIconDownload.setValign(CENTER);
            this.prefixIconDownload.setSizeRequest(ICON_SIZE, ICON_SIZE);
            this.prefixIconDownload.setVisible(false);

            this.prefixIconStar = Image.fromIconName(Icons.Starred.getIconName());
            this.prefixIconStar.setPixelSize(24);
            this.prefixIconStar.setHalign(CENTER);
            this.prefixIconStar.setValign(CENTER);
            this.prefixIconStar.setSizeRequest(ICON_SIZE, ICON_SIZE);
            this.prefixIconStar.addCssClass(Classes.starred.className());
            this.prefixIconStar.setVisible(false);

            this.prefixBox.append(prefixArt);
            this.prefixBox.append(prefixIconDownload);
            this.prefixBox.append(prefixIconStar);

            // Content box for title and subtitle
            var contentBox = new Box(VERTICAL, 2);
            contentBox.setHalign(START);
            contentBox.setValign(CENTER);
            contentBox.setHexpand(true);

            this.titleLabel = Label.builder()
                    .setLabel("")
                    .setHalign(START)
                    .setXalign(0)
                    .build();
            this.titleLabel.setSingleLineMode(true);
            this.titleLabel.setEllipsize(EllipsizeMode.END);

            this.subtitleLabel = Label.builder()
                    .setLabel("")
                    .setHalign(START)
                    .setXalign(0)
                    .setCssClasses(cssClasses(Classes.labelDim.className(), Classes.caption.className()))
                    .build();
            this.subtitleLabel.setSingleLineMode(true);

            contentBox.append(titleLabel);
            contentBox.append(subtitleLabel);

            this.append(prefixBox);
            this.append(contentBox);
        }

        public void bind(GPlaylist gPlaylist) {
            this.gPlaylist = gPlaylist;
            updateFromPlaylist(this.gPlaylist.getPlaylist());

            // Connect to notify signal to update when playlist data changes
            this.notifySignal = this.gPlaylist.onNotify("name", _ -> {
                log.info("gPlaylist.onNotify: name {}", this.gPlaylist.getPlaylist());
                updateFromPlaylist(this.gPlaylist.getPlaylist());
            });
        }

        private void updateFromPlaylist(PlaylistSimple playlist) {
            this.titleLabel.setLabel(playlist.name());
            this.subtitleLabel.setLabel(playlist.songCount() + " items");

            if (playlist.kind() == PlaylistKind.DOWNLOADED) {
                this.prefixArt.setVisible(false);
                this.prefixIconStar.setVisible(false);
                this.prefixIconDownload.setVisible(true);
            } else if (playlist.kind() == PlaylistKind.STARRED) {
                this.prefixArt.setVisible(false);
                this.prefixIconDownload.setVisible(false);
                this.prefixIconStar.setVisible(true);
            } else {
                this.prefixIconDownload.setVisible(false);
                this.prefixIconStar.setVisible(false);
                this.prefixArt.setVisible(true);
                this.prefixArt.update(playlist.coverArtId());
            }
        }

        public void unbind() {
            var notifySig = this.notifySignal;
            if (notifySig != null) {
                notifySig.disconnect();
                this.notifySignal = null;
            }
            this.gPlaylist = null;
        }
    }
}