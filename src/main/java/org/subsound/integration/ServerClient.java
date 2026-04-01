package org.subsound.integration;

import org.subsound.configuration.Config.ServerConfig;
import org.subsound.integration.servers.subsonic.SubsonicClientV2;
import org.subsound.utils.Utils;
import io.soabase.recordbuilder.core.RecordBuilder;
import io.soabase.recordbuilder.core.RecordBuilderFull;
import org.subsonic.restapi.AlbumID3;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

public interface ServerClient {
    // serverId is a client generated or server generated globally unique id for this specific server integration
    //String serverId();
    ServerType getServerType();

    ListArtists getArtists();
    ArtistInfo getArtistInfo(String artistId);
    ArtistInfo getArtistWithAlbums(String artistId);
    AlbumInfo getAlbumInfo(String albumId);
    ListPlaylists getPlaylists();
    Playlist getPlaylist(String playlistId);
    AddSongToPlaylist addToPlaylist(AddSongToPlaylist req);
    ListStarred getStarred();
    SongInfo getSong(String songId);
    HomeOverview getHomeOverview();
    void starId(String id);
    void unStarId(String id);
    boolean testConnection();
    ServerInfo getServerInfo();
    SearchResult search(String query);
    void scrobble(ScrobbleRequest req);
    URI getStreamUri(String songId);
    StreamResponse openStream(TranscodeInfo transcodeInfo);
    CoverArtResponse downloadCoverArt(CoverArt coverArt, int maxSize);
    ScanStatus scanStatus();
    ScanStatus startScan();

    sealed interface ScanStatus {
        record Scanning(long count) implements ScanStatus {}
        record NotScanning() implements ScanStatus {}
    }

    record ScrobbleRequest(String songId, Instant playedAt) {}
    record SearchResult(
            List<ArtistEntry> artists,
            List<ArtistAlbumInfo> albums,
            List<SongInfo> songs
    ) {}

    record ServerInfo(
            String apiVersion,
            long songCount,
            Optional<Integer> folderCount,
            Optional<Instant> lastScan,
            Optional<String> serverVersion
    ) {}

    record TranscodedStream(
            String songId,
            URI streamUri
            // Map<String, String> headers
    ){}
    record TranscodeInfo(
            String songId,
            Optional<Integer> originalBitRate,
            int estimatedBitRate,
            Duration duration,
            // the streamFormat is the format we will receive when loading streamUri
            // "mp3" | "ogg"
            String streamFormat
    ) {
        public long estimateContentSize() {
            return Utils.estimateContentLength(duration.getSeconds(), estimatedBitRate);
        }
    }

