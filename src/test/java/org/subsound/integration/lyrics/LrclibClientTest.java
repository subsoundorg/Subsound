package org.subsound.integration.lyrics;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

public class LrclibClientTest {

    private MockWebServer server;
    private LrclibClient client;

    // Recorded JSON fixtures from lrclib.net
    // GET /api/get?track_name=sultans+of+swing&artist_name=dire+straits
    private static final String EXACT_MATCH_RESPONSE = """
            {
              "id": 1360,
              "name": "Sultans Of Swing",
              "trackName": "Sultans Of Swing",
              "artistName": "Dire Straits",
              "albumName": "Dire Straits (Remastered)",
              "duration": 349.0,
              "instrumental": false,
              "plainLyrics": "You get a shiver in the dark...",
              "syncedLyrics": "[00:12.84] You get a shiver in the dark, it's raining in the park, but meantime\\n[00:20.14] South of the river, you stop and you hold everything\\n[00:26.94] A band is blowing Dixie, double-four time\\n[00:32.89] You feel alright when you hear that music ring\\n[00:41.84] Well, now you step inside, but you don't see too many faces\\n[00:49.41] Coming in out of the rain to hear the jazz go down\\n[00:56.48] Competition in other places\\n[01:02.72] Ah, but the horns, they blowing that sound\\n[01:09.22] Way on down south\\n[01:12.54] Way on down south, London town\\n[01:27.16] You check out Guitar George, he knows all the chords\\n[01:34.18] Mind, he's strictly rhythm, he doesn't want to make it cry or sing\\n[01:41.50] Yes and an old guitar is all he can afford\\n[01:47.88] When he gets up under the lights to play his thing\\n[01:57.42] And Harry doesn't mind if he doesn't make the scene\\n[02:03.63] He's got a daytime job, he's doing alright\\n[02:10.58] He can play the honky-tonk like anything\\n[02:17.19] Saving it up for Friday night\\n[02:23.90] With the Sultans\\n[02:27.10] With the Sultans of Swing\\n[04:27.48] \\"Goodnight, now it's time to go home\\"\\n[04:33.36] Then he makes it fast with one more thing\\n[04:40.12] \\"We are the Sultans\\n[04:43.32] We are the Sultans of Swing\\""
            }
            """;

