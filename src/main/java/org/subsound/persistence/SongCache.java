package org.subsound.persistence;

import org.subsound.integration.ServerClient.StreamResponse;
import org.subsound.integration.ServerClient.TranscodeInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Function;

import static org.subsound.utils.Utils.sha256;

public class SongCache implements SongCacheChecker {
    private final Logger log = LoggerFactory.getLogger(SongCache.class);

    private final Path root;
    private final Function<TranscodeInfo, StreamResponse> streamOpener;

    public SongCache(
            Path cacheDir,
            Function<TranscodeInfo, StreamResponse> streamOpener
    ) {
        this.streamOpener = streamOpener;
        var f = cacheDir.toFile();
        if (!f.exists()) {
            if (!f.mkdirs()) {
                throw new RuntimeException("unable to create cache dir=" + cacheDir);
            }
        }
        if (!f.isDirectory()) {
            throw new RuntimeException("error: given cache dir=" + cacheDir + " is not a directory");
        }
        this.root = cacheDir;
    }

    public record SongCacheQuery(String serverId, String songId, String streamFormat) {
    }

    public record CacheSong(
            String serverId,
            String songId,
            TranscodeInfo transcodeInfo,
            // originalFileSuffix is the original file format originalFileSuffix
            String originalFileSuffix,
            // original size
            long originalSize,
            DownloadProgressHandler progressHandler
    ) {
    }

    public enum CacheResult {
        HIT, MISS,
    }

    public record LoadSongResult(
            CacheResult result,
            // uri to the cached local file
            // file:///absolute/path/file.mp3
            URI uri
    ) {
    }

    public LoadSongResult getSong(CacheSong songData) {
        // Check cache
        var cachePath = this.cachePath(songData);
        var cacheFile = cachePath.cachePath.toAbsolutePath().toFile();
        if (cacheFile.isDirectory()) {
            cacheFile.delete();
        }
        if (cacheFile.length() == 0) {
            cacheFile.delete();
        }
        if (cacheFile.exists()) {
            return new LoadSongResult(CacheResult.HIT, cacheFile.toURI());
        }

        cachePath.tmpFilePath.getParent().toFile().mkdirs();
        var cacheTmpFile = cachePath.tmpFilePath.toAbsolutePath().toFile();
        if (cacheTmpFile.exists()) {
            cacheTmpFile.delete();
        }
        try {
            cacheTmpFile.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try (var streamResponse = streamOpener.apply(songData.transcodeInfo)) {
            long estimatedContentSize = songData.transcodeInfo.estimateContentSize();
            long downloadSize = downloadTo(
                    streamResponse,
                    new FileOutputStream(cacheTmpFile),
                    songData.originalSize,
                    estimatedContentSize,
                    songData.progressHandler
            );
            // rename tmp file to target file.
            cacheTmpFile.renameTo(cacheFile);
            return new LoadSongResult(CacheResult.MISS, cacheFile.toURI());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public interface DownloadProgressHandler {
        void progress(long total, long count);
    }

    private long downloadTo(
            StreamResponse streamResponse,
            OutputStream output,
            long originalSize,
            long estimatedContentSize,
            DownloadProgressHandler ph
    ) {
        long expectedSize = streamResponse.contentLength() > 0
                ? streamResponse.contentLength()
                : estimatedContentSize;

        log.info("estimateContentLength: originalSize={} expectedSize={}", originalSize, expectedSize);

        try (var stream = streamResponse.inputStream()) {
            byte[] buffer = new byte[8192];
            long sum = 0L;
            int n;
            while (-1 != (n = stream.read(buffer))) {
                output.write(buffer, 0, n);
                sum += n;
                if (sum > expectedSize) {
                    expectedSize = sum;
                }
                ph.progress(expectedSize, sum);
            }

            // When transcoding, Content-Length is only an estimate.
            // Make sure we finish the progressbar by flushing with the final size before exiting:
            var finalSize = Math.max(expectedSize, sum);
            log.info("sending final flush: originalSize={} expectedSize={} estimatedSizeBytes={} finalSize={}", originalSize, expectedSize, estimatedContentSize, finalSize);
            ph.progress(finalSize, finalSize);
            return sum;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    record CacheKey(String part1, String part2, String part3) {
    }

    static CacheKey toCacheKey(String songId) {
        // I mean, ideally we would use the checksum of the data, but we dont have this until after we download,
        // and this is even more weird for live transcoded streams.
        // So instead we take a hash of the songId as this will probably also give a uniform distribution into our buckets:
        String shasum = sha256(songId);
        return new CacheKey(
                shasum.substring(0, 2),
                shasum.substring(2, 4),
                shasum.substring(4, 6)
        );
    }

    record CachehPath(
            Path cachePath,
            Path tmpFilePath
    ) {
    }

    private CachehPath cachePath(CacheSong songData) {
        return cachePath(new SongCacheQuery(songData.serverId, songData.songId, songData.transcodeInfo.streamFormat()));
    }

    private CachehPath cachePath(SongCacheQuery query) {
        var songId = query.songId();
        var key = toCacheKey(songId);
        var fileName = "%s.%s".formatted(songId, query.streamFormat());
        var cachePath = joinPath(root, query.serverId(), "songs", key.part1, key.part2, key.part3, fileName);
        var cachePathTmp = joinPath(cachePath.getParent(), fileName + ".tmp");
        return new CachehPath(cachePath, cachePathTmp);
    }

    @Override
    public boolean isCached(SongCacheQuery query) {
        var path = cachePath(query).cachePath();
        var file = path.toAbsolutePath().toFile();
        return file.exists() && file.length() > 1;
    }

    public boolean deleteCached(SongCacheQuery query) {
        var path = cachePath(query).cachePath();
        var file = path.toAbsolutePath().toFile();
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }

    public void clearSongs(String serverId) {
        var songsDir = root.resolve(serverId).resolve("songs");
        deleteTree(songsDir);
    }

    private void deleteTree(Path dir) {
        if (!dir.toFile().exists()) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } catch (IOException e) {
            log.warn("Failed to delete directory: {}", dir, e);
        }
    }

    public static Path joinPath(Path base, String... elements) {
        return Arrays.stream(elements).map(Path::of).reduce(base, Path::resolve);
    }
}
