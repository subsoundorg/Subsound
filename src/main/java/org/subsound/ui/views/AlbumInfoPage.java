package org.subsound.ui.views;

import org.subsound.app.state.AppManager;
import org.subsound.app.state.AppManager.AlbumInfo;
import org.subsound.app.state.AppManager.AppState;
import org.subsound.app.state.AppManager.StateListener;
import org.subsound.app.state.NetworkMonitoring.NetworkStatus;
import org.subsound.app.state.PlayerAction;
import org.subsound.app.state.PlaylistsStore.GPlaylist;
import org.subsound.integration.ServerClient.ObjectIdentifier.AlbumIdentifier;
import org.subsound.integration.ServerClient.PlaylistKind;
import org.subsound.app.state.PlayerAction.PlayAndReplaceQueue;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.ui.components.AppNavigation;
import org.subsound.ui.components.Classes;
import org.subsound.ui.components.ClickLabel;
import org.subsound.ui.components.Icons;
import org.subsound.ui.components.NowPlayingOverlayIcon;
import org.subsound.ui.components.NowPlayingOverlayIcon.NowPlayingState;
import org.subsound.ui.components.PlaylistChooser;
import org.subsound.ui.components.RoundedAlbumArt;
import org.subsound.ui.components.SongDownloadStatusIcon;
import org.subsound.ui.components.StarButton;
import org.subsound.ui.models.GSongInfo;
import org.subsound.ui.models.GDownloadState;
import org.subsound.ui.models.GSongInfo.Signal;
import org.subsound.ui.views.PlaylistListViewV2.GPlaylistEntry;
import org.subsound.utils.ImageUtils;
import org.subsound.utils.Utils;
import org.gnome.adw.Clamp;
import org.gnome.gdk.Display;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.ContentFit;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.ListBoxRow;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.EventControllerKey;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.Overflow;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Picture;
import org.gnome.gtk.Popover;
import org.gnome.gtk.Revealer;
import org.gnome.gtk.RevealerTransitionType;
import org.gnome.gtk.ScrolledWindow;
import org.gnome.gtk.Separator;
import org.gnome.gtk.Stack;
import org.gnome.gtk.StateFlags;
import org.gnome.gtk.Widget;
import org.gnome.pango.EllipsizeMode;
import org.javagi.gobject.SignalConnection;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.subsound.utils.Utils.addHover;
import static org.subsound.utils.Utils.cssClasses;
import static org.subsound.utils.Utils.formatBytesSI;
import static org.subsound.utils.Utils.formatDurationMedium;
import static org.gnome.gtk.Align.BASELINE_FILL;
import static org.gnome.gtk.Align.CENTER;
import static org.gnome.gtk.Align.END;
import static org.gnome.gtk.Align.START;
import static org.gnome.gtk.Orientation.HORIZONTAL;
import static org.gnome.gtk.Orientation.VERTICAL;

public class AlbumInfoPage extends Box implements StateListener {
    private final AppManager appManager;

    //private final ArtistInfo artistInfo;
    private final AlbumInfo info;

    private final Box headerBox;
    private final Picture backdropPicture;
    private final Overlay headerOverlay;
    private final ScrolledWindow scroll;
    private final Box mainContainer;
    private final Box albumInfoBox;
    private final ClickLabel artistNameLabel;
    private final ListBox listView;
    private final List<AlbumSongActionRow> rows;
    private final Widget artistImage;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Popover playlistPopover;
    private static final int COVER_SIZE = 300;
    private static final CssProvider COLOR_PROVIDER = CssProvider.builder().build();
    private static final AtomicBoolean isProviderInit = new AtomicBoolean(false);
    private volatile NetworkStatus networkStatus;

