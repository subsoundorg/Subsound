package org.subsound.persistence.database;

import java.util.Optional;
import java.util.UUID;

public record DownloadQueueItem(
        String songId,
        UUID serverId,
        DownloadStatus status,
        double progress,
        String errorMessage,
        String streamFormat,
        long originalSize,
        Optional<Integer> originalBitRate,
        int estimatedBitRate,
        long durationSeconds,
        Optional<String> checksum
) {
    public enum DownloadStatus {
        PENDING, DOWNLOADING, COMPLETED, FAILED, CACHED;

        public boolean isDownloaded() {
            return this == CACHED || this == COMPLETED;
        }
    }
}