    record StreamResponse(
            String songId,
            InputStream inputStream,
            long contentLength,   // -1 when unknown
            String contentType
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            inputStream.close();
        }
    }

    record CoverArtResponse(byte[] data, String contentType) {}

    @RecordBuilderFull
    record SongInfo(
            String id,
            String title,
            Optional<Integer> trackNumber,
            Optional<Integer> discNumber,
            Optional<Integer> bitRate,
            long size,
            Optional<Integer> year,
            String genre,
            Long playCount,
            Optional<Integer> userRating,
            String artistId,
            String artist,
            String albumId,
            String album,
            Duration duration,
            Optional<Instant> starred,
            Optional<Instant> created,
            Optional<CoverArt> coverArt,
            String suffix,
            TranscodeInfo transcodeInfo,
            URI downloadUri
    ) implements ServerClientSongInfoBuilder.With {
        public boolean isStarred() {
            return starred.isPresent();
        }

        @Override
        public String toString() {
            return "SongInfo{" +
                    "id='" + id + '\'' +
                    ", artist='" + artist + '\'' +
                    ", title='" + title + '\'' +
                    '}';
        }
    }

    @RecordBuilder
    record AlbumInfo(
            String id,
            String name,
            int songCount,
            Optional<Integer> year,
            String artistId,
            String artistName,
            Duration duration,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt,
            List<SongInfo> songs
    ) {
        public boolean isStarred() {
            return starredAt.isPresent();
        }

        public Duration totalPlayTime() {
            return Duration.ofSeconds(this.songs.stream().mapToLong(a -> a.duration.toSeconds()).sum());
        }
    }

    sealed interface ObjectIdentifier {
        String getId();
        record ArtistIdentifier(String artistId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return artistId;
            }
        }
        record AlbumIdentifier(String albumId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return albumId;
            }
        }
        record PlaylistIdentifier(String playlistId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return playlistId;
            }
        }
        record SongIdentifier(String songId) implements ObjectIdentifier {
            @Override
            public String getId() {
                return songId;
            }
        }
        static ArtistIdentifier ofArtist(String artistId) {
            return new ArtistIdentifier(artistId);
        }
        static AlbumIdentifier ofAlbum(String albumId) {
            return new AlbumIdentifier(albumId);
        }
        static PlaylistIdentifier ofPlaylist(String playlistId) {
            return new PlaylistIdentifier(playlistId);
        }
    }


    @RecordBuilder
    record ArtistInfo(
            String id,
            String name,
            int albumCount,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt,
            List<ArtistAlbumInfo> albums,
            Biography biography
    ) {
        public Duration totalPlayTime() {
            return Duration.ofSeconds(this.albums.stream().mapToLong(a -> a.duration.toSeconds()).sum());
        }

        public int songCount() {
            return this.albums.stream().mapToInt(a -> a.songCount).sum();
        }
    }

    @RecordBuilder
    record ArtistAlbumInfo(
            String id,
            String name,
            int songCount,
            String artistId,
            String artistName,
            Duration duration,
            Optional<String> genre,
            Optional<Integer> year,
            Optional<Instant> starredAt,
            Optional<CoverArt> coverArt
    ) {
        public boolean isStarred() {
            return starredAt.isPresent();
        }
        public static ArtistAlbumInfo create(AlbumID3 album, Optional<CoverArt> coverArt) {
            return new ArtistAlbumInfo(
                    album.getId(),
                    album.getName(),
                    album.getSongCount(),
                    album.getArtistId(),
                    album.getArtist(),
                    Duration.ofSeconds(album.getDuration()),
                    ofNullable(album.getGenre()).filter(s -> !s.isBlank()),
                    ofNullable(album.getYear()),
                    ofNullable(album.getStarred()).map(ts -> ts.toInstant(ZoneOffset.UTC)),
                    coverArt
            );
        }
    }

    @RecordBuilder
    record CoverArt(
            String serverId,
            String coverArtId,
            URI coverArtLink,
            // The absolute filepath of where we expect to find our local copy of the artwork
            Path coverArtFilePath,
            // identifier is used to possibly connect this CoverArt with some entity, such as a artist or album
            Optional<ObjectIdentifier> identifier
    ) {
    }

    @RecordBuilder
    record ArtistEntry(
            String id,
            String name,
            int albumCount,
            Optional<CoverArt> coverArt,
            Optional<Instant> starredAt
    ) {
    }

    @RecordBuilder
    record ListArtists(
            List<ArtistEntry> list
    ) {
    }

    @RecordBuilder
    record ListStarred(
            List<SongInfo> songs
    ) {
    }

    enum PlaylistKind {
        NORMAL, STARRED, DOWNLOADED;
    }
    record PlaylistSimple(
            String id,
            String name,
            PlaylistKind kind,
            Optional<CoverArt> coverArtId,
            int songCount,
            Instant changedAt,
            Instant created
    ) {}

    record Playlist(
            String id,
            String name,
            PlaylistKind kind,
            Optional<CoverArt> coverArtId,
            int songCount,
            Instant changedAt,
            Instant created,
            List<SongInfo> songs
    ) {}

    @RecordBuilder
    record ListPlaylists(
            List<PlaylistSimple> playlists
    ) {}

    record Biography(
            String original,
            String cleaned,
            String link
    ) {}

    public record HomeOverview(
            List<ArtistAlbumInfo> recent,
            List<ArtistAlbumInfo> newest,
            List<ArtistAlbumInfo> frequent,
            List<ArtistAlbumInfo> highest,
            List<ArtistAlbumInfo> byYear
    ) {}

    enum ServerType {
        SUBSONIC,
        ;

        public String value() {
            return switch (this) {
                case SUBSONIC -> "SUBSONIC";
            };
        }
    }

    static ServerClient create(ServerConfig cfg) {
        return switch (cfg.type()) {
            case SUBSONIC -> SubsonicClientV2.create(cfg);
        };
    }

    record AddSongToPlaylist(
            String playlistId,
            List<String> songIds
    ){}

    PlaylistSimple playlistCreate(PlaylistCreateRequest req);
    record PlaylistCreateRequest(String name, List<String> songIds){}
    void playlistRename(PlaylistRenameRequest req);
    record PlaylistRenameRequest(String id, String newName) {}
    void playlistDelete(PlaylistDeleteRequest req);
    record PlaylistDeleteRequest(String id) {}
    void playlistRemove(PlaylistRemoveSongRequest req);
    record SongRemoval(int indexInPlaylist, String songId) {}
    record PlaylistRemoveSongRequest(String playlistId, List<SongRemoval> songIds) {}


    enum TranscodeFormat {
        source,
        mp3,
        opus,
    }
    record TranscodeSettings(
            TranscodeFormat format,
            TranscodeBitrate bitrate
    ) {}
    sealed interface TranscodeBitrate {
        record SourceQuality() implements TranscodeBitrate {}
        record MaximumBitrate(int maxBitrate) implements TranscodeBitrate {
            public int maxBitrate() {
                return maxBitrate;
            }
            public static MaximumBitrate of(int maxBitrate) {
                return new MaximumBitrate(maxBitrate);
            }
        }
    }
}