    public static class AlbumSongActionRow extends ListBoxRow {
        private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AlbumSongActionRow.class);
        private final AppManager appManager;
        private final AlbumInfo albumInfo;
        public final GSongInfo gSongInfo;
        public final SongInfo songInfo;
        private final int index;
        private final Function<PlayerAction, CompletableFuture<Void>> onAction;
        public final NowPlayingOverlayIcon icon;
        private final StarButton starredButton;
        private final SongDownloadStatusIcon downloadStatusIcon = new SongDownloadStatusIcon();
        private final Box mainBox;
        private final Box prefixBox;
        private final Box suffixBox;
        private final Box centerBox;
        private final Label rowTitleLabel;
        private final Label rowSubTitleLabel;
        private SignalConnection<NotifyCallback> signalPlaying;
        private SignalConnection<NotifyCallback> signalFavorited;
        private SignalConnection<NotifyCallback> signalDownloadStatus;
        private volatile NetworkStatus networkStatus;

        public AlbumSongActionRow(
                AppManager appManager,
                NetworkStatus networkStatus,
                AlbumInfo albumInfo,
                int index,
                GSongInfo gSongInfo,
                Function<PlayerAction, CompletableFuture<Void>> onAction
        ) {
            super();
            this.mainBox = new Box(HORIZONTAL, 2);
            this.mainBox.setHomogeneous(false);
            this.mainBox.setMarginTop(4);
            this.mainBox.setMarginBottom(4);
            this.mainBox.setMarginStart(2);
            this.mainBox.setMarginEnd(2);
            this.prefixBox = new Box(HORIZONTAL, 0);
            this.prefixBox.setHalign(START);
            this.prefixBox.setValign(CENTER);
            this.prefixBox.setVexpand(true);
            this.prefixBox.setHomogeneous(true);

            this.centerBox = new Box(HORIZONTAL, 0);
            this.centerBox.setHexpand(true);
            this.centerBox.setVexpand(true);
            this.centerBox.setValign(CENTER);
            this.centerBox.setHalign(START);
            this.centerBox.setMarginStart(8);
            this.centerBox.setMarginEnd(8);

            this.suffixBox = new Box(HORIZONTAL, 0);
            this.suffixBox.setHalign(END);
            this.suffixBox.setValign(CENTER);
            this.suffixBox.setVexpand(true);
            this.mainBox.append(this.prefixBox);
            this.mainBox.append(this.centerBox);
            this.mainBox.append(this.suffixBox);
            this.rowTitleLabel = new Label("Title");
            this.rowTitleLabel.setHalign(START);
            this.rowTitleLabel.setUseMarkup(false);
            this.rowTitleLabel.setMaxWidthChars(30);
            this.rowTitleLabel.setLines(1);
            this.rowTitleLabel.setSingleLineMode(true);
            this.rowTitleLabel.setEllipsize(EllipsizeMode.END);
            this.rowTitleLabel.addCssClass(Classes.title3.className());
            this.rowSubTitleLabel = new Label("Subtitle");
            this.rowSubTitleLabel.setHalign(START);
            this.rowSubTitleLabel.setUseMarkup(false);
            this.rowSubTitleLabel.setLines(1);
            this.rowSubTitleLabel.setMaxWidthChars(36);
            this.rowSubTitleLabel.setSingleLineMode(true);
            this.rowSubTitleLabel.addCssClass(Classes.labelDim.className());
            this.rowSubTitleLabel.addCssClass(Classes.caption.className());
            var titleBox = new Box(VERTICAL, 2);
            titleBox.setVexpand(true);
            titleBox.setHomogeneous(false);
            titleBox.append(this.rowTitleLabel);
            titleBox.append(this.rowSubTitleLabel);
            this.centerBox.append(titleBox);
            this.setChild(mainBox);
            this.appManager = appManager;
            this.albumInfo = albumInfo;
            this.index = index;
            this.gSongInfo = gSongInfo;
            this.songInfo = gSongInfo.getSongInfo();
            this.onAction = onAction;
            this.networkStatus = networkStatus;
            this.addCssClass(Classes.rounded.className());
            this.addCssClass("AlbumSongActionRow");
            this.setActivatable(true);
            this.setFocusable(true);
            this.setFocusOnClick(true);
            this.setHexpand(true);

            var suffix = Box.builder()
                    .setOrientation(HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(CENTER)
                    .setVexpand(true)
                    .setSpacing(8)
                    .build();

            starredButton = new StarButton(
                    songInfo.starred(),
                    newValue -> {
                        var action = newValue ? new PlayerAction.Star(songInfo) : new PlayerAction.Unstar(songInfo);
                        return this.onAction.apply(action);
                    }
            );

            var hoverBox = Box.builder()
                    .setOrientation(HORIZONTAL)
                    .setHalign(Align.END)
                    .setValign(CENTER)
                    .setSpacing(8)
                    .build();

            var menuPopover = getPopover();
            var menuButton = MenuButton.builder()
                    .setIconName(Icons.OpenMenu.getIconName())
                    .setPopover(menuPopover)
                    .build();
            menuButton.addCssClass("flat");
            menuButton.addCssClass("circular");

            var fileFormatLabel = Optional.ofNullable(songInfo.suffix())
                    .filter(fileExt -> !fileExt.isBlank())
                    .map(fileExt -> Button.builder()
                            .setLabel(fileExt)
                            .setSensitive(false)
                            .setVexpand(false)
                            .setValign(CENTER)
                            .setCssClasses(cssClasses("dim-label"))
                            .build()
                    );

            var fileSizeLabel = infoLabel(formatBytesSI(songInfo.size()), cssClasses("dim-label"));
            var bitRateLabel = songInfo.bitRate()
                    .map(bitRate -> infoLabel("%d kbps".formatted(bitRate), cssClasses("dim-label")));
            bitRateLabel.ifPresent(hoverBox::append);
            fileFormatLabel.ifPresent(hoverBox::append);
            hoverBox.append(fileSizeLabel);

            var revealer = Revealer.builder()
                    .setChild(hoverBox)
                    .setRevealChild(false)
                    .setTransitionType(RevealerTransitionType.CROSSFADE)
                    .build();

            suffix.append(revealer);
            suffix.append(downloadStatusIcon);
            var starredButtonBox = Box.builder().setMarginStart(2).setMarginEnd(2).setVexpand(true).setValign(CENTER).build();
            starredButtonBox.append(starredButton);
            suffix.append(starredButtonBox);
            var menuButtonBox = Box.builder().setMarginStart(2).setMarginEnd(2).setVexpand(true).setValign(CENTER).build();
            menuButtonBox.append(menuButton);
            suffix.append(menuButtonBox);

            String durationString = Utils.formatDurationShortest(songInfo.duration());
            String subtitle = durationString;

            Label songNumberLabel = Label.builder()
                    .setLabel(songInfo.trackNumber().map(String::valueOf).orElse(""))
                    .setWidthChars(2)
                    .setMaxWidthChars(2)
                    .setSingleLineMode(true)
                    .setJustify(Justification.RIGHT)
                    .setEllipsize(EllipsizeMode.START)
                    .setCssClasses(cssClasses("dim-label", "numeric"))
                    .build();
            icon = new NowPlayingOverlayIcon(48, songNumberLabel);
            var isHoverActive = new AtomicBoolean(false);

            addHover(
                    this,
                    () -> {
                        isHoverActive.set(true);
                        revealer.setRevealChild(true);
                        this.icon.setIsHover(true);
                    },
                    () -> {
                        isHoverActive.set(false);
                        var focused = this.hasFocus() || menuButton.hasFocus();
                        revealer.setRevealChild(focused);
                        this.icon.setIsHover(false);
                    }
            );
            this.onStateFlagsChanged(flags -> {
                var hasFocus = menuButton.hasFocus() || flags.contains(StateFlags.FOCUSED) || flags.contains(StateFlags.FOCUS_WITHIN) || flags.contains(StateFlags.FOCUS_VISIBLE);
                var hasHover = isHoverActive.get();
                revealer.setRevealChild(hasFocus || hasHover);
                this.icon.setIsHover(hasFocus || hasHover);
            });

            this.onMap(() -> {
                signalPlaying = this.gSongInfo.onNotify(
                        GSongInfo.Signal.IS_PLAYING.getId(),
                        cb -> {
                            log.info("{}: isPlaying={}", this.gSongInfo.getTitle(), this.gSongInfo.getIsPlaying());
                            this.updateUI();
                        }
                );
                signalFavorited = this.gSongInfo.onNotify(
                        GSongInfo.Signal.IS_FAVORITE.getId(),
                        cb -> this.updateUI()
                );
                signalDownloadStatus = this.gSongInfo.onNotify(
                        Signal.DOWNLOAD_STATE.getId(),
                        cb -> this.updateUI()
                );
                this.updateUI();
            });
            this.onUnmap(() -> {
                //log.info("disconnect gSongInfo signals");
                if (signalPlaying != null) {
                    signalPlaying.disconnect();
                    signalPlaying = null;
                }
                if (signalFavorited != null) {
                    signalFavorited.disconnect();
                    signalFavorited = null;
                }
                if (signalDownloadStatus != null) {
                    signalDownloadStatus.disconnect();
                    signalDownloadStatus = null;
                }
            });
            this.prefixBox.append(icon);
            this.suffixBox.append(suffix);
            this.rowTitleLabel.setLabel(songInfo.title());
            this.rowSubTitleLabel.setLabel(subtitle);
        }

        private void updateUI() {
            Utils.runOnMainThread(() -> {
                this.updateRow(new RowState(this.gSongInfo.getIsPlaying(), this.gSongInfo.getIsStarred(), this.gSongInfo.getDownloadStateEnum(), this.networkStatus));
            });
        }

        public void updateNetworkStatus(NetworkStatus networkStatus) {
            if (this.networkStatus == networkStatus) {
                return;
            }
            this.networkStatus = networkStatus;
            this.updateUI();
        }

        record RowState(boolean isPlaying, boolean isStarred, GDownloadState downloadState, NetworkStatus networkStatus){}
        private void updateRow(RowState next) {
            boolean isOffline = next.networkStatus == NetworkStatus.OFFLINE;
            boolean isPlaying = next.isPlaying;
            if (isPlaying) {
                this.addCssClass(Classes.colorAccent.className());
                this.icon.setPlayingState(NowPlayingState.PLAYING);
                // TODO: get a richer is-playing state
                //switch (next.player().state()) {
                //    case PAUSED, INIT -> row.icon.setPlayingState(NowPlayingState.PAUSED);
                //    case PLAYING, BUFFERING, READY, END_OF_STREAM -> row.icon.setPlayingState(NowPlayingState.PLAYING);
                //}

            } else {
                this.removeCssClass(Classes.colorAccent.className());
                this.icon.setPlayingState(NowPlayingState.NONE);
            }
            if (next.isStarred != this.starredButton.isStarred()) {
                this.starredButton.setStarredAt(this.gSongInfo.getStarredAt());
            }
            this.downloadStatusIcon.updateDownloadState(next.downloadState);
            if (isOffline && !next.downloadState.isDownloaded()) {
                this.setActivatable(false);
                this.setSensitive(false);
            } else {
                this.setActivatable(true);
                this.setSensitive(true);
            }
        }

        private @NonNull Popover getPopover() {
            // Context menu popover with Stack for submenu navigation
            var stack = new Stack();

            // Main menu content
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

            var playMenuItem = menuItem("Play");
            playMenuItem.onClicked(() -> {
                menuPopover.popdown();
                int idx = this.index;
                var playContext = new AlbumIdentifier(this.albumInfo.album().id());
                int count = 0;
                var queue = new ArrayList<PlayerAction.QueueSlot>(this.albumInfo.songs().size());
                for (GSongInfo song : this.albumInfo.songs()) {
                    PlayerAction.QueueSlot queueSlot = new PlayerAction.QueueSlot(
                            GPlaylistEntry.makeQueueItemId(playContext, song.getId(), count++),
                            song.getSongInfo()
                    );
                    queue.add(queueSlot);
                }
                this.onAction.apply(new PlayAndReplaceQueue(
                        playContext,
                        queue,
                        idx
                ));
            });

            var playNextMenuItem = menuItem("Play Next");
            playNextMenuItem.onClicked(() -> {
                menuPopover.popdown();
                this.onAction.apply(new PlayerAction.Enqueue(songInfo));
            });

            var addToQueueMenuItem = menuItem("Add to Queue");
            addToQueueMenuItem.onClicked(() -> {
                menuPopover.popdown();
                this.onAction.apply(new PlayerAction.EnqueueLast(songInfo));
            });

            var favoriteMenuItem = menuItem(songInfo.isStarred() ? "Unstar" : "Add to Starred");
            favoriteMenuItem.onClicked(() -> {
                menuPopover.popdown();
                var action = songInfo.isStarred()
                        ? new PlayerAction.Unstar(songInfo)
                        : new PlayerAction.Star(songInfo);
                this.onAction.apply(action);
            });

            var addToPlaylistMenuItem = menuItem("Add to Playlist\u2026");
            addToPlaylistMenuItem.onClicked(() -> {
                stack.setVisibleChildName("playlists");
            });

            var downloadMenuItem = menuItem("Download");
            downloadMenuItem.onClicked(() -> {
                menuPopover.popdown();
                this.onAction.apply(new PlayerAction.AddToDownloadQueue(songInfo));
            });

            menuContent.append(playMenuItem);
            menuContent.append(playNextMenuItem);
            menuContent.append(addToQueueMenuItem);
            menuContent.append(favoriteMenuItem);
            menuContent.append(addToPlaylistMenuItem);
            menuContent.append(downloadMenuItem);

            // Playlist selection submenu
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

            // Populate playlist buttons from cache
            var playlists = this.appManager.getPlaylistsListStore();
            for (int i = 0; i < playlists.getNItems(); i++) {
                GPlaylist gPlaylist = playlists.getItem(i);
                // Skip synthetic playlists (Starred, Downloaded)
                if (gPlaylist.getPlaylist().kind() != PlaylistKind.NORMAL) {
                    continue;
                }
                String playlistId = gPlaylist.getId();
                String playlistName = gPlaylist.getName();
                var btn = menuItem(playlistName);
                btn.onClicked(() -> {
                    menuPopover.popdown();
                    this.onAction.apply(new PlayerAction.AddToPlaylist(
                            songInfo, playlistId, playlistName
                    ));
                });
                playlistsView.append(btn);
            }

            stack.addNamed(menuContent, "main");
            stack.addNamed(playlistsView, "playlists");
            stack.setVisibleChildName("main");

            // Reset to main menu when popover closes
            menuPopover.onClosed(() -> stack.setVisibleChildName("main"));

            return menuPopover;
        }

        private static Button menuItem(String labelText) {
            var label = new Label();
            label.setLabel(labelText);
            label.setHalign(Align.START);
            label.setJustify(Justification.LEFT);
            label.setEllipsize(EllipsizeMode.END);
            //label.setMaxWidthChars(24);
            label.setSingleLineMode(true);
            label.addCssClass("body");
            var button = new Button();
            button.setChild(label);
            button.addCssClass("flat");
            return button;
        }

    }

