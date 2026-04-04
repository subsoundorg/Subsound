package org.subsound.integration.lyrics;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.configuration.constants.Constants;
import org.subsound.utils.Utils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.subsound.utils.LogUtils.loggingInterceptor;
import static org.subsound.utils.LogUtils.userAgentInterceptor;

/**
 * Client for lrclib.net — a free synced lyrics API.
 * Fetches time-synced LRC lyrics by exact match or search fallback.
 */
public class LrclibClient {
    private static final String DEFAULT_BASE_URL = "https://lrclib.net";
    private static final int DURATION_TOLERANCE_SECONDS = 5;
    private static final Pattern LRC_TIMESTAMP = Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})]");

    private final Logger log = LoggerFactory.getLogger(LrclibClient.class);
    private final String baseUrl;
    private final OkHttpClient httpClient;

    public record LyricLine(long timeMs, String text) {}

    record LrcLibResult(
            int id,
            String trackName,
            String artistName,
            String albumName,
            Double duration,
            String plainLyrics,
            String syncedLyrics
    ) {}

    private LrclibClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .addInterceptor(userAgentInterceptor(Constants.USER_AGENT))
                .addInterceptor(loggingInterceptor(log))
                .build();
    }

    public static LrclibClient create() {
        return new LrclibClient(DEFAULT_BASE_URL);
    }

    static LrclibClient create(String baseUrl) {
        return new LrclibClient(baseUrl);
    }

    /**
     * Fetch synced lyrics for a song. Tries exact match first, then search fallback.
     *
     * @return synced lyric lines sorted by time, or empty if not found
     */
    public Optional<List<LyricLine>> getLyrics(String title, String artist, @Nullable String album, @Nullable Integer durationSeconds) {
        if ((title == null || title.isBlank()) && (artist == null || artist.isBlank())) {
            return Optional.empty();
        }
        try {
            var exact = fetchExactMatch(title, artist, album, durationSeconds);
            if (exact != null && exact.syncedLyrics() != null && !exact.syncedLyrics().isBlank()) {
                var lines = parseLrc(exact.syncedLyrics());
                if (!lines.isEmpty()) {
                    return Optional.of(lines);
                }
            }

            var searchResults = fetchSearch(title, artist);
            if (searchResults == null || searchResults.length == 0) {
                return Optional.empty();
            }

            var best = pickBestMatch(searchResults, durationSeconds);
            if (best != null && best.syncedLyrics() != null && !best.syncedLyrics().isBlank()) {
                var lines = parseLrc(best.syncedLyrics());
                if (!lines.isEmpty()) {
                    return Optional.of(lines);
                }
            }

            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to fetch lyrics for '{}' by '{}': {}", title, artist, e.getMessage());
            return Optional.empty();
        }
    }

    private @Nullable LrcLibResult fetchExactMatch(String title, String artist, @Nullable String album, @Nullable Integer durationSeconds) {
        var params = new LinkedHashMap<String, String>();
        params.put("track_name", title != null ? title : "");
        params.put("artist_name", artist != null ? artist : "");
        if (album != null && !album.isBlank()) {
            params.put("album_name", album);
        }
        if (durationSeconds != null && durationSeconds > 0) {
            params.put("duration", String.valueOf(durationSeconds));
        }
        return fetchJsonOrNull(buildUrl("/api/get", params), LrcLibResult.class);
    }

    private LrcLibResult @Nullable [] fetchSearch(String title, String artist) {
        var query = ((title != null ? title : "") + " " + (artist != null ? artist : "")).trim();
        if (query.isBlank()) {
            return null;
        }
        return fetchJsonOrNull(buildUrl("/api/search", Map.of("q", query)), LrcLibResult[].class);
    }

    private static @Nullable LrcLibResult pickBestMatch(LrcLibResult[] results, @Nullable Integer durationSeconds) {
        var withSynced = new ArrayList<LrcLibResult>();
        for (var r : results) {
            if (r.syncedLyrics() != null && !r.syncedLyrics().isBlank()) {
                withSynced.add(r);
            }
        }
        if (withSynced.isEmpty()) {
            return null;
        }
        if (durationSeconds == null || durationSeconds <= 0) {
            return withSynced.getFirst();
        }

        LrcLibResult best = null;
        double bestDiff = Double.MAX_VALUE;
        for (var r : withSynced) {
            double diff = r.duration() != null ? Math.abs(r.duration() - durationSeconds) : 0;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = r;
            }
        }
        if (best == null || bestDiff > DURATION_TOLERANCE_SECONDS) {
            return null;
        }
        return best;
    }

    static List<LyricLine> parseLrc(String lrcText) {
        if (lrcText == null || lrcText.isBlank()) {
            return List.of();
        }
        var lines = new ArrayList<LyricLine>();
        for (String raw : lrcText.split("\n")) {
            var matcher = LRC_TIMESTAMP.matcher(raw);
            var timestamps = new ArrayList<Long>();
            int lastMatchEnd = 0;
            while (matcher.find()) {
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                String msStr = matcher.group(3);
                int millis = msStr.length() == 2
                        ? Integer.parseInt(msStr) * 10
                        : Integer.parseInt(msStr);
                long timeMs = (minutes * 60L + seconds) * 1000L + millis;
                timestamps.add(timeMs);
                lastMatchEnd = matcher.end();
            }
            if (timestamps.isEmpty()) {
                continue;
            }
            String text = raw.substring(lastMatchEnd).trim();
            if (text.isEmpty()) {
                continue;
            }
            for (long timeMs : timestamps) {
                lines.add(new LyricLine(timeMs, text));
            }
        }
        lines.sort(Comparator.comparingLong(LyricLine::timeMs));
        return List.copyOf(lines);
    }

    private HttpUrl buildUrl(String path, Map<String, String> params) {
        var builder = HttpUrl.parse(baseUrl + path).newBuilder();
        for (var entry : params.entrySet()) {
            builder.setQueryParameter(entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    private <T> @Nullable T fetchJsonOrNull(HttpUrl url, Class<T> responseClass) {
        var request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            var body = response.body() != null ? response.body().string() : "";
            if (body.isBlank()) {
                return null;
            }
            return Utils.fromJson(body, responseClass);
        } catch (IOException e) {
            log.warn("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

}
