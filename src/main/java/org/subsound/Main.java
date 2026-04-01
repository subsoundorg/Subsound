package org.subsound;

import org.subsound.app.state.AppManager;
import org.subsound.configuration.Config;
import org.subsound.configuration.constants.Constants;
import org.subsound.integration.platform.secret.SecretService;
import org.subsound.integration.platform.mpriscontroller.ArtworkHttpServer;
import org.subsound.integration.platform.mpriscontroller.MPrisController;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.utils.LogUtils;
import org.subsound.utils.Utils;
import org.freedesktop.gstreamer.gst.Gst;
import org.gnome.adw.Application;
import org.gnome.glib.GLib;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.gio.ApplicationFlags;
import org.javagi.base.Out;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class Main {
    private final static Logger log = LoggerFactory.getLogger(Main.class);
    private final static String rootLogLevel = Optional.ofNullable(System.getenv("JAVA_LOG_LEVEL")).orElse("INFO");
    static {
        LogUtils.setRootLogLevel(rootLogLevel);
        // Bridge/route all JUL log records to the SLF4J API.
        SLF4JBridgeHandler.install();
    }

    private final Config config;
    private final AppManager appManager;
    private final MPrisController mprisController;
    private final ArtworkHttpServer artworkServer;

    public Main(String[] args) {
        // Initialisation Gst
        Gst.init(new Out<>(new String[]{}));
        var supportedImageFormats = Pixbuf.getFormats();
        var supportedMsg = supportedImageFormats.stream()
                .map(format -> format.getName())
                .collect(Collectors.joining(",")).toLowerCase();
        if (supportedMsg.contains("webp")) {
            log.info("pixbufFormats supported={}", supportedMsg);
        } else {
            log.warn("pixbufFormats supported={}", supportedMsg);
            log.warn("GDK pixbufFormats reports missing webp support");
        }

        var secretService = SecretService.create();
        this.config = Config.createDefault(secretService);
        var thumbnailCache = new ThumbnailCache(config.dataDir);
        var player = new PlaybinPlayer();
        var mainAppRef = new AtomicReference<MainApplication>();
        var app = new Application(Constants.APP_ID, ApplicationFlags.DEFAULT_FLAGS);
        this.appManager = new AppManager(this.config, player, thumbnailCache, app::quit);
        this.artworkServer = new ArtworkHttpServer(thumbnailCache);
        this.mprisController = new MPrisController(appManager, artworkServer);
        Utils.doAsync(() -> {
            try {
                this.mprisController.run();
            } catch (Throwable throwable) {
                log.warn("error starting mprisController: ", throwable);
            }
        });

        // Handle SIGINT (Ctrl+C) and SIGTERM for graceful shutdown
        GLib.unixSignalAdd(GLib.PRIORITY_DEFAULT, 2, () -> {  // SIGINT
            log.info("Received SIGINT, shutting down...");
            app.quit();
            return GLib.SOURCE_REMOVE;
        });
        GLib.unixSignalAdd(GLib.PRIORITY_DEFAULT, 15, () -> { // SIGTERM
            log.info("Received SIGTERM, shutting down...");
            app.quit();
            return GLib.SOURCE_REMOVE;
        });

        try {
            app.onActivate(() -> {
                MainApplication mainApp = new MainApplication(appManager);
                mainAppRef.set(mainApp);
                mainApp.runActivate(app);
            });
            app.onShutdown(() -> {
                var mainApp = mainAppRef.get();
                if (mainApp != null) {
                    // Save window size before shutdown
                    var size = mainApp.getLastWindowSize();
                    if (size != null) {
                        this.config.windowWidth = size.width();
                        this.config.windowHeight = size.height();
                        try {
                            config.saveToFile();
                        } catch (Exception e) {
                            log.warn("Failed to save config", e);
                        }
                    }
                    mainApp.shutdown();
                }
                mprisController.stop();
                artworkServer.stop();
                player.quit();
                appManager.shutdown();
                Utils.ASYNC_EXECUTOR.shutdownNow();
            });
            app.run(args);
        } finally {
            player.quit();
        }
    }

    public static void main(String[] args) {
        new Main(args);
    }
}

