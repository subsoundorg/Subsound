package org.subsound.ui.components;

import org.gnome.adw.ActionRow;
import org.gnome.adw.Clamp;
import org.gnome.adw.ResponseAppearance;
import org.gnome.adw.SwitchRow;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.SelectionMode;
import org.gnome.gtk.Widget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.NetworkMonitoring.NetworkState;
import org.subsound.app.state.NetworkMonitoring.NetworkStatus;
import org.subsound.app.state.PlayerAction;
import org.subsound.integration.ServerClient;
import org.subsound.ui.views.AboutView;
import org.subsound.utils.Utils;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.subsound.ui.components.Classes.boxedList;

public class ServerBadge extends Box implements AppManager.StateListener {
    private static final Logger log = LoggerFactory.getLogger(ServerBadge.class);

    private final Widget parentWindow;
    private final AppManager appManager;
    private final ActionRow serverRow;
    private final ActionRow statsRow;
    private final Label statusDot;
    private final ActionRow triggerScanButton;
    private final SwitchRow offlineSwitch;
    private final ActionRow syncButton;
    private final ActionRow configureServerButton;
    private final ActionRow logoutButton;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pingTask;
    private volatile NetworkStatus currentNetworkStatus = NetworkStatus.ONLINE;
    private final AtomicBoolean updatingSwitch = new AtomicBoolean(false);

