package org.subsound.persistence.database;

import io.soabase.recordbuilder.core.RecordBuilder;

import java.util.Optional;
import java.util.UUID;

@RecordBuilder
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
) implements DownloadQueueItemBuilder.With {
    public enum DownloadStatus {
        PENDING, DOWNLOADING, COMPLETED, FAILED, CACHED;

        public boolean isDownloaded() {
            return this == CACHED || this == COMPLETED;
        }
    }
}
