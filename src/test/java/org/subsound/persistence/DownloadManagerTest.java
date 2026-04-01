package org.subsound.persistence;

import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.subsound.integration.ServerClientSongInfoBuilder;
import org.subsound.persistence.DownloadManager.DownloadManagerEvent;
import org.subsound.persistence.database.Database;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.DownloadQueueItem;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

public class DownloadManagerTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    public MockMusicServer mockMusicServer;
    @Before
    public void before() {
        mockMusicServer = new MockMusicServer();
    }

    @After
    public void tearDown() {
        mockMusicServer.stop();
        mockMusicServer = null;
    }

    @Test
    public void testDownloadQueueFlow() throws Exception {
        File dataDir = folder.newFolder("data");
        File dbFile = new File(dataDir, "test.db");
        Database db = new Database("jdbc:sqlite:" + dbFile.getAbsolutePath());
        
        UUID serverId = UUID.randomUUID();
        DatabaseServerService dbService = new DatabaseServerService(serverId, db);
        var songId = mockMusicServer.getSamples().stream().findAny().orElseThrow().songId();
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
                .downloadUri(URI.create("file:///dev/null"))
                .build();

        var eventList = new ArrayList<DownloadManagerEvent>();
        SongCache songCache = new SongCache(dataDir.toPath(), transcodeInfo -> this.mockMusicServer.openStream(transcodeInfo));
        DownloadManager downloadManager = new DownloadManager(dbService, songCache, eventList::add);
        // Enqueue
        downloadManager.enqueue(songInfo);
        
        // Wait for it to be processed
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < 10000) {
            var items = dbService.listDownloadQueue();
            if (items.stream().allMatch(item -> item.status() != DownloadQueueItem.DownloadStatus.PENDING)) {
                break;
            }
            Thread.sleep(100);
        }

        var items = dbService.listDownloadQueue();
        Assertions.assertThat(items).allMatch(item -> item.status() != DownloadQueueItem.DownloadStatus.PENDING);
        
        downloadManager.stop();
    }
}
