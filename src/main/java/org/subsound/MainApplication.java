package org.subsound;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.integration.ServerClient;
import org.subsound.app.state.PlayerAction.PlayMode;
import org.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.ui.components.AppNavigation;
import org.subsound.ui.components.Classes;
import org.subsound.ui.components.Icons;
import org.subsound.ui.components.CommandPalette;
import org.subsound.ui.components.OnboardingOverlay;
import org.subsound.ui.components.PlayerBar;
import org.subsound.ui.components.ServerBadge;
import org.subsound.ui.components.ServerConfigForm;
import org.subsound.ui.components.SettingsPage;
import org.subsound.ui.views.AlbumInfoLoader;
import org.subsound.ui.views.ArtistInfoLoader;
import org.subsound.ui.views.ArtistListLoader;
import org.subsound.ui.views.FrontpagePage;
import org.subsound.ui.views.PlaylistsViewLoader;
import org.subsound.ui.views.TestPlayerPage;
import org.gnome.adw.Application;
import org.gnome.adw.ApplicationWindow;
import org.gnome.adw.ColorScheme;
import org.gnome.adw.HeaderBar;
import org.gnome.adw.NavigationPage;
import org.gnome.adw.NavigationView;
import org.gnome.adw.StyleManager;
import org.gnome.adw.ToastOverlay;
import org.gnome.adw.ToolbarView;
import org.gnome.adw.ViewStack;
import org.gnome.adw.ViewStackPage;
import org.gnome.adw.ViewSwitcher;
import org.gnome.adw.ViewSwitcherPolicy;
import org.gnome.gdk.Display;
import org.gnome.glib.Variant;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Button;
import org.gnome.gtk.CallbackAction;
import org.gnome.gtk.CssProvider;
import org.gnome.gtk.Gtk;
import org.gnome.gtk.MenuButton;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Popover;
import org.gnome.gtk.Shortcut;
import org.gnome.gtk.ShortcutController;
import org.gnome.gtk.ShortcutScope;
import org.gnome.gtk.ShortcutTrigger;
import org.gnome.gtk.IconTheme;
import org.gnome.gtk.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.subsound.utils.Utils.mustRead;

public class MainApplication {
    private static final Logger log = LoggerFactory.getLogger(MainApplication.class);
    private final AppManager appManager;
    private final ThumbnailCache thumbLoader;
    private final String cssMain;
    private ApplicationWindow window;
    private WindowSize lastWindowSize;

    private final ViewStack viewStack = ViewStack.builder().build();
    private final Button backButton;
    private final NavigationView navigationView = NavigationView.builder().setPopOnEscape(true).setAnimateTransitions(true).build();
    private final ToastOverlay toastOverlay = ToastOverlay.builder().setChild(this.navigationView).build();
    private ToolbarView toolbarView;
    private final HeaderBar headerBar;
    private final ViewSwitcher viewSwitcher;

    private Popover settingsPopover;
    private final MenuButton settingsButton;
    private final PlayerBar playerBar;
    private final Box bottomBar;
    private final Shortcut playPauseShortcut;
    private final Shortcut shuffleModeShortCut;
    private final Shortcut commandPaletteShortcut;
    private final CommandPalette commandPalette;
    private final ServerBadge serverBadge;
    private AppNavigation appNavigation;
    private PlaylistsViewLoader playlistsViewLoader;
    private final CssProvider mainProvider;