    // GET /api/search?q=sultans+of+swing+dire+straits
    private static final String SEARCH_RESPONSE = """
            [
              {
                "id": 11091867,
                "name": "Sultans Of Swing",
                "trackName": "Sultans Of Swing",
                "artistName": "Dire Straits",
                "albumName": "Dire Straits - Sultans Of Swing",
                "duration": 347.0,
                "instrumental": false,
                "plainLyrics": "You get a shiver in the dark...",
                "syncedLyrics": "[00:12.04] You get a shiver in the dark\\n[00:19.65] South of the river\\n[00:25.94] A band is blowing Dixie\\n[02:23.58] With the Sultans\\n[02:26.31] With the Sultans of Swing"
              },
              {
                "id": 22318541,
                "name": "Sultans of Swing",
                "trackName": "Sultans of Swing",
                "artistName": "Dire Straits",
                "albumName": "Dire Straits - Sultans of Swing",
                "duration": 344.293878,
                "instrumental": false,
                "plainLyrics": "You get a shiver in the dark...",
                "syncedLyrics": "[00:12.84] You get a shiver in the dark\\n[00:20.14] South of the river\\n[02:23.90] With the Sultans\\n[02:27.10] With the Sultans of Swing"
              },
              {
                "id": 27462413,
                "name": "Sultans Of Swing",
                "trackName": "Sultans Of Swing",
                "artistName": "Dire Straits",
                "albumName": "Dire Straits - Sultans Of Swing",
                "duration": 350.458776,
                "instrumental": false,
                "plainLyrics": "You get a shiver in the dark...",
                "syncedLyrics": "[00:12.84] You get a shiver in the dark\\n[00:20.14] South of the river\\n[02:23.90] With the Sultans\\n[02:27.10] With the Sultans of Swing"
              }
            ]
            """;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = LrclibClient.create(server.url("/").toString().replaceAll("/$", ""));
    }

    @After
    public void tearDown() throws IOException {
        server.close();
    }

    @Test
    public void testExactMatchReturnsLyrics() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(EXACT_MATCH_RESPONSE)
                .build());

        var result = client.getLyrics("Sultans Of Swing", "Dire Straits", null, 349);

        assertThat(result).isPresent();
        var lines = result.get();
        assertThat(lines).isNotEmpty();
        assertThat(lines.getFirst().text()).isEqualTo("You get a shiver in the dark, it's raining in the park, but meantime");
        assertThat(lines.getFirst().timeMs()).isEqualTo(12840);
        assertThat(lines.getLast().text()).isEqualTo("We are the Sultans of Swing\"");
        assertThat(lines.getLast().timeMs()).isEqualTo(283320);

        // verify the request path and params
        RecordedRequest req = server.takeRequest();
        assertThat(req.getRequestLine()).contains("/api/get");
        assertThat(req.getRequestLine()).contains("track_name=Sultans");
        assertThat(req.getRequestLine()).contains("artist_name=Dire");
    }

    @Test
    public void testExactMatchSendsQueryParams() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(EXACT_MATCH_RESPONSE)
                .build());

        client.getLyrics("Sultans Of Swing", "Dire Straits", "Dire Straits", 349);

        RecordedRequest req = server.takeRequest();
        var url = req.getUrl().toString();
        assertThat(url).contains("track_name=Sultans");
        assertThat(url).contains("artist_name=Dire");
        assertThat(url).contains("album_name=Dire");
        assertThat(url).contains("duration=349");
    }

    @Test
    public void testExactMatchSendsUserAgent() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(EXACT_MATCH_RESPONSE)
                .build());

        client.getLyrics("Sultans Of Swing", "Dire Straits", null, null);

        RecordedRequest req = server.takeRequest();
        assertThat(req.getHeaders().get("User-Agent")).contains("Subsound");
    }

    @Test
    public void testFallsBackToSearchWhenExactMatchReturns404() throws Exception {
        // exact match returns 404
        server.enqueue(new MockResponse.Builder()
                .code(404)
                .body("{}")
                .build());
        // search returns results
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(SEARCH_RESPONSE)
                .build());

        var result = client.getLyrics("Sultans Of Swing", "Dire Straits", null, 347);

        assertThat(result).isPresent();
        var lines = result.get();
        assertThat(lines).isNotEmpty();
        assertThat(lines.getFirst().text()).isEqualTo("You get a shiver in the dark");

        // verify both requests were made
        RecordedRequest req1 = server.takeRequest();
        assertThat(req1.getRequestLine()).contains("/api/get");
        RecordedRequest req2 = server.takeRequest();
        assertThat(req2.getRequestLine()).contains("/api/search");
        assertThat(req2.getUrl().toString()).contains("q=Sultans");
    }

    @Test
    public void testSearchPicksBestDurationMatch() throws Exception {
        // exact match 404
        server.enqueue(new MockResponse.Builder().code(404).body("{}").build());
        // search returns 3 results: durations 347.0, 344.3, 350.5
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(SEARCH_RESPONSE)
                .build());

        // requesting duration=350 should pick the 350.5 result (id=27462413)
        var result = client.getLyrics("Sultans Of Swing", "Dire Straits", null, 350);

        assertThat(result).isPresent();
    }

    @Test
    public void testSearchRejectsDurationMismatchOver5Seconds() throws Exception {
        // exact match 404
        server.enqueue(new MockResponse.Builder().code(404).body("{}").build());
        // search results have durations 347.0, 344.3, 350.5
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(SEARCH_RESPONSE)
                .build());

        // requesting duration=400 — all results are >5s away
        var result = client.getLyrics("Sultans Of Swing", "Dire Straits", null, 400);

        assertThat(result).isEmpty();
    }

    @Test
    public void testReturnsEmptyWhenBothTitleAndArtistBlank() {
        var result = client.getLyrics("", "", null, null);
        assertThat(result).isEmpty();
    }

    @Test
    public void testReturnsEmptyWhenBothTitleAndArtistNull() {
        var result = client.getLyrics(null, null, null, null);
        assertThat(result).isEmpty();
    }

    @Test
    public void testReturnsEmptyWhenServerReturns500() throws Exception {
        server.enqueue(new MockResponse.Builder().code(500).body("error").build());
        server.enqueue(new MockResponse.Builder().code(500).body("error").build());

        var result = client.getLyrics("Sultans Of Swing", "Dire Straits", null, null);

        assertThat(result).isEmpty();
    }

    @Test
    public void testReturnsEmptyWhenNoSyncedLyrics() throws Exception {
        var noSyncedResponse = """
                {
                  "id": 999,
                  "trackName": "Test",
                  "artistName": "Test Artist",
                  "albumName": "Test Album",
                  "duration": 200.0,
                  "instrumental": false,
                  "plainLyrics": "Just plain lyrics here",
                  "syncedLyrics": null
                }
                """;
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(noSyncedResponse)
                .build());
        // search also returns empty
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("[]")
                .build());

        var result = client.getLyrics("Test", "Test Artist", null, null);

        assertThat(result).isEmpty();
    }

    @Test
    public void testParseLrcBasic() {
        var lrc = "[01:23.45] Hello world\n[02:34.56] Second line";
        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).timeMs()).isEqualTo(83450);
        assertThat(lines.get(0).text()).isEqualTo("Hello world");
        assertThat(lines.get(1).timeMs()).isEqualTo(154560);
        assertThat(lines.get(1).text()).isEqualTo("Second line");
    }

    @Test
    public void testParseLrcThreeDigitMillis() {
        var lrc = "[01:23.456] Hello";
        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().timeMs()).isEqualTo(83456);
    }

    @Test
    public void testParseLrcTwoDigitCentiseconds() {
        var lrc = "[00:12.84] Test line";
        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(1);
        // 12 seconds + 840 ms
        assertThat(lines.getFirst().timeMs()).isEqualTo(12840);
        assertThat(lines.getFirst().text()).isEqualTo("Test line");
    }

    @Test
    public void testParseLrcSkipsEmptyTextLines() {
        var lrc = "[00:10.00] Hello\n[00:20.00] \n[00:30.00] World";
        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).text()).isEqualTo("Hello");
        assertThat(lines.get(1).text()).isEqualTo("World");
    }

    @Test
    public void testParseLrcSkipsMetadataLines() {
        var lrc = "[ar:Dire Straits]\n[ti:Sultans Of Swing]\n[00:12.84] First lyric line";
        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().text()).isEqualTo("First lyric line");
    }

    @Test
    public void testParseLrcMultipleTimestamps() {
        var lrc = "[00:10.00][01:20.00] Repeated chorus line";
        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).timeMs()).isEqualTo(10000);
        assertThat(lines.get(0).text()).isEqualTo("Repeated chorus line");
        assertThat(lines.get(1).timeMs()).isEqualTo(80000);
        assertThat(lines.get(1).text()).isEqualTo("Repeated chorus line");
    }

    @Test
    public void testParseLrcSortsByTime() {
        var lrc = "[02:00.00] Second\n[00:30.00] First\n[01:00.00] Middle";
        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).text()).isEqualTo("First");
        assertThat(lines.get(1).text()).isEqualTo("Middle");
        assertThat(lines.get(2).text()).isEqualTo("Second");
    }

    @Test
    public void testParseLrcEmptyInput() {
        assertThat(LrclibClient.parseLrc("")).isEmpty();
        assertThat(LrclibClient.parseLrc(null)).isEmpty();
    }

    @Test
    public void testParseLrcRealSultansOfSwingFixture() {
        var lrc = "[00:12.84] You get a shiver in the dark, it's raining in the park, but meantime\n"
                + "[00:20.14] South of the river, you stop and you hold everything\n"
                + "[00:26.94] A band is blowing Dixie, double-four time\n"
                + "[00:32.89] You feel alright when you hear that music ring\n"
                + "[00:37.95] \n"
                + "[00:41.84] Well, now you step inside, but you don't see too many faces\n"
                + "[04:43.32] We are the Sultans of Swing\"";

        var lines = LrclibClient.parseLrc(lrc);

        assertThat(lines).hasSize(6);  // 7 lines minus 1 empty-text line
        assertThat(lines.getFirst().timeMs()).isEqualTo(12840);
        assertThat(lines.getFirst().text()).startsWith("You get a shiver");
        assertThat(lines.getLast().timeMs()).isEqualTo(283320);
        assertThat(lines.getLast().text()).contains("Sultans of Swing");
    }
}
