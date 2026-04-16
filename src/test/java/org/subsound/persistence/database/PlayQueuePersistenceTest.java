package org.subsound.persistence.database;

import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PlayQueuePersistenceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private DBSong makeSong(String id, UUID serverId, String albumId, String title) {
        return new DBSong(
                id, serverId, albumId, "Album Name", title,
                Optional.of(2024), "artist-1", "Artist Name",
                Duration.ofMinutes(3),
                Optional.empty(), Optional.of("cover-1"),
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                Optional.of(1), Optional.of(1), Optional.of(320),
                5000000L, "Rock", "mp3"
        );
    }

    @Test
    public void testSaveAndRestorePlayQueue() throws Exception {
        var dbFile = folder.newFile("test_play_queue.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        var service = new DatabaseServerService(serverId, db);

        // Insert songs into the songs table so the JOIN can find them
        var song1 = makeSong("song-1", serverId, "album-1", "Song One");
        var song2 = makeSong("song-2", serverId, "album-1", "Song Two");
        var song3 = makeSong("song-3", serverId, "album-2", "Song Three");
        service.insert(song1);
        service.insert(song2);
        service.insert(song3);

        // Save a play queue with 3 items
        var queueItems = List.of(
                new PlayQueueItemRow(serverId.toString(), 0, "song-1", "album-1||song-1||0", "AUTOMATIC", 0, 100, null),
                new PlayQueueItemRow(serverId.toString(), 1, "song-2", "album-1||song-2||1", "USER_ADDED", 1, 200, null),
                new PlayQueueItemRow(serverId.toString(), 2, "song-3", "album-2||song-3||2", "AUTOMATIC", 2, 50, null)
        );
        service.savePlayQueueItems(queueItems);

        // Restore and verify
        var restored = service.loadPlayQueueItems();
        Assertions.assertThat(restored).hasSize(3);

        // Verify ordering
        Assertions.assertThat(restored.get(0).songId()).isEqualTo("song-1");
        Assertions.assertThat(restored.get(1).songId()).isEqualTo("song-2");
        Assertions.assertThat(restored.get(2).songId()).isEqualTo("song-3");

        // Verify queue metadata is preserved
        Assertions.assertThat(restored.get(0).queueItemId()).isEqualTo("album-1||song-1||0");
        Assertions.assertThat(restored.get(1).queueKind()).isEqualTo("USER_ADDED");
        Assertions.assertThat(restored.get(2).queueKind()).isEqualTo("AUTOMATIC");

        // Verify shuffle/original order
        Assertions.assertThat(restored.get(0).originalOrder()).isEqualTo(0);
        Assertions.assertThat(restored.get(0).shuffleOrder()).isEqualTo(100);
        Assertions.assertThat(restored.get(2).shuffleOrder()).isEqualTo(50);

        // Verify song data from JOIN
        Assertions.assertThat(restored.get(0).song()).isNotNull();
        Assertions.assertThat(restored.get(0).song().name()).isEqualTo("Song One");
        Assertions.assertThat(restored.get(0).song().id()).isEqualTo("song-1");
        Assertions.assertThat(restored.get(1).song()).isNotNull();
        Assertions.assertThat(restored.get(1).song().name()).isEqualTo("Song Two");
        Assertions.assertThat(restored.get(2).song()).isNotNull();
        Assertions.assertThat(restored.get(2).song().albumId()).isEqualTo("album-2");
    }

    @Test
    public void testSaveOverwritesPreviousQueue() throws Exception {
        var dbFile = folder.newFile("test_play_queue_overwrite.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        var service = new DatabaseServerService(serverId, db);

        var song1 = makeSong("song-1", serverId, "album-1", "Song One");
        var song2 = makeSong("song-2", serverId, "album-1", "Song Two");
        service.insert(song1);
        service.insert(song2);

        // Save initial queue
        service.savePlayQueueItems(List.of(
                new PlayQueueItemRow(serverId.toString(), 0, "song-1", "q1", "AUTOMATIC", 0, 0, null)
        ));
        Assertions.assertThat(service.loadPlayQueueItems()).hasSize(1);

        // Save new queue — should replace the old one
        service.savePlayQueueItems(List.of(
                new PlayQueueItemRow(serverId.toString(), 0, "song-2", "q2", "AUTOMATIC", 0, 0, null),
                new PlayQueueItemRow(serverId.toString(), 1, "song-1", "q3", "USER_ADDED", 1, 0, null)
        ));
        var restored = service.loadPlayQueueItems();
        Assertions.assertThat(restored).hasSize(2);
        Assertions.assertThat(restored.get(0).songId()).isEqualTo("song-2");
        Assertions.assertThat(restored.get(1).songId()).isEqualTo("song-1");
    }

    @Test
    public void testMissingSongReturnsNullInRow() throws Exception {
        var dbFile = folder.newFile("test_play_queue_missing.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        var service = new DatabaseServerService(serverId, db);

        // Only insert song-1, not song-2
        var song1 = makeSong("song-1", serverId, "album-1", "Song One");
        service.insert(song1);

        // Save queue referencing both
        service.savePlayQueueItems(List.of(
                new PlayQueueItemRow(serverId.toString(), 0, "song-1", "q1", "AUTOMATIC", 0, 0, null),
                new PlayQueueItemRow(serverId.toString(), 1, "song-missing", "q2", "AUTOMATIC", 1, 0, null)
        ));

        var restored = service.loadPlayQueueItems();
        Assertions.assertThat(restored).hasSize(2);
        Assertions.assertThat(restored.get(0).song()).isNotNull();
        Assertions.assertThat(restored.get(0).song().name()).isEqualTo("Song One");
        // Missing song should have null
        Assertions.assertThat(restored.get(1).song()).isNull();
        Assertions.assertThat(restored.get(1).songId()).isEqualTo("song-missing");
    }

    @Test
    public void testEmptyQueueRestore() throws Exception {
        var dbFile = folder.newFile("test_play_queue_empty.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());

        UUID serverId = UUID.randomUUID();
        var service = new DatabaseServerService(serverId, db);

        var restored = service.loadPlayQueueItems();
        Assertions.assertThat(restored).isEmpty();
    }

    @Test
    public void testQueueStateJsonPersistence() throws Exception {
        var dbFile = folder.newFile("test_queue_state.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        var configService = new PlayerConfigService(db);

        // Initially empty
        Assertions.assertThat(configService.loadQueueState()).isEmpty();

        // Save and restore
        var state = new PlayQueueStateJson(5, "SHUFFLE", "album", "album-42");
        configService.saveQueueState(state);

        var restored = configService.loadQueueState();
        Assertions.assertThat(restored).isPresent();
        Assertions.assertThat(restored.get().position()).isEqualTo(5);
        Assertions.assertThat(restored.get().playMode()).isEqualTo("SHUFFLE");
        Assertions.assertThat(restored.get().playContextType()).isEqualTo("album");
        Assertions.assertThat(restored.get().playContextId()).isEqualTo("album-42");

        // Overwrite with different state
        var state2 = new PlayQueueStateJson(0, "NORMAL", null, null);
        configService.saveQueueState(state2);

        var restored2 = configService.loadQueueState();
        Assertions.assertThat(restored2).isPresent();
        Assertions.assertThat(restored2.get().position()).isEqualTo(0);
        Assertions.assertThat(restored2.get().playMode()).isEqualTo("NORMAL");
        Assertions.assertThat(restored2.get().playContextType()).isNull();
    }
}