    public ServerBadge(
            Widget parentWindow,
            AppManager appManager,
            Runnable onClose
    ) {
        super(Orientation.VERTICAL, 0);
        this.parentWindow = parentWindow;
        this.appManager = appManager;

        this.setMarginTop(8);
        this.setMarginBottom(8);
        this.setMarginStart(8);
        this.setMarginEnd(8);

        this.triggerScanButton = ActionRow.builder()
                .setTitle("Trigger scan")
                .setTooltipText("Start server scan for new songs and folders")
                .setActivatable(true)
                .build();
        var scanIcon = Image.fromIconName("emblem-synchronizing-symbolic");
        scanIcon.setPixelSize(16);
        scanIcon.setSizeRequest(32,-1);
        scanIcon.setHalign(Align.CENTER);
        scanIcon.setValign(Align.CENTER);
        triggerScanButton.addPrefix(scanIcon);
        triggerScanButton.addCssClass(Classes.flat.className());
        triggerScanButton.addCssClass(Classes.heading.className());
        triggerScanButton.onActivated(() -> {
            onClose.run();
            appManager.handleAction(new PlayerAction.TriggerServerScan());
        });

        var aboutButton = ActionRow.builder()
                .setTitle("About")
                .setActivatable(true)
                .build();
        var aboutIcon = Image.fromIconName("help-about-symbolic");
        aboutIcon.setPixelSize(16);
        aboutIcon.setSizeRequest(32,-1);
        aboutIcon.setHalign(Align.CENTER);
        aboutIcon.setValign(Align.CENTER);
        aboutButton.addPrefix(aboutIcon);
        aboutButton.addCssClass(Classes.flat.className());
        aboutButton.addCssClass(Classes.heading.className());
        aboutButton.onActivated(() -> {
            onClose.run();
            AboutView.show(this.parentWindow);
        });

        // Status indicator shown as suffix on the server row
        this.statusDot = Label.builder()
                .setLabel("\u25CF") // ●
                .setValign(Align.CENTER)
                .setTooltipText("Checking…")
                .build();
        this.statusDot.addCssClass(Classes.labelDim.className());

        // Server hostname + username row
        this.serverRow = ActionRow.builder()
                .setTitle(getServerHostNameOrNotConnected())
                .setSubtitle("Checking connectivity…")
                .setUseMarkup(false)
                .build();
        this.serverRow.setSubtitleLines(1);
        this.serverRow.setTitleLines(1);
        this.serverRow.addPrefix(statusDot);

        // Songs / folders / version — hidden until server info arrives
        this.statsRow = ActionRow.builder()
                .setTitle("")
                .setSubtitle("")
                .setUseMarkup(false)
                .setVisible(false)
                .build();

        // Offline mode toggle
        this.offlineSwitch = SwitchRow.builder()
                .setTitle("Offline mode")
                .setSubtitle("Use only cached data. Remember to Sync Library to enable offline mode.")
                .build();
        this.offlineSwitch.onNotify("active", _ -> {
            if (updatingSwitch.get()) return;
            if (offlineSwitch.getActive()) {
                appManager.handleAction(new PlayerAction.OverrideNetworkStatus(Optional.of(NetworkStatus.OFFLINE)));
            } else {
                appManager.handleAction(new PlayerAction.OverrideNetworkStatus(Optional.empty()));
            }
        });

        // Sync library action
        this.syncButton = ActionRow.builder()
                .setTitle("Sync library")
                .setActivatable(true)
                .setTooltipText("Syncs metadata for offline use")
                .build();
        this.syncButton.addCssClass(Classes.flat.className());
        this.syncButton.addCssClass(Classes.heading.className());
        var syncIcon = Image.fromIconName("view-refresh-symbolic");
        syncIcon.setPixelSize(16);
        syncIcon.setSizeRequest(32,-1);
        syncIcon.setHalign(Align.CENTER);
        syncIcon.setValign(Align.CENTER);
        syncButton.addPrefix(syncIcon);
        this.syncButton.onActivated(() -> {
            onClose.run();
            syncButton.setSensitive(false);
            appManager.handleAction(new PlayerAction.SyncDatabase()).whenComplete((v, err) ->
                    Utils.runOnMainThread(() -> syncButton.setSensitive(currentNetworkStatus != NetworkStatus.OFFLINE))
            );
        });

        this.configureServerButton = ActionRow.builder()
                .setTitle("Configure server")
                .setActivatable(true)
                .build();
        var configIcon = Image.fromIconName(Icons.Settings.getIconName());
        configIcon.setPixelSize(16);
        configIcon.setSizeRequest(32,-1);
        configIcon.setHalign(Align.CENTER);
        configIcon.setValign(Align.CENTER);
        this.configureServerButton.addCssClass(Classes.flat.className());
        this.configureServerButton.addCssClass(Classes.heading.className());
        configureServerButton.addPrefix(configIcon);
        this.configureServerButton.onActivated(() -> {
            onClose.run();
            appManager.navigateTo(new AppNavigation.AppRoute.SettingsPage());
        });

        this.logoutButton = ActionRow.builder()
                .setTitle("Log out")
                .setActivatable(true)
                .setTooltipText("Delete all settings and cached data, then quit")
                .build();
        var logoutIcon = Image.fromIconName("system-log-out-symbolic");
        logoutIcon.setPixelSize(16);
        logoutIcon.setSizeRequest(32, -1);
        logoutIcon.setHalign(Align.CENTER);
        logoutIcon.setValign(Align.CENTER);
        logoutButton.addPrefix(logoutIcon);
        logoutButton.addCssClass(Classes.flat.className());
        logoutButton.addCssClass("error");
        logoutButton.onActivated(() -> {
            onClose.run();
            AdwDialogHelper.ofDialog(
                    parentWindow,
                    "Log out?",
                    "This will delete all settings, cached songs, thumbnails, and the local database, then quit the app.",
                    List.of(
                            new AdwDialogHelper.Response("cancel", "_Cancel"),
                            new AdwDialogHelper.Response("logout", "_Log out", ResponseAppearance.DESTRUCTIVE)
                    )
            ).thenAccept(result -> {
                if ("logout".equals(result.label())) {
                    appManager.handleAction(new PlayerAction.Logout());
                }
            });
        });

        var list = ListBox.builder()
                .setSelectionMode(SelectionMode.NONE)
                .setCssClasses(boxedList.add())
                .build();
        list.append(serverRow);
        list.append(statsRow);
        list.append(offlineSwitch);
        list.append(triggerScanButton);
        list.append(syncButton);
        list.append(configureServerButton);
        list.append(aboutButton);
        list.append(logoutButton);

        var clamp = new Clamp();
        clamp.setMaximumSize(240);
        clamp.setHalign(Align.FILL);
        clamp.setValign(Align.FILL);
        clamp.setChild(list);
        this.append(clamp);

        this.pingTask = scheduler.scheduleWithFixedDelay(this::checkConnectivity, 0, 30, TimeUnit.SECONDS);

        this.appManager.addOnStateChanged(this);
        var initialNetworkState = this.appManager.getState().networkState();
        this.currentNetworkStatus = initialNetworkState.status();
        updateNetworkStatus(initialNetworkState);

        this.onDestroy(() -> {
            this.appManager.removeOnStateChanged(this);
            if (pingTask != null) {
                pingTask.cancel(false);
            }
            scheduler.shutdown();
        });
    }

