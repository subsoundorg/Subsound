package org.subsound.app.state;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClientSongInfoBuilder;
import org.subsound.persistence.database.Database;
import org.subsound.persistence.database.DatabaseServerService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class StarredListStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static ArrayList<String> ids(String... ids) {
        return new ArrayList<>(List.of(ids));
    }

    private static SongInfo songInfo(String id, Instant starred) {
        return ServerClientSongInfoBuilder.builder()
                .id(id)
                .title("Song " + id)
                .artistId("artist-1")
                .artist("Artist")
                .albumId("album-1")
                .album("Album")
                .duration(Duration.ofMinutes(3))
                .size(1000L)
                .genre("")
                .suffix("mp3")
                .starred(Optional.of(starred))
                .build();
    }

    /**
     * Simulates applying the diff to a mutable list, mirroring how
     * refreshAsync applies removals (backwards) and insertions (forwards)
     * to the ListStore.
     */
    private static List<String> applyDiff(List<String> current, List<String> newIds, StarredListStore.IndexDiff diff) {
        var result = new ArrayList<>(current);
        // Remove backwards to preserve indices
        var removals = diff.removalIndices();
        for (int i = removals.size() - 1; i >= 0; i--) {
            result.remove((int) removals.get(i));
        }
        // Insert forwards at computed positions
        for (var ins : diff.insertions()) {
            result.add(ins.position(), newIds.get(ins.position()));
        }
        return result;
    }

    private static void assertDiffProduces(List<String> current, List<String> newIds) {
        var diff = StarredListStore.computeDiff(current, newIds);
        var result = applyDiff(current, newIds, diff);
        assertThat(result).containsExactlyElementsOf(newIds);
    }

    @Test
    public void emptyToEmpty() {
        assertDiffProduces(ids(), ids());
    }

    @Test
    public void firstLoad() {
        assertDiffProduces(ids(), ids("A", "B", "C"));
    }

    @Test
    public void noChanges() {
        assertDiffProduces(ids("A", "B", "C"), ids("A", "B", "C"));
    }

    @Test
    public void removeAll() {
        assertDiffProduces(ids("A", "B", "C"), ids());
    }

    @Test
    public void removeFromMiddle() {
        assertDiffProduces(ids("A", "B", "C", "D", "E"), ids("A", "C", "E"));
    }

    @Test
    public void insertAtFront() {
        assertDiffProduces(ids("B", "C"), ids("A", "B", "C"));
    }

    @Test
    public void insertAtEnd() {
        assertDiffProduces(ids("A", "B"), ids("A", "B", "C"));
    }

    @Test
    public void insertInMiddle() {
        assertDiffProduces(ids("A", "C"), ids("A", "B", "C"));
    }

    @Test
    public void mixedRemovalsAndInsertions() {
        assertDiffProduces(
                ids("A", "B", "C", "D", "E"),
                ids("F", "A", "C", "G", "E")
        );
    }

    @Test
    public void completeReplacement() {
        assertDiffProduces(ids("A", "B", "C"), ids("D", "E", "F"));
    }

    @Test
    public void multipleInsertionsAtFront() {
        assertDiffProduces(ids("C"), ids("A", "B", "C"));
    }

    @Test
    public void noChangesProducesNoOps() {
        var diff = StarredListStore.computeDiff(ids("A", "B", "C"), ids("A", "B", "C"));
        assertThat(diff.removalIndices()).isEmpty();
        assertThat(diff.insertions()).isEmpty();
    }

    @Test
    public void persistIfChanged_firstLoad_storesAllSongs() throws Exception {
        var dbFile = folder.newFile("test.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        var serverId = UUID.randomUUID();
        var dbService = new DatabaseServerService(serverId, db);

        var now = Instant.now();
        var songs = List.of(
                songInfo("A", now),
                songInfo("B", now),
                songInfo("C", now)
        );

        // First load: empty -> [A, B, C], diff has insertions
        var diff = StarredListStore.computeDiff(ids(), ids("A", "B", "C"));
        boolean hasChanges = !diff.removalIndices().isEmpty() || !diff.insertions().isEmpty();
        assertThat(hasChanges).isTrue();

        StarredListStore.persistIfChanged(hasChanges, songs, dbService, serverId);

        // DB should now have 3 starred songs
        var starred = dbService.listSongsByStarredAt();
        assertThat(starred).hasSize(3);
        assertThat(starred.stream().map(s -> s.id()).toList()).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    public void persistIfChanged_noChanges_skipsDbWrites() throws Exception {
        var dbFile = folder.newFile("test.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        var serverId = UUID.randomUUID();
        var dbService = new DatabaseServerService(serverId, db);

        var now = Instant.now();
        var songs = List.of(
                songInfo("A", now),
                songInfo("B", now)
        );

        // Initial load
        StarredListStore.persistIfChanged(true, songs, dbService, serverId);
        assertThat(dbService.listSongsByStarredAt()).hasSize(2);

        // No changes: diff is empty, so DB should not be touched
        var diff = StarredListStore.computeDiff(ids("A", "B"), ids("A", "B"));
        boolean hasChanges = !diff.removalIndices().isEmpty() || !diff.insertions().isEmpty();
        assertThat(hasChanges).isFalse();

        // Call with no changes — should be a no-op
        StarredListStore.persistIfChanged(hasChanges, songs, dbService, serverId);

        // Still 2 starred songs, unchanged
        assertThat(dbService.listSongsByStarredAt()).hasSize(2);
    }

    @Test
    public void persistIfChanged_removalsUpdateDb() throws Exception {
        var dbFile = folder.newFile("test.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        var serverId = UUID.randomUUID();
        var dbService = new DatabaseServerService(serverId, db);

        var now = Instant.now();

        // Initial: 3 starred songs
        var initialSongs = List.of(
                songInfo("A", now),
                songInfo("B", now),
                songInfo("C", now)
        );
        StarredListStore.persistIfChanged(true, initialSongs, dbService, serverId);
        assertThat(dbService.listSongsByStarredAt()).hasSize(3);

        // User unstars B: new list is [A, C]
        var newSongs = List.of(
                songInfo("A", now),
                songInfo("C", now)
        );
        var diff = StarredListStore.computeDiff(ids("A", "B", "C"), ids("A", "C"));
        boolean hasChanges = !diff.removalIndices().isEmpty() || !diff.insertions().isEmpty();
        assertThat(hasChanges).isTrue();

        StarredListStore.persistIfChanged(hasChanges, newSongs, dbService, serverId);

        // DB should now have only A and C starred; B should have starred_at cleared
        var starred = dbService.listSongsByStarredAt();
        assertThat(starred).hasSize(2);
        assertThat(starred.stream().map(s -> s.id()).toList()).containsExactlyInAnyOrder("A", "C");

        // B should still exist in DB but not be starred
        var songB = dbService.getSongById("B");
        assertThat(songB).isPresent();
        assertThat(songB.get().starredAt()).isEmpty();
    }

    @Test
    public void persistIfChanged_insertionsAndRemovals() throws Exception {
        var dbFile = folder.newFile("test.db");
        var db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        var serverId = UUID.randomUUID();
        var dbService = new DatabaseServerService(serverId, db);

        var now = Instant.now();

        // Initial: [A, B]
        var initialSongs = List.of(songInfo("A", now), songInfo("B", now));
        StarredListStore.persistIfChanged(true, initialSongs, dbService, serverId);

        // Change: [B, C] — A removed, C added
        var newSongs = List.of(songInfo("B", now), songInfo("C", now));
        var diff = StarredListStore.computeDiff(ids("A", "B"), ids("B", "C"));
        boolean hasChanges = !diff.removalIndices().isEmpty() || !diff.insertions().isEmpty();
        assertThat(hasChanges).isTrue();

        StarredListStore.persistIfChanged(hasChanges, newSongs, dbService, serverId);

        // DB: B and C starred, A unstarred
        var starred = dbService.listSongsByStarredAt();
        assertThat(starred).hasSize(2);
        assertThat(starred.stream().map(s -> s.id()).toList()).containsExactlyInAnyOrder("B", "C");

        var songA = dbService.getSongById("A");
        assertThat(songA).isPresent();
        assertThat(songA.get().starredAt()).isEmpty();
    }
}