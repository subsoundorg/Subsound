package org.subsound.integration.servers.subsonic;

import com.google.gson.annotations.SerializedName;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.configuration.Config.ServerConfig;
import org.subsound.configuration.constants.Constants;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ObjectIdentifier.AlbumIdentifier;
import org.subsound.integration.ServerClient.ObjectIdentifier.ArtistIdentifier;
import org.subsound.integration.ServerClient.ObjectIdentifier.PlaylistIdentifier;
import org.subsound.utils.Utils;
import org.subsound.utils.javahttp.TextUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static java.util.Optional.ofNullable;
import static org.subsound.app.state.AppManager.SERVER_ID;
import static org.subsound.persistence.ThumbnailCache.toCachePath;
import static org.subsound.utils.LogUtils.loggingInterceptor;

/**
 * JSON-based Subsonic API client using OkHttpClient.
 * Replaces the feign-based SubsonicClient with direct HTTP + Gson parsing.
 * Uses OkHttp's callTimeout for proper end-to-end request timeouts.
 */
public class SubsonicClientV2 implements ServerClient {
    private static final Logger log = LoggerFactory.getLogger(SubsonicClientV2.class);
    private static final String API_VERSION = "1.16.1";
    private static final HexFormat HEX = HexFormat.of().withLowerCase();
    private static final Random RANDOM = new Random();

    private final String serverId;
    private final Path dataDir;
    private final URI serverUri;
    private final String username;
    private final String password;
    private final String streamFormat;  // "" = source
    private final int streamBitRate;    // 0 = source
    private final OkHttpClient httpClient;
    private final OkHttpClient streamingHttpClient;

    public SubsonicClientV2(ServerConfig cfg) {
        this.serverId = cfg.id();
        this.dataDir = cfg.dataDir();
        this.serverUri = URI.create(cfg.url());
        this.username = cfg.username();
        this.password = cfg.password();

        var httpBuilder = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(loggingInterceptor(log));

        if (cfg.tlsSkipVerify()) {
            configureTrustAllCerts(httpBuilder);
        }

        this.httpClient = httpBuilder.build();
        this.streamingHttpClient = this.httpClient.newBuilder()
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(10, TimeUnit.MINUTES)
                .build();

        // Derive transcode settings from config (same logic as SubsonicClient.createSettings)
        TranscodeFormat fmt = cfg.audioFormat() != null ? cfg.audioFormat() : TranscodeFormat.source;
        this.streamFormat = switch (fmt) {
            case source -> "";
            case mp3 -> "mp3";
            case opus -> "opus";
        };
        this.streamBitRate = switch (cfg.audioBitrate()) {
            case null -> 0;
            case TranscodeBitrate.SourceQuality _ -> 0;
            case TranscodeBitrate.MaximumBitrate(var kbps) -> kbps;
        };
    }

