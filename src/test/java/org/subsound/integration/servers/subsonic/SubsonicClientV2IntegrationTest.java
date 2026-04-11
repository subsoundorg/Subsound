package org.subsound.integration.servers.subsonic;

import org.junit.Ignore;
import org.junit.Test;
import org.subsound.configuration.Config;
import org.subsound.integration.platform.secret.NoopSecretService;

/**
 * Integration test that calls a real Navidrome/Subsonic server using SubsonicClientV2.
 * Run manually with a configured server in ~/.config/io.github.subsoundorg.Subsound/config.json
 */
@Ignore("Requires a real server connection")
public class SubsonicClientV2IntegrationTest {

    private SubsonicClientV2 createClient() {
        var config = Config.createDefault(new NoopSecretService());
        var cfg = config.serverConfig;
        return SubsonicClientV2.create(cfg);
    }

    @Test
    public void testPing() {
        var client = createClient();
        var ok = client.testConnection();
        System.out.println("Ping: " + ok);
        assert ok;
    }

    @Test
    public void testGetServerInfo() {
        var client = createClient();
        var info = client.getServerInfo();
        System.out.println("API version: " + info.apiVersion());
        System.out.println("Song count: " + info.songCount());
        info.serverVersion().ifPresent(v -> System.out.println("Server version: " + v));
        info.folderCount().ifPresent(f -> System.out.println("Folder count: " + f));
        info.lastScan().ifPresent(s -> System.out.println("Last scan: " + s));
    }

    @Test
    public void testGetArtists() {
        var client = createClient();
        var artists = client.getArtists();
        System.out.println("Artist count: " + artists.list().size());
        artists.list().stream().limit(5).forEach(a ->
                System.out.println("  - " + a.name() + " (" + a.albumCount() + " albums)")
        );
    }

    @Test
    public void testGetArtistInfo() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();

        var artistInfo = client.getArtistInfo(firstArtistId);
        System.out.println("Artist: " + artistInfo.name());
        System.out.println("Album count: " + artistInfo.albumCount());
        System.out.println("Albums: " + artistInfo.albums().size());
        System.out.println("Biography: " + artistInfo.biography().original());
        artistInfo.albums().forEach(a -> System.out.println("  - " + a.name() + " (" + a.year().orElse(0) + ")"));
    }

    @Test
    public void testGetArtistWithAlbums() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();

        var artistInfo = client.getArtistWithAlbums(firstArtistId);
        System.out.println("Artist: " + artistInfo.name());
        System.out.println("Album count: " + artistInfo.albumCount());
        System.out.println("Albums: " + artistInfo.albums().size());
        artistInfo.albums().forEach(a -> System.out.println("  - " + a.name() + " (" + a.year().orElse(0) + ")"));
    }

    @Test
    public void testGetAlbumInfo() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();
        var artistInfo = client.getArtistWithAlbums(firstArtistId);
        var firstAlbumId = artistInfo.albums().getFirst().id();

        var album = client.getAlbumInfo(firstAlbumId);
        System.out.println("Album: " + album.name());
        System.out.println("Artist: " + album.artistName());
        System.out.println("Songs: " + album.songs().size());
        album.songs().forEach(s -> System.out.println("  - " + s.title() + " (" + s.duration().toSeconds() + "s)"));
    }

    @Test
    public void testGetSong() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();
        var artistInfo = client.getArtistWithAlbums(firstArtistId);
        var firstAlbumId = artistInfo.albums().getFirst().id();
        var album = client.getAlbumInfo(firstAlbumId);
        var firstSongId = album.songs().getFirst().id();

        var song = client.getSong(firstSongId);
        System.out.println("Song: " + song.title());
        System.out.println("Artist: " + song.artist());
        System.out.println("Duration: " + song.duration().toSeconds() + "s");
        System.out.println("Stream URI: " + client.getStreamUri(song.id()));
    }

    @Test
    public void testGetStarred() {
        var client = createClient();
        var starred = client.getStarred();
        System.out.println("Starred songs: " + starred.songs().size());
        starred.songs().stream().limit(5).forEach(s ->
                System.out.println("  - " + s.artist() + " - " + s.title())
        );
    }

    @Test
    public void testGetPlaylists() {
        var client = createClient();
        var playlists = client.getPlaylists();
        System.out.println("Playlist count: " + playlists.playlists().size());
        playlists.playlists().forEach(p ->
                System.out.println("  - " + p.name() + " (" + p.songCount() + " songs)")
        );
    }

    @Test
    public void testGetPlaylist() {
        var client = createClient();
        var playlists = client.getPlaylists();
        if (playlists.playlists().isEmpty()) {
            System.out.println("No playlists found, skipping");
            return;
        }
        var firstPlaylistId = playlists.playlists().getFirst().id();
        var playlist = client.getPlaylist(firstPlaylistId);
        System.out.println("Playlist: " + playlist.name());
        System.out.println("Songs: " + playlist.songs().size());
        playlist.songs().stream().limit(5).forEach(s ->
                System.out.println("  - " + s.artist() + " - " + s.title())
        );
    }

    @Test
    public void testSearch() {
        var client = createClient();
        var result = client.search("the");
        System.out.println("Artists: " + result.artists().size());
        System.out.println("Albums: " + result.albums().size());
        System.out.println("Songs: " + result.songs().size());
    }

    @Test
    public void testGetHomeOverview() {
        var client = createClient();
        var home = client.getHomeOverview();
        System.out.println("Recent: " + home.recent().size());
        System.out.println("Newest: " + home.newest().size());
        System.out.println("Frequent: " + home.frequent().size());
        System.out.println("Highest: " + home.highest().size());
        System.out.println("By year: " + home.byYear().size());
    }

    @Test
    public void testScanStatus() {
        var client = createClient();
        var status = client.scanStatus();
        System.out.println("Scan status: " + status);
    }

    @Test
    public void testGetStreamUri() {
        var client = createClient();
        var uri = client.getStreamUri("some-song-id");
        System.out.println("Stream URI: " + uri);
        // Verify it contains auth params
        var uriStr = uri.toString();
        assert uriStr.contains("u=") : "Missing username param";
        assert uriStr.contains("t=") : "Missing token param";
        assert uriStr.contains("s=") : "Missing salt param";
        assert uriStr.contains("id=some-song-id") : "Missing song id param";
    }
}
