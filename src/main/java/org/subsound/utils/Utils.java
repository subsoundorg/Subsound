package org.subsound.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import org.apache.commons.codec.Resources;
import org.apache.commons.io.IOUtils;
import org.gnome.gio.File;
import org.gnome.glib.GLib;
import org.gnome.glib.SourceOnceFunc;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.EventControllerMotion;
import org.gnome.gtk.FileDialog;
import org.gnome.gtk.GestureClick;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.PropagationLimit;
import org.gnome.gtk.PropagationPhase;
import org.gnome.gtk.Widget;
import org.gnome.gtk.Window;
import org.javagi.gobject.SignalConnection;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.subsound.utils.javahttp.InstantAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;


public class Utils {
    public static final ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final HexFormat HEX = HexFormat.of().withLowerCase();
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setStrictness(Strictness.STRICT)
            .registerTypeAdapter(Instant.class, new InstantAdapter())
            .create();

    // Test mode flag: when true, runOnMainThread executes synchronously instead of using GLib main loop
    private static volatile boolean testMode = false;

    public static void setTestMode(boolean enabled) {
        testMode = enabled;
    }

    public static <T> CompletableFuture<T> doAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, ASYNC_EXECUTOR);
    }

    public static CompletableFuture<Void> doAsync(Runnable supplier) {
        return CompletableFuture.runAsync(supplier, ASYNC_EXECUTOR);
    }

    public static void runOnMainThread(SourceOnceFunc fn) {
        // TODO: find a better way to deal with GTK main thread in tests
        if (testMode) {
            // In test mode, execute synchronously (no GTK main loop available)
            fn.run();
            return;
        }
        // Have to add a return GLib.SOURCE_REMOVE at the end of the callback to make sure it only runs once.
        // GLib.idleAdd calls g_idle_add_full which has proper memory management with a DestroyNotify callback.
        // Java-GI uses that to free the upcall allocation.
        GLib.idleAdd(GLib.PRIORITY_DEFAULT_IDLE, () -> {
            fn.run();
            return GLib.SOURCE_REMOVE;
        });
    }

    public static CompletableFuture<Void> runOnMainThreadFuture(SourceOnceFunc fn) {
        var future = new CompletableFuture<Void>();
        runOnMainThread(() -> {
            try {
                fn.run();
                future.complete(null);
            } catch (Throwable e) {
                future.completeExceptionally(e);
                throw e;
            }
        });
        return future;
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] data) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            md.update(data);
            byte[] digest = md.digest();
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static String sha256(InputStream is) throws IOException {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while (-1 != (n = is.read(buffer))) {
                md.update(buffer, 0, n);
            }
            byte[] digest = md.digest();
            return HEX.formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static long copyLarge(InputStream input, OutputStream output) throws IOException {
        return copyLarge(input, output, new byte[8192]);
    }

    private static long copyLarge(InputStream input, OutputStream output, byte[] buffer) throws IOException {
        long count = 0L;
        int n;
        if (input != null) {
            while(-1 != (n = input.read(buffer))) {
                output.write(buffer, 0, n);
                count += (long)n;
            }
        }

        return count;
    }

    public static String formatDurationLong(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();
        return  (days == 0 ? "" : days + " days, ") +
                (hours == 0 ? "" : hours + " hours, ") +
                (minutes == 0 ? "" : minutes + " minutes, ") +
                (seconds == 0 ? "" : seconds + " seconds");
    }

    public static String formatDurationMedium(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();
        var string = (days == 0 ? "" : days + " days, ") +
                (hours == 0 ? "" : hours + " hr, ") +
                (minutes == 0 ? "" : minutes + " min, ") +
                (seconds > 0 && days > 0 ? "" : seconds == 0 ? "" : seconds + " sec");
        string = string.trim();
        while (string.endsWith(",")) {
            string = string.substring(0, string.length() - 1);
            string = string.trim();
        }
        return string;
    }

    public static String formatDurationShort(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();

        return  (days == 0 ? "" : days + ":") +
                (hours == 0 ? "" : "%02d:".formatted(hours)) +
                ("%02d:".formatted(minutes)) +
                ("%02d".formatted(seconds));
    }

    public static String formatDurationShortest(Duration d) {
        long days = d.toDays();
        d = d.minusDays(days);
        long hours = d.toHours();
        d = d.minusHours(hours);
        long minutes = d.toMinutes();
        d = d.minusMinutes(minutes);
        long seconds = d.getSeconds();

        return  (days == 0 ? "" : days + ":") +
                (hours == 0 ? "" : "%d:".formatted(hours)) +
                ((hours > 0 || days > 0) ? "%02d:".formatted(minutes) : "%d:".formatted(minutes)) +
                ("%02d".formatted(seconds));
    }

    // formatBytes is 1024-base
    public static String formatBytes(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absB < 1024) {
            return bytes + " B";
        }
        long value = absB;
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format("%.1f %ciB", value / 1024.0, ci.current());
    }

    private static final String[] SI_UNITS = { "B", "kB", "MB", "GB", "TB", "PB", "EB" };
    private static final String[] BINARY_UNITS = { "B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB" };
    public static String humanReadableByteCount(final long bytes, final boolean useSIUnits, final Locale locale)
    {
        final String[] units = useSIUnits ? SI_UNITS : BINARY_UNITS;
        final int base = useSIUnits ? 1000 : 1024;

        // When using the smallest unit no decimal point is needed, because it's the exact number.
        if (bytes < base) {
            return bytes + " " + units[0];
        }

        final int exponent = (int) (Math.log(bytes) / Math.log(base));
        final String unit = units[exponent];
        return String.format(locale, "%.1f %s", bytes / Math.pow(base, exponent), unit);
    }

    // formatBytes SI is 1000-base
    public static String formatBytesSI(long bytes) {
        if (-1000 < bytes && bytes < 1000) {
            return bytes + " B";
        }
        CharacterIterator ci = new StringCharacterIterator("kMGTPE");
        while (bytes <= -999_950 || bytes >= 999_950) {
            bytes /= 1000;
            ci.next();
        }
        return String.format("%.1f %cB", bytes / 1000.0, ci.current());
    }

    public static String[] cssClasses(String... clazz) {
        return clazz;
    }

    public static String plural(int i, String singular, String plural) {
        if (i == 1) {
            return singular;
        }
        return plural;
    }

    public static String getEnv(String envName, String defaultValue) {
        var val = System.getenv(envName);
        if (val == null || val.isBlank()) {
            return defaultValue;
        }
        return val;
    }

    public static String removeTrailingSlash(String path) {
        if (path == null || path.isBlank() || !path.endsWith("/")) {
            return path;
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    public record SignalWidget<T extends Widget>(
            T widget,
            SignalConnection<?> signalConnection
    ) {}

    public static <T extends Widget> SignalWidget<T> addClick(T row, Runnable onClick) {
        var gestureClick = new GestureClick();
        row.addController(gestureClick);
        var signal = gestureClick.onReleased((int nPress, double x, double y) -> {
            //System.out.println("addClick.gestureClick.onReleased: " + nPress);
            onClick.run();
        });

        return new SignalWidget<>(row, signal);
    }

    public static <T extends Widget> T addHover(T row, Runnable onEnter, Runnable onLeave) {
        var ec = EventControllerMotion.builder().setPropagationPhase(PropagationPhase.CAPTURE).setPropagationLimit(PropagationLimit.NONE).build();
        ec.onEnter((x, y) -> {
            onEnter.run();
        });
        ec.onLeave(() -> {
            onLeave.run();
        });
        row.addController(ec);
        return row;
    }

    public record HoverController(
            EventControllerMotion eventController,
            SignalConnection<?> enterSignal,
            SignalConnection<?> leaveSignal
    ){
        public void disconnect() {
            enterSignal.disconnect();
            leaveSignal.disconnect();
        }
    }

    public static HoverController addHover2(Runnable onEnter, Runnable onLeave) {
        var ec = new EventControllerMotion();
        ec.setPropagationPhase(PropagationPhase.CAPTURE);
        ec.setPropagationLimit(PropagationLimit.NONE);
        var enterCallbackSignalConnection = ec.onEnter((x, y) -> {
            onEnter.run();
        });
        var leaveSignal = ec.onLeave(() -> {
            onLeave.run();
        });
        return new HoverController(ec, enterCallbackSignalConnection, leaveSignal);
    }

    public static boolean withinEpsilon(double value1, double value2, double epsilon) {
        var diff = Math.abs(value1 - value2);
        return diff < epsilon;
    }

    public static Box.Builder<? extends Box.Builder> borderBox(Orientation orientation, int margins) {
        return Box.builder()
                .setOrientation(orientation)
                .setHexpand(true)
                .setVexpand(true)
                .setMarginStart(margins)
                .setMarginTop(margins)
                .setMarginEnd(margins)
                .setMarginBottom(margins);
    }

    public static Label.Builder<? extends Label.Builder> heading1(String labelText) {
        return Label.builder().setLabel(labelText).setHalign(Align.START).setCssClasses(cssClasses("title-3"));
    }

    public static <T> T fromJson(String s, Class<T> clazz) {
        return GSON.fromJson(s, clazz);
    }
    public static <T> String toJson(T obj) {
        return GSON.toJson(obj);
    }

    public static @Nullable String firstNotNull(String ...ss) {
        for (String s : ss) {
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    public static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    public static @NonNull String firstNotBlank(String ...ss) {
        for (String s : ss) {
            if (s != null && !s.isBlank()) {
                return s;
            }
        }
        return "";
    }

    public static String mustRead(Path cssFile) {
        try {
            var relPath = cssFile.toString();
            try {
                var localFilePath = "src/main/resources/" + relPath;
                return Files.readString(Path.of(localFilePath), StandardCharsets.UTF_8);
            } catch (NoSuchFileException e) {
                // assume we run in a jar:
                InputStream inputStream = Resources.getInputStream(relPath);
                return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] mustReadBytes(String resourcesFilePath) {
        try {
            var relPath = resourcesFilePath;
            try {
                var localFilePath = "src/main/resources/" + relPath;
                return Files.readAllBytes(Path.of(localFilePath));
            } catch (NoSuchFileException e) {
                // assume we run in a jar:
                URL resource = Utils.class.getResource(relPath);
                if (resource != null) {
                    Path path = Path.of(resource.toURI());
                    return mustReadBytes(path);
                }
                InputStream inputStream = Resources.getInputStream(relPath);
                return IOUtils.toByteArray(inputStream);
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] mustReadBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static long estimateContentLength(double durationSeconds, double bitRate) {
        var estimatedBytes = durationSeconds * bitRate / 8.0 * 1024.0;
        return (long) estimatedBytes;
    }

    public record FileDialogResult(String path) {}

    public static CompletableFuture<FileDialogResult> selectFolder(Window parentWindow) {
        var result = new CompletableFuture<FileDialogResult>();

        FileDialog fileDialog = new FileDialog();
        fileDialog.selectFolder(parentWindow, null, (dialog, asyncResult, _) -> {
            try {
                File file = fileDialog.selectFolderFinish(asyncResult);
                var path = file.getPath();
                result.complete(new FileDialogResult(path));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {
        T get() throws E; // Declare the checked exception E
    }
    public static <T, E extends Exception> T timeIt(Consumer<Duration> durationConsumer, CheckedSupplier<T, E> operation) {
        var start = System.nanoTime();
        try {
            return operation.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            var end = System.nanoTime();
            var duration = end - start;
            durationConsumer.accept(Duration.ofNanos(duration));
        }
    }

    public static void timeIt(Consumer<Duration> durationConsumer, Runnable operation) {
        var start = System.nanoTime();
        try {
            operation.run();
        } finally {
            var end = System.nanoTime();
            var duration = end - start;
            durationConsumer.accept(Duration.ofNanos(duration));
        }
    }

//    public static File getResourceDirectory(String resource) {
//        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
//        URL res = classLoader.getResource(resource);
//        File fileDirectory;
//        if ("jar".equals(res.getProtocol())) {
//            InputStream input = classLoader.getResourceAsStream(resource);
//            List<String> fileNames = IOUtils.readLines(input, StandardCharsets.UTF_8);
//            fileNames.forEach(name -> {
//                String fileResourceName = resource + File.separator + name;
//                File tempFile = new File(fileDirectory.getPath() + File.pathSeparator + name);
//                InputStream fileInput = classLoader.getResourceAsStream(resourceFileName);
//                FileUtils.copyInputStreamToFile(fileInput, tempFile);
//            });
//            fileDirectory.deleteOnExit();
//        } else {
//            fileDirectory = new File(res.getFile());
//        }
//
//        return fileDirectory;
//    }
}
