package org.subsound.persistence.database;

import org.subsound.integration.ServerClient.CoverArt;
import org.subsound.integration.ServerClient.SongInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record DBSong(
        String id,
        UUID serverId,
        String albumId,
        String albumName,
        String name,
        Optional<Integer> year,
        String artistId,
        String artistName,
        Duration duration,
        Optional<Instant> starredAt,
        Optional<String> coverArtId,
        Instant createdAt,
        Optional<Integer> trackNumber,
        Optional<Integer> discNumber,
        Optional<Integer> bitRate,
        long size,
        String genre,
        String suffix
) {
    public static DBSong from(SongInfo songInfo, UUID serverId) {
        return new DBSong(
                songInfo.id(),
                serverId,
                songInfo.albumId(),
                songInfo.album(),
                songInfo.title(),
                songInfo.year(),
                songInfo.artistId(),
                songInfo.artist(),
                songInfo.duration(),
                songInfo.starred(),
                songInfo.coverArt().map(CoverArt::coverArtId),
                songInfo.created().orElse(Instant.now()),
                songInfo.trackNumber(),
                songInfo.discNumber(),
                songInfo.bitRate(),
                songInfo.size(),
                songInfo.genre(),
                songInfo.suffix()
        );
    }
}
