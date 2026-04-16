package org.subsound.persistence.database;

import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.AlbumInfo;
import org.subsound.integration.ServerClient.ArtistAlbumInfo;
import org.subsound.integration.ServerClient.ArtistEntry;
import org.subsound.integration.ServerClient.ArtistInfo;
import org.subsound.integration.ServerClient.CoverArt;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.SongCache;
import org.subsound.persistence.SongCacheChecker;
import org.subsound.persistence.ThumbnailCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class SyncService {
    private static final Logger logger = LoggerFactory.getLogger(SyncService.class);

    private final ServerClient serverClient;
    private final DatabaseServerService databaseServerService;
    private final UUID serverId;
    private final ThumbnailCache thumbnailCache;
    private final SongCacheChecker songCacheChecker;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final List<CoverArt> collectedCoverArts = new CopyOnWriteArrayList<>();

    public SyncService(ServerClient serverClient, DatabaseServerService databaseServerService, UUID serverId, ThumbnailCache thumbnailCache, SongCacheChecker songCacheChecker) {
        this.serverClient = serverClient;
        this.databaseServerService = databaseServerService;
        this.serverId = serverId;
        this.thumbnailCache = thumbnailCache;
        this.songCacheChecker = songCacheChecker;
    }

    public record SyncStats(int artists, int albums, int songs, int playlists) {}

    public SyncStats syncAll() {
        var start = System.nanoTime();
        logger.info("Starting full sync for server: {}", serverId);
        try {
            // Step 1: Fetch artists first to verify server is online before deleting
            var artists = serverClient.getArtists().list();
            logger.info("Fetched {} artists from server", artists.size());

            // Step 2: Truncate existing data (server confirmed online)
            logger.info("Truncating existing data for server: {}", serverId);
            databaseServerService.deleteAllPlaylistSongs();
            databaseServerService.deleteAllPlaylists();
            databaseServerService.deleteAllSongs();
            databaseServerService.deleteAllAlbums();
            databaseServerService.deleteAllArtists();

            // Step 3: Sync all data from server in parallel
            collectedCoverArts.clear();
            List<Future<SyncStats>> futures = new ArrayList<>();
            for (ArtistEntry artistEntry : artists) {
                futures.add(executor.submit(() -> syncArtist(artistEntry.id())));
            }
            // Wait for all and aggregate stats
            var stats = new SyncStats(0, 0, 0, 0);
            for (Future<SyncStats> future : futures) {
                var s = future.get();
                stats = new SyncStats(
                        stats.artists + s.artists,
                        stats.albums + s.albums,
                        stats.songs + s.songs,
                        stats.playlists
                );
            }
            int playlistCount = syncPlaylists();
            stats = new SyncStats(stats.artists, stats.albums, stats.songs, playlistCount);

            // Step 4: Verify and clean up orphaned downloads
            int orphanedDownloads = databaseServerService.removeOrphanedDownloads();
            if (orphanedDownloads > 0) {
                logger.warn("Cleaned up {} orphaned download references", orphanedDownloads);
            }

            // Step 5: Verify remaining downloads are still cached on disk, re-queue any missing
            var completedDownloads = databaseServerService.listDownloadQueue(List.of(DownloadQueueItem.DownloadStatus.COMPLETED));
            int requeued = 0;
            for (var item : completedDownloads) {
                var query = new SongCache.SongCacheQuery(item.serverId().toString(), item.songId(), item.streamFormat());
                if (!songCacheChecker.isCached(query)) {
                    databaseServerService.updateDownloadProgress(item.songId(), DownloadQueueItem.DownloadStatus.PENDING, 0.0, null);
                    requeued++;
                }
            }
            if (requeued > 0) {
                logger.info("Re-queued {} downloads with missing cache files", requeued);
            }

            // Step 5b: Clean up CACHED entries whose files are no longer on disk
            var cachedEntries = databaseServerService.listDownloadQueue(List.of(DownloadQueueItem.DownloadStatus.CACHED));
            int removedCached = 0;
            for (var item : cachedEntries) {
                var query = new SongCache.SongCacheQuery(item.serverId().toString(), item.songId(), item.streamFormat());
                if (!songCacheChecker.isCached(query)) {
                    databaseServerService.removeFromDownloadQueue(item.songId());
                    removedCached++;
                }
            }
            if (removedCached > 0) {
                logger.info("Removed {} cached entries with missing cache files", removedCached);
            }

            // Step 6: Cache all collected thumbnails
            logger.info("Caching {} thumbnails", collectedCoverArts.size());
            List<CompletableFuture<Void>> thumbFutures = collectedCoverArts.stream()
                    .distinct()
                    .map(ca -> thumbnailCache.loadThumbAsync(ca).thenAccept(loaded -> {}))
                    .toList();
            CompletableFuture.allOf(thumbFutures.toArray(new CompletableFuture[0])).join();
            logger.info("Finished caching thumbnails");

            var elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            logger.info("Synced {} artists, {} albums, {} songs, {} playlists in {}ms", stats.artists, stats.albums, stats.songs, stats.playlists, elapsedMillis);
            logger.info("Full sync completed for server: {}", serverId);
            return stats;
        } catch (Exception e) {
            logger.error("Error during full sync", e);
            throw new RuntimeException("Sync failed", e);
        } finally {
            executor.shutdown();
        }
    }

    private SyncStats syncArtist(String artistId) {
        ArtistInfo artistInfo = serverClient.getArtistWithAlbums(artistId);
        Artist artist = new Artist(
                artistInfo.id(),
                serverId,
                artistInfo.name(),
                artistInfo.albumCount(),
                artistInfo.starredAt(),
                artistInfo.coverArt().map(ca -> ca.coverArtId()),
                artistInfo.biography() != null ? java.util.Optional.of(new Artist.Biography(artistInfo.biography().original())) : java.util.Optional.empty()
        );
        databaseServerService.insert(artist);

        // Collect artist cover art for thumbnail caching
        artistInfo.coverArt().ifPresent(collectedCoverArts::add);

        int songs = 0;
        for (ArtistAlbumInfo albumInfoSimple : artistInfo.albums()) {
            songs += syncAlbum(albumInfoSimple.id(), albumInfoSimple.genre());
        }
        return new SyncStats(1, artistInfo.albums().size(), songs, 0);
    }

    private int syncAlbum(String albumId, java.util.Optional<String> genre) {
        AlbumInfo albumInfo = serverClient.getAlbumInfo(albumId);
        if (albumId.contains("al-5CcViuxlnidLI1TGKZcjjN")) {
            System.out.println("Album: %s %s %s".formatted(albumInfo.id(), albumInfo.name(), albumInfo.artistName()));
        }
        Album album = new Album(
                albumInfo.id(),
                serverId,
                albumInfo.artistId(),
                albumInfo.name(),
                albumInfo.songCount(),
                albumInfo.year(),
                albumInfo.artistName(),
                albumInfo.duration(),
                albumInfo.starredAt(),
                albumInfo.coverArt().map(CoverArt::coverArtId),
                java.time.Instant.now(),
                genre
        );
        // Collect album cover art for thumbnail caching
        albumInfo.coverArt().ifPresent(collectedCoverArts::add);

        var start = System.nanoTime();

        List<DBSong> songs = new ArrayList<>();
        for (SongInfo songInfo : albumInfo.songs()) {
            songs.add(DBSong.from(songInfo, serverId));

            // Collect song cover art for thumbnail caching
            songInfo.coverArt().ifPresent(collectedCoverArts::add);
        }
        databaseServerService.syncAlbumBatch(album, songs);
        var elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        logger.info("syncing: songs={} for album {} in {}ms", albumInfo.songs().size(), albumInfo.id(), elapsedMillis);
        return albumInfo.songs().size();
    }

    private int syncPlaylists() {
        try {
            var playlists = serverClient.getPlaylists().playlists();
            logger.info("Fetched {} playlists", playlists.size());
            for (var playlistSimple : playlists) {
                var playlist = serverClient.getPlaylist(playlistSimple.id());
                PlaylistRow row = new PlaylistRow(
                        playlist.id(),
                        serverId,
                        playlist.name(),
                        playlist.songCount(),
                        playlist.songs().stream()
                                .map(SongInfo::duration)
                                .reduce(java.time.Duration.ZERO, java.time.Duration::plus),
                        playlist.coverArtId().map(CoverArt::coverArtId),
                        playlist.created(),
                        java.time.Instant.now()
                );
                databaseServerService.upsertPlaylist(row);
                // Note: playlist_songs already deleted at start of syncAll()
                for (int i = 0; i < playlist.songs().size(); i++) {
                    databaseServerService.insertPlaylistSong(playlist.id(), playlist.songs().get(i).id(), i);
                }
            }
            return playlists.size();
        } catch (Exception e) {
            logger.error("Error syncing playlists", e);
            return 0;
        }
    }
}
