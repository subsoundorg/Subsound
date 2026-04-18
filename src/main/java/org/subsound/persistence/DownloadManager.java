package org.subsound.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.DownloadQueueItem;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import org.subsound.utils.Utils;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class DownloadManager implements DownloadNotifier {
    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);
    private final DatabaseServerService dbService;
    private final SongCache songCache;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r);
        t.setDaemon(true);
        t.setName("download-manager");
        return t;
    });

    private final List<Consumer<DownloadManagerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, DownloadQueueItem> downloadQueue = new ConcurrentHashMap<>();
    private final Set<String> queuedIds = ConcurrentHashMap.newKeySet();
    private volatile boolean running = true;

    public DownloadManager(
            DatabaseServerService dbService,
            SongCache songCache
    ) {
        this.dbService = dbService;
        this.songCache = songCache;
        // Warm both caches from DB in a single query: queuedIds tracks non-CACHED,
        // songStatusCache holds the full item so lookups don't round-trip to DB.
        dbService.listDownloadQueue(List.of(DownloadStatus.values())).forEach(item -> {
            if (item.status() != DownloadStatus.CACHED) {
                queuedIds.add(item.songId());
            }
            downloadQueue.put(item.songId(), item);
        });
        startQueueProcessor();
    }

    public void subscribe(Consumer<DownloadManagerEvent> listener) {
        listeners.add(listener);
    }

    public List<DownloadQueueItem> listDownloadQueue() {
        return dbService.listDownloadQueue();
    }

    public record DownloadManagerEvent(
            String songId,
            Type type,
            Optional<DownloadQueueItem> item
    ) {
        public enum Type {
            REMOVED_FROM_QUEUE,
            DOWNLOAD_PENDING,
            DOWNLOAD_STARTED,
            DOWNLOAD_COMPLETED,
            DOWNLOAD_FAILED,
            SONG_CACHED;
        }
    }

    private void startQueueProcessor() {
        executor.scheduleAtFixedRate(() -> {
            if (!this.isRunning()) {
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
        return Optional.ofNullable(downloadQueue.get(songId));
    }
    // loadSong loads fresh data from db
    private Optional<DownloadQueueItem> loadSong(String songId) {
        return dbService.getDownloadQueueItem(songId);
    }

    public void enqueue(SongInfo songInfo) {
        var current = getSongStatus(songInfo.id());
        if (current.isPresent() && current.get().status() == DownloadStatus.COMPLETED) {
            return;
        }
        queuedIds.add(songInfo.id());
        dbService.addToDownloadQueue(songInfo);
        // update cached data:
        this.loadSong(songInfo.id()).ifPresent(s -> downloadQueue.put(songInfo.id(), s));
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
            // update cached data:
            this.loadSong(songId).ifPresent(s -> downloadQueue.put(s.songId(), s));
        } else {
            // Not yet downloaded (PENDING / DOWNLOADING / FAILED) — remove entirely
            dbService.removeFromDownloadQueue(songId);
            downloadQueue.remove(songId);
        }
        this.publishEvent(songId);
    }

    public void markAsCached(SongInfo songInfo, String checksum) {
        var current = getSongStatus(songInfo.id());
        if (current.isPresent() && current.get().status().isDownloaded()) {
            return;
        }
        dbService.addToCacheTracking(songInfo, checksum);
        // update cached data:
        this.loadSong(songInfo.id()).ifPresent(s -> downloadQueue.put(s.songId(), s));
        this.publishEvent(songInfo.id());
    }

    private void publishEvent(String songId) {
        this.publishEvent(songId, this.getSongStatus(songId));
    }

    private void publishEvent(String songId, Optional<DownloadQueueItem> next) {
        var event = next
                .map(item -> new DownloadManagerEvent(
                        songId,
                        switch (item.status()) {
                            case PENDING -> DownloadManagerEvent.Type.DOWNLOAD_PENDING;
                            case DOWNLOADING -> DownloadManagerEvent.Type.DOWNLOAD_STARTED;
                            case COMPLETED -> DownloadManagerEvent.Type.DOWNLOAD_COMPLETED;
                            case FAILED -> DownloadManagerEvent.Type.DOWNLOAD_FAILED;
                            case CACHED -> DownloadManagerEvent.Type.SONG_CACHED;
                        },
                        next
                )).orElseGet(() -> new DownloadManagerEvent(songId, DownloadManagerEvent.Type.REMOVED_FROM_QUEUE, next));
        for (var listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("Download listener threw for song {}", songId, e);
            }
        }
    }

    public boolean isRunning() {
        return this.running;
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> cls) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            if (cls.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private void processQueue() {
        List<DownloadQueueItem> pendingItems = dbService.listDownloadQueue(List.of(
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED
        ));
        for (DownloadQueueItem item : pendingItems) {
            if (!this.running) {
                return;
            }
            if (item.status() == DownloadStatus.PENDING || item.status() == DownloadStatus.DOWNLOADING) {
                downloadSong(item);
            }
        }
    }

    private void downloadSong(DownloadQueueItem item) {
        try {
            this.dbService.updateDownloadProgress(item.songId(), DownloadStatus.DOWNLOADING, item.progress(), null);
            downloadQueue.put(item.songId(), item.withStatus(DownloadStatus.DOWNLOADING).withProgress(0));
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
                        downloadQueue.put(item.songId(), item.withStatus(DownloadStatus.DOWNLOADING).withProgress(progress));
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
            this.loadSong(item.songId()).ifPresent(s -> downloadQueue.put(s.songId(), s));
            this.publishEvent(item.songId());
            log.info("Downloaded song: {} with checksum: {}", item.songId(), checksum);
        } catch (Exception e) {
            if (!this.running && hasCause(e, InterruptedIOException.class)) {
                log.info("Download cancelled during shutdown: {}", item.songId());
                return;
            }
            log.error("Failed to download song: {}", item.songId(), e);
            dbService.updateDownloadProgress(item.songId(), DownloadStatus.FAILED, 0.0, e.getMessage());
            downloadQueue.put(item.songId(), item.withStatus(DownloadStatus.FAILED).withProgress(0.0).withErrorMessage(e.getMessage()));
            this.publishEvent(item.songId());
        }
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
    }
}
