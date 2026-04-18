package org.subsound.ui.models;

import org.gnome.glib.Type;
import org.javagi.gobject.types.Types;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;

public enum GDownloadState {
    NONE, // ie. not downloaded. need to online and connected to server to play
    PENDING, // song is in the download-list and waiting for download to start
    DOWNLOADING, // song is in the download-list and is in progress
    DOWNLOADED, // song is in the download-list and completed
    CACHED; // song is in the cache from streaming, but not explicitly in the download list

    private static final Type gtype = Types.register(GDownloadState.class);
    public static Type getType() {
        return gtype;
    }
    public static GDownloadState fromOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0 -> NONE;
            case 1 -> PENDING;
            case 2 -> DOWNLOADING;
            case 3 -> DOWNLOADED;
            case 4 -> CACHED;
            default -> throw new IllegalArgumentException("Invalid download state: " + ordinal);
        };
    }

    public static GDownloadState from(DownloadStatus status) {
        return switch (status) {
            case PENDING -> PENDING;
            case DOWNLOADING -> DOWNLOADING;
            case COMPLETED -> DOWNLOADED;
            case CACHED -> CACHED;
            case FAILED -> NONE;
        };
    }

    public boolean isDownloaded() {
        return switch (this) {
            case NONE, DOWNLOADING, PENDING -> false;
            case DOWNLOADED, CACHED -> true;
        };
    }
}
