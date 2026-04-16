package org.subsound.persistence.database;

import org.subsound.integration.ServerClient;
import org.subsound.persistence.database.Artist.Biography;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.integration.ServerClientSongInfoBuilder;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabaseServerServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testAlbumOperations() throws Exception {
        File dbFile = folder.newFile("test_album_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Album album1 = new Album(
                "album-1",
                serverId,
                "artist-1",
                "Album One",
                10,
                Optional.of(2020),
                "Artist Name",
                Duration.ofMinutes(45),
                Optional.of(now),
                Optional.of("cover-1"),
                now.minus(1, ChronoUnit.DAYS),
                Optional.of("Rock")
        );

        Album album2 = new Album(
                "album-2",
                serverId,
                "artist-1",
                "Album Two",
                12,
                Optional.empty(),
                "Artist Name",
                Duration.ofMinutes(50),
                Optional.empty(),
                Optional.empty(),
                now,
                Optional.empty()
        );

        Album album3 = new Album(
                "album-3",
                serverId,
                "artist-2",
                "Album Three",
                8,
                Optional.of(2022),
                "Other Artist",
                Duration.ofMinutes(30),
                Optional.empty(),
                Optional.of("cover-3"),
                now.minus(2, ChronoUnit.DAYS),
                Optional.empty()
        );

        // Test insert
        service.insert(album1);
        service.insert(album2);
        service.insert(album3);

        // Test getAlbumById
        Optional<Album> found = service.getAlbumById("album-1");
        Assertions.assertThat(found).isPresent();
        Assertions.assertThat(found.get()).usingRecursiveComparison().isEqualTo(album1);

        // Test listAlbumsByArtist
        List<Album> artist1Albums = service.listAlbumsByArtist("artist-1");
        Assertions.assertThat(artist1Albums).hasSize(2);
        Assertions.assertThat(artist1Albums).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrder(album1, album2);

        // Test listAlbumsByAddedAt (should be descending)
        List<Album> albumsByAddedAt = service.listAlbumsByAddedAt();
        Assertions.assertThat(albumsByAddedAt).hasSize(3);
        Assertions.assertThat(albumsByAddedAt).usingRecursiveFieldByFieldElementComparator().containsExactly(album2, album1, album3);
    }

    @Test
    public void testArtistOperations() throws Exception {
        File dbFile = folder.newFile("test_artist_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId1 = UUID.randomUUID();
        UUID serverId2 = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId1, db);
        DatabaseServerService service2 = new DatabaseServerService(serverId2, db);

        Artist artist1 = new Artist(
                "artist-1",
                serverId1,
                "Artist One",
                5,
                Optional.of(Instant.now().truncatedTo(ChronoUnit.MILLIS)),
                Optional.of("cover-1"),
                Optional.of(new Biography("Long bio"))
        );

        var artist2 = new Artist(
                "artist-2",
                serverId1,
                "Artist Two",
                10,
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
        );

        var artist3 = new Artist(
                "artist-3",
                serverId2,
                "Artist Three",
                2,
                Optional.empty(),
                Optional.of("cover-3"),
                Optional.of(new Biography("Long bio"))
        );

        // Test insert
        service.insert(artist1);
        service.insert(artist2);
        service.insert(artist3);

        // Test listArtists for serverId1
        var artistsServer1 = service.listArtists();
        Assertions.assertThat(artistsServer1).hasSize(2);
        Assertions.assertThat(artistsServer1)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactlyInAnyOrder(artist1, artist2);

        // Test listArtists for serverId2
        var artistsServer2 = service2.listArtists();
        Assertions.assertThat(artistsServer2).hasSize(1);
        Assertions.assertThat(artistsServer2)
                .usingRecursiveFieldByFieldElementComparator()
                .containsExactly(artist3);

        // Test getArtistById
        var foundArtist = service.getArtistById("artist-1");
        Assertions.assertThat(foundArtist).isPresent();
        Assertions.assertThat(foundArtist.get())
                .usingRecursiveComparison()
                .isEqualTo(artist1);

        // Test getArtistById with non-existent id
        Optional<Artist> notFoundArtist = service.getArtistById("non-existent");
        Assertions.assertThat(notFoundArtist).isEmpty();
    }

    @Test
    public void testSongOperations() throws Exception {
        File dbFile = folder.newFile("test_song_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        DBSong song1 = new DBSong(
                "song-1",
                serverId,
                "album-1",
                "Album One",
                "Song One",
                Optional.of(2020),
                "artist-1",
                "Artist Name",
                Duration.ofMinutes(3),
                Optional.of(now),
                Optional.of("cover-1"),
                now,
                Optional.of(1),
                Optional.of(1),
                Optional.of(320),
                5000000L,
                "Rock",
                "mp3"
        );

        DBSong song2 = new DBSong(
                "song-2",
                serverId,
                "album-1",
                "Album One",
                "Song Two",
                Optional.empty(),
                "artist-1",
                "Artist Name",
                Duration.ofMinutes(4),
                Optional.empty(),
                Optional.empty(),
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0L,
                "",
                ""
        );

        DBSong song3 = new DBSong(
                "song-3",
                serverId,
                "album-2",
                "Album Two",
                "Song Three",
                Optional.of(2022),
                "artist-2",
                "Other Artist",
                Duration.ofMinutes(5),
                Optional.of(now.minus(1, ChronoUnit.HOURS)),
                Optional.of("cover-3"),
                now,
                Optional.of(3),
                Optional.empty(),
                Optional.of(256),
                8000000L,
                "Pop",
                "flac"
        );

        // Test insert
        service.insert(song1);
        service.insert(song2);
        service.insert(song3);

        // Test getSongById
        Optional<DBSong> found = service.getSongById("song-1");
        Assertions.assertThat(found).isPresent();
        Assertions.assertThat(found.get()).usingRecursiveComparison().isEqualTo(song1);

        // Test listSongsByAlbumId
        List<DBSong> album1Songs = service.listSongsByAlbumId("album-1");
        Assertions.assertThat(album1Songs).hasSize(2);
        Assertions.assertThat(album1Songs).usingRecursiveFieldByFieldElementComparator().containsExactlyInAnyOrder(song1, song2);

        // Test listSongsByStarredAt (should be descending)
        List<DBSong> starredSongs = service.listSongsByStarredAt();
        Assertions.assertThat(starredSongs).hasSize(2);
        Assertions.assertThat(starredSongs).usingRecursiveFieldByFieldElementComparator().containsExactly(song1, song3);
    }

    @Test
    public void testPlaylistRemoveSong() throws Exception {
        File dbFile = folder.newFile("test_playlist_remove.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        String playlistId = "playlist-1";

        // Insert 5 songs at positions 0–4
        service.insertPlaylistSong(playlistId, "song-a", 0);
        service.insertPlaylistSong(playlistId, "song-b", 1);
        service.insertPlaylistSong(playlistId, "song-c", 2);
        service.insertPlaylistSong(playlistId, "song-d", 3);
        service.insertPlaylistSong(playlistId, "song-e", 4);

        // Remove the middle song; remaining songs should be renumbered 0–3
        service.playlistRemoveSong(new ServerClient.PlaylistRemoveSongRequest(
                playlistId,
                List.of(new ServerClient.SongRemoval(2, "song-c"))
        ));
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly("song-a", "song-b", "song-d", "song-e");

        // Guard: wrong position for song-b (now at 1, not 0) — nothing removed
        service.playlistRemoveSong(new ServerClient.PlaylistRemoveSongRequest(
                playlistId,
                List.of(new ServerClient.SongRemoval(0, "song-b"))
        ));
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly("song-a", "song-b", "song-d", "song-e");

        // Batch removal: remove song-a (pos 0) and song-d (pos 2) in one request;
        // survivors song-b and song-e should be renumbered 0 and 1
        service.playlistRemoveSong(new ServerClient.PlaylistRemoveSongRequest(
                playlistId,
                List.of(
                        new ServerClient.SongRemoval(0, "song-a"),
                        new ServerClient.SongRemoval(2, "song-d")
                )
        ));
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly("song-b", "song-e");
    }

    @Test
    public void testPlaylistDuplicateSongOrder() throws Exception {
        File dbFile = folder.newFile("test_playlist_duplicates.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        String playlistId = "playlist-dup";

        // Add 3 entries of song1
        service.insertPlaylistSong(playlistId, "song1", 0);
        service.insertPlaylistSong(playlistId, "song1", 1);
        service.insertPlaylistSong(playlistId, "song1", 2);

        // Add 2 entries of song2
        service.insertPlaylistSong(playlistId, "song2", 3);
        service.insertPlaylistSong(playlistId, "song2", 4);

        // Add 4 more entries of song1
        service.insertPlaylistSong(playlistId, "song1", 5);
        service.insertPlaylistSong(playlistId, "song1", 6);
        service.insertPlaylistSong(playlistId, "song1", 7);
        service.insertPlaylistSong(playlistId, "song1", 8);

        // Verify the full order is preserved: 3×song1, 2×song2, 4×song1
        Assertions.assertThat(service.listPlaylistSongIds(playlistId))
                .containsExactly(
                        "song1", "song1", "song1",
                        "song2", "song2",
                        "song1", "song1", "song1", "song1"
                );
    }

    @Test
    public void testDownloadQueueOperations() throws Exception {
        File dbFile = folder.newFile("test_download_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        UUID serverId = UUID.randomUUID();
        DatabaseServerService service = new DatabaseServerService(serverId, db);

        var songId = "song-1";
        SongInfo songInfo = ServerClientSongInfoBuilder.builder()
                .id(songId)
                .title("Song One")
                .artistId("artist-1")
                .artist("Artist Name")
                .albumId("album-1")
                .album("Album Name")
                .duration(Duration.ofMinutes(3))
                .size(1000L)
                .suffix("mp3")
                .transcodeInfo(new TranscodeInfo(
                        songId,
                        Optional.of(320),
                        128,
                        Duration.ofMinutes(3),
                        "mp3"
                ))
                .downloadUri(URI.create("http://example.com/download"))
                .build();

        // Test addToDownloadQueue
        service.addToDownloadQueue(songInfo);

        // Test listDownloadQueue
        List<DownloadQueueItem> queue = service.listDownloadQueue();
        Assertions.assertThat(queue).hasSize(1);
        DownloadQueueItem item = queue.get(0);
        Assertions.assertThat(item.songId()).isEqualTo("song-1");
        Assertions.assertThat(item.status()).isEqualTo(DownloadQueueItem.DownloadStatus.PENDING);

        // Test updateDownloadProgress
        service.updateDownloadProgress("song-1", DownloadQueueItem.DownloadStatus.DOWNLOADING, 0.5, null);
        queue = service.listDownloadQueue();
        Assertions.assertThat(queue.get(0).status()).isEqualTo(DownloadQueueItem.DownloadStatus.DOWNLOADING);
        Assertions.assertThat(queue.get(0).progress()).isEqualTo(0.5);

        // Test removeFromDownloadQueue
        service.removeFromDownloadQueue("song-1");
        queue = service.listDownloadQueue();
        Assertions.assertThat(queue).isEmpty();
    }
}
