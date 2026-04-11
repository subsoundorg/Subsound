package org.subsound.ui.views;

import org.gnome.adw.Carousel;
import org.gnome.gio.ListStore;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.CustomFilter;
import org.gnome.gtk.FilterListModel;
import org.gnome.gtk.GridView;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListItem;
import org.gnome.gtk.ListTabBehavior;
import org.gnome.gtk.ListView;
import org.gnome.gtk.NoSelection;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PolicyType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.SignalListItemFactory;
import org.gnome.gtk.SingleSelection;
import org.gnome.gtk.SliceListModel;
import org.gnome.gtk.SortListModel;
import org.gnome.gtk.Stack;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gio.ListIndexModel;
import org.javagi.gobject.SignalConnection;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.NetworkMonitoring;
import org.subsound.app.state.PlayerAction;
import org.subsound.app.state.PlaylistsStore.GPlaylist;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ArtistAlbumInfo;
import org.subsound.integration.ServerClient.HomeOverview;
import org.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.ui.components.AlbumFlowBoxChild;
import org.subsound.ui.components.AlbumsFlowBox;
import org.subsound.ui.components.AppNavigation;
import org.subsound.ui.components.AppNavigation.AppRoute.RoutePlaylistsOverview;
import org.subsound.ui.components.BoxHolder;
import org.subsound.ui.components.Classes;
import org.subsound.ui.components.LoadingSpinner;
import org.subsound.ui.components.OverviewAlbumChild;
import org.subsound.ui.components.RefreshButton;
import org.subsound.ui.components.RoundedAlbumArt;
import org.subsound.ui.views.FrontpagePage.FrontpagePageState.Loading;
import org.subsound.utils.Utils;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.END;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.ui.views.ArtistInfoFlowBox.BIG_SPACING;
import static org.subsound.utils.Utils.borderBox;
import static org.subsound.utils.Utils.heading1;