    /**
     * Called when the popover is shown to refresh hostname and stats.
     */
    public void refresh() {
        serverRow.setTitle(getServerHostNameOrNotConnected());
        Utils.doAsync(this::fetchServerStatus).thenAccept(status ->
                Utils.runOnMainThread(() -> applyStatus(status))
        );
    }

    private void checkConnectivity() {
        try {
            var status = fetchServerStatus();
            Utils.runOnMainThread(() -> applyStatus(status));
        } catch (Exception e) {
            log.info("Connectivity check failed", e);
            var usernameOpt = appManager.getConfig().getServerConfig().map(cfg -> cfg.username());
            Utils.runOnMainThread(() -> applyStatus(new ServerStatus(usernameOpt, false, null)));
        }
    }

    private record ServerStatus(Optional<String> username, boolean online, ServerClient.ServerInfo serverInfo) {}

    private ServerStatus fetchServerStatus() {
        var usernameOpt = appManager.getConfig().getServerConfig().map(cfg -> cfg.username());
        if (appManager.getConfig().getServerConfig().isEmpty()) {
            return new ServerStatus(usernameOpt, false, null);
        }
        try {
            boolean online = appManager.useClient(ServerClient::testConnection);
            if (online) {
                var info = appManager.useClient(ServerClient::getServerInfo);
                return new ServerStatus(usernameOpt, true, info);
            }
            return new ServerStatus(usernameOpt, false, null);
        } catch (Exception e) {
            log.info("Failed to fetch server status", e);
            return new ServerStatus(usernameOpt, false, null);
        }
    }

    private void applyStatus(ServerStatus status) {
        serverRow.setSubtitle(status.username().map("@%s"::formatted).orElse(""));

        if (status.online()) {
            statusDot.removeCssClass("error");
            statusDot.removeCssClass(Classes.labelDim.className());
            statusDot.addCssClass("success");
            statusDot.setTooltipText("Online");

            if (status.serverInfo() != null) {
                var info = status.serverInfo();
                var folders = info.folderCount().map(" · %d folders"::formatted).orElse("");
                statsRow.setTitle("%,d songs%s".formatted(info.songCount(), folders));
                statsRow.setSubtitle(
                        info.serverVersion().map("v%s"::formatted)
                                .orElse("API v" + info.apiVersion())
                );
                statsRow.setVisible(true);
            }
        } else {
            statusDot.removeCssClass("success");
            statusDot.removeCssClass(Classes.labelDim.className());
            statusDot.addCssClass("error");
            statusDot.setTooltipText("Offline");
            statsRow.setVisible(false);
        }
    }

    private String getServerHostNameOrNotConnected() {
        return getServerHostName().map(ss -> Utils.getEnv("SERVER_HOST", "").equals(ss) ? "server.example.org" : ss).orElse("Not connected");
    }

    private Optional<String> getServerHostName() {
        var cfg = this.appManager.getConfig();
        String serverHost = null;
        if (cfg.serverConfig != null && cfg.serverConfig.url() != null && !cfg.serverConfig.url().isBlank()) {
            try {
                URI uri = URI.create(cfg.serverConfig.url());
                serverHost = uri.getHost();
                if (serverHost == null) {
                    serverHost = cfg.serverConfig.url();
                } else {
                    var parts = serverHost.split(":");
                    serverHost = parts[0];
                }
            } catch (Exception e) {
                serverHost = cfg.serverConfig.url();
            }
        }
        return Optional.ofNullable(serverHost);
    }

    public void shutdown() {
        if (pingTask != null) {
            pingTask.cancel(false);
        }
        scheduler.shutdown();
    }

    @Override
    public void onStateChanged(AppManager.AppState state) {
        var networkState = state.networkState();
        if (networkState.status() != this.currentNetworkStatus) {
            this.currentNetworkStatus = networkState.status();
            Utils.runOnMainThread(() -> updateNetworkStatus(networkState));
        }
    }

    private void updateNetworkStatus(NetworkState networkState) {
        updatingSwitch.set(true);
        try {
            boolean offline = networkState.status() == NetworkStatus.OFFLINE;
            offlineSwitch.setActive(offline);
            if (offline) {
                triggerScanButton.setSensitive(false);
                syncButton.setSensitive(false);
                syncButton.setTooltipText("Must be online to sync library");
            } else {
                triggerScanButton.setSensitive(true);
                syncButton.setSensitive(true);
                syncButton.setTooltipText("Sync library to enable offline mode");
            }
        } finally {
            updatingSwitch.set(false);
        }
    }
}