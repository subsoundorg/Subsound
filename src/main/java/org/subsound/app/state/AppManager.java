package org.subsound.app.state;

import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.BehaviorSubject;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.gnome.adw.ToastOverlay;
import org.gnome.gio.ListStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.NetworkMonitoring.NetworkState;
import org.subsound.app.state.PlayerAction.Enqueue;
import org.subsound.app.state.PlayerAction.PlayPositionInQueue;
import org.subsound.configuration.Config;
import org.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.PlaylistCreateRequest;
import org.subsound.integration.ServerClient.PlaylistDeleteRequest;
import org.subsound.integration.ServerClient.PlaylistRemoveSongRequest;
import org.subsound.integration.ServerClient.PlaylistRenameRequest;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeFormat;
import org.subsound.persistence.CachingClient;
import org.subsound.persistence.DownloadManager;
import org.subsound.persistence.ScrobbleService;
import org.subsound.persistence.SongCache;
import org.subsound.persistence.SongCache.CacheSong;
import org.subsound.persistence.SongCache.LoadSongResult;
import org.subsound.persistence.ThumbnailCache;
import org.subsound.persistence.database.Database;
import org.subsound.persistence.database.DatabaseService;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.Server;
import org.subsound.persistence.database.DownloadQueueItem;
import org.subsound.persistence.database.PlayerConfig;
import org.subsound.persistence.database.PlayerConfigService;
import org.subsound.persistence.database.PlayerStateJson;
import org.subsound.persistence.database.SyncService;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.sound.PlaybinPlayer.AudioSource;
import org.subsound.sound.PlaybinPlayer.Source;
import org.subsound.ui.components.AppNavigation;
import org.subsound.ui.models.GQueueItem;
import org.subsound.ui.models.GSongInfo;
import org.subsound.ui.models.GSongStore;
import org.subsound.utils.Utils;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.subsound.app.state.AppManager.NowPlaying.State.LOADING;
import static org.subsound.app.state.AppManager.NowPlaying.State.READY;
import static org.subsound.utils.Utils.doAsync;
import static org.subsound.utils.Utils.runOnMainThread;
import static org.subsound.utils.Utils.sha256;
import static org.subsound.utils.Utils.timeIt;

public class AppManager {
    private static final Logger log = LoggerFactory.getLogger(AppManager.class);

    public static final Executor ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    public static final String SERVER_ID = "8034888b-5544-4dbe-b9ec-be5ad02831cd";

    private final Config config;
    private final PlaybinPlayer player;
    private final PlayQueue playQueue;
    private final SongCache songCache;
    private final ThumbnailCache thumbnailCache;
    private final AtomicReference<CachingClient> client;
    private final BehaviorSubject<AppState> currentState;
    private final CopyOnWriteArrayList<StateListener> listeners = new CopyOnWriteArrayList<>();
    private final GSongStore gSongStore;
    private final StarredListStore starredList;
    private final PlaylistsStore playlistsStore;
    private final SearchResultStore searchResultStore;
    private final ScheduledExecutorService preferenceSaveScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Database database;
    private final DatabaseService databaseService;
    private final DatabaseServerService dbService;
    private final PlayerConfigService playerConfigService;
    private final DownloadManager downloadManager;
    private final ScrobbleService scrobbleService;
    private final NetworkMonitoring networkMonitor;
    private final Runnable onQuit;
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> pendingPreferenceSave;
    private volatile UUID scrobbledForRequestId = null;

    private ToastOverlay toastOverlay;
    private AppNavigation navigator;

