package org.subsound.persistence.database;

import org.jspecify.annotations.Nullable;

public record PlayQueueStateJson(
        @Nullable Integer position,
        String playMode,
        @Nullable String playContextType,
        @Nullable String playContextId
) {
    public static PlayQueueStateJson empty() {
        return new PlayQueueStateJson(null, "NORMAL", null, null);
    }
}