    public MainApplication(AppManager appManager) {
        this.appManager = appManager;
        this.appManager.setToastOverlay(this.toastOverlay);
        this.thumbLoader = appManager.getThumbnailCache();
        this.cssMain = mustRead(Path.of("css/main.css"));
        this.mainProvider = new CssProvider();
        this.mainProvider.loadFromString(cssMain);
        Gtk.styleContextAddProviderForDisplay(Display.getDefault(), this.mainProvider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
        // Register custom icons with the icon theme:
        registerCustomIcons();
        // force to dark mode as we currently look terrible in light mode:
        StyleManager.getDefault().setColorScheme(ColorScheme.FORCE_DARK);

        //var playPauseTrigger = ShortcutTrigger.parseString("<Control>KP_Space");
        var playPauseTrigger = ShortcutTrigger.parseString("<Control>p");
        var playPauseAction = new CallbackAction((Widget widget, @Nullable Variant args) -> {
            log.info("playPauseAction: callback action");
            if (this.appManager.getState().player().state().isPlaying()) {
                this.appManager.pause();
            } else {
                this.appManager.play();
            }
            return true;
        });
        this.playPauseShortcut = Shortcut.builder().setTrigger(playPauseTrigger).setAction(playPauseAction).build();

        var shuffleModeTrigger = ShortcutTrigger.parseString("<Control>s");
        var shuffleModeShortCutAction = new CallbackAction((Widget widget, @Nullable Variant args) -> {
            log.info("shuffleModeShortCutAction: callback action");
            var current = this.appManager.getState().queue().playMode();
            PlayMode next  = current == PlayMode.SHUFFLE ? PlayMode.NORMAL : PlayMode.SHUFFLE;
            this.appManager.handleAction(new PlayerAction.SetPlayMode(next));
            return true;
        });
        this.shuffleModeShortCut = Shortcut.builder().setTrigger(shuffleModeTrigger).setAction(shuffleModeShortCutAction).build();


        this.toolbarView = ToolbarView.builder().build();
        this.commandPalette = new CommandPalette(appManager, toolbarView);
        var commandPaletteTrigger = ShortcutTrigger.parseString("<Control>k|<Meta>k");
        var commandPaletteAction = new CallbackAction((Widget widget, @Nullable Variant args) -> {
            log.info("command palette toggle");
            this.commandPalette.toggle();
            return true;
        });
        commandPaletteShortcut = Shortcut.builder().setTrigger(commandPaletteTrigger).setAction(commandPaletteAction).build();

        // Create popover menu content
        var popoverContent = Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setSpacing(8)
                .setMarginTop(4)
                .setMarginBottom(4)
                .setMarginStart(4)
                .setMarginEnd(4)
                .build();

        // Server badge at the top
        this.serverBadge = new ServerBadge(this.toolbarView, appManager, () -> settingsPopover.popdown());
        popoverContent.append(serverBadge);

        this.settingsPopover = Popover.builder()
                .setChild(popoverContent)
                .build();
        this.settingsPopover.onShow(serverBadge::refresh);

//        configureServerButton.onClicked(() -> {
//            settingsPopover.popdown();
//            appNavigation.navigateTo(new AppNavigation.AppRoute.SettingsPage());
//        });

        settingsButton = MenuButton.builder()
                .setIconName(Icons.Settings.getIconName())
                .setTooltipText("Settings")
                .setPopover(this.settingsPopover)
                .build();

        viewSwitcher = ViewSwitcher.builder()
                .setPolicy(ViewSwitcherPolicy.WIDE)
                .setStack(viewStack)
                .build();

        this.backButton = Button.builder()
                .setIconName("go-previous-symbolic")
                .setTooltipText("Back")
                .build();
        this.backButton.addCssClass(Classes.flat.className());
        this.backButton.setVisible(false);
        this.backButton.onClicked(() -> navigationView.pop());

        var searchButton = new Button();
        searchButton.setLabel("Search");
        searchButton.setTooltipText("Search");
        searchButton.onClicked(() -> this.commandPalette.toggle());
        searchButton.addCssClass("flat");
        searchButton.setIconName(Icons.Search.getIconName());

        headerBar = HeaderBar.builder()
                .setHexpand(true)
                .setTitleWidget(viewSwitcher)
                .setShowBackButton(true)
                .build();
        headerBar.packStart(this.backButton);
        headerBar.packEnd(settingsButton);
        headerBar.packEnd(searchButton);
        // TODO: these classes were an attempt to find better background colors to blend different parts of the UI
        //headerBar.addCssClass("background");
        //headerBar.addCssClass("view");

        playerBar = new PlayerBar(appManager);
        bottomBar = new Box(Orientation.VERTICAL, 2);
        bottomBar.append(playerBar);

        toolbarView.addTopBar(headerBar);
        toolbarView.setContent(toastOverlay);
        toolbarView.addBottomBar(bottomBar);

        viewStack.getPages().onSelectionChanged((position, nItems) -> {
            var visibleChild = viewStack.getVisibleChildName();
            log.info("viewSwitcher.Pages.SelectionModel.onSelectionChanged.visibleChild: {}", visibleChild);
            if (visibleChild == null) {
                return;
            }
            // clear navigation stack that could be over the page we just selected:
            int size = navigationView.getNavigationStack().getNItems();
            log.info("viewStack.onSelectionChanged: stack size={}", size);
            while (size > 1) {
                navigationView.pop();
                size--;
            }
        });
        navigationView.onPushed(() -> {
            int size = navigationView.getNavigationStack().getNItems();
            log.info("navigationView.push: size={}", size);
            boolean canPop = size > 1;
            this.backButton.setVisible(canPop);
            //headerBar.setShowBackButton(canPop);
        });

        navigationView.onPopped(page -> {
            int size = navigationView.getNavigationStack().getNItems();
            log.info("navigationView.pop: size={} page={} child={}", size, page.getClass().getName(), page.getChild().getClass().getName());
            boolean canPop = size > 1;
            //  When navigating to an album or artist page via RouteAlbumInfo or RouteArtistInfo, a NavigationPage is pushed onto the NavigationView,
            //  but there's no back button in the header bar to pop the page.
            //  The AdwHeaderBar is placed outside the NavigationView in a global ToolbarView.
            //  The automatic back button behavior from libadwaita only works when HeaderBar is inside each NavigationPage.
            //  Moving it inside would require restructuring and duplicating the HeaderBar for each page view,
            //  so I am not certain of the trade-offs here. Its also very nice that the HeaderBar is always the same HeaderBar.
            this.backButton.setVisible(canPop);
            //headerBar.setShowBackButton(canPop);
        });

    }

    public void runActivate(Application app) {
        this.appNavigation = new AppNavigation((appRoute) -> switch (appRoute) {
            case AppNavigation.AppRoute.RouteAlbumsOverview routeAlbumsOverview -> {
                viewStack.setVisibleChildName("albumsPage");
                yield true;
            }
            case AppNavigation.AppRoute.RouteArtistInfo routeArtistInfo -> {
                var content = new ArtistInfoLoader(this.thumbLoader, this.appManager, albumInfo -> {
                    appNavigation.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(albumInfo.id()));
                });
                content.setArtistId(routeArtistInfo.artistId());
                var albumPage = NavigationPage.builder().setChild(content).build();
                navigationView.push(albumPage);
                yield true;
            }
            case AppNavigation.AppRoute.RouteArtistsOverview routeArtistsOverview -> {
                viewStack.setVisibleChildName("artistsPage");
                yield true;
            }
            case AppNavigation.AppRoute.RouteHome routeHome -> {
                viewStack.setVisibleChildName("testPage");
                yield false;
            }
            case AppNavigation.AppRoute.RoutePlaylistsOverview route -> {
                viewStack.setVisibleChildName("playlistPage");
                route.preselect().ifPresent(id -> playlistsViewLoader.preselect(id.playlistId()));
                yield false;
            }
            case AppNavigation.AppRoute.RouteStarred starred -> {
                viewStack.setVisibleChildName("starredPage");
                yield false;
            }
            case AppNavigation.AppRoute.SettingsPage s -> {
                var cfg = this.appManager.getConfig();
                var info = cfg.serverConfig == null ? null : new ServerConfigForm.SettingsInfo(
                        cfg.serverConfig.type(),
                        cfg.serverConfig.url(),
                        cfg.serverConfig.username(),
                        cfg.serverConfig.password(),
                        cfg.serverConfig.tlsSkipVerify()
                );
                var transcodeSettings = cfg.serverConfig != null
                        ? new ServerClient.TranscodeSettings(cfg.serverConfig.audioFormat(), cfg.serverConfig.audioBitrate())
                        : null;
                var settings = new SettingsPage(
                        info,
                        cfg.dataDir,
                        transcodeSettings,
                        appManager::handleAction
                );
                NavigationPage navPage = NavigationPage.builder().setChild(settings).setTitle("Settings").build();
                navigationView.push(navPage);

                yield true;
            }
            case AppNavigation.AppRoute.RouteAlbumInfo route -> {
                var viewAlbumPage = new AlbumInfoLoader(this.thumbLoader, this.appManager, appManager::handleAction)
                        .setAlbumId(route.albumId());
                NavigationPage albumPage = NavigationPage.builder().setChild(viewAlbumPage).setTitle("Album").build();
                //var albumPage = new SubsoundPage(viewAlbumPage, "Album");
                navigationView.push(albumPage);

//                albumInfoContainer.setAlbumId(route.albumId());
//                viewStack.setVisibleChildName("albumInfoPage");
                yield true;
            }
        });
        this.appManager.setNavigator(this.appNavigation);
        var cfg = this.appManager.getConfig();

        {
            if (appManager.getConfig().isTestpageEnabled) {
                var testPlayerPage = new TestPlayerPage(this.appManager);
                if (!testPlayerPage.knownSongs.isEmpty()) {
                    var songInfo = testPlayerPage.knownSongs.getFirst().toSongInfo();
                    this.appManager.loadSourceAsync(new PlayerAction.PlaySong(songInfo)).join();
                }
                ViewStackPage testPage = viewStack.addTitled(testPlayerPage, "testPage", "Testpage");
            }
        }
        {
            var frontPageContainer = new FrontpagePage(
                    thumbLoader,
                    appManager,
                    albumInfo -> this.appNavigation.navigateTo(new AppNavigation.AppRoute.RouteAlbumInfo(albumInfo.id())),
                    this.appNavigation::navigateTo
            );
            ViewStackPage frontPage = viewStack.addTitledWithIcon(frontPageContainer, "frontPage", "Home", Icons.GoHome.getIconName());
        }
        {
            var playlistsContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            this.playlistsViewLoader = new PlaylistsViewLoader(thumbLoader, appManager, appNavigation::navigateTo);
            playlistsContainer.append(this.playlistsViewLoader);
            ViewStackPage starredPage = viewStack.addTitledWithIcon(playlistsContainer, "playlistPage", "Playlists", Icons.Starred.getIconName());
        }
        {
            var artistsContainer = BoxFullsize().setValign(Align.FILL).setHalign(Align.FILL).build();
            var artistListBox = new ArtistListLoader(
                    thumbLoader,
                    appManager,
                    this.appNavigation::navigateTo
            );
            artistsContainer.append(artistListBox);
            ViewStackPage artistsPage = viewStack.addTitledWithIcon(artistsContainer, "artistsPage", "Artists", Icons.Artist.getIconName());
        }
        if (cfg.onboarding != OnboardingState.DONE) {
            var onboardingOverlay = getOnboardingOverlay(this.appManager, viewStack);
            var onboardingPage = NavigationPage.builder().setChild(onboardingOverlay).setTag("onboardingOverlay").build();
            navigationView.push(onboardingPage);
        } else {
            var mainPage = NavigationPage.builder().setChild(viewStack).setTag("main").build();
            navigationView.push(mainPage);
        }

        var shortcutController = new ShortcutController();
        shortcutController.addShortcut(this.playPauseShortcut);
        shortcutController.addShortcut(this.shuffleModeShortCut);
        shortcutController.addShortcut(this.commandPaletteShortcut);
        shortcutController.setScope(ShortcutScope.GLOBAL);

        // Pack everything together, and show the window
        this.window = ApplicationWindow.builder()
                .setApplication(app)
                .setDefaultWidth(cfg.windowWidth).setDefaultHeight(cfg.windowHeight)
                .setContent(commandPalette)
                .build();
        window.addController(shortcutController);

        // Capture window size before it's destroyed
        window.onCloseRequest(() -> {
            this.lastWindowSize = new WindowSize(window.getWidth(), window.getHeight());
            return false; // Allow window to close
        });

        window.present();
    }

