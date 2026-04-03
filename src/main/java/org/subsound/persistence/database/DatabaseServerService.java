package org.subsound.persistence.database;

import org.subsound.integration.ServerClient;
import org.subsound.persistence.database.Artist.Biography;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;
import org.subsound.persistence.database.ScrobbleEntry.ScrobbleStatus;
import org.subsound.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class DatabaseServerService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseServerService.class);
    private final UUID serverId;
    private final Database database;

    public DatabaseServerService(UUID serverId, Database database) {
        this.serverId = serverId;
        this.database = database;
    }

    public void insert(Album album) {
        String sql = """
                INSERT OR REPLACE INTO albums (id, server_id, artist_id, name, song_count, year, artist_name, duration_ms, starred_at_ms, cover_art_id, added_at_ms, genre)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, album.id());
            pstmt.setString(2, album.serverId().toString());
            pstmt.setString(3, album.artistId());
            pstmt.setString(4, album.name());
            pstmt.setInt(5, album.songCount());
            if (album.year().isPresent()) {
                pstmt.setInt(6, album.year().get());
            } else {
                pstmt.setNull(6, Types.INTEGER);
            }
            pstmt.setString(7, album.artistName());
            pstmt.setLong(8, album.duration().toMillis());
            if (album.starredAt().isPresent()) {
                pstmt.setLong(9, album.starredAt().get().toEpochMilli());
            } else {
                pstmt.setNull(9, Types.INTEGER);
            }
            if (album.coverArtId().isPresent()) {
                pstmt.setString(10, album.coverArtId().get());
            } else {
                pstmt.setNull(10, Types.VARCHAR);
            }
            pstmt.setLong(11, album.addedAt().toEpochMilli());
            if (album.genre().isPresent()) {
                pstmt.setString(12, album.genre().get());
            } else {
                pstmt.setNull(12, Types.VARCHAR);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert album", e);
            throw new RuntimeException("Failed to insert album", e);
        }
    }

    public List<Album> listAlbumsByArtist(String artistId) {
        List<Album> albums = new ArrayList<>();
        String sql = "SELECT * FROM albums WHERE server_id = ? AND artist_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, artistId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    albums.add(mapResultSetToAlbum(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list albums for artist: {}", artistId, e);
            throw new RuntimeException("Failed to list albums by artist", e);
        }
        return albums;
    }

    public List<Album> listAlbumsByAddedAt() {
        String sql = "SELECT * FROM albums WHERE server_id = ? ORDER BY added_at_ms DESC";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                List<Album> albums = new ArrayList<>();
                while (rs.next()) {
                    albums.add(mapResultSetToAlbum(rs));
                }
                return albums;
            }
        } catch (SQLException e) {
            logger.error("Failed to list albums by added_at_ms for server: {}", serverId, e);
            throw new RuntimeException("Failed to list albums by added_at_ms", e);
        }
    }

    public List<Album> listAlbumsByYear(int limit) {
        String sql = "SELECT * FROM albums WHERE server_id = ? AND year IS NOT NULL ORDER BY year DESC LIMIT ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                var albums = new ArrayList<Album>();
                while (rs.next()) {
                    albums.add(mapResultSetToAlbum(rs));
                }
                return albums;
            }
        } catch (SQLException e) {
            logger.error("Failed to list albums by year for server: {}", serverId, e);
            throw new RuntimeException("Failed to list albums by year", e);
        }
    }

    public Optional<Album> getAlbumById(String albumId) {
        String sql = "SELECT * FROM albums WHERE server_id = ? AND id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, albumId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToAlbum(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get album by id: {}", albumId, e);
            throw new RuntimeException("Failed to get album by id", e);
        }
        return Optional.empty();
    }

    private Album mapResultSetToAlbum(ResultSet rs) throws SQLException {
        int yearValue = rs.getInt("year");
        Optional<Integer> year = rs.wasNull() ? Optional.empty() : Optional.of(yearValue);

        long starredAtMs = rs.getLong("starred_at_ms");
        Optional<Instant> starredAt = rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(starredAtMs));

        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        String genre = rs.getString("genre");
        Optional<String> genreOptional = Optional.ofNullable(genre);

        return new Album(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("artist_id"),
                rs.getString("name"),
                rs.getInt("song_count"),
                year,
                rs.getString("artist_name"),
                Duration.ofMillis(rs.getLong("duration_ms")),
                starredAt,
                coverArtIdOptional,
                Instant.ofEpochMilli(rs.getLong("added_at_ms")),
                genreOptional
        );
    }

    public void insert(Artist artist) {
        String sql = "INSERT OR REPLACE INTO artists (id, server_id, name, album_count, starred_at, cover_art_id, biography) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, artist.id());
            pstmt.setString(2, artist.serverId().toString());
            pstmt.setString(3, artist.name());
            pstmt.setInt(4, artist.albumCount());
            if (artist.starredAt().isPresent()) {
                pstmt.setLong(5, artist.starredAt().get().toEpochMilli());
            } else {
                pstmt.setNull(5, Types.INTEGER);
            }
            if (artist.coverArtId().isPresent()) {
                pstmt.setString(6, artist.coverArtId().get());
            } else {
                pstmt.setNull(6, Types.VARCHAR);
            }
            if (artist.biography().isPresent()) {
                var biography = artist.biography().get();
                var data = Utils.toJson(biography);
                pstmt.setBytes(7, data.getBytes(StandardCharsets.UTF_8));
            } else {
                pstmt.setNull(7, Types.BLOB);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert artist", e);
            throw new RuntimeException("Failed to insert artist", e);
        }
    }

    public Optional<Artist> getArtistById(String id) {
        String sql = "SELECT id, server_id, name, album_count, starred_at, cover_art_id, biography FROM artists WHERE server_id = ? AND  id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToArtist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get artist by id: {}", id, e);
            throw new RuntimeException("Failed to get artist by id", e);
        }
        return Optional.empty();
    }

    public List<Artist> listArtists() {
        List<Artist> artists = new ArrayList<>();
        String sql = "SELECT id, server_id, name, album_count, starred_at, cover_art_id, biography FROM artists WHERE server_id = ?";
        try (Connection conn = database.openConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    artists.add(mapResultSetToArtist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list artists for server: {}", serverId, e);
            throw new RuntimeException("Failed to list artists", e);
        }
        return artists;
    }

    private Artist mapResultSetToArtist(ResultSet rs) throws SQLException {
        long starredAt = rs.getLong("starred_at");
        Optional<Instant> starredAtInstant = rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(starredAt));

        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        byte[] biography = rs.getBytes("biography");
        Optional<Biography> biographyOptional = Optional.empty();
        if (biography != null && biography.length > 0) {
            Biography bio = Utils.fromJson(new String(biography, StandardCharsets.UTF_8), Biography.class);
            biographyOptional = Optional.of(bio);
        }

        return new Artist(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("name"),
                rs.getInt("album_count"),
                starredAtInstant,
                coverArtIdOptional,
                biographyOptional
        );
    }

    public void insert(Song song) {
        String sql = """
                INSERT OR REPLACE INTO songs (id, server_id, album_id, album_name, name, year, artist_id, artist_name, duration_ms, starred_at_ms, cover_art_id, created_at_ms, track_number, disc_number, bit_rate, size, genre, suffix)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, song.id());
            pstmt.setString(2, song.serverId().toString());
            pstmt.setString(3, song.albumId());
            pstmt.setString(4, song.albumName());
            pstmt.setString(5, song.name());
            if (song.year().isPresent()) {
                pstmt.setInt(6, song.year().get());
            } else {
                pstmt.setNull(6, Types.INTEGER);
            }
            pstmt.setString(7, song.artistId());
            pstmt.setString(8, song.artistName());
            pstmt.setLong(9, song.duration().toMillis());
            if (song.starredAt().isPresent()) {
                pstmt.setLong(10, song.starredAt().get().toEpochMilli());
            } else {
                pstmt.setNull(10, Types.INTEGER);
            }
            if (song.coverArtId().isPresent()) {
                pstmt.setString(11, song.coverArtId().get());
            } else {
                pstmt.setNull(11, Types.VARCHAR);
            }
            pstmt.setLong(12, song.createdAt().toEpochMilli());
            if (song.trackNumber().isPresent()) {
                pstmt.setInt(13, song.trackNumber().get());
            } else {
                pstmt.setNull(13, Types.INTEGER);
            }
            if (song.discNumber().isPresent()) {
                pstmt.setInt(14, song.discNumber().get());
            } else {
                pstmt.setNull(14, Types.INTEGER);
            }
            if (song.bitRate().isPresent()) {
                pstmt.setInt(15, song.bitRate().get());
            } else {
                pstmt.setNull(15, Types.INTEGER);
            }
            pstmt.setLong(16, song.size());
            pstmt.setString(17, song.genre());
            pstmt.setString(18, song.suffix());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert song", e);
            throw new RuntimeException("Failed to insert song", e);
        }
    }

    public void syncAlbumBatch(Album album, List<Song> songs) {
        String albumSql = """
                INSERT OR REPLACE INTO albums (id, server_id, artist_id, name, song_count, year, artist_name, duration_ms, starred_at_ms, cover_art_id, added_at_ms, genre)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        String songSql = """
                INSERT OR REPLACE INTO songs (id, server_id, album_id, album_name, name, year, artist_id, artist_name, duration_ms, starred_at_ms, cover_art_id, created_at_ms, track_number, disc_number, bit_rate, size, genre, suffix)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.openConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(albumSql)) {
                    pstmt.setString(1, album.id());
                    pstmt.setString(2, album.serverId().toString());
                    pstmt.setString(3, album.artistId());
                    pstmt.setString(4, album.name());
                    pstmt.setInt(5, album.songCount());
                    if (album.year().isPresent()) {
                        pstmt.setInt(6, album.year().get());
                    } else {
                        pstmt.setNull(6, Types.INTEGER);
                    }
                    pstmt.setString(7, album.artistName());
                    pstmt.setLong(8, album.duration().toMillis());
                    if (album.starredAt().isPresent()) {
                        pstmt.setLong(9, album.starredAt().get().toEpochMilli());
                    } else {
                        pstmt.setNull(9, Types.INTEGER);
                    }
                    if (album.coverArtId().isPresent()) {
                        pstmt.setString(10, album.coverArtId().get());
                    } else {
                        pstmt.setNull(10, Types.VARCHAR);
                    }
                    pstmt.setLong(11, album.addedAt().toEpochMilli());
                    if (album.genre().isPresent()) {
                        pstmt.setString(12, album.genre().get());
                    } else {
                        pstmt.setNull(12, Types.VARCHAR);
                    }
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = conn.prepareStatement(songSql)) {
                    for (Song song : songs) {
                        pstmt.setString(1, song.id());
                        pstmt.setString(2, song.serverId().toString());
                        pstmt.setString(3, song.albumId());
                        pstmt.setString(4, song.albumName());
                        pstmt.setString(5, song.name());
                        if (song.year().isPresent()) {
                            pstmt.setInt(6, song.year().get());
                        } else {
                            pstmt.setNull(6, Types.INTEGER);
                        }
                        pstmt.setString(7, song.artistId());
                        pstmt.setString(8, song.artistName());
                        pstmt.setLong(9, song.duration().toMillis());
                        if (song.starredAt().isPresent()) {
                            pstmt.setLong(10, song.starredAt().get().toEpochMilli());
                        } else {
                            pstmt.setNull(10, Types.INTEGER);
                        }
                        if (song.coverArtId().isPresent()) {
                            pstmt.setString(11, song.coverArtId().get());
                        } else {
                            pstmt.setNull(11, Types.VARCHAR);
                        }
                        pstmt.setLong(12, song.createdAt().toEpochMilli());
                        if (song.trackNumber().isPresent()) {
                            pstmt.setInt(13, song.trackNumber().get());
                        } else {
                            pstmt.setNull(13, Types.INTEGER);
                        }
                        if (song.discNumber().isPresent()) {
                            pstmt.setInt(14, song.discNumber().get());
                        } else {
                            pstmt.setNull(14, Types.INTEGER);
                        }
                        if (song.bitRate().isPresent()) {
                            pstmt.setInt(15, song.bitRate().get());
                        } else {
                            pstmt.setNull(15, Types.INTEGER);
                        }
                        pstmt.setLong(16, song.size());
                        pstmt.setString(17, song.genre());
                        pstmt.setString(18, song.suffix());
                        pstmt.addBatch();
                    }
                    pstmt.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logger.error("Failed to sync album batch", e);
            throw new RuntimeException("Failed to sync album batch", e);
        }
    }

    public List<Song> listSongsByAlbumId(String albumId) {
        List<Song> songs = new ArrayList<>();
        String sql = "SELECT * FROM songs WHERE server_id = ? AND album_id = ? ORDER BY disc_number, track_number";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, albumId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songs.add(mapResultSetToSong(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list songs for album: {}", albumId, e);
            throw new RuntimeException("Failed to list songs by album_id", e);
        }
        return songs;
    }

    public List<Song> listSongsByStarredAt() {
        List<Song> songs = new ArrayList<>();
        String sql = "SELECT * FROM songs WHERE server_id = ? AND starred_at_ms IS NOT NULL ORDER BY starred_at_ms DESC";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songs.add(mapResultSetToSong(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list starred songs for server: {}", serverId, e);
            throw new RuntimeException("Failed to list starred songs", e);
        }
        return songs;
    }

    public void clearStarredExcept(Set<String> starredSongIds) {
        if (starredSongIds.isEmpty()) {
            String sql = "UPDATE songs SET starred_at_ms = NULL WHERE server_id = ? AND starred_at_ms IS NOT NULL";
            try (Connection conn = database.openConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, this.serverId.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                logger.error("Failed to clear starred songs", e);
                throw new RuntimeException("Failed to clear starred songs", e);
            }
            return;
        }
        String placeholders = starredSongIds.stream().map(_ -> "?").collect(Collectors.joining(","));
        String sql = "UPDATE songs SET starred_at_ms = NULL WHERE server_id = ? AND starred_at_ms IS NOT NULL AND id NOT IN (" + placeholders + ")";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            int idx = 2;
            for (String id : starredSongIds) {
                pstmt.setString(idx++, id);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to clear stale starred songs", e);
            throw new RuntimeException("Failed to clear stale starred songs", e);
        }
    }

    public Optional<Song> getSongById(String songId) {
        String sql = "SELECT * FROM songs WHERE server_id = ? AND id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, songId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToSong(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get song by id: {}", songId, e);
            throw new RuntimeException("Failed to get song by id", e);
        }
        return Optional.empty();
    }

    private Song mapResultSetToSong(ResultSet rs) throws SQLException {
        int year = rs.getInt("year");
        Optional<Integer> yearOptional = rs.wasNull() ? Optional.empty() : Optional.of(year);

        long starredAt = rs.getLong("starred_at_ms");
        Optional<Instant> starredAtInstant = rs.wasNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(starredAt));

        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        int trackNumber = rs.getInt("track_number");
        Optional<Integer> trackNumberOpt = rs.wasNull() ? Optional.empty() : Optional.of(trackNumber);

        int discNumber = rs.getInt("disc_number");
        Optional<Integer> discNumberOpt = rs.wasNull() ? Optional.empty() : Optional.of(discNumber);

        int bitRate = rs.getInt("bit_rate");
        Optional<Integer> bitRateOpt = rs.wasNull() ? Optional.empty() : Optional.of(bitRate);

        return new Song(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("album_id"),
                rs.getString("album_name") != null ? rs.getString("album_name") : "",
                rs.getString("name"),
                yearOptional,
                rs.getString("artist_id"),
                rs.getString("artist_name"),
                Duration.ofMillis(rs.getLong("duration_ms")),
                starredAtInstant,
                coverArtIdOptional,
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                trackNumberOpt,
                discNumberOpt,
                bitRateOpt,
                rs.getLong("size"),
                rs.getString("genre") != null ? rs.getString("genre") : "",
                rs.getString("suffix") != null ? rs.getString("suffix") : ""
        );
    }

    // Playlist methods

    public void upsertPlaylist(PlaylistRow playlist) {
        String sql = """
                INSERT OR REPLACE INTO playlists (id, server_id, name, song_count, duration_ms, cover_art_id, created_at_ms, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlist.id());
            pstmt.setString(2, playlist.serverId().toString());
            pstmt.setString(3, playlist.name());
            pstmt.setInt(4, playlist.songCount());
            pstmt.setLong(5, playlist.duration().toMillis());
            if (playlist.coverArtId().isPresent()) {
                pstmt.setString(6, playlist.coverArtId().get());
            } else {
                pstmt.setNull(6, Types.VARCHAR);
            }
            pstmt.setLong(7, playlist.createdAt().toEpochMilli());
            pstmt.setLong(8, playlist.updatedAt().toEpochMilli());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert playlist", e);
            throw new RuntimeException("Failed to insert playlist", e);
        }
    }

    public void insertPlaylistSong(String playlistId, String songId, int sortOrder) {
        String sql = "INSERT OR REPLACE INTO playlist_songs (playlist_id, server_id, song_id, sort_order) VALUES (?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, this.serverId.toString());
            pstmt.setString(3, songId);
            pstmt.setInt(4, sortOrder);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert playlist song", e);
            throw new RuntimeException("Failed to insert playlist song", e);
        }
    }

    public void playlistRemoveSong(ServerClient.PlaylistRemoveSongRequest req) {
        if (req.songIds().isEmpty()) {
            return;
        }

        // Build: DELETE ... WHERE ... AND (song_id, sort_order) IN (VALUES (?,?), (?,?), ...)
        String valuePlaceholders = req.songIds().stream()
                .map(_ -> "(?,?)")
                .collect(java.util.stream.Collectors.joining(","));
        String deleteSql = """
                DELETE FROM playlist_songs
                WHERE playlist_id = ? AND server_id = ?
                  AND (song_id, sort_order) IN (VALUES %s)
                """.formatted(valuePlaceholders);

        // Renumber survivors in one sort pass via ROW_NUMBER() window function (O(N log N))
        String renumberSql = """
                WITH ranked AS (
                    SELECT sort_order,
                           CAST(ROW_NUMBER() OVER (ORDER BY sort_order) AS INTEGER) - 1 AS new_order
                    FROM playlist_songs
                    WHERE playlist_id = ? AND server_id = ?
                )
                UPDATE playlist_songs
                SET sort_order = ranked.new_order
                FROM ranked
                WHERE playlist_songs.playlist_id = ?
                  AND playlist_songs.server_id   = ?
                  AND playlist_songs.sort_order  = ranked.sort_order
                """;

        try (Connection conn = database.openConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                    pstmt.setString(1, req.playlistId());
                    pstmt.setString(2, this.serverId.toString());
                    int col = 3;
                    for (var removal : req.songIds()) {
                        pstmt.setString(col++, removal.songId());
                        pstmt.setInt(col++, removal.indexInPlaylist());
                    }
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = conn.prepareStatement(renumberSql)) {
                    pstmt.setString(1, req.playlistId());
                    pstmt.setString(2, this.serverId.toString());
                    pstmt.setString(3, req.playlistId());
                    pstmt.setString(4, this.serverId.toString());
                    pstmt.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            logger.error("Failed to remove song from playlist: playlistId={}", req.playlistId(), e);
            throw new RuntimeException("Failed to remove song from playlist", e);
        }
    }

    public void deletePlaylist(String playlistId) {
        deletePlaylistSongs(playlistId);
        String sql = "DELETE FROM playlists WHERE id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete playlist", e);
            throw new RuntimeException("Failed to delete playlist", e);
        }
    }

    public void deletePlaylistSongs(String playlistId) {
        String sql = "DELETE FROM playlist_songs WHERE playlist_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to delete playlist songs", e);
            throw new RuntimeException("Failed to delete playlist songs", e);
        }
    }

    public void deleteAllArtists() {
        String sql = "DELETE FROM artists WHERE server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            int deleted = pstmt.executeUpdate();
            logger.info("Deleted {} artists for server {}", deleted, serverId);
        } catch (SQLException e) {
            logger.error("Failed to delete artists", e);
            throw new RuntimeException("Failed to delete artists", e);
        }
    }

    public void deleteAllAlbums() {
        String sql = "DELETE FROM albums WHERE server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            int deleted = pstmt.executeUpdate();
            logger.info("Deleted {} albums for server {}", deleted, serverId);
        } catch (SQLException e) {
            logger.error("Failed to delete albums", e);
            throw new RuntimeException("Failed to delete albums", e);
        }
    }

    public void deleteAllSongs() {
        String sql = "DELETE FROM songs WHERE server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            int deleted = pstmt.executeUpdate();
            logger.info("Deleted {} songs for server {}", deleted, serverId);
        } catch (SQLException e) {
            logger.error("Failed to delete songs", e);
            throw new RuntimeException("Failed to delete songs", e);
        }
    }

    public void deleteAllPlaylists() {
        String sql = "DELETE FROM playlists WHERE server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            int deleted = pstmt.executeUpdate();
            logger.info("Deleted {} playlists for server {}", deleted, serverId);
        } catch (SQLException e) {
            logger.error("Failed to delete playlists", e);
            throw new RuntimeException("Failed to delete playlists", e);
        }
    }

    public void deleteAllPlaylistSongs() {
        String sql = "DELETE FROM playlist_songs WHERE server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            int deleted = pstmt.executeUpdate();
            logger.info("Deleted {} playlist_songs for server {}", deleted, serverId);
        } catch (SQLException e) {
            logger.error("Failed to delete playlist_songs", e);
            throw new RuntimeException("Failed to delete playlist_songs", e);
        }
    }

    public int removeOrphanedDownloads() {
        String sql = """
                DELETE FROM download_queue
                WHERE server_id = ?
                AND song_id NOT IN (SELECT id FROM songs WHERE server_id = ?)
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, this.serverId.toString());
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                logger.warn("Removed {} orphaned downloads for server {}", deleted, serverId);
            }
            return deleted;
        } catch (SQLException e) {
            logger.error("Failed to remove orphaned downloads", e);
            throw new RuntimeException("Failed to remove orphaned downloads", e);
        }
    }

    public List<PlaylistRow> listPlaylists() {
        List<PlaylistRow> playlists = new ArrayList<>();
        String sql = "SELECT * FROM playlists WHERE server_id = ? ORDER BY name";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    playlists.add(mapResultSetToPlaylist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list playlists for server: {}", serverId, e);
            throw new RuntimeException("Failed to list playlists", e);
        }
        return playlists;
    }

    public Optional<PlaylistRow> getPlaylistById(String playlistId) {
        String sql = "SELECT * FROM playlists WHERE server_id = ? AND id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, playlistId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToPlaylist(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get playlist by id: {}", playlistId, e);
            throw new RuntimeException("Failed to get playlist by id", e);
        }
        return Optional.empty();
    }

    public List<String> listPlaylistSongIds(String playlistId) {
        List<String> songIds = new ArrayList<>();
        String sql = "SELECT song_id FROM playlist_songs WHERE playlist_id = ? AND server_id = ? ORDER BY sort_order";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, playlistId);
            pstmt.setString(2, this.serverId.toString());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    songIds.add(rs.getString("song_id"));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list playlist songs for playlist: {}", playlistId, e);
            throw new RuntimeException("Failed to list playlist songs", e);
        }
        return songIds;
    }

    private PlaylistRow mapResultSetToPlaylist(ResultSet rs) throws SQLException {
        String coverArtId = rs.getString("cover_art_id");
        Optional<String> coverArtIdOptional = Optional.ofNullable(coverArtId);

        return new PlaylistRow(
                rs.getString("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("name"),
                rs.getInt("song_count"),
                Duration.ofMillis(rs.getLong("duration_ms")),
                coverArtIdOptional,
                Instant.ofEpochMilli(rs.getLong("created_at_ms")),
                Instant.ofEpochMilli(rs.getLong("updated_at_ms"))
        );
    }

    public void addToDownloadQueue(SongInfo songInfo) {
        String sql = """
                INSERT INTO download_queue (song_id, server_id, status, stream_uri, stream_format, original_size, original_bitrate, estimated_bitrate, duration_seconds)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(song_id, server_id) DO UPDATE SET
                    status = ?,
                    progress = 0.0,
                    stream_uri = excluded.stream_uri,
                    stream_format = excluded.stream_format,
                    original_size = excluded.original_size,
                    original_bitrate = excluded.original_bitrate,
                    estimated_bitrate = excluded.estimated_bitrate,
                    duration_seconds = excluded.duration_seconds
                WHERE download_queue.status = 'CACHED'
                """;
        try (Connection conn = database.openConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, songInfo.id());
            pstmt.setString(2, this.serverId.toString());
            pstmt.setString(3, DownloadQueueItem.DownloadStatus.PENDING.name());
            pstmt.setNull(4, Types.VARCHAR); // songUri must be resolved by server client anyway
            pstmt.setString(5, songInfo.transcodeInfo().streamFormat());
            pstmt.setLong(6, songInfo.size());
            if (songInfo.transcodeInfo().originalBitRate().isPresent()) {
                pstmt.setInt(7, songInfo.transcodeInfo().originalBitRate().get());
            } else {
                pstmt.setNull(7, Types.INTEGER);
            }
            pstmt.setInt(8, songInfo.transcodeInfo().estimatedBitRate());
            pstmt.setLong(9, songInfo.transcodeInfo().duration().toSeconds());
            // ON CONFLICT DO UPDATE SET status = ?
            pstmt.setString(10, DownloadQueueItem.DownloadStatus.PENDING.name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add song to download queue: {}", songInfo.id(), e);
            throw new RuntimeException("Failed to add song to download queue", e);
        }
    }

    public void addToCacheTracking(SongInfo songInfo, String checksum) {
        String sql = "INSERT OR IGNORE INTO download_queue (song_id, server_id, status, progress, stream_uri, stream_format, original_size, original_bitrate, estimated_bitrate, duration_seconds, checksum) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, songInfo.id());
            pstmt.setString(2, this.serverId.toString());
            pstmt.setString(3, DownloadQueueItem.DownloadStatus.CACHED.name());
            pstmt.setDouble(4, 1.0);
            pstmt.setNull(5, Types.VARCHAR); // songUri must be resolved by server client anyway
            pstmt.setString(6, songInfo.transcodeInfo().streamFormat());
            pstmt.setLong(7, songInfo.size());
            if (songInfo.transcodeInfo().originalBitRate().isPresent()) {
                pstmt.setInt(8, songInfo.transcodeInfo().originalBitRate().get());
            } else {
                pstmt.setNull(8, Types.INTEGER);
            }
            pstmt.setInt(9, songInfo.transcodeInfo().estimatedBitRate());
            pstmt.setLong(10, songInfo.transcodeInfo().duration().toSeconds());
            if (checksum != null) {
                pstmt.setString(11, checksum);
            } else {
                pstmt.setNull(11, Types.VARCHAR);
            }
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to add song to cache tracking: {}", songInfo.id(), e);
            throw new RuntimeException("Failed to add song to cache tracking", e);
        }
    }

    public List<String> clearCachedSongs() {
        List<String> deletedSongIds = new ArrayList<>();
        String selectSql = "SELECT song_id FROM download_queue WHERE server_id = ? AND status = 'CACHED'";
        String deleteSql = "DELETE FROM download_queue WHERE server_id = ? AND status = 'CACHED'";
        try (Connection conn = database.openConnection()) {
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, this.serverId.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        deletedSongIds.add(rs.getString("song_id"));
                    }
                }
            }
            try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                pstmt.setString(1, this.serverId.toString());
                int deleted = pstmt.executeUpdate();
                logger.info("Cleared {} cached songs for server {}", deleted, serverId);
            }
        } catch (SQLException e) {
            logger.error("Failed to clear cached songs", e);
            throw new RuntimeException("Failed to clear cached songs", e);
        }
        return deletedSongIds;
    }

    public Optional<DownloadQueueItem> getDownloadQueueItem(String songId) {
        String sql = "SELECT * FROM download_queue WHERE server_id = ? AND song_id = ?";
        try (Connection conn = database.openConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, songId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapDownloadQueueItem(rs));
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static DownloadQueueItem mapDownloadQueueItem(ResultSet rs) throws SQLException {
        int originalBitRate = rs.getInt("original_bitrate");
        Optional<Integer> originalBitRateOpt = rs.wasNull() ? Optional.empty() : Optional.of(originalBitRate);

        return new DownloadQueueItem(
                rs.getString("song_id"),
                UUID.fromString(rs.getString("server_id")),
                DownloadQueueItem.DownloadStatus.valueOf(rs.getString("status")),
                rs.getDouble("progress"),
                rs.getString("error_message"),
                rs.getString("stream_format"),
                rs.getLong("original_size"),
                originalBitRateOpt,
                rs.getInt("estimated_bitrate"),
                rs.getLong("duration_seconds"),
                Optional.ofNullable(rs.getString("checksum"))
        );
    }

    public List<DownloadQueueItem> listDownloadQueue() {
        return listDownloadQueue(List.of(
                DownloadStatus.PENDING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.FAILED
        ));
    }

    public List<DownloadQueueItem> listDownloadQueue(List<DownloadStatus> statuses) {
        List<DownloadQueueItem> items = new ArrayList<>();
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        String sql = "SELECT * FROM download_queue WHERE server_id = ? AND status IN (" + placeholders + ")";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            for (int i = 0; i < statuses.size(); i++) {
                pstmt.setString(i + 2, statuses.get(i).name());
            }
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    items.add(mapDownloadQueueItem(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list download queue for server: {}", serverId, e);
            throw new RuntimeException("Failed to list download queue", e);
        }
        return items;
    }

    public void updateDownloadProgress(
            String songId,
            DownloadQueueItem.DownloadStatus status,
            double progress,
            String errorMessage
    ) {
        updateDownloadProgress(songId, status, progress, errorMessage, null);
    }

    public void updateDownloadProgress(
            String songId,
            DownloadQueueItem.DownloadStatus status,
            double progress,
            String errorMessage,
            String checksum
    ) {
        String sql = "UPDATE download_queue SET status = ?, progress = ?, error_message = ?, checksum = ? WHERE song_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status.name());
            pstmt.setDouble(2, progress);
            pstmt.setString(3, errorMessage);
            pstmt.setString(4, checksum);
            pstmt.setString(5, songId);
            pstmt.setString(6, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update download progress for song: {}", songId, e);
            throw new RuntimeException("Failed to update download progress", e);
        }
    }

    public void removeFromDownloadQueue(String songId) {
        String sql = "DELETE FROM download_queue WHERE song_id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, songId);
            pstmt.setString(2, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to remove song from download queue: {}", songId, e);
            throw new RuntimeException("Failed to remove song from download queue", e);
        }
    }

    // Scrobble methods

    public void insertScrobble(String songId, Instant playedAt) {
        String sql = "INSERT INTO scrobbles (server_id, song_id, played_at_ms, status) VALUES (?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, songId);
            pstmt.setLong(3, playedAt.toEpochMilli());
            pstmt.setString(4, ScrobbleStatus.PENDING.name());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert scrobble for song: {}", songId, e);
            throw new RuntimeException("Failed to insert scrobble", e);
        }
    }

    public List<ScrobbleEntry> listPendingScrobbles() {
        List<ScrobbleEntry> entries = new ArrayList<>();
        String sql = "SELECT * FROM scrobbles WHERE server_id = ? AND status IN (?, ?) ORDER BY played_at_ms ASC";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, ScrobbleStatus.PENDING.name());
            pstmt.setString(3, ScrobbleStatus.FAILED.name());
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    entries.add(mapResultSetToScrobble(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to list pending scrobbles for server: {}", serverId, e);
            throw new RuntimeException("Failed to list pending scrobbles", e);
        }
        return entries;
    }

    public void updateScrobbleStatus(long id, ScrobbleStatus status) {
        String sql = "UPDATE scrobbles SET status = ? WHERE id = ? AND server_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, status.name());
            pstmt.setLong(2, id);
            pstmt.setString(3, this.serverId.toString());
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update scrobble status for id: {}", id, e);
            throw new RuntimeException("Failed to update scrobble status", e);
        }
    }

    public void deleteSubmittedScrobbles() {
        String sql = "DELETE FROM scrobbles WHERE server_id = ? AND status = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, this.serverId.toString());
            pstmt.setString(2, ScrobbleStatus.SUBMITTED.name());
            int deleted = pstmt.executeUpdate();
            if (deleted > 0) {
                logger.info("Deleted {} submitted scrobbles for server {}", deleted, serverId);
            }
        } catch (SQLException e) {
            logger.error("Failed to delete submitted scrobbles", e);
            throw new RuntimeException("Failed to delete submitted scrobbles", e);
        }
    }

    private ScrobbleEntry mapResultSetToScrobble(ResultSet rs) throws SQLException {
        return new ScrobbleEntry(
                rs.getLong("id"),
                UUID.fromString(rs.getString("server_id")),
                rs.getString("song_id"),
                rs.getLong("played_at_ms"),
                rs.getLong("created_at_ms"),
                ScrobbleStatus.valueOf(rs.getString("status"))
        );
    }
}
