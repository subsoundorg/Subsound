package org.subsound.persistence.database;

import org.jspecify.annotations.Nullable;
import org.subsound.integration.ServerClient.ServerType;

import java.time.Instant;
import java.util.UUID;

public record Server(
        UUID id,
        boolean isPrimary,
        ServerType serverType,
        String serverUrl,
        String username,
        Instant createdAt,
        boolean tlsSkipVerify,
        @Nullable String audioFormat,
        @Nullable Integer audioBitrate
) {
}
