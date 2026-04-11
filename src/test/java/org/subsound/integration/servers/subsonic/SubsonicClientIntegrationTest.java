package org.subsound.integration.servers.subsonic;

import org.subsound.configuration.Config;
import org.subsound.integration.platform.secret.NoopSecretService;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Integration test that calls a real Navidrome/Subsonic server.
 * Run manually with a configured server in ~/.local/share/io.github.subsoundorg.Subsound/config.json
 */
@Ignore("Requires a real server connection")
public class SubsonicClientIntegrationTest {

    private SubsonicClientV2 createClient() {
        var config = Config.createDefault(new NoopSecretService());
        var cfg = config.serverConfig;
        return SubsonicClientV2.create(cfg);
    }

    @Test
    public void testGetArtistInfoParsing() {
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
    public void testGetArtistWithAlbumsParsing() {
        var client = createClient();
        var artists = client.getArtists();
        var firstArtistId = artists.list().getFirst().id();

        var artistInfo = client.getArtistWithAlbums(firstArtistId);
        System.out.println("Artist: " + artistInfo.name());
        System.out.println("Album count: " + artistInfo.albumCount());
        System.out.println("Albums: " + artistInfo.albums().size());
        artistInfo.albums().forEach(a -> System.out.println("  - " + a.name() + " (" + a.year().orElse(0) + ")"));
    }
}