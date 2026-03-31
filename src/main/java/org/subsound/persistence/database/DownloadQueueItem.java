package org.subsound.persistence.database;

import org.subsound.ui.models.GDownloadState;

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

        public GDownloadState toState() {
            return switch (this) {
                case PENDING -> GDownloadState.PENDING;
                case DOWNLOADING -> GDownloadState.DOWNLOADING;
                case COMPLETED -> GDownloadState.DOWNLOADED;
                case CACHED -> GDownloadState.CACHED;
                case FAILED -> GDownloadState.NONE;
            };
        }
    }
}
