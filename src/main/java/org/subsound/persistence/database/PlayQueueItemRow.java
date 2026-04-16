package org.subsound.persistence.database;

import org.jspecify.annotations.Nullable;

public record PlayQueueItemRow(
        String serverId,
        int sortOrder,
        String songId,
        String queueItemId,
        String queueKind,
        int originalOrder,
        int shuffleOrder,
        @Nullable DBSong song
) {}