    public AppManager(
            Config config,
            PlaybinPlayer player,
            ThumbnailCache thumbnailCache,
            Runnable onQuit
    ) {
        this.config = config;
        this.onQuit = onQuit;
        this.player = player;
        this.thumbnailCache = thumbnailCache;
        this.database = new Database();
        this.databaseService = new DatabaseService(this.database);
        this.playerConfigService = new PlayerConfigService(this.database);
        // Apply saved player preferences (volume/mute) from DB
        // Must be done after currentState is initialized since setVolume/setMute trigger state changes
        var savedConfig = this.playerConfigService.loadPlayerConfig();
        var savedPlayerState = savedConfig
                .map(PlayerConfig::playerState)
                .orElse(PlayerStateJson.defaultState());

        var savedServerId = savedConfig.map(PlayerConfig::serverId).orElse(SERVER_ID);
        this.dbService = new DatabaseServerService(
                UUID.fromString(savedServerId),
                this.database
        );
        this.songCache = new SongCache(
                config.dataDir,
                transcodeInfo -> this.useClient(c -> c.openStream(transcodeInfo))
        );
        this.gSongStore = new GSongStore(
                songId -> this.useClient(c -> c.getSong(songId))
        );
        this.downloadManager = new DownloadManager(
                dbService,
                songCache,
                downloadEvent -> {
                    this.gSongStore.setDownloadState(
                            downloadEvent.item().songId(),
                            downloadEvent.type().toState()
                    );
                }
        );
        // initial load of download queue:
        this.downloadManager.listDownloads().forEach(item -> {
            this.gSongStore.setDownloadState(item.songId(), item.status().toState());
        });
        this.networkMonitor = new GioNetworkStatusMonitor(this::updateNetworkState);
        this.playQueue = new PlayQueue(
                player,
                this.gSongStore,
                nextState -> this.setState(old -> old.withQueue(nextState)),
                songInfo -> loadSourceAsync(new PlayerAction.PlaySong(songInfo.getSongInfo()))
        );

        // Load server configuration from DB (or migrate from legacy JSON)
        this.client = new AtomicReference<>();
        this.thumbnailCache.setDownloader(
                (coverArt, maxSize) -> this.useClient(c -> c.downloadCoverArt(coverArt, maxSize))
        );
        initServerConfig(config);

        player.onStateChanged(next -> {
            this.setState(old -> old.withPlayer(next));
            this.checkScrobble(next);
        });

        this.currentState = BehaviorSubject.createDefault(buildState());
        var disposable = this.currentState
                .throttleLatest(250, TimeUnit.MILLISECONDS, true)
                //.throttleLatest(50, TimeUnit.MILLISECONDS, true)
                .observeOn(Schedulers.io())
                .subscribeOn(Schedulers.io())
                .forEach(next -> this.notifyListeners());

        // restore saved volume:
        this.player.setVolume(savedPlayerState.volume());
        this.player.setMute(savedPlayerState.muted());

        // Restore last playing song from DB (without auto-playing)
        var lastPlayback = savedPlayerState.currentPlayback();
        if (lastPlayback != null && lastPlayback.songId() != null && !lastPlayback.songId().isBlank()) {
            restoreLastPlayingSong(lastPlayback);
        }

        this.starredList = new StarredListStore(this);
        this.playlistsStore = new PlaylistsStore(this);
        this.searchResultStore = new SearchResultStore(this.client::get);

        if (this.client.get() != null) {
            this.starredList.refreshAsync();
            this.playlistsStore.refreshListAsync();
        }
        this.scrobbleService = new ScrobbleService(
                dbService,
                () -> this.client.get(),
                () -> this.getState().networkState()
        );
    }

    private void initServerConfig(Config config) {
        if (config.serverId == null) {
            return; // No server configured — onboarding needed
        }

        var secretService = config.getSecretService();
        var serverOpt = this.databaseService.getServerById(config.serverId);

        if (serverOpt.isPresent()) {
            // Server found in DB — build config from it
            var server = serverOpt.get();
            var password = resolvePassword(secretService, server.id().toString(), server.username(), config);
            config.serverConfig = buildServerConfig(config.dataDir, server, password);
            var serverClient = ServerClient.create(config.serverConfig);
            this.client.set(wrapWithCaching(serverClient));
        } else if (config.legacyServerConfig != null) {
            // Legacy JSON config — migrate to DB
            log.info("Migrating legacy server config to database for serverId={}", config.serverId);
            var legacy = config.legacyServerConfig;
            var password = resolvePassword(secretService, legacy.id(), legacy.username(), config);
            // Also check legacy password from JSON (may be plain-text in old format)
            if (password == null && legacy.password() != null && !legacy.password().isBlank()) {
                password = legacy.password();
                // Migrate to keyring
                boolean stored = secretService.storeCredentialsSync(legacy.id(), legacy.username(), password);
                if (stored) {
                    log.info("Migrated legacy password to keyring for server={}", legacy.id());
                }
            }

            var server = new Server(
                    UUID.fromString(legacy.id()),
                    true,
                    legacy.type(),
                    legacy.url(),
                    legacy.username(),
                    Instant.now(),
                    false,
                    legacy.audioFormat(),
                    legacy.transcodeBitrate()
            );
            this.databaseService.insert(server);
            config.legacyServerConfig = null;

            // Re-save JSON in new format (strips legacy server fields)
            try {
                config.saveToFile();
                log.info("Saved config in new format (server details moved to database)");
            } catch (IOException e) {
                log.warn("Failed to re-save config after migration", e);
            }

            config.serverConfig = buildServerConfig(config.dataDir, server, password);
            var serverClient = ServerClient.create(config.serverConfig);
            this.client.set(wrapWithCaching(serverClient));
        } else {
            log.warn("serverId={} set in config but no server found in database", config.serverId);
        }
    }

    private static @org.jspecify.annotations.Nullable String resolvePassword(
            org.subsound.integration.platform.secret.SecretService secretService,
            String serverId,
            String username,
            Config config
    ) {
        var creds = secretService.lookupCredentialsSync(serverId);
        if (creds != null) {
            return creds.password();
        }
        // Fallback: password stored in config file when keyring is unavailable (e.g. macOS)
        return config.fallbackPassword;
    }

    private static Config.ServerConfig buildServerConfig(Path dataDir, Server server, @org.jspecify.annotations.Nullable String password) {
        TranscodeFormat audioFormat = null;
        if (server.audioFormat() != null) {
            try {
                audioFormat = TranscodeFormat.valueOf(server.audioFormat());
            } catch (IllegalArgumentException ignored) {}
        }
        ServerClient.TranscodeBitrate audioBitrate = null;
        if (server.audioBitrate() != null && server.audioBitrate() > 0) {
            audioBitrate = ServerClient.TranscodeBitrate.MaximumBitrate.of(server.audioBitrate());
        }
        return new Config.ServerConfig(
                dataDir,
                server.id().toString(),
                server.serverType(),
                server.serverUrl(),
                server.username(),
                password != null ? password : "",
                audioFormat,
                audioBitrate,
                server.tlsSkipVerify()
        );
    }