    private static void configureTrustAllCerts(OkHttpClient.Builder builder) {
        try {
            X509TrustManager trustAllManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{trustAllManager}, new SecureRandom());
            builder.sslSocketFactory(sslContext.getSocketFactory(), trustAllManager);
            builder.hostnameVerifier((hostname, session) -> true);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException("Failed to configure TLS skip verify", e);
        }
    }

    private String generateSalt() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    static String md5(String input) {
        try {
            var md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private HttpUrl buildUrl(String path, Map<String, String> extraParams) {
        var salt = generateSalt();
        var token = md5(password + salt);
        var pathPrefix = Utils.removeTrailingSlash(this.serverUri.getPath());
        var requestPath = pathPrefix + path;
        var builder = HttpUrl.get(this.serverUri).newBuilder(requestPath)
                .setQueryParameter("u", username)
                .setQueryParameter("s", salt)
                .setQueryParameter("t", token)
                .setQueryParameter("c", Constants.APP_ID)
                .setQueryParameter("f", "json")
                .setQueryParameter("v", API_VERSION);
        for (var param : extraParams.entrySet()) {
            builder.setQueryParameter(param.getKey(), param.getValue());
        }
        return builder.build();
    }

    private URI buildUri(String path) {
        return buildUrl(path, Map.of()).uri();
    }

    private URI buildUri(String path, Map<String, String> extraParams) {
        return buildUrl(path, extraParams).uri();
    }

    private HttpUrl buildUrlMulti(String path, Map<String, String> singleParams, Map<String, List<String>> multiParams) {
        var salt = generateSalt();
        var token = md5(password + salt);
        var pathPrefix = Utils.removeTrailingSlash(this.serverUri.getPath());
        var requestPath = pathPrefix + path;
        var builder = HttpUrl.get(this.serverUri).newBuilder(requestPath)
                .setQueryParameter("u", username)
                .setQueryParameter("s", salt)
                .setQueryParameter("t", token)
                .setQueryParameter("c", Constants.APP_ID)
                .setQueryParameter("f", "json")
                .setQueryParameter("v", API_VERSION);
        for (var param : singleParams.entrySet()) {
            builder.setQueryParameter(param.getKey(), param.getValue());
        }
        for (var param : multiParams.entrySet()) {
            for (var value : param.getValue()) {
                builder.addQueryParameter(param.getKey(), value);
            }
        }
        return builder.build();
    }

    private <T> T fetchJson(String path, Map<String, String> params, Class<T> responseClass) {
        var url = buildUrl(path, params);
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code() + " from " + path + ": " + body);
            }
            return Utils.fromJson(body, responseClass);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T postJson(String path, Map<String, String> params, Class<T> responseClass) {
        var url = buildUrl(path, params);
        var request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code() + " from " + path + ": " + body);
            }
            return Utils.fromJson(body, responseClass);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void fetchVoid(String path, Map<String, String> params) {
        var url = buildUrl(path, params);
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code() + " from " + path + ": " + body);
            }
            var parsed = Utils.fromJson(body, PingResponseJson.class);
            if (!"ok".equalsIgnoreCase(parsed.subsonicResponse.status)) {
                throw new RuntimeException("Subsonic error from " + path + ": " + body);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void fetchVoidMulti(String path, Map<String, String> singleParams, Map<String, List<String>> multiParams) {
        var url = buildUrlMulti(path, singleParams, multiParams);
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            var body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP " + response.code() + " from " + path + ": " + body);
            }
            var parsed = Utils.fromJson(body, PingResponseJson.class);
            if (!"ok".equalsIgnoreCase(parsed.subsonicResponse.status)) {
                throw new RuntimeException("Subsonic error from " + path + ": " + body);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private <T extends HasStatus> T fetchAndCheck(String path, Map<String, String> params, Class<T> responseClass) {
        var parsed = fetchJson(path, params, responseClass);
        parsed.checkOk(path);
        return parsed;
    }

    private <T extends HasStatus> T postAndCheck(String path, Map<String, String> params, Class<T> responseClass) {
        var parsed = postJson(path, params, responseClass);
        parsed.checkOk(path);
        return parsed;
    }

    interface HasStatus {
        String getStatus();
        default void checkOk(String path) {
            if (!"ok".equalsIgnoreCase(getStatus())) {
                throw new RuntimeException("Subsonic error from " + path + ": status=" + getStatus());
            }
        }
    }

    static class PingResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        PingInner subsonicResponse;
        static class PingInner {
            String status;
            String version;
            String type;
            String serverVersion;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    // -- Child (song) --
    record ChildJson(
            String id,
            String parent,
            Boolean isDir,
            String title,
            String album,
            String artist,
            Integer track,
            Integer year,
            String genre,
            String coverArt,
            Long size,
            String suffix,
            Integer duration,
            Integer bitRate,
            Long playCount,
            Integer userRating,
            Integer discNumber,
            Instant starred,
            Instant created,
            String albumId,
            String artistId,
            Boolean isVideo
    ) {}

    // -- ArtistID3 --
    record ArtistID3Json(
            String id,
            String name,
            Integer albumCount,
            String coverArt,
            Instant starred
    ) {}

    // -- Index (for getArtists) --
    record IndexJson(
            String name,
            List<ArtistID3Json> artist
    ) {}

    // -- AlbumID3 --
    record AlbumID3Json(
            String id,
            String name,
            String artist,
            String artistId,
            String coverArt,
            Integer songCount,
            Integer duration,
            Integer year,
            String genre,
            Instant starred
    ) {}

    // -- AlbumWithSongs (for getAlbum) --
    record AlbumWithSongsJson(
            String id,
            String name,
            String artist,
            String artistId,
            String coverArt,
            Integer songCount,
            Integer duration,
            Integer year,
            String genre,
            Instant starred,
            List<ChildJson> song
    ) {}

    // -- ArtistWithAlbums (for getArtist) --
    record ArtistWithAlbumsJson(
            String id,
            String name,
            Integer albumCount,
            String coverArt,
            Instant starred,
            List<AlbumID3Json> album
    ) {}

    // -- ArtistInfo2 --
    record ArtistInfo2Json(
            String biography,
            String musicBrainzId,
            String lastFmUrl,
            String smallImageUrl,
            String mediumImageUrl,
            String largeImageUrl
    ) {}

    // -- PlaylistJson --
    static class PlaylistJson {
        String id;
        String name;
        String owner;
        @SerializedName("public")
        Boolean isPublic;
        int songCount;
        @SerializedName("duration")
        Integer durationSeconds;
        String coverArt;
        Instant created;
        Instant changed;
        List<ChildJson> entry = List.of();
    }

    // -- ScanStatus --
    record ScanStatusJson(
            Boolean scanning,
            Integer count,
            Integer folderCount,
            Instant lastScan,
            String scanType,
            Long elapsedTime
    ) {}

    // -- Starred2 --
    record Starred2Json(
            List<ChildJson> song,
            List<AlbumID3Json> album,
            List<ArtistID3Json> artist
    ) {}

    // -- SearchResult3 --
    record SearchResult3Json(
            List<ArtistID3Json> artist,
            List<AlbumID3Json> album,
            List<ChildJson> song
    ) {}

    // -- AlbumList2 --
    record AlbumList2Json(
            List<AlbumID3Json> album
    ) {}

    static class GetArtistsResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetArtistsInner subsonicResponse;
        static class GetArtistsInner {
            String status;
            ArtistsJson artists;
        }
        static class ArtistsJson {
            List<IndexJson> index;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetArtistResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetArtistInner subsonicResponse;
        static class GetArtistInner {
            String status;
            ArtistWithAlbumsJson artist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetArtistInfo2ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetArtistInfo2Inner subsonicResponse;
        static class GetArtistInfo2Inner {
            String status;
            ArtistInfo2Json artistInfo2;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetAlbumResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetAlbumInner subsonicResponse;
        static class GetAlbumInner {
            String status;
            AlbumWithSongsJson album;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetSongResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetSongInner subsonicResponse;
        static class GetSongInner {
            String status;
            ChildJson song;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetPlaylistsResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetPlaylistsInner subsonicResponse;
        static class GetPlaylistsInner {
            String status;
            PlaylistsListJson playlists;
        }
        static class PlaylistsListJson {
            List<PlaylistJson> playlist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetPlaylistResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetPlaylistInner subsonicResponse;
        static class GetPlaylistInner {
            String status;
            PlaylistJson playlist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetStarred2ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetStarred2Inner subsonicResponse;
        static class GetStarred2Inner {
            String status;
            Starred2Json starred2;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class GetAlbumList2ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        GetAlbumList2Inner subsonicResponse;
        static class GetAlbumList2Inner {
            String status;
            AlbumList2Json albumList2;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class SearchResult3ResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        SearchResult3Inner subsonicResponse;
        static class SearchResult3Inner {
            String status;
            SearchResult3Json searchResult3;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class ScanStatusResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        ScanStatusInner subsonicResponse;
        static class ScanStatusInner {
            String status;
            String version;
            String serverVersion;
            ScanStatusJson scanStatus;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    static class CreatePlaylistResponseJson implements HasStatus {
        @SerializedName("subsonic-response")
        CreatePlaylistInner subsonicResponse;
        static class CreatePlaylistInner {
            String status;
            PlaylistJson playlist;
        }
        @Override public String getStatus() { return subsonicResponse.status; }
    }

    private SongInfo toSongInfo(ChildJson song) {
        var duration = Duration.ofSeconds(song.duration() != null ? song.duration() : 0);
        var downloadUri = buildUri("/rest/download", Map.of("id", song.id()));
        var defaultSuffix = song.suffix() != null && !song.suffix().isEmpty() ? song.suffix() : "mp3";

        var transcodeInfo = new TranscodeInfo(
                song.id(),
                ofNullable(song.bitRate()),
                streamBitRate,
                duration,
                streamFormat.isEmpty() ? defaultSuffix : streamFormat
        );

        return new SongInfo(
                song.id(),
                song.title(),
                ofNullable(song.track()),
                ofNullable(song.discNumber()),
                ofNullable(song.bitRate()),
                song.size() != null ? song.size() : 0,
                ofNullable(song.year()),
                ofNullable(song.genre()).orElse(""),
                song.playCount() != null ? song.playCount() : 0L,
                ofNullable(song.userRating()),
                song.artistId(),
                song.artist(),
                song.albumId(),
                song.album(),
                duration,
                ofNullable(song.starred()),
                ofNullable(song.created()),
                toCoverArt(song.albumId(), new AlbumIdentifier(song.albumId())),
                song.suffix() != null ? song.suffix() : "",
                transcodeInfo,
                downloadUri
        );
    }

    private List<SongInfo> toSongInfoList(List<ChildJson> songs) {
        if (songs == null) {
            return List.of();
        }
        return songs.stream()
                .filter(c -> c.isDir() == null || !c.isDir())
                .filter(c -> c.isVideo() == null || !c.isVideo())
                .map(this::toSongInfo)
                .toList();
    }

    private ArtistEntry toArtistEntry(ArtistID3Json artist) {
        return new ArtistEntry(
                artist.id(),
                artist.name(),
                artist.albumCount() != null ? artist.albumCount() : 0,
                toCoverArt(artist.coverArt(), new ArtistIdentifier(artist.id())),
                ofNullable(artist.starred())
        );
    }

    private ArtistAlbumInfo toArtistAlbumInfo(AlbumID3Json album) {
        return new ArtistAlbumInfo(
                album.id(),
                album.name(),
                album.songCount() != null ? album.songCount() : 0,
                album.artistId(),
                album.artist(),
                Duration.ofSeconds(album.duration() != null ? album.duration() : 0),
                ofNullable(album.genre()).filter(s -> !s.isBlank()),
                ofNullable(album.year()),
                ofNullable(album.starred()),
                toCoverArt(album.coverArt(), new AlbumIdentifier(album.id()))
        );
    }

    private List<ArtistAlbumInfo> toAlbumInfoList(ArtistWithAlbumsJson artist) {
        if (artist.album() == null) {
            return List.of();
        }
        return artist.album().stream()
                .map(this::toArtistAlbumInfo)
                .toList();
    }

    private PlaylistSimple toPlaylistSimple(PlaylistJson pl) {
        return new PlaylistSimple(
                pl.id,
                pl.name,
                PlaylistKind.NORMAL,
                toCoverArt(pl.coverArt, new PlaylistIdentifier(pl.id))
                        .or(() -> ofNullable(pl.entry).flatMap(entries -> entries.stream()
                                .filter(c -> c.coverArt() != null && !c.coverArt().isBlank())
                                .findFirst()
                                .flatMap(c -> toCoverArt(c.coverArt(), new ObjectIdentifier.SongIdentifier(c.id())))
                        )),
                pl.songCount,
                pl.changed,
                pl.created
        );
    }

    private Optional<CoverArt> toCoverArt(String coverArtId, ObjectIdentifier identifier) {
        return ofNullable(coverArtId).map(id -> {
            var coverArtLink = buildUri("/rest/getCoverArt", Map.of("id", id));
            var filePath = toCachePath(this.dataDir, this.serverId, coverArtId);
            return new CoverArt(SERVER_ID, id, coverArtLink, filePath.cachePath().toAbsolutePath(), Optional.of(identifier));
        });
    }

    @Override
    public ServerType getServerType() {
        return ServerType.SUBSONIC;
    }

    @Override
    public boolean testConnection() {
        try {
            var parsed = fetchJson("/rest/ping", Map.of(), PingResponseJson.class);
            return "ok".equalsIgnoreCase(parsed.subsonicResponse.status);
        } catch (Exception e) {
            log.warn("testConnection failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public URI getStreamUri(String songId) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("id", songId);
        if (streamBitRate > 0) {
            params.put("maxBitRate", String.valueOf(streamBitRate));
        }
        if (!streamFormat.isEmpty()) {
            params.put("format", streamFormat);
        }
        return buildUri("/rest/stream", params);
    }

    @Override
    public void starId(String id) {
        fetchVoid("/rest/star", Map.of("id", id));
    }

    @Override
    public void unStarId(String id) {
        fetchVoid("/rest/unstar", Map.of("id", id));
    }

    @Override
    public void scrobble(ScrobbleRequest req) {
        fetchVoid("/rest/scrobble", Map.of(
                "id", req.songId(),
                "time", String.valueOf(req.playedAt().toEpochMilli())
        ));
    }

    @Override
    public SongInfo getSong(String songId) {
        var res = fetchAndCheck("/rest/getSong", Map.of("id", songId), GetSongResponseJson.class);
        return toSongInfo(res.subsonicResponse.song);
    }

    @Override
    public AlbumInfo getAlbumInfo(String albumId) {
        var res = fetchAndCheck("/rest/getAlbum", Map.of("id", albumId), GetAlbumResponseJson.class);
        var album = res.subsonicResponse.album;
        return new AlbumInfo(
                album.id(),
                album.name(),
                album.songCount() != null ? album.songCount() : 0,
                ofNullable(album.year()),
                album.artistId(),
                album.artist(),
                Duration.ofSeconds(album.duration() != null ? album.duration() : 0),
                ofNullable(album.starred()),
                toCoverArt(album.coverArt(), new AlbumIdentifier(album.id())),
                toSongInfoList(album.song())
        );
    }

    @Override
    public ArtistInfo getArtistInfo(String artistId) {
        var task1 = Utils.doAsync(() -> fetchAndCheck(
                "/rest/getArtist", Map.of("id", artistId), GetArtistResponseJson.class
        ));
        var task2 = Utils.doAsync(() -> fetchAndCheck(
                "/rest/getArtistInfo2", Map.of("id", artistId), GetArtistInfo2ResponseJson.class
        ));
        return task1.thenCombine(task2, (artistRes, infoRes) -> {
            var artist = artistRes.subsonicResponse.artist;
            var info = infoRes.subsonicResponse.artistInfo2;
            var albums = toAlbumInfoList(artist);
            var bio = ofNullable(info.biography())
                    .filter(str -> !str.isBlank())
                    .orElse("");
            Biography biography = TextUtils.parseLink(bio);
            return new ArtistInfo(
                    artist.id(),
                    artist.name(),
                    artist.albumCount() != null ? artist.albumCount() : 0,
                    ofNullable(artist.starred()),
                    toCoverArt(artist.coverArt(), new ArtistIdentifier(artist.id())),
                    albums,
                    biography
            );
        }).join();
    }

    @Override
    public ArtistInfo getArtistWithAlbums(String artistId) {
        var res = fetchAndCheck("/rest/getArtist", Map.of("id", artistId), GetArtistResponseJson.class);
        var artist = res.subsonicResponse.artist;
        var albums = toAlbumInfoList(artist);
        return new ArtistInfo(
                artist.id(),
                artist.name(),
                artist.albumCount() != null ? artist.albumCount() : 0,
                ofNullable(artist.starred()),
                toCoverArt(artist.coverArt(), new ArtistIdentifier(artist.id())),
                albums,
                new Biography("", "", "")
        );
    }

    @Override
    public ListArtists getArtists() {
        var res = fetchAndCheck("/rest/getArtists", Map.of(), GetArtistsResponseJson.class);
        var indexes = res.subsonicResponse.artists.index;
        var list = indexes == null ? List.<ArtistEntry>of() : indexes.stream()
                .filter(idx -> idx.artist() != null)
                .flatMap(idx -> idx.artist().stream())
                .map(this::toArtistEntry)
                .toList();
        return new ListArtists(list);
    }

    @Override
    public ListStarred getStarred() {
        var res = fetchAndCheck("/rest/getStarred2", Map.of(), GetStarred2ResponseJson.class);
        var starred = res.subsonicResponse.starred2;
        var songs = starred != null && starred.song() != null
                ? starred.song().stream().map(this::toSongInfo).toList()
                : List.<SongInfo>of();
        return new ListStarred(songs);
    }

    @Override
    public ListPlaylists getPlaylists() {
        var res = fetchAndCheck("/rest/getPlaylists", Map.of(), GetPlaylistsResponseJson.class);
        var playlists = res.subsonicResponse.playlists;
        if (playlists == null || playlists.playlist == null) {
            return new ListPlaylists(List.of());
        }
        var list = playlists.playlist.stream()
                .map(this::toPlaylistSimple)
                .toList();
        return new ListPlaylists(list);
    }

    @Override
    public Playlist getPlaylist(String playlistId) {
        var res = fetchAndCheck("/rest/getPlaylist", Map.of("id", playlistId), GetPlaylistResponseJson.class);
        var pl = res.subsonicResponse.playlist;
        var songs = toSongInfoList(pl.entry);
        return new Playlist(
                pl.id,
                pl.name,
                PlaylistKind.NORMAL,
                toCoverArt(pl.coverArt, new PlaylistIdentifier(pl.id)),
                songs.size(),
                pl.changed,
                pl.created,
                songs
        );
    }

    @Override
    public HomeOverview getHomeOverview() {
        var recentTask = Utils.doAsync(() -> loadAlbumList("recent"));
        var newestTask = Utils.doAsync(() -> loadAlbumList("newest"));
        var frequentTask = Utils.doAsync(() -> loadAlbumList("frequent"));
        var highestTask = Utils.doAsync(() -> loadAlbumList("highest"));
        var yearTask = Utils.doAsync(() -> loadAlbumListByYear());

        return CompletableFuture
                .allOf(recentTask, newestTask, frequentTask, highestTask, yearTask)
                .thenApply(_ -> new HomeOverview(
                        recentTask.join(),
                        newestTask.join(),
                        frequentTask.join(),
                        highestTask.join(),
                        yearTask.join()
                ))
                .join();
    }

    private List<ArtistAlbumInfo> loadAlbumList(String type) {
        var res = fetchAndCheck("/rest/getAlbumList2", Map.of("type", type), GetAlbumList2ResponseJson.class);
        var albumList = res.subsonicResponse.albumList2;
        if (albumList == null || albumList.album() == null) {
            return List.of();
        }
        return albumList.album().stream()
                .map(this::toArtistAlbumInfo)
                .toList();
    }

    private List<ArtistAlbumInfo> loadAlbumListByYear() {
        int currentYear = Instant.now().atZone(ZoneOffset.UTC).getYear();
        var res = fetchAndCheck("/rest/getAlbumList2", Map.of(
                "type", "byYear",
                "fromYear", String.valueOf(currentYear),
                "toYear", "1900",
                "size", "20"
        ), GetAlbumList2ResponseJson.class);
        var albumList = res.subsonicResponse.albumList2;
        if (albumList == null || albumList.album() == null) {
            return List.of();
        }
        return albumList.album().stream()
                .map(this::toArtistAlbumInfo)
                .toList();
    }

    @Override
    public SearchResult search(String query) {
        var res = fetchAndCheck("/rest/search3", Map.of("query", query), SearchResult3ResponseJson.class);
        var sr = res.subsonicResponse.searchResult3;
        var artists = sr != null && sr.artist() != null
                ? sr.artist().stream().map(this::toArtistEntry).toList()
                : List.<ArtistEntry>of();
        var albums = sr != null && sr.album() != null
                ? sr.album().stream().map(this::toArtistAlbumInfo).toList()
                : List.<ArtistAlbumInfo>of();
        var songs = sr != null && sr.song() != null
                ? sr.song().stream().map(this::toSongInfo).toList()
                : List.<SongInfo>of();
        return new SearchResult(artists, albums, songs);
    }

    @Override
    public AddSongToPlaylist addToPlaylist(AddSongToPlaylist req) {
        fetchVoidMulti("/rest/updatePlaylist",
                Map.of("playlistId", req.playlistId()),
                Map.of("songIdToAdd", req.songIds())
        );
        return req;
    }

    @Override
    public PlaylistSimple playlistCreate(PlaylistCreateRequest request) {
        var res = postAndCheck("/rest/createPlaylist", Map.of("name", request.name()), CreatePlaylistResponseJson.class);
        var pl = res.subsonicResponse.playlist;
        var playlist = toPlaylistSimple(pl);
        if (!request.songIds().isEmpty()) {
            this.addToPlaylist(new AddSongToPlaylist(playlist.id(), request.songIds()));
        }
        return playlist;
    }

    @Override
    public void playlistRename(PlaylistRenameRequest req) {
        fetchVoid("/rest/updatePlaylist", Map.of("playlistId", req.id(), "name", req.newName()));
    }

    @Override
    public void playlistDelete(PlaylistDeleteRequest req) {
        fetchVoid("/rest/deletePlaylist", Map.of("id", req.id()));
    }

    @Override
    public void playlistRemove(PlaylistRemoveSongRequest req) {
        var indices = req.songIds().stream()
                .map(r -> String.valueOf(r.indexInPlaylist()))
                .toList();
        fetchVoidMulti("/rest/updatePlaylist",
                Map.of("playlistId", req.playlistId()),
                Map.of("songIndexToRemove", indices)
        );
    }

    @Override
    public ScanStatus scanStatus() {
        var res = fetchAndCheck("/rest/getScanStatus", Map.of(), ScanStatusResponseJson.class);
        var scan = res.subsonicResponse.scanStatus;
        if (scan.scanning() != null && scan.scanning()) {
            return new ScanStatus.Scanning(scan.count() != null ? scan.count() : 0);
        } else {
            return new ScanStatus.NotScanning();
        }
    }

    @Override
    public ScanStatus startScan() {
        return switch (scanStatus()) {
            case ScanStatus.Scanning scanning -> scanning;
            case ScanStatus.NotScanning _ -> {
                var res = fetchAndCheck("/rest/startScan", Map.of(), ScanStatusResponseJson.class);
                var scan = res.subsonicResponse.scanStatus;
                int count = scan.count() != null ? scan.count() : 0;
                yield new ScanStatus.Scanning(count);
            }
        };
    }

    @Override
    public ServerInfo getServerInfo() {
        var res = fetchAndCheck("/rest/getScanStatus", Map.of(), ScanStatusResponseJson.class);
        var inner = res.subsonicResponse;
        var scan = inner.scanStatus;
        long count = scan.count() != null ? scan.count() : 0;
        Optional<Integer> folderCount = scan.folderCount() != null ? Optional.of(scan.folderCount()) : Optional.empty();
        Optional<Instant> lastScan = scan.lastScan() != null ? Optional.of(scan.lastScan()) : Optional.empty();
        Optional<String> serverVersion = inner.serverVersion != null ? Optional.of(inner.serverVersion) : Optional.empty();
        String apiVersion = inner.version != null ? inner.version : API_VERSION;
        return new ServerInfo(apiVersion, count, folderCount, lastScan, serverVersion);
    }

    @Override
    public StreamResponse openStream(TranscodeInfo transcodeInfo) {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("id", transcodeInfo.songId());
        if (streamBitRate > 0) {
            params.put("maxBitRate", String.valueOf(streamBitRate));
        }
        if (!streamFormat.isEmpty()) {
            params.put("format", streamFormat);
        }
        var url = buildUrl("/rest/stream", params);
        var request = new Request.Builder().url(url).get().build();
        try {
            Response response = streamingHttpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                response.close();
                throw new RuntimeException("HTTP %d from /rest/stream for songId=%s".formatted(
                        response.code(), transcodeInfo.songId()));
            }
            String contentType = response.header("Content-Type", "");
            if (contentType.isEmpty() || contentType.contains("xml") || contentType.contains("html") || contentType.contains("json")) {
                response.close();
                throw new RuntimeException("Unexpected content-type=%s from /rest/stream for songId=%s".formatted(
                        contentType, transcodeInfo.songId()));
            }
            long contentLength = response.body() != null ? response.body().contentLength() : -1;
            InputStream wrappedStream = new FilterInputStream(response.body().byteStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        response.close();
                    }
                }
            };
            return new StreamResponse(transcodeInfo.songId(), wrappedStream, contentLength, contentType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open stream for songId=%s".formatted(transcodeInfo.songId()), e);
        }
    }

    @Override
    public CoverArtResponse downloadCoverArt(CoverArt coverArt, int maxSize) {
        var url = HttpUrl.get(coverArt.coverArtLink()).newBuilder()
                .setQueryParameter("size", "%d".formatted(maxSize))
                .build();
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("HTTP %d fetching coverArt=%s".formatted(
                        response.code(), coverArt.coverArtId()));
            }
            String contentType = response.header("Content-Type", "image/webp");
            if (contentType.contains("xml") || contentType.contains("html") || contentType.contains("json")) {
                throw new RuntimeException("Unexpected content-type=%s for coverArt=%s".formatted(
                        contentType, coverArt.coverArtId()));
            }
            byte[] data = response.body() != null ? response.body().bytes() : new byte[0];
            return new CoverArtResponse(data, contentType);
        } catch (IOException e) {
            throw new RuntimeException("Failed to download coverArt=%s".formatted(coverArt.coverArtId()), e);
        }
    }

    public static SubsonicClientV2 create(ServerConfig cfg) {
        return new SubsonicClientV2(cfg);
    }
}