public class FrontpagePage extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(FrontpagePage.class);

    sealed interface FrontpagePageState {
        record Loading() implements FrontpagePageState {}
        record Ready(HomeOverview data, ServerClient.ServerInfo serverInfo, String serverUrl) implements FrontpagePageState {}
        record Error(Throwable t) implements FrontpagePageState {}
    }

    private final ThumbnailCache thumbLoader;
    private final AppManager appManager;
    private final Consumer<ArtistAlbumInfo> onAlbumSelected;
    private final Consumer<AppNavigation.AppRoute> onNavigate;
    private final AtomicReference<FrontpagePageState> state = new AtomicReference<>(new Loading());
    private final AtomicBoolean isMapped = new AtomicBoolean(false);
    private final AtomicReference<NetworkMonitoring.NetworkStatus> lastNetworkStatus = new AtomicReference<>();
    private final AtomicReference<Optional<String>> lastServerId = new AtomicReference<>(Optional.empty());

    private final ScrolledWindow scroll;
    private final Box view;
    private final Stack viewStack;
    private final LoadingSpinner spinner = new LoadingSpinner();
    private final Label errorLabel;
    private final HomeView homeView;

    public FrontpagePage(ThumbnailCache thumbLoader, AppManager appManager, Consumer<ArtistAlbumInfo> onAlbumSelected, Consumer<AppNavigation.AppRoute> onNavigate) {
        super(Orientation.VERTICAL, 0);
        this.onAlbumSelected = onAlbumSelected;
        this.onNavigate = onNavigate;
        this.setHexpand(true);
        this.setVexpand(true);
        this.setHalign(START);
        this.setValign(START);

        this.view = borderBox(Orientation.VERTICAL, BIG_SPACING).setSpacing(BIG_SPACING).setHalign(START).setValign(START).build();
        this.thumbLoader = thumbLoader;
        this.appManager = appManager;
        this.homeView = new HomeView(this.appManager, this.onAlbumSelected, this.onNavigate);
        this.onMap(() -> {
            appManager.addOnStateChanged(this);
            if (this.isMapped.get()) {
                return;
            }
            log.info("FrontpagePage: onMap doLoad");
            isMapped.set(true);
            this.doLoad();
        });
        this.onUnmap(() -> {
            log.info("FrontpagePage: onUnmap");
            appManager.removeOnStateChanged(this);
            //isMapped.set(false);
        });
        //this.onRealize(() -> this.doLoad());

        this.viewStack = Stack.builder().setHhomogeneous(false).setHexpand(true).setVexpand(true).build();
        this.viewStack.setHalign(START);
        this.viewStack.setValign(START);
        this.viewStack.addNamed(spinner, "loading");
        this.errorLabel = Label.builder().build();
        this.viewStack.addNamed(errorLabel, "error");
        this.viewStack.addNamed(homeView, "home");

        var homeBox = borderBox(HORIZONTAL, 0).build();
        homeBox.setHalign(Align.FILL);
        var homeLabel = Label.builder().setLabel("Home").setHalign(START).setCssClasses(Classes.titleLarge.add()).build();
        var reloadButton = new RefreshButton(this::doLoad);
        reloadButton.setHexpand(true);
        reloadButton.setVexpand(true);
        reloadButton.setValign(CENTER);
        reloadButton.setHalign(END);
        homeBox.append(homeLabel);
        homeBox.append(reloadButton);
        this.view.append(homeBox);
        this.view.append(viewStack);
        this.scroll = ScrolledWindow.builder()
                .setVexpand(true)
                .setHscrollbarPolicy(PolicyType.NEVER)
                .setPropagateNaturalHeight(true)
                .setPropagateNaturalWidth(true)
                .setChild(view)
                .build();
        this.append(scroll);
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var prev = lastNetworkStatus.getAndSet(state.networkState().status());
        if (prev == NetworkMonitoring.NetworkStatus.OFFLINE
                && state.networkState().status() == NetworkMonitoring.NetworkStatus.ONLINE) {
            log.info("FrontpagePage: network came online, reloading");
            this.doLoad();
            return;
        }
        var prevServerId = lastServerId.getAndSet(state.serverState().serverId());
        if (!prevServerId.equals(state.serverState().serverId())) {
            log.info("FrontpagePage: server changed, reloading");
            this.doLoad();
        }
    }

    private void doLoad() {
        this.setState(new Loading());
        Utils.doAsync(() -> {
            try {
                var data = this.appManager.useClient(ServerClient::getHomeOverview);
                ServerClient.ServerInfo serverInfo;
                try {
                    serverInfo = this.appManager.useClient(ServerClient::getServerInfo);
                } catch (Exception e) {
                    log.warn("failed to load server info", e);
                    serverInfo = new ServerClient.ServerInfo("?", 0, Optional.empty(), Optional.empty(), Optional.empty());
                }
                var cfg = this.appManager.getConfig().serverConfig;
                var serverUrl = cfg != null ? cfg.url() : "";
                this.setState(new FrontpagePageState.Ready(data, serverInfo, serverUrl));
            } catch (Exception e) {
                this.setState(new FrontpagePageState.Error(e));
            }
        });
    }

    private void setState(FrontpagePageState next) {
        this.state.set(next);
        this.updateView();
    }

    private void updateView() {
        if (!this.isMapped.get()) {
            return;
        }
        Utils.runOnMainThread(() -> {
            var s = this.state.get();
            switch (s) {
                case Loading l -> {
                    this.errorLabel.setLabel("");
                    this.viewStack.setVisibleChildName("loading");
                }
                case FrontpagePageState.Error t -> {
                    this.errorLabel.setLabel(t.t.getMessage());
                    this.viewStack.setVisibleChildName("error");
                }
                case FrontpagePageState.Ready ready -> {
                    this.errorLabel.setLabel("");
                    this.homeView.setData(ready.data(), ready.serverInfo(), ready.serverUrl());
                    this.viewStack.setVisibleChildName("home");
                }
            }
        });
    }

    private static class HomeView extends Box {
        private final AppManager appManager;
        private final Consumer<ArtistAlbumInfo> onAlbumSelected;
        private final Consumer<AppNavigation.AppRoute> onNavigate;

        private HomeOverview data;

        private final Label playlistsLabel = heading1("Playlists").build();
        private final Label recentLabel = heading1("Recently played").build();
        private final Label newLabel = heading1("Recently added releases").build();
        private final Label mostLabel = heading1("Most played").build();
        private final Label yearlyLabel = heading1("Most recent").build();

        private final HorizontalPlaylistsView playlistsView;
        private final BoxHolder<HorizontalAlbumsFlowBoxV3> recentList;
        private final BoxHolder<HorizontalAlbumsFlowBoxV3> newList;
        private final BoxHolder<HorizontalAlbumsFlowBoxV3> mostPlayedList;
        private final BoxHolder<HorizontalAlbumsFlowBoxV3> mostRecentYear;

        public HomeView(AppManager appManager, Consumer<ArtistAlbumInfo> onAlbumSelected, Consumer<AppNavigation.AppRoute> onNavigate) {
            super(Orientation.VERTICAL, BIG_SPACING);
            this.appManager = appManager;
            this.onAlbumSelected = onAlbumSelected;
            this.onNavigate = onNavigate;
            this.setHexpand(true);
            this.setVexpand(true);
            this.setHalign(START);
            this.setValign(START);
            this.playlistsView = new HorizontalPlaylistsView(appManager.getPlaylistsListStore(), appManager, onNavigate);
            this.recentList = holder();
            this.newList = holder();
            this.mostPlayedList = holder();
            this.mostRecentYear = holder();
            this.append(wrap(playlistsLabel, playlistsView));
            this.append(wrap(recentLabel, recentList));
            this.append(wrap(newLabel, newList));
            this.append(wrap(mostLabel, mostPlayedList));
            this.append(wrap(yearlyLabel, mostRecentYear));
        }

        private static Box wrap(Label label, Box widget) {
            var b = Box.builder().setOrientation(VERTICAL).setSpacing(8).build();
            b.append(label);
            b.append(widget);
            return b;
        }

        private <T extends Box> BoxHolder<T> holder() {
            var h = new BoxHolder<T>();
            h.setHexpand(true);
            h.setHalign(START);
            h.setValign(START);
            return h;
        }

        public static class HorizontalAlbumsFlowBoxV1 extends Box {
            private final ScrolledWindow scroll;

            public HorizontalAlbumsFlowBoxV1(AlbumsFlowBox child) {
                super(HORIZONTAL, 0);
                this.setHalign(START);
                this.setHexpand(true);
                this.scroll = ScrolledWindow.builder().setHalign(START).setHexpand(true).build();
                this.scroll.setPropagateNaturalWidth(true);
                this.scroll.setPropagateNaturalHeight(true);
                this.scroll.setChild(child);
                this.append(this.scroll);
            }
        }

        public static class HorizontalAlbumsFlowBoxV2 extends Box {
            private final List<ArtistAlbumInfo> albums;
            private final List<AlbumFlowBoxChild> list;
            private final Carousel carousel;
            private final AppManager thumbLoader;

            public HorizontalAlbumsFlowBoxV2(List<ArtistAlbumInfo> albums, AppManager thumbLoader) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.setHalign(START);
                this.setHexpand(true);
                this.carousel = Carousel.builder().setHexpand(true).build();
                this.carousel.setHalign(START);
                this.carousel.setHexpand(true);
                this.list = this.albums.stream().map(album -> {
                    var item = new AlbumFlowBoxChild(this.thumbLoader, album);
                    return item;
                }).toList();
                for (AlbumFlowBoxChild albumFlowBoxChild : list) {
                    this.carousel.append(albumFlowBoxChild);
                }
                this.append(this.carousel);
            }
        }

        public static class HorizontalAlbumsFlowBoxV3 extends Box {
            private final List<ArtistAlbumInfo> albums;
            private final List<OverviewAlbumChild> list;
            //private final List<AlbumFlowBoxChild> list;
            private final Box carousel;
            private final AppManager thumbLoader;
            private final ScrolledWindow scroll;
            private final Function<PlayerAction, CompletableFuture<Void>> onAction;
            private final Consumer<ArtistAlbumInfo> onAlbumSelected;

            public HorizontalAlbumsFlowBoxV3(
                    List<ArtistAlbumInfo> albums,
                    AppManager thumbLoader,
                    Function<PlayerAction, CompletableFuture<Void>> onAction,
                    Consumer<ArtistAlbumInfo> onAlbumSelected
            ) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.onAction = onAction;
                this.onAlbumSelected = onAlbumSelected;
                this.setHalign(START);
                this.setHexpand(true);
                this.carousel = Box.builder()
                        .setOrientation(HORIZONTAL)
                        .setHexpand(true)
                        .setHalign(START)
                        .setValign(START)
                        .setSpacing(BIG_SPACING / 2)
                        .build();
                this.list = this.albums.stream().map(album -> {
//                    var item = new AlbumFlowBoxChild(this.thumbLoader, album);
                    var item = new OverviewAlbumChild(this.thumbLoader, this.onAlbumSelected);
                    item.setAlbumInfo(album);
                    return item;
                }).toList();
                for (var albumFlowBoxChild : list) {
                    this.carousel.append(albumFlowBoxChild);
                }
                this.scroll = ScrolledWindow.builder()
                        .setHexpand(true)
                        .setVexpand(true)
                        .setPropagateNaturalHeight(true)
                        .setPropagateNaturalWidth(true)
                        .setVscrollbarPolicy(PolicyType.NEVER)
                        .setOverlayScrolling(true)
                        //.setVadjustment(Adjustment.builder().setUpper(0))
                        .build();
                this.scroll.setChild(this.carousel);
                this.append(this.scroll);
            }
        }

        public static class HorizontalAlbumsListView extends Box {
            private final AppManager thumbLoader;
            private final Consumer<ArtistAlbumInfo> onAlbumSelected;
            private final List<ArtistAlbumInfo> albums;
            private final ListView listView;
            private final ListIndexModel listModel;
            private final ScrolledWindow scroll;

            public HorizontalAlbumsListView(
                    List<ArtistAlbumInfo> albums,
                    AppManager thumbLoader,
                    Consumer<ArtistAlbumInfo> onAlbumSelected
            ) {
                super(HORIZONTAL, 0);
                this.albums = albums;
                this.thumbLoader = thumbLoader;
                this.onAlbumSelected = onAlbumSelected;
                this.setHalign(START);
                this.setHexpand(true);
                var factory = new SignalListItemFactory();
                factory.onSetup(object -> {
                    ListItem listitem = (ListItem) object;
                    listitem.setActivatable(true);
                    var item = new OverviewAlbumChild(thumbLoader, this.onAlbumSelected);
                    listitem.setChild(item);
                });
                factory.onBind(object -> {
                    ListItem listitem = (ListItem) object;
                    ListIndexModel.ListIndex item = (ListIndexModel.ListIndex) listitem.getItem();
                    OverviewAlbumChild child = (OverviewAlbumChild) listitem.getChild();
                    if (child == null || item == null) {
                        return;
                    }
                    listitem.setActivatable(true);

                    // The ListIndexModel contains ListIndexItems that contain only their index in the list.
                    int index = item.getIndex();

                    // Retrieve the index of the item and show the entry from the ArrayList with random strings.
                    var album = this.albums.get(index);
                    child.setAlbumInfo(album);

                });
                this.listModel = ListIndexModel.newInstance(albums.size());
                this.listView = ListView.builder()
                        .setModel(new SingleSelection(this.listModel))
                        .setOrientation(HORIZONTAL)
                        .setHexpand(true)
                        .setHalign(START)
                        .setSingleClickActivate(true)
                        .setFactory(factory)
                        .build();
//                this.list = this.albums.stream().map(album -> {
//                    var item = new AlbumFlowBoxChild(this.thumbLoader, album);
//                    return item;
//                }).toList();
//                for (AlbumFlowBoxChild albumFlowBoxChild : list) {
//                    this.carousel.append(albumFlowBoxChild);
//                }
                this.scroll = ScrolledWindow.builder()
                        .setHexpand(true)
                        .setVexpand(true)
                        .setPropagateNaturalHeight(true)
                        .setPropagateNaturalWidth(true)
                        .setVscrollbarPolicy(PolicyType.NEVER)
                        .build();
                this.scroll.setChild(this.listView);
                this.append(this.scroll);
            }
        }

        private HorizontalAlbumsFlowBoxV3 flowBox(List<ArtistAlbumInfo> list) {
            return new HorizontalAlbumsFlowBoxV3(list, this.appManager, this.appManager::handleAction, this.onAlbumSelected);
        }

        public void setData(HomeOverview homeOverview, ServerClient.ServerInfo serverInfo, String serverUrl) {
            this.data = homeOverview;
            this.update(homeOverview);
        }

        private void update(HomeOverview data) {
            var r = flowBox(data.recent());
            var n = flowBox(data.newest());
            var freq = flowBox(data.frequent());
            var yearly = flowBox(data.byYear());
            Utils.runOnMainThread(() -> {
                this.recentList.setChild(r);
                this.newList.setChild(n);
                this.mostPlayedList.setChild(freq);
                this.mostRecentYear.setChild(yearly);
            });
        }
    }

    public static class HorizontalPlaylistsView extends Box {
        private static final int COVER_SIZE = 72;
        private static final int MAX_ITEMS = 6;
        private final GridView gridView;
        private final FilterListModel<GPlaylist> filteredModel;
        private final SortListModel<GPlaylist> sortedModel;
        private final SliceListModel<GPlaylist> slicedModel;

        public HorizontalPlaylistsView(ListStore<GPlaylist> store, AppManager appManager, Consumer<AppNavigation.AppRoute> onNavigate) {
            super(VERTICAL, 0);
            this.setHalign(Align.FILL);
            this.setHexpand(true);

            this.filteredModel = new FilterListModel<>(store, new CustomFilter(item -> {
                if (item instanceof GPlaylist gp) {
                    return gp.getPlaylist().kind() == PlaylistKind.NORMAL;
                }
                return false;
            }));
            this.sortedModel = new SortListModel<>(this.filteredModel,
                    new GPlaylistSorter(Comparator.comparing((GPlaylist a) -> a.getPlaylist().changedAt()).reversed())
            );
            this.slicedModel = new SliceListModel<>(this.sortedModel, 0, MAX_ITEMS);

            var factory = new SignalListItemFactory();
            factory.onSetup(obj -> {
                var listItem = (ListItem) obj;
                listItem.setChild(new PlaylistOverviewChild(appManager, COVER_SIZE));
            });
            factory.onBind(obj -> {
                var listItem = (ListItem) obj;
                var gp = (GPlaylist) listItem.getItem();
                if (gp != null && listItem.getChild() instanceof PlaylistOverviewChild child) {
                    child.bind(gp);
                }
            });
            factory.onUnbind(obj -> {
                var listItem = (ListItem) obj;
                if (listItem.getChild() instanceof PlaylistOverviewChild child) {
                    child.unbind();
                }
            });
            factory.onTeardown(obj -> ((ListItem) obj).setChild(null));

            this.gridView = GridView.builder()
                    .setModel(new NoSelection(this.slicedModel))
                    .setFactory(factory)
                    .setHalign(Align.START)
                    .setValign(Align.START)
                    .setHexpand(true)
                    .setVexpand(false)
                    .setSingleClickActivate(true)
                    .setMinColumns(2)
                    .setMaxColumns(3)
                    .build();
            this.gridView.setOrientation(HORIZONTAL);
            this.gridView.setTabBehavior(ListTabBehavior.ITEM);
            this.gridView.addCssClass(Classes.transparent.className());

            var sig = gridView.onActivate(index -> {
                var gp = this.slicedModel.getItem(index);
                if (gp != null) {
                    onNavigate.accept(new RoutePlaylistsOverview(Optional.of(new PlaylistIdentifier(gp.getId()))));
                }
            });
            this.onDestroy(sig::disconnect);

            this.append(gridView);
        }
    }

    private static class PlaylistOverviewChild extends Box {
        private final RoundedAlbumArt art;
        private final Label nameLabel;
        private final Label countLabel;
        @Nullable private GPlaylist gPlaylist;
        @Nullable private SignalConnection<NotifyCallback> gpSignal;

        public PlaylistOverviewChild(AppManager appManager, int coverSize) {
            super(HORIZONTAL, 10);
            int margin = 10;
            this.setMarginStart(margin);
            this.setMarginEnd(margin);
            this.setMarginTop(margin);
            this.setMarginBottom(margin);
            this.setSizeRequest(240, -1);

            this.art = new RoundedAlbumArt(Optional.empty(), appManager, coverSize);
            this.art.setClickable(false);
            this.art.setValign(Align.CENTER);

            this.nameLabel = new Label();
            this.nameLabel.setHalign(START);
            this.nameLabel.setXalign(0f);
            this.nameLabel.setWrap(true);
            this.nameLabel.setHexpand(true);
            this.nameLabel.setLines(2);
            this.nameLabel.addCssClass(Classes.heading.className());
            this.nameLabel.setMaxWidthChars(20);
            this.nameLabel.setEllipsize(EllipsizeMode.END);

            this.countLabel = new Label();
            this.countLabel.setHalign(START);
            this.countLabel.setXalign(0f);
            this.countLabel.addCssClass(Classes.labelDim.className());
            this.countLabel.addCssClass(Classes.caption.className());

            var textBox = new Box(VERTICAL, 2);
            textBox.setOrientation(VERTICAL);
            textBox.setSpacing(2);
            textBox.setHalign(START);
            textBox.setValign(Align.CENTER);
            textBox.setHexpand(true);

            textBox.append(nameLabel);
            textBox.append(countLabel);

            this.append(art);
            this.append(textBox);
        }

        public void bind(GPlaylist gp) {
            this.gPlaylist = gp;
            this.gpSignal = gp.onChanged(p -> this.refresh());
            this.refresh();
        }

        private void refresh() {
            var gp = this.gPlaylist;
            if (gp == null) {
                return;
            }
            var playlist = gp.getPlaylist();
            this.nameLabel.setLabel(playlist.name());
            this.countLabel.setLabel(playlist.songCount() + " songs");
            this.art.update(playlist.coverArtId());
        }

        public void unbind() {
            this.gPlaylist = null;
            var sig = this.gpSignal;
            if (sig != null) {
                gpSignal.disconnect();
                this.gpSignal = null;
            }
            this.nameLabel.setLabel("");
            this.countLabel.setLabel("");
            this.art.update(Optional.empty());
        }
    }

    private static class ServerInfoCard extends Box {
        private final Label hostLabel;
        private final Label versionLabel;
        private final Label apiVersionLabel;
        private final Label songsLabel;
        private final Label foldersLabel;
        private final Label lastScanLabel;

        public ServerInfoCard() {
            super(HORIZONTAL, 16);
            this.addCssClass("card");

            var icon = Image.fromIconName("network-server-symbolic");
            icon.setPixelSize(40);
            icon.setHalign(CENTER);
            icon.setValign(CENTER);
            icon.addCssClass(Classes.labelDim.className());
            icon.setMarginStart(16);
            icon.setMarginTop(16);
            icon.setMarginBottom(16);

            this.hostLabel = Label.builder().setHalign(START).setXalign(0f).build();
            this.hostLabel.addCssClass(Classes.heading.className());

            this.versionLabel = Label.builder().setHalign(START).setXalign(0f).build();
            this.versionLabel.addCssClass(Classes.labelDim.className());
            this.versionLabel.addCssClass(Classes.caption.className());

            this.apiVersionLabel = Label.builder().setHalign(START).setXalign(0f).build();
            this.apiVersionLabel.addCssClass(Classes.labelDim.className());
            this.apiVersionLabel.addCssClass(Classes.caption.className());

            this.songsLabel = Label.builder().setHalign(START).setXalign(0f).build();
            this.songsLabel.addCssClass(Classes.labelDim.className());
            this.songsLabel.addCssClass(Classes.caption.className());

            this.foldersLabel = Label.builder().setHalign(START).setXalign(0f).setVisible(false).build();
            this.foldersLabel.addCssClass(Classes.labelDim.className());
            this.foldersLabel.addCssClass(Classes.caption.className());

            this.lastScanLabel = Label.builder().setHalign(START).setXalign(0f).setVisible(false).build();
            this.lastScanLabel.addCssClass(Classes.labelDim.className());
            this.lastScanLabel.addCssClass(Classes.caption.className());

            var textBox = Box.builder()
                    .setOrientation(VERTICAL)
                    .setSpacing(3)
                    .setHalign(START)
                    .setValign(CENTER)
                    .setMarginTop(16)
                    .setMarginBottom(16)
                    .setMarginEnd(16)
                    .build();
            textBox.append(hostLabel);
            textBox.append(versionLabel);
            textBox.append(apiVersionLabel);
            textBox.append(songsLabel);
            textBox.append(foldersLabel);
            textBox.append(lastScanLabel);

            this.append(icon);
            this.append(textBox);
        }

        public void update(ServerClient.ServerInfo info, String serverUrl) {
            try {
                var host = URI.create(serverUrl).getHost();
                this.hostLabel.setLabel(host != null && !host.isBlank() ? host : serverUrl);
            } catch (Exception e) {
                this.hostLabel.setLabel(serverUrl);
            }
            this.versionLabel.setLabel(
                info.serverVersion().map(v -> v.startsWith("v") ? v : "v%s".formatted(v)).map(v -> "Version: %s".formatted(v)).orElse("Version: ")
            );
            this.apiVersionLabel.setLabel(Optional.ofNullable(info.apiVersion()).map(v -> "API Version: %s".formatted(v)).orElse("API Version: "));
            this.songsLabel.setLabel(String.format("%,d songs", info.songCount()));
            info.folderCount().ifPresentOrElse(
                f -> { this.foldersLabel.setLabel(f + " folders"); this.foldersLabel.setVisible(true); },
                () -> this.foldersLabel.setVisible(false)
            );
            info.lastScan().ifPresentOrElse(
                scan -> {
                    var age = Duration.between(scan, Instant.now());
                    this.lastScanLabel.setLabel("Scanned " + Utils.formatDurationMedium(age) + " ago");
                    this.lastScanLabel.setVisible(true);
                },
                () -> this.lastScanLabel.setVisible(false)
            );
        }
    }
}
