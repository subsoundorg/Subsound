package org.subsound.persistence;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.gnome.gdk.Texture;
import org.gnome.gdkpixbuf.Pixbuf;
import org.javagi.base.Out;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.integration.ServerClient.CoverArt;
import org.subsound.integration.ServerClient.CoverArtResponse;
import org.subsound.utils.ImageUtils;
import org.subsound.utils.ImageUtils.ColorValue;
import org.subsound.utils.ThumbHashUtils;
import org.subsound.utils.Utils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import static org.subsound.persistence.SongCache.joinPath;
import static org.subsound.utils.Utils.sha256;

/**
 * ThumbnailCache does 3 things:
 *  1. make sure to get requested images from server onto local disk
 *  2. make sure to cache remote photos on disk and organize this. we store originals on disk.
 *  3. keep often requested images as GDK Pixbufs in a in-memory-cache
 */
public class ThumbnailCache {
    private static final Logger log = LoggerFactory.getLogger(ThumbnailCache.class);

    private final Path root;
    // semaphore limits concurrency a little, we could send 1000s request concurrently on page load of a e.g. starred page:
    private final Semaphore semaphore = new Semaphore(4);
    private volatile BiFunction<CoverArt, Integer, CoverArtResponse> downloader;
    private final Cache<PixbufCacheKey, CachedTexture> pixbufCache = Caffeine.newBuilder().maximumSize(1000).recordStats().build();
    private final int maxArtworkSize = 1024;

    record PixbufCacheKey(
            CoverArt coverArt,
            String id,
            int size
    ) {
    }

    public ThumbnailCache(Path root) {
        this.root = root;
    }

    public void setDownloader(BiFunction<CoverArt, Integer, CoverArtResponse> downloader) {
        this.downloader = downloader;
    }

    public record CachedTexture(
            Texture texture,
            List<ColorValue> palette,
            Texture backdropTexture
    ) {}

    public CompletableFuture<CachedTexture> loadPixbuf(CoverArt coverArt, int size) {
        return Utils.doAsync(() -> {
            try {
                var key = new PixbufCacheKey(coverArt, coverArt.coverArtId(), size);

                var pixbuf = pixbufCache.get(key, k -> {
                    log.debug("ThumbCache: cache miss: {} size={}", k.coverArt.coverArtId(), k.size);
                    ThumbLoaded loaded = loadThumbAsync(k.coverArt).join();
                    String path = loaded.path().cachePath().toAbsolutePath().toString();
                    try {
                        // load at twice the requested size, as the texture for some reason looks very bad in some situations
                        // at the requested size.
                        var loadSize = 2 * k.size;
                        var p = Pixbuf.fromFileAtSize(path, loadSize, loadSize);
                        var scaledOut = new Out<byte[]>();
                        var outputFormat = p.getHasAlpha() ? "png" : "jpeg";
                        boolean success = p.saveToBufferv(scaledOut, outputFormat, null, null);
                        if (!success) {
                            throw new RuntimeException("halp");
                        }
                        var imageResult = ImageUtils.processImage(scaledOut.get());
                        var texture = Texture.forPixbuf(p);
                        Texture backdropTexture = null;
                        try {
                            backdropTexture = ThumbHashUtils.thumbHashToTexture(imageResult.thumbHash());
                        } catch (Exception e) {
                            log.warn("Failed to generate ThumbHash backdrop", e);
                        }
                        return new CachedTexture(texture, imageResult.palette(), backdropTexture);
                    } catch (Throwable e) {
                        log.error("Failed to loadPixbuf: id={}", coverArt.coverArtId(), e);
                        throw new RuntimeException("unable to create pixbuf from path='%s'".formatted(path), e);
                    }
                });
                return pixbuf;
            } catch (Throwable t) {
                log.error("error loading pixbuf: val={} size={}", coverArt, size, t);
                throw new RuntimeException(t);
            }
        });
    }

    public record ThumbLoaded(CachePath path) {}