    private void updateNetworkState(Void unused) {
        var next = this.networkMonitor.getState();
        this.setState(appState -> appState.withNetworkState(next));

        // Update CachingClient with network status
        var client = this.client.get();
        if (client != null) {
            client.setNetworkStatus(next.status());
        }
    }

    /**
     * Asynchronously restores the last playing song from the server without auto-playing.
     */
    private void restoreLastPlayingSong(PlayerStateJson.PlaybackPosition storedPlayback) {
        var songId = storedPlayback.songId();
        if (songId == null || songId.isBlank()) {
            // cant restore this...
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                var serverClient = this.client.get();
                if (serverClient == null) {
                    throw new RuntimeException("Failed to restore last playing song: serverClient is null");
                }
                return serverClient.getSong(songId);
            } catch (Exception e) {
                throw new RuntimeException("Failed to restore last playing song: songId=%s".formatted(songId), e);
            }
        }, ASYNC_EXECUTOR)
                .thenComposeAsync(songInfo -> this.loadSourceAsync(new PlayerAction.PlaySong(songInfo, true)).thenApply(v -> songInfo),
                ASYNC_EXECUTOR)
                .thenComposeAsync(songInfo -> {
                    var position = Duration.ofMillis(storedPlayback.positionMillis());
                    log.info("restoreLastPlayingSong: seekTo position={}ms", position.toMillis());
                    return this.handleAction(new PlayerAction.SeekTo(position)).thenApply(v -> songInfo);
                }, ASYNC_EXECUTOR)
                .thenAccept(songInfo -> log.info("restoreLastPlayingSong: restored songId={} title={}", songId, songInfo.title()))
                .exceptionally(throwable -> {
                    log.warn("Failed to restore last playing song: songId={}", songId, throwable);
                    return null;
                });
    }

    private AppState buildState() {
        var initialServerId = this.config.serverConfig != null
                ? ServerState.of(this.config.serverConfig.id())
                : ServerState.empty();
        return new AppState(Optional.empty(), this.player.getState(), this.playQueue.getState(), this.networkMonitor.getState(), initialServerId);
    }

    public AppState getState() {
        return this.currentState.getValue();
    }

    public ThumbnailCache getThumbnailCache() {
        return thumbnailCache;
    }

    public <T> T useClient(Function<ServerClient, T> useFunc) {
        var c = this.client.get();
        if (c == null) {
            throw new IllegalStateException("No server client configured");
        }
        return useFunc.apply(c);
    }
    public void useClient1(Consumer<ServerClient> useFunc) {
        var c = this.client.get();
        if (c == null) {
            throw new IllegalStateException("No server client configured");
        }
        useFunc.accept(c);
    }

    public Config getConfig() {
        return config;
    }

    public java.util.List<DownloadQueueItem> getDownloadQueue() {
        return dbService.listDownloadQueue(java.util.List.of(
                DownloadQueueItem.DownloadStatus.COMPLETED,
                DownloadQueueItem.DownloadStatus.PENDING,
                DownloadQueueItem.DownloadStatus.DOWNLOADING,
                DownloadQueueItem.DownloadStatus.FAILED
        ));
    }

    public AppManager setToastOverlay(ToastOverlay toastOverlay) {
        this.toastOverlay = toastOverlay;
        return this;
    }

    public void playPause() {
        if (this.currentState.getValue().player.state().isPlaying()) {
            this.pause();
        } else {
            if (this.currentState.getValue().player.source().isPresent()) {
                this.play();
            }
        }
    }

    public void navigateTo(AppNavigation.AppRoute route) {
        this.navigator.navigateTo(route);
    }

    public void setNavigator(AppNavigation appNavigation) {
        this.navigator = appNavigation;
    }

    public void shutdown() {
        if (!this.isShutdown.compareAndSet(false, true)) {
            return;
        }
        var start = System.currentTimeMillis();
        timeIt(
                duration -> log.info("shutdown: saveCurrentPlayerPreferencesImmediately: {}ms", duration.toMillis()),
                this::saveCurrentPlayerPreferencesImmediately
        );

        var saveTask = this.pendingPreferenceSave;
        if (saveTask != null) {
            saveTask.cancel(false);
        }
        timeIt(
                duration -> log.info("shutdown: preferenceSaveScheduler: {}ms", duration.toMillis()),
                this.preferenceSaveScheduler::shutdown
        );
        timeIt(
                duration -> log.info("shutdown: downloadManager: {}ms", duration.toMillis()),
                this.downloadManager::stop
        );
        timeIt(
                duration -> log.info("shutdown: scrobbleService: {}ms", duration.toMillis()),
                this.scrobbleService::stop
        );
        var elapsed = System.currentTimeMillis() - start;
        log.info("AppManager shutdown completed in %dms".formatted(elapsed));
    }

    public Optional<Duration> getPlayerPosition() {
        return this.player.getCurrentPosition();
    }

    public SearchResultStore getSearchResultStore() {
        return this.searchResultStore;
    }

    public GSongStore getSongStore() {
        return this.gSongStore;
    }

    public record AlbumInfo(
            ServerClient.AlbumInfo album,
            List<GSongInfo> songs
    ){}
    public CompletableFuture<AlbumInfo> getAlbumInfoAsync(String albumId) {
        return doAsync(() -> {
            var data = this.useClient(serverClient -> serverClient.getAlbumInfo(albumId));

            var songs = data.songs().stream().map(gSongStore::newInstance).toList();
            return new AlbumInfo(data, songs);
        });
    }

    public interface StateListener {
        void onStateChanged(AppState state);
    }

    public void addOnStateChanged(StateListener lis) {
        listeners.add(lis);
    }

    public void removeOnStateChanged(StateListener lis) {
        listeners.remove(lis);
    }

    public record BufferingProgress(long total, long count) {}

    @RecordBuilderFull
    public record NowPlaying (
            SongInfo song,
            State state,
            UUID requestId,
            BufferingProgress bufferingProgress,
            Optional<LoadSongResult> cacheResult
    ) implements AppManagerNowPlayingBuilder.With {
        public enum State {
            LOADING,
            READY,
        }
    }

    public record ServerState(Optional<String> serverId) {
        public static ServerState empty() {
            return new ServerState(Optional.empty());
        }
        public static ServerState of(String id) {
            return new ServerState(Optional.of(id));
        }
    }

    @RecordBuilderFull
    public record AppState(
            Optional<NowPlaying> nowPlaying,
            PlaybinPlayer.PlayerState player,
            PlayQueue.PlayQueueState queue,
            NetworkState networkState,
            ServerState serverState
    ) implements AppManagerAppStateBuilder.With {}

    public void enqueue(SongInfo songInfo) {
        this.playQueue.enqueue(songInfo);
    }


    public CompletableFuture<LoadSongResult> loadSourceAsync(PlayerAction.PlaySong songInfo) {

        return CompletableFuture.supplyAsync(
                () -> this.loadSourceSync(songInfo),
                ASYNC_EXECUTOR
        ).exceptionally(throwable -> {
            log.error("loadSourceAsync: failed to load: {} {}", songInfo.song().id(), songInfo.song().title(), throwable);
            throw new RuntimeException(throwable);
        });
    }

    public interface ProgressHandler {
        interface ProgressUpdate {}
        record Start(SongInfo songInfo) implements ProgressUpdate {}
        record Update(SongInfo songInfo, float percent) implements ProgressUpdate {}
        record Completed(SongInfo songInfo) implements ProgressUpdate {}

        void update(ProgressUpdate u);
    }

    private LoadSongResult loadSourceSync(PlayerAction.PlaySong playCmd) {
        var songInfo = playCmd.song();
        try {
            var result = loadSourceSyncInner(songInfo, playCmd.startPaused());
            if (result == null) {
                // Song was not available (e.g. offline and not cached)
                return null;
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to load song: id={} title={}", playCmd.song().id(), playCmd.song().title(), e);
            this.setState(old -> old.withNowPlaying(Optional.empty()));
            this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Failed to load song")));
            throw e;
        }
    }

    private LoadSongResult loadSourceSyncInner(SongInfo songInfo, boolean startPaused) {
        this.pause();
        UUID requestId = UUID.randomUUID();
        // resolve the songUri:
        // - authentication in the url may have been changed since it was generated
        // - transcode settings my have changed since it was generated
        var songUri = resolveStreamUri(songInfo);

        // If server is unreachable and song is not cached, we can't play it
        if (songUri.isEmpty()) {
            var cacheQuery = new SongCache.SongCacheQuery(
                    SERVER_ID, songInfo.id(), songInfo.transcodeInfo().streamFormat()
            );
            if (!songCache.isCached(cacheQuery)) {
                this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Song not available offline")));
                this.setState(old -> old.withNowPlaying(Optional.empty()));
                return null;
            }
        }

        this.setState(old -> old.with()
                .nowPlaying(Optional.of(new NowPlaying(
                        songInfo,
                        LOADING,
                        requestId,
                        new BufferingProgress(songInfo.size(), 0),
                        Optional.empty()
                )))
                .player(songUri
                        .map(uri -> old.player.withSource(Optional.of(new Source(
                                uri,
                                Optional.of(Duration.ZERO),
                                Optional.of(songInfo.duration())
                        ))))
                        .orElse(old.player)
                )
                .build()
        );
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        LoadSongResult cachedSong = songCache.getSong(new CacheSong(
                SERVER_ID,
                songInfo.id(),
                songInfo.transcodeInfo(),
                songInfo.suffix(),
                songInfo.size(),
                (total, count) -> {
                    if (isCancelled.get()) {
                        return;
                    }
                    this.setState(old -> {
                        Optional<NowPlaying> nowPlaying = old.nowPlaying();
                        if (nowPlaying.isEmpty()) {
                            isCancelled.set(true);
                            return old;
                        }
                        var np = nowPlaying.get();
                        if (!np.requestId.equals(requestId)) {
                            isCancelled.set(true);
                            return old;
                        }
                        return old.withNowPlaying(Optional.of(np.withBufferingProgress(
                                new BufferingProgress(total, count)
                        )));
                    });
                }
        ));
        log.info("cached: result={} id={} title={}", cachedSong.result().name(), songInfo.id(), songInfo.title());
        // Track this song as cached so it shows as available offline
        String checksum = null;
        try (var is = cachedSong.uri().toURL().openStream()) {
            checksum = sha256(is);
        } catch (Exception e) {
            log.warn("Failed to calculate checksum for cached song: {}", songInfo.id(), e);
        }
        this.downloadManager.markAsCached(songInfo, checksum);
        AppState appState = this.currentState.getValue();
        var currentSongId = appState.nowPlaying().map(NowPlaying::song).map(SongInfo::id).orElse("");
        if (!currentSongId.equals(songInfo.id())) {
            // we changed song while loading. Ignore this and do nothing:
            return cachedSong;
        }
        boolean startPlaying = !startPaused;
        this.player.setSource(
                new AudioSource(cachedSong.uri(), songInfo.duration()),
                startPlaying
        );

        this.setState(old -> old.withNowPlaying(Optional.of(new NowPlaying(
                songInfo,
                READY,
                requestId,
                new BufferingProgress(1000, 1000),
                Optional.of(cachedSong)
        ))));

        // block after updating UI NowPlaying state
        if (!startPlaying) {
            // Block until GStreamer has prerolled (reached PAUSED), so subsequent seeks work.
            this.player.waitUntilReady();
        }
        return cachedSong;
    }

    private Optional<URI> resolveStreamUri(SongInfo songInfo) {
        try {
            return Optional.of(this.useClient(client -> client.getStreamUri(songInfo.id())));
        } catch (Exception e) {
            log.warn("Failed to resolve stream URI for song={}", songInfo.id());
            return Optional.empty();
        }
    }

    public void play() {
        this.player.play();
    }

    public void pause() {
        this.player.pause();
    }

    public void mute() {
        this.player.setMute(true);
        saveCurrentPlayerPreferences();
    }

    public void unMute() {
        this.player.setMute(false);
        saveCurrentPlayerPreferences();
    }

    public void seekTo(Duration position) {
        this.player.seekTo(position);
    }

    public void setVolume(double linearVolume) {
        this.player.setVolume(linearVolume);
        saveCurrentPlayerPreferences();
    }

    public void next() {
        this.playQueue.attemptPlayNext();
    }

    public void prev() {
        this.playQueue.attemptPlayPrev();
    }

    public CompletableFuture<Void> handleAction(PlayerAction action) {
        return doAsync(() -> {
            log.info("handleAction: payload={}", action);
            switch (action) {
                // config actions:
                case PlayerAction.SaveConfig settings -> this.saveConfig(settings);
                case PlayerAction.SaveTranscodeFormat a -> this.saveTranscodeFormat(a);
                case PlayerAction.Toast t -> this.toast(t);

                // player actions:
                case Enqueue a -> this.playQueue.enqueue(a.song());
                case PlayerAction.EnqueueLast a -> this.playQueue.enqueueLast(a.song());
                case PlayerAction.RemoveFromQueue a -> this.playQueue.removeAt(a.position());
                case PlayPositionInQueue a -> this.playQueue.playPosition(a.position());
                case PlayerAction.PlayAndReplaceQueue a -> this.playQueue.playAndReplaceQueue(a);
                case PlayerAction.Pause a -> this.pause();
                case PlayerAction.Play a -> this.play();
                case PlayerAction.PlayNext a -> this.playQueue.attemptPlayNext();
                case PlayerAction.PlayPrev a -> this.playQueue.attemptPlayPrev();
                case PlayerAction.SeekTo seekTo -> this.player.seekTo(seekTo.position());
                case PlayerAction.SetPlayMode a -> {
                    switch (a.mode()) {
                        case SHUFFLE -> this.playQueue.shuffle();
                        case NORMAL -> this.playQueue.unshuffle();
                        case REPEAT_ONE -> this.playQueue.setPlayMode(a.mode());
                    }
                }

                // API actions
                case PlayerAction.Star a -> this.starSong(a);
                case PlayerAction.Star2 a -> this.starSong(a);
                case PlayerAction.StarRefresh a -> this.starredList.handleRefresh(a);
                case PlayerAction.Unstar a -> this.unstarSong(a);
                case PlayerAction.PlaySong playSong -> this.loadSourceAsync(playSong);
                case PlayerAction.RefreshPlaylists _ -> this.playlistsStore.refreshListAsync();
                case PlayerAction.AddToPlaylist a -> {
                    this.useClient(c -> c.addToPlaylist(new ServerClient.AddSongToPlaylist(a.playlistId(), List.of(a.song().id()))));
                    this.playlistsStore.refreshPlaylistAsync(a.playlistId());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Added to " + a.playlistName())));
                }
                case PlayerAction.AddManyToPlaylist a -> {
                    doAsync(() -> {
                        var songsIds = a.songs().stream().map(GSongInfo::getId).toList();
                        this.useClient(c -> c.addToPlaylist(new ServerClient.AddSongToPlaylist(a.playlistId(), songsIds)));
                        this.playlistsStore.refreshPlaylistAsync(a.playlistId());
                    });
                    String msg = "Adding %d items to %s".formatted(a.songs().size(), a.playlistName());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(msg)));
                }
                case PlayerAction.RemoveFromPlaylist a -> {
                    this.useClient1(c -> c.playlistRemove(new PlaylistRemoveSongRequest(
                            a.playlistId(),
                            List.of(new ServerClient.SongRemoval(a.originalPosition(), a.song().id()))
                    )));
                    //this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Removed from " + a.playlistName())));
                }
                case PlayerAction.AddToDownloadQueue a -> {
                    this.downloadManager.enqueue(a.song());
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Added to download queue")));
                }
                case PlayerAction.CreatePlaylist c -> {
                    var songs = c.songs().stream().map(GSongInfo::getId).toList();
                    var createdPlaylist = this.useClient(client -> client.playlistCreate(new PlaylistCreateRequest(
                            c.playlistName(),
                            songs
                    )));
                    this.playlistsStore.addNewPlaylist(createdPlaylist);
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Created playlist %s".formatted(c.playlistName()))));
                }
                case PlayerAction.DeletePlaylist d -> {
                    this.useClient1(client -> client.playlistDelete(new PlaylistDeleteRequest(d.playlistId())));
                }
                case PlayerAction.RenamePlaylist r -> {
                    // unconditionally rename the playlist in the UI first, we dont really care if we fail to rename at server
                    this.playlistsStore.renamePlaylist(r.playlistId(), r.newName());
                    this.useClient1(client -> client.playlistRename(new PlaylistRenameRequest(r.playlistId(), r.newName())));
                }
                case PlayerAction.AddManyToDownloadQueue a -> {
                    for (var song : a.songs()) {
                        this.downloadManager.enqueue(song.getSongInfo());
                    }
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Added %d items to download queue".formatted(a.songs().size()))));
                }
                case PlayerAction.OverrideNetworkStatus(var a) -> {
                    this.networkMonitor.setOverrideState(a);
                }
                case PlayerAction.SyncDatabase _ -> {
                    var syncService = new SyncService(
                            this.client.get(), this.dbService, UUID.fromString(SERVER_ID), this.thumbnailCache, this.songCache
                    );
                    var stats = syncService.syncAll();
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast(
                            "Synced %d artists, %d albums, %d songs".formatted(
                                    stats.artists(), stats.albums(), stats.songs()
                            )
                    )));
                }
                case PlayerAction.ClearSongCache _ -> {
                    this.songCache.clearSongs(SERVER_ID);
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Song cache cleared")));
                }
                case PlayerAction.ClearThumbnailCache _ -> {
                    this.thumbnailCache.clearThumbnails(SERVER_ID);
                    this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Thumbnail cache cleared")));
                }
                case PlayerAction.TriggerServerScan triggerServerScan -> {
                    Utils.doAsync(() -> this.useClient(ServerClient::startScan))
                            .thenApply(status -> {
                                this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Started server scan")));
                                return status;
                            })
                            .exceptionally(throwable -> {
                                log.error("Failed to start server scan", throwable);
                                this.toast(new PlayerAction.Toast(new org.gnome.adw.Toast("Failed to start server: %s".formatted(throwable.getMessage()))));
                                return null;
                            });
                }
                case PlayerAction.Quit _ -> {
                    this.player.pause();
                    this.shutdown();
                    this.onQuit.run();
                }
                case PlayerAction.Logout _ -> {
                    var serverId = Optional.ofNullable(this.config.serverConfig)
                            .map(Config.ServerConfig::id).orElse("");
                    player.pause();
                    shutdown();
                    database.close();
                    if (!serverId.isBlank()) {
                        this.config.getSecretService().deleteCredentials(serverId);
                    }
                    try {
                        Files.deleteIfExists(config.getConfigFilePath());
                    } catch (Exception e) {
                        log.warn("delete config failed", e);
                    }
                    var dataDir = config.dataDir;
                    // be careful before calling delete on a dir:
                    if (dataDir != null && dataDir.toAbsolutePath().toString().toLowerCase().contains("subsound")) {
                        deleteDirectoryRecursively(dataDir);
                    }
                    onQuit.run();
                }
            }
        });
    }

    private static void deleteDirectoryRecursively(java.nio.file.Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (Exception e) { log.warn("Failed to delete {}", p, e); }
                    });
        } catch (Exception e) {
            log.warn("Failed to walk dir for deletion: {}", dir, e);
        }
    }

    private void toast(PlayerAction.Toast t) {
        if (this.toastOverlay == null) {
            log.warn("unable to toast because toastOverlay={}", this.toastOverlay);
            return;
        }
        t.toast().setTimeout((int) t.timeout().toSeconds());
        runOnMainThread(() -> {
            this.toastOverlay.addToast(t.toast());
        });
    }

    private void saveConfig(PlayerAction.SaveConfig settings) {
        this.config.onboarding = OnboardingState.DONE;
        this.config.serverId = SERVER_ID;

        // Preserve existing audio settings
        String existingAudioFormat = null;
        Integer existingAudioBitrate = null;
        var existingServer = this.databaseService.getServerById(SERVER_ID);
        if (existingServer.isPresent()) {
            existingAudioFormat = existingServer.get().audioFormat();
            existingAudioBitrate = existingServer.get().audioBitrate();
        }

        // Upsert full server record to DB
        var server = new Server(
                UUID.fromString(SERVER_ID),
                true,
                settings.next().type(),
                settings.next().serverUrl(),
                settings.next().username(),
                existingServer.map(Server::createdAt).orElse(Instant.now()),
                settings.next().tlsSkipVerify(),
                existingAudioFormat,
                existingAudioBitrate
        );
        this.databaseService.upsert(server);

        // Store credentials in keyring
        var secretService = this.config.getSecretService();
        boolean stored = secretService.storeCredentialsSync(
                SERVER_ID,
                settings.next().username(),
                settings.next().password()
        );
        this.config.credentialsInKeyring = stored;

        // Build runtime config from DB + password
        this.config.serverConfig = buildServerConfig(
                this.config.dataDir, server, settings.next().password()
        );

        try {
            this.config.saveToFile();
            var newClient = ServerClient.create(this.config.serverConfig);
            this.setClient(newClient);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void saveTranscodeFormat(PlayerAction.SaveTranscodeFormat action) {
        if (this.config.serverConfig == null) {
            return;
        }

        // Load current server from DB, update audio fields
        var serverOpt = this.databaseService.getServerById(this.config.serverConfig.id());
        if (serverOpt.isEmpty()) {
            return;
        }
        var server = serverOpt.get();
        var format = action.audioFormat().format();
        var bitrate = action.audioFormat().bitrate();
        String audioFormatStr = format != null ? format.name() : null;
        Integer audioBitrateInt = switch (bitrate) {
            case null -> null;
            case ServerClient.TranscodeBitrate.SourceQuality _ -> null;
            case ServerClient.TranscodeBitrate.MaximumBitrate(var kbps) -> kbps;
        };

        var updatedServer = new Server(
                server.id(), server.isPrimary(), server.serverType(),
                server.serverUrl(), server.username(), server.createdAt(),
                server.tlsSkipVerify(), audioFormatStr, audioBitrateInt
        );
        this.databaseService.upsert(updatedServer);

        // Rebuild runtime config
        this.config.serverConfig = buildServerConfig(
                this.config.dataDir, updatedServer, this.config.serverConfig.password()
        );

        try {
            this.config.saveToFile();
            var newClient = ServerClient.create(this.config.serverConfig);
            this.setClient(newClient);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void setClient(ServerClient newClient) {
        if (newClient != null) {
            var client = wrapWithCaching(newClient);
            this.client.set(client);
            this.starredList.refreshAsync();
            this.playlistsStore.refreshListAsync();
            var newServerId = this.config.serverConfig != null
                    ? ServerState.of(this.config.serverConfig.id())
                    : ServerState.empty();
            this.setState(old -> old.withServerState(newServerId));
        } else {
            this.client.set(null);
        }
    }

    private CachingClient wrapWithCaching(ServerClient raw) {
        // Do not force-sync the network status at creation: CachingClient defaults to ONLINE
        // so the server is tried first. The network monitor will update it once it has
        // queried the real connectivity (which in flatpak can take ~500 ms via the portal).
        var client = new CachingClient(raw, this.dbService, SERVER_ID, this.config.dataDir);
        // Async DNS pre-check: if the server hostname can't be resolved within 5 seconds,
        // flip to offline immediately so feign requests don't block on the OS DNS timeout (~30s on macOS).
        if (this.config.serverConfig != null) {
            var host = URI.create(this.config.serverConfig.url()).getHost();
            CompletableFuture.runAsync(() -> {
                try {
                    java.net.InetAddress.getByName(host);
                } catch (Exception e) {
                    log.info("DNS pre-check: host {} not resolvable", host);
                    client.setNetworkStatus(NetworkMonitoring.NetworkStatus.OFFLINE);
                }
            }).orTimeout(5, TimeUnit.SECONDS).exceptionally(e -> {
                log.info("DNS pre-check: timed out resolving {}, switching to offline", host);
                client.setNetworkStatus(NetworkMonitoring.NetworkStatus.OFFLINE);
                return null;
            });
        }
        return client;
    }

    /**
     * Saves player preferences with debouncing (500ms delay).
     * If called multiple times within the delay, only the last call will actually save.
     */
    public void saveCurrentPlayerPreferences() {
        // Cancel any pending save
        var ref = pendingPreferenceSave;
        if (ref != null) {
            ref.cancel(false);
            pendingPreferenceSave = null;
        }
        // Schedule a new save after 500ms
        pendingPreferenceSave = preferenceSaveScheduler.schedule(
                this::saveCurrentPlayerPreferencesImmediately,
                2000,
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Saves player preferences immediately without debouncing.
     * Use this for shutdown or when immediate save is required.
     */
    public void saveCurrentPlayerPreferencesImmediately() {
        // Cancel any pending debounced save
        var ref = pendingPreferenceSave;
        if (ref != null) {
            ref.cancel(false);
            pendingPreferenceSave = null;
        }
        var state = this.player.getState();
        // Convert linear volume to cubic for storage (setVolume expects cubic)
        double cubicVolume = PlaybinPlayer.toVolumeCubic(state.volume());
        var playerState = this.currentState.getValue();
        var currentSongId = playerState.nowPlaying()
                .map(NowPlaying::song)
                .map(SongInfo::id)
                .orElse(null);
        var currentSource = this.player.getState().source();
        var playerPosition = currentSource.flatMap(Source::position).orElse(Duration.ZERO);
        var playerDuration = currentSource.flatMap(Source::duration).orElse(Duration.ZERO);

        PlayerStateJson.PlaybackPosition playbackPosition = currentSongId != null
                ? new PlayerStateJson.PlaybackPosition(currentSongId, playerPosition.toMillis(), playerDuration.toMillis())
                : null;
        var playerConfig = new PlayerConfig(
                SERVER_ID,
                new PlayerStateJson(cubicVolume, state.muted(), playbackPosition),
                java.time.Instant.now()
        );
        try {
            this.playerConfigService.savePlayerConfig(playerConfig);
            log.info("saveCurrentPlayerPreferencesImmediately: saved player preferences: volume={} (linear={}), muted={}", cubicVolume, state.volume(), state.muted());
        } catch (Exception e) {
            log.error("failed to save player preferences", e);
        }
    }

    private void unstarSong(PlayerAction.Unstar a) {
        this.starredList.removeStarred(a.song());
        this.client.get().unStarId(a.song().id());
        this.playlistsStore.updateStarredCount(this.starredList.getStore().getNItems());
        setState(appState -> appState.nowPlaying()
                .map(nowPlaying -> {
                    var song = nowPlaying.song();
                    if (song.id().equals(a.song().id())) {
                        var updated = nowPlaying.withSong(song.withStarred(Optional.empty()));
                        return appState.withNowPlaying(Optional.of(updated));
                    } else {
                        return appState;
                    }
                }).orElse(appState));
    }

    private void starSong(PlayerAction.Star2 a) {
        starSong(a.song().getSongInfo());
    }

    private void starSong(PlayerAction.Star a) {
        starSong(a.song());

    }
    private void starSong(SongInfo song) {
        try {
            this.starredList.addStarred(song);
            this.client.get().starId(song.id());
        } catch (Exception e) {
            this.starredList.removeStarred(song);
            throw e;
        }
        this.playlistsStore.updateStarredCount(this.starredList.getStore().getNItems());
        setState(appState -> appState.nowPlaying()
                .map(nowPlaying -> {
                    var currentSong = nowPlaying.song();
                    if (currentSong.id().equals(song.id())) {
                        var updated = nowPlaying.withSong(currentSong.withStarred(Optional.ofNullable(Instant.now())));
                        return appState.withNowPlaying(Optional.of(updated));
                    } else {
                        return appState;
                    }
                }).orElse(appState));
    }

    public ListStore<GSongInfo> getStarredList() {
        return this.starredList.getStore();
    }

    public ListStore<PlaylistsStore.GPlaylist> getPlaylistsListStore() {
        return this.playlistsStore.playlistsListStore();
    }

    public ListStore<GQueueItem> getPlayQueueListStore() {
        return this.playQueue.getListStore();
    }

    private final Lock lock = new ReentrantLock();

    private void setState(Function<AppState, AppState> modifier) {
        try {
            lock.lock();
            var start = System.nanoTime();
            var nextState = modifier.apply(this.currentState.getValue());
            this.currentState.onNext(nextState);
            var elapsedNanos = System.nanoTime() - start;
            var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            if (elapsedMillis > 10) {
                log.warn("long running update took {}ms for state={}", elapsedMillis, nextState);
            }
        } finally {
            lock.unlock();
        }
    }

    //TODO: consider if this is better implemented in the PlayQueue
    private void checkScrobble(PlaybinPlayer.PlayerState playerState) {
        var nowPlaying = getState().nowPlaying();
        if (nowPlaying.isEmpty()) {
            this.scrobbledForRequestId = null;
            return;
        }
        var np = nowPlaying.get();
        var requestId = np.requestId();

        // Already scrobbled this playback instance
        if (requestId.equals(this.scrobbledForRequestId)) {
            return;
        }

        var source = playerState.source();
        if (source.isEmpty()) {
            return;
        }

        var dur = source.get().duration();
        if (dur.isEmpty()) {
            return;
        }
        long durMs = dur.get().toMillis();
        if (durMs <= 0) {
            return;
        }
        var now = System.currentTimeMillis();
        var playedStartedAt = playerState.playbackStartedAt().map(Instant::toEpochMilli).orElse(now);
        long posMs = now - playedStartedAt;

        boolean thresholdMet;
        if (durMs < 30_000) {
            // Short tracks: must play nearly the full track:
            thresholdMet = posMs >= (durMs * 0.95);
        } else {
            // Normal tracks: 50% of track or 4 minutes, whichever comes first
            thresholdMet = posMs >= durMs / 2 || posMs >= 4 * 60 * 1000;
        }

        if (thresholdMet) {
            scrobbledForRequestId = requestId;
            double durationSecond = durMs / 1000.0;
            double playedForSecond = posMs / 1000.0;
            double playedPercentage = playedForSecond / durationSecond;
            String playedPercentageStr = "%.1f".formatted(100 * playedPercentage);
            var songId = np.song().id();
            var title = np.song().title();
            doAsync(() -> {
                try {

                    dbService.insertScrobble(songId, Instant.ofEpochMilli(playedStartedAt));
                    scrobbleService.triggerSubmit();
                    log.info("Scrobble recorded for song: duration={}s playback={}s playedPercentage={} songId={} title={}", durationSecond, playedForSecond, playedPercentageStr, songId, title);
                } catch (Exception e) {
                    log.error("Failed to record scrobble for song: {}", songId, e);
                }
            });
        }
    }

    private void notifyListeners() {
        var state = this.currentState.getValue();
        Thread.startVirtualThread(() -> {
            for (StateListener stateListener : listeners) {
                stateListener.onStateChanged(state);
            }
        });
    }
}
