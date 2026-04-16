package org.subsound.persistence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.DownloadQueueItem;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import org.subsound.ui.models.GDownloadState;
import org.subsound.utils.Utils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class DownloadManager {
    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private final DatabaseServerService dbService;
    private final SongCache songCache;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final Consumer<DownloadManagerEvent> onEvent;
    private final Cache<String, Optional<DownloadQueueItem>> songStatusCache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(1000)
            .build();
    private final Set<String> queuedIds = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    public DownloadManager(
            DatabaseServerService dbService,
            SongCache songCache,
            Consumer<DownloadManagerEvent> onEvent
    ) {
        this.dbService = dbService;
        this.songCache = songCache;
        this.onEvent = onEvent;
        // Initialize in-memory set from DB (all non-CACHED statuses)
        dbService.listDownloadQueue(List.of(
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED,
                DownloadStatus.COMPLETED
        )).forEach(item -> queuedIds.add(item.songId()));
        startQueueProcessor();
    }

    public List<DownloadQueueItem> listDownloadQueue() {
        return dbService.listDownloadQueue();
    }
    public List<DownloadQueueItem> listDownloads(boolean cacheAll) {
        var list = dbService.listDownloadQueue(List.of(DownloadStatus.values()));
        if (cacheAll) {
            list.forEach(item -> songStatusCache.put(item.songId(), Optional.of(item)));
        }
        return list;
    }

    public record DownloadManagerEvent(
            Type type,
            DownloadQueueItem item
    ) {
        public enum Type {
            DOWNLOAD_PENDING,
            DOWNLOAD_STARTED,
            DOWNLOAD_COMPLETED,
            DOWNLOAD_FAILED,
            SONG_CACHED;

            public GDownloadState toState() {
                return switch (this) {
                    case DOWNLOAD_PENDING -> GDownloadState.PENDING;
                    case DOWNLOAD_STARTED -> GDownloadState.DOWNLOADING;
                    case DOWNLOAD_COMPLETED -> GDownloadState.DOWNLOADED;
                    case DOWNLOAD_FAILED -> GDownloadState.NONE;
                    case SONG_CACHED -> GDownloadState.CACHED;
                };
            }
        }
    }

    private void startQueueProcessor() {
        executor.scheduleAtFixedRate(() -> {
            if (!this.running) {
                return;
            }
            try {
                processQueue();
            } catch (Exception e) {
                log.error("Error in download queue processor", e);
            }
        }, 5000, 5000, TimeUnit.MILLISECONDS);
    }

    public Optional<DownloadQueueItem> getSongStatus(String songId) {
        return songStatusCache.get(songId, this::loadSong);
    }
    private Optional<DownloadQueueItem> loadSong(String songId) {
        return dbService.getDownloadQueueItem(songId);
    }

    public void enqueue(SongInfo songInfo) {
        queuedIds.add(songInfo.id());
        dbService.addToDownloadQueue(songInfo);
        songStatusCache.invalidate(songInfo.id());
        this.publishEvent(songInfo.id());
    }

    public int getQueuedCount() {
        return queuedIds.size();
    }

    public void removeFromQueue(String songId) {
        queuedIds.remove(songId);
        var current = getSongStatus(songId);
        if (current.isPresent() && current.get().status().isDownloaded()) {
            // File is on disk — keep it available offline but remove from explicit download list
            dbService.updateDownloadProgress(songId, DownloadStatus.CACHED, 1.0, null);
        } else {
            // Not yet downloaded (PENDING / DOWNLOADING / FAILED) — remove entirely
            dbService.removeFromDownloadQueue(songId);
        }
        songStatusCache.invalidate(songId);
        this.publishEvent(songId);
    }

    public void markAsCached(SongInfo songInfo, String checksum) {
        dbService.addToCacheTracking(songInfo, checksum);
        songStatusCache.invalidate(songInfo.id());
        this.publishEvent(songInfo.id());
    }

    private void publishEvent(String songId) {
        this.getSongStatus(songId).ifPresent(this::publishEvent);
    }
    private void publishEvent(DownloadQueueItem item) {
        var eventOpt = new DownloadManagerEvent(
                switch (item.status()) {
                    case PENDING -> DownloadManagerEvent.Type.DOWNLOAD_PENDING;
                    case DOWNLOADING -> DownloadManagerEvent.Type.DOWNLOAD_STARTED;
                    case COMPLETED -> DownloadManagerEvent.Type.DOWNLOAD_COMPLETED;
                    case FAILED -> DownloadManagerEvent.Type.DOWNLOAD_FAILED;
                    case CACHED -> DownloadManagerEvent.Type.SONG_CACHED;
                },
                item
        );
        this.onEvent.accept(eventOpt);
    }

    private void processQueue() {
        List<DownloadQueueItem> pendingItems = dbService.listDownloadQueue(List.of(
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED
        ));
        for (DownloadQueueItem item : pendingItems) {
            if (item.status() == DownloadStatus.PENDING || item.status() == DownloadStatus.DOWNLOADING) {
                downloadSong(item);
            }
        }
    }

    private void downloadSong(DownloadQueueItem item) {
        try {
            this.dbService.updateDownloadProgress(item.songId(), DownloadStatus.DOWNLOADING, item.progress(), null);
            this.songStatusCache.invalidate(item.songId());
            this.publishEvent(item.songId());

            var transcodeInfo = new TranscodeInfo(
                    item.songId(),
                    item.originalBitRate(),
                    item.estimatedBitRate(),
                    Duration.ofSeconds(item.durationSeconds()),
                    item.streamFormat()
            );

            var cacheSong = new SongCache.CacheSong(
                    item.serverId().toString(),
                    item.songId(),
                    transcodeInfo,
                    "", // originalFileSuffix - maybe not critical if we have transcodeInfo
                    item.originalSize(),
                    (total, count) -> {
                        double progress = (double) count / total;
                        dbService.updateDownloadProgress(item.songId(), DownloadStatus.DOWNLOADING, progress, null);
                    }
            );

            var result = songCache.getSong(cacheSong);

            String checksum = null;
            if (result.result() == SongCache.CacheResult.HIT || result.result() == SongCache.CacheResult.MISS) {
                try (var is = result.uri().toURL().openStream()) {
                    checksum = Utils.sha256(is);
                } catch (Exception e) {
                    log.warn("Failed to calculate checksum for downloaded song: {}", item.songId(), e);
                }
            }

            this.dbService.updateDownloadProgress(item.songId(), DownloadStatus.COMPLETED, 1.0, null, checksum);
            this.songStatusCache.invalidate(item.songId());
            this.publishEvent(item.songId());
            log.info("Downloaded song: {} with checksum: {}", item.songId(), checksum);
        } catch (Exception e) {
            log.error("Failed to download song: {}", item.songId(), e);
            dbService.updateDownloadProgress(item.songId(), DownloadStatus.FAILED, 0.0, e.getMessage());
            this.songStatusCache.invalidate(item.songId());
            this.publishEvent(item.songId());
        }
    }

    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}