    public AlbumInfoPage(
            AppManager appManager,
            AlbumInfo info,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.appManager = appManager;
        this.info = info;
        this.onAction = onAction;
        if (isProviderInit.compareAndSet(false, true)) {
            Gtk.styleContextAddProviderForDisplay(Display.getDefault(), COLOR_PROVIDER, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1);
        }
        this.artistImage = this.info.album().coverArt()
                .map(coverArt -> new RoundedAlbumArt(
                        coverArt,
                        this.appManager,
                        COVER_SIZE
                ))
                .map(albumArt -> {
                    albumArt.setClickable(false);
                    albumArt.addCssClass("album-main-cover");
                    return albumArt;
                })
                .map(artwork -> (Widget) artwork)
                .orElseGet(() -> RoundedAlbumArt.placeholderImage(COVER_SIZE));

        this.listView = ListBox.builder()
                .setValign(START)
                .setHalign(Align.FILL)
                .setHexpand(true)
                // require double click to activate:
                .setActivateOnSingleClick(false)
                .setCssClasses(Classes.darken.add(
                        Classes.rounded
                        //Classes.boxedList
                ))
                .setShowSeparators(false)
                .build();

        this.listView.onRowActivated(row -> {
            var playContext = new AlbumIdentifier(this.info.album().id());
            int count = 0;
            var queue = new ArrayList<PlayerAction.QueueSlot>(this.info.songs().size());
            for (GSongInfo song : this.info.songs()) {
                PlayerAction.QueueSlot queueSlot = new PlayerAction.QueueSlot(
                        GPlaylistEntry.makeQueueItemId(playContext, song.getId(), count++),
                        song.getSongInfo()
                );
                queue.add(queueSlot);
            }
            this.onAction.apply(new PlayAndReplaceQueue(
                    playContext,
                    queue,
                    row.getIndex()
            ));
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
            return false;
        });
        this.listView.addController(keyController);

        var initialNetworkStatus = this.appManager.getState().networkState().status();
        this.networkStatus = initialNetworkStatus;
        this.rows = new ArrayList<>(this.info.songs().size());
        int idx = 0;
        for (var songInfo : this.info.songs()) {
            var row = new AlbumSongActionRow(
                    this.appManager,
                    initialNetworkStatus,
                    this.info,
                    idx,
                    songInfo,
                    this.onAction
            );
            this.rows.add(row);
            this.listView.append(row);
            idx++;
        }

        this.headerBox = Box.builder().setHalign(BASELINE_FILL).setSpacing(0).setValign(START).setOrientation(VERTICAL).setHexpand(true).build();
        this.headerBox.addCssClass("album-info-main");

        this.mainContainer = Box.builder().setHalign(BASELINE_FILL).setSpacing(8).setValign(START).setOrientation(VERTICAL).setHexpand(true).setVexpand(true).setMarginBottom(10).setHomogeneous(false).build();
        this.albumInfoBox = Box.builder().setHalign(CENTER).setSpacing(4).setValign(START).setOrientation(VERTICAL).setHexpand(true).setMarginBottom(10).build();
        this.albumInfoBox.append(infoLabel(this.info.album().name(), Classes.titleLarge2.add()));
        this.artistNameLabel = new ClickLabel(
                this.info.album().artistName(),
                () -> this.appManager.navigateTo(new AppNavigation.AppRoute.RouteArtistInfo(this.info.album().artistId()))
        );
        this.artistNameLabel.setUseMarkup(false);
        this.artistNameLabel.setEllipsize(EllipsizeMode.END);
        this.artistNameLabel.addCssClass(Classes.titleLarge3.className());
        this.artistNameLabel.setLabel(this.info.album().artistName());
        this.albumInfoBox.append(this.artistNameLabel);
        this.albumInfoBox.append(infoLabel(this.info.album().year().map(String::valueOf).orElse(""), Classes.labelDim.add(Classes.bodyText)));
        this.albumInfoBox.append(infoLabel("%d songs, %s".formatted(this.info.album().songCount(), formatDurationMedium(this.info.album().totalPlayTime())), Classes.labelDim.add(Classes.bodyText)));
        //this.albumInfoBox.append(infoLabel("%s playtime".formatted(formatDurationMedium(this.albumInfo.totalPlayTime())), Classes.labelDim.add(Classes.bodyText)));

        var downloadAllButtonContent = Box.builder()
                .setOrientation(HORIZONTAL)
                .setSpacing(6)
                .build();
        downloadAllButtonContent.append(Label.builder().setLabel("Download all").build());
        downloadAllButtonContent.append(org.gnome.gtk.Image.fromIconName(Icons.FolderDownload.getIconName()));
        var downloadAllButton = Button.builder()
                //.setLabel("Download All")
                //.setIconName(Icons.FolderDownload.getIconName())
                .setChild(downloadAllButtonContent)
                .setHalign(CENTER)
                .build();
        downloadAllButton.addCssClass("flat");
        downloadAllButton.onClicked(() -> {
            var songs = this.info.songs();
            this.onAction.apply(new PlayerAction.AddManyToDownloadQueue(songs));
        });
        this.playlistPopover = buildPlaylistPopover();
        var addToPlaylistButton = MenuButton.builder()
                .setLabel("Add to Playlist\u2026")
                .setIconName(Icons.Playlists.getIconName())
                .setPopover(this.playlistPopover)
                .setHalign(CENTER)
                .build();
        addToPlaylistButton.addCssClass("flat");

        var actionButtonsBox = Box.builder()
                .setOrientation(HORIZONTAL)
                .setHalign(Align.END)
                .setSpacing(8)
                .setMarginBottom(4)
                .build();
        actionButtonsBox.append(downloadAllButton);
        actionButtonsBox.append(addToPlaylistButton);

        this.headerBox.append(this.artistImage);
        this.headerBox.append(this.albumInfoBox);

        this.backdropPicture = new Picture();
        this.backdropPicture.setContentFit(ContentFit.COVER);
        this.backdropPicture.setCanShrink(true);

        this.headerOverlay = new Overlay();
        // Use an empty box as the child so the backdrop texture size doesn't
        // inflate the overlay. Only headerBox (via measureOverlay) determines height.
        var overlayChild = new Box(VERTICAL, 0);
        this.headerOverlay.setChild(overlayChild);
        this.headerOverlay.addOverlay(this.backdropPicture);
        this.headerOverlay.addOverlay(this.headerBox);
        this.headerOverlay.setMeasureOverlay(this.headerBox, true);
        this.headerOverlay.setOverflow(Overflow.HIDDEN);
        this.headerOverlay.setHexpand(true);

        this.mainContainer.append(this.headerOverlay);
        var listHolder = Box.builder().setOrientation(VERTICAL).setHalign(CENTER).setValign(START).setVexpand(true).build();
        listHolder.append(actionButtonsBox);
        listHolder.append(listView);
        this.mainContainer.append(listHolder);

        this.scroll = ScrolledWindow.builder().setChild(mainContainer).setHexpand(true).setVexpand(true).build();
        this.setHexpand(true);
        this.setVexpand(true);
        this.append(this.scroll);
        this.appManager.addOnStateChanged(this);
        this.onMap(() -> {
            this.appManager.addOnStateChanged(this);
            this.appManager.getThumbnailCache().loadPixbuf(
                    this.info.album().coverArt().get(),
                    COVER_SIZE
            ).thenAccept(this::switchBackdrop);
        });
        this.onUnmap(() -> {
            this.appManager.removeOnStateChanged(this);
        });
    }

