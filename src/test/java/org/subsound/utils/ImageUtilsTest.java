package org.subsound.utils;

import org.gnome.gio.InputStream;
import org.gnome.gio.MemoryInputStream;
import org.javagi.base.GErrorException;
import org.javagi.base.Out;
import org.gnome.gdkpixbuf.Pixbuf;
import org.gnome.glib.GError;
import org.junit.Ignore;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.subsound.utils.ImageUtils.createRGBString;
import static org.assertj.core.api.Assertions.assertThat;

public class ImageUtilsTest {

    public static Pixbuf readPixbufImageBuffer(byte[] bytes, int maxSize) {
        // Load pixbuf from in-memory bytes
        try (var imageStream = MemoryInputStream.fromData(bytes)) {
            return Pixbuf.fromStreamAtScale(imageStream, maxSize, maxSize, true, null);
        } catch (IOException closeEx) {
            throw new RuntimeException("Unexpected error closing image stream", closeEx);
        } catch (GErrorException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testDominantColors() throws IOException {
        {
            byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/test1.jpg"));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(content));
            var palette = ImageUtils.getPalette(img);
            var css = palette.stream().map(p -> createRGBString(p.colors())).collect(Collectors.joining(";\n"));
            System.out.println(css);
        }

        {
            byte[] content = Files.readAllBytes(Path.of("src/test/resources/fixtures/test2.jpg"));
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(content));
            var palette = ImageUtils.getPalette(img);
            var css = palette.stream().map(p -> createRGBString(p.colors())).collect(Collectors.joining(";\n"));
            System.out.println(css);
        }
    }

    @Test
    @Ignore
    // This test crashes in flatpak environment:
    //
    // org.javagi.base.GErrorException: Loader process exited early with status '1'Command:
    // env -i XDG_RUNTIME_DIR="/run/user/1000" "flatpak-spawn" "--sandbox" "--watch-bus" "--directory=/" "--forward-fd=127" "prlimit" "--as=17012097024" "/usr/libexec/glycin-loaders/2+/glycin-image-rs" "--dbus-fd" "127"
    //	at org.gnome.gdkpixbuf.Pixbuf.constructFromFileAtSize(Pixbuf.java:517)
    //	at org.gnome.gdkpixbuf.Pixbuf.fromFileAtSize(Pixbuf.java:500)
    //	at org.subsound.utils.ImageUtilsTest.testPixbufToBufferedImage(ImageUtilsTest.java:46)
    //	at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
    //	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
    //	at org.junit.runners.model.FrameworkMethod$1.runReflectiveCall(FrameworkMethod.java:59)
    //	at org.junit.internal.runners.model.ReflectiveCallable.run(ReflectiveCallable.java:12)
    public void testPixbufToBufferedImage() throws GErrorException, IOException {
        var path = Path.of("src/test/resources/fixtures/test2.jpg");
        int size = 300;
        var p = Pixbuf.fromFileAtSize(path.toAbsolutePath().toString(), size, size);
        var scaledOut = new Out<byte[]>();
        var errs = new GError[]{null};
        boolean success = p.saveToBuffer(scaledOut, "jpeg", errs, null, null);
        //boolean success = p.saveToBufferv(scaledOut, "jpeg", null, null);
        if (!success) {
            throw new RuntimeException("halp");
        }

        var tmpPath = Path.of(path.getParent().toAbsolutePath().toString() + File.pathSeparator + path.getFileName() + ".thumb.jpg");
        //Files.write(tmpPath, scaledOut.get());
        var palette = ImageUtils.getPalette(scaledOut.get());
        assertThat(palette).hasSize(5);
    }
}