    public CompletableFuture<ThumbLoaded> loadThumbAsync(CoverArt coverArt) {
        var cachePath = toCachePath(this.root, coverArt.serverId(), coverArt.coverArtId());
        var cacheAbsPath = cachePath.cachePath().toAbsolutePath();
        return Utils.doAsync(() -> {
            // Fast path: already on disk
            if (cacheAbsPath.toFile().exists() && cacheAbsPath.toFile().length() > 0) {
                return new ThumbLoaded(cachePath);
            }
            try {
                semaphore.acquire(1);
                try {
                    // Double-check after acquiring semaphore
                    if (cacheAbsPath.toFile().exists() && cacheAbsPath.toFile().length() > 0) {
                        return new ThumbLoaded(cachePath);
                    }

                    var dl = this.downloader;
                    if (dl == null) {
                        throw new IllegalStateException("ThumbnailCache downloader not set, cannot fetch: " + coverArt.coverArtId());
                    }
                    var response = dl.apply(coverArt, this.maxArtworkSize);
                    byte[] body = response.data();
                    Files.createDirectories(cacheAbsPath.getParent());

                    var requestId = UUID.randomUUID().toString();
                    var tmpFilePath = cacheAbsPath.resolveSibling(
                            cacheAbsPath.getFileName() + "." + requestId + ".tmp"
                    );
                    tmpFilePath.toFile().deleteOnExit();
                    try {
                        try (var out = Files.newOutputStream(tmpFilePath)) {
                            out.write(body);
                        }
                        Files.move(tmpFilePath, cacheAbsPath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException ex) {
                        Files.deleteIfExists(tmpFilePath);
                        throw ex;
                    }

                    return new ThumbLoaded(cachePath);
                } finally {
                    semaphore.release(1);
                }
            } catch (IOException e) {
                throw new RuntimeException("error loading: " + coverArt.coverArtLink(), e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public record CacheStats(
            long inmemoryCount,
            long inmemoryHits,
            long inMemoryMisses,
            long totalCount,
            long totalBytes
    ) {}

    public CompletableFuture<CacheStats> getStats(String serverId) {
        return Utils.doAsync(() -> {
            var thumbsDir = root.resolve(serverId).resolve("thumbs");
            var byteSize = new AtomicLong();
            var count = new AtomicLong();
            try {
                Files.walk(thumbsDir)
                        .map(Path::toFile)
                        .filter(File::isFile)
                        .forEach(fd -> {
                            byteSize.addAndGet(fd.length());
                            count.incrementAndGet();
                        });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            var s = pixbufCache.stats();
            return new CacheStats(
                    pixbufCache.estimatedSize(),
                    s.hitCount(),
                    s.missCount(),
                    count.get(),
                    byteSize.get()
            );
        });
    }

    public void clearThumbnails(String serverId) {
        var thumbsDir = root.resolve(serverId).resolve("thumbs");
        deleteTree(thumbsDir);
        pixbufCache.invalidateAll();
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

    record CacheKey(String part1, String part2, String part3) {
    }

    static CacheKey toCacheKey(String coverArtId) {
        // I mean, ideally we would use the checksum of the data, but we dont have this until after we download,
        // and this is even more weird for resized coverArt.
        // So instead we take a hash of the coverArtId as this will probably also give a uniform distribution into our buckets:
        String shasum = sha256(coverArtId);
        var cacheKey = new CacheKey(
                shasum.substring(0, 2),
                shasum.substring(2, 4),
                shasum.substring(4, 6)
        );
        return cacheKey;
    }

    public record CachePath(
            Path cachePath
    ) {
    }

    public Optional<Path> getCachedPath(String serverId, String coverArtId) {
        var path = toCachePath(root, serverId, coverArtId).cachePath();
        return Files.exists(path) ? Optional.of(path) : Optional.empty();
    }

    public static CachePath toCachePath(Path root, String serverId, String coverArtId) {
        var key = toCacheKey(coverArtId);
        var fileName = "%s".formatted(coverArtId);
        var cachePath = joinPath(root, serverId, "thumbs", key.part1, key.part2, key.part3, fileName);
        return new CachePath(cachePath);
    }

    // Vp9/webp is not supported by gdk Pixbuf. We must convert it to png/jpg first:
    public static byte[] convertWebpToJpeg(byte[] blob) {
        try {
            var out = new ByteArrayOutputStream();
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(blob));
            if (ImageIO.write(read, "jpg", out)) {
                return out.toByteArray();
            } else {
                throw new IllegalStateException("no jpg writer?");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