    @Override
    public void onStateChanged(AppState state) {
        boolean isChanged = false;
        var next = state.networkState().status();
        if (next != this.networkStatus) {
            this.networkStatus = next;
            isChanged = true;
        }
        if (isChanged) {
            this.updateUI();
        }
    }

    private void updateUI() {
        this.rows.forEach(row -> {
            row.updateNetworkStatus(this.networkStatus);
        });
    }

    private PlaylistChooser buildPlaylistPopover() {
        return new PlaylistChooser(
                this.appManager.getPlaylistsListStore(),
                (playlistId, playlistName) -> {
                    var songs = this.info.songs();
                    this.onAction.apply(new PlayerAction.AddManyToPlaylist(
                            songs, playlistId, playlistName
                    ));
                }
        );
    }

    private void switchBackdrop(ThumbnailCache.CachedTexture cachedTexture) {
        var backdrop = cachedTexture.backdropTexture();
        if (backdrop == null) {
            // Fallback: use palette-based CSS gradient
            StringBuilder colors = new StringBuilder();
            List<ImageUtils.ColorValue> palette = cachedTexture.palette();
            for (int i = 0; i < palette.size(); i++) {
                ImageUtils.ColorValue colorValue = palette.get(i);
                colors.append("@define-color background_color_%d %s;\n".formatted(i, colorValue.rgba().toString()));
            }
            COLOR_PROVIDER.loadFromString(colors.toString());
            return;
        }
        Utils.runOnMainThread(() -> this.backdropPicture.setPaintable(backdrop));
    }

    public static Label infoLabel(String label, String[] cssClazz) {
        var lbl = new Label();
        lbl.setUseMarkup(false);
        lbl.setEllipsize(EllipsizeMode.END);
        for (String clazz : cssClazz) {
            lbl.addCssClass(clazz);
        }
        lbl.setLabel(label);
        return lbl;
    }
}
