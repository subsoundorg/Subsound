package org.subsound.ui.components;

import org.gnome.gdk.Paintable;
import org.gnome.gdk.PaintableFlags;
import org.gnome.gdk.Texture;
import org.gnome.gobject.GObject;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.util.Set;

public class BackgroundPaintable extends GObject implements Paintable {
    private @Nullable Texture currentTexture = null;

    public void setTexture(@Nullable Texture texture) {
        this.currentTexture = texture;
        this.invalidateContents();
    }

    @Override
    public void snapshot(org.gnome.gdk.Snapshot gdkSnapshot, double w, double h) {
        if (currentTexture == null) {
            return;
        }
        try (var arena = Arena.ofConfined()) {
            float width = (float) w;
            float height = (float) h;
            var snapshot = (org.gnome.gtk.Snapshot) gdkSnapshot;
            snapshot.pushOpacity(0.75f);
            snapshot.pushBlur(80.0f);
            currentTexture.snapshot(snapshot, width, height);
            snapshot.pop();
            snapshot.pop();
        }
    }

    @Override
    public Set<PaintableFlags> getFlags() {
        return Set.of();
    }
}