    @NonNull
    private OnboardingOverlay getOnboardingOverlay(AppManager appManager, ViewStack viewStack) {
        return new OnboardingOverlay(appManager, viewStack);
    }

//    public class SubsoundPage extends NavigationPage {
//        public SubsoundPage(Widget child, String title) {
//            super(wrap(child, headerBar, bottomBar), title);
//        }
//
//        private static ToolbarView wrap(Widget child, Widget headerBar, Widget bottomBar) {
//            ToolbarView toolbarView = ToolbarView.builder().build();
//            toolbarView.addTopBar(headerBar);
//            toolbarView.setContent(child);
//            toolbarView.addBottomBar(bottomBar);
//            return toolbarView;
//        }
//    }

    public static Box.Builder<? extends Box.Builder> BoxFullsize() {
        return Box.builder()
                .setOrientation(Orientation.VERTICAL)
                .setValign(Align.CENTER)
                .setHalign(Align.CENTER)
                .setVexpand(true)
                .setHexpand(true);
    }

    public record WindowSize(int width, int height) {}

    public WindowSize getLastWindowSize() {
        return lastWindowSize;
    }

    public void shutdown() {
        this.serverBadge.shutdown();
    }

    private static void registerCustomIcons() {
        var iconTheme = IconTheme.getForDisplay(Display.getDefault());
        // In Flatpak, icons are installed to /app/share/icons/hicolor/ which GTK searches by default.
        // For local development, add the resources directory as an additional search path:
        var localPath = Path.of("src/main/resources/icons");
        if (Files.isDirectory(localPath.resolve("hicolor"))) {
            iconTheme.addSearchPath(localPath.toAbsolutePath().toString());
            log.info("registerCustomIcons: added local icon search path: {}", localPath.toAbsolutePath());
        }
    }
}