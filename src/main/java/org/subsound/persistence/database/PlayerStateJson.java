package org.subsound.persistence.database;

import org.jspecify.annotations.Nullable;

/**
 * JSON-serializable player state: volume, mute, and current playback position.
 */
public record PlayerStateJson(
    double volume,
    boolean muted,
    @Nullable PlaybackPosition currentPlayback
) {

    public record PlaybackPosition(
        String songId,
        long positionMillis,
        long durationMillis,
        @Nullable String playContextType,
        @Nullable String playContextId
    ) {
        public PlaybackPosition(String songId, long positionMillis, long durationMillis) {
            this(songId, positionMillis, durationMillis, null, null);
        }
    }

    public static PlayerStateJson defaultState() {
        return new PlayerStateJson(1.0, false, null);
    }
}
