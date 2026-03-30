package org.subsound.ui.components;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Adwaita available style classes stylesheet:
 * https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/style-classes.html
 *
 * colors: https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/style-classes.html#colors
 *
 * Available named colors:
 * https://gnome.pages.gitlab.gnome.org/libadwaita/doc/1.5/named-colors.html#palette-colors
 */
public enum Classes {
    none(""),
    card("card"),
    boxedList("boxed-list"),
    richlist("rich-list"),
    colorSuccess("success"),
    colorAccent("accent"),
    colorWarning("warning"),
    colorError("error"),
    suggestedAction("suggested-action"),
    destructiveAction("destructive-action"),
    titleLarge("larger-title"),
    titleLarge2("larger-title-2"),
    titleLarge3("larger-title-3"),
    title1("title1"),
    title2("title2"),
    title3("title3"),
    heading("heading"),
    captionHeading("caption-heading"),
    caption("caption"),
    monospace("monospace"),
    bodyText("body"), // The .body style class is the default text style.
    labelDim("dim-label"),
    circular("circular"),
    labelNumeric("numeric"), // display label with numbers as monospace-ish
    flat("flat"),
    clickLabel("click-label"),
    // The .background style class can be used with any widget to give it the default window background and foreground colors.
    background("background"),
    // The .transparent style class can be used to give the widget a transparent background color.
    transparent("transparent"),
    blurred("blurred"),
    darken("darken"),
    rounded("rounded"),
    shadow("shadow"),
    // starred gives a yellow font color for icons
    starred("starred"),
    activatable("activatable"),
    queueAutomatic("queue-automatic"),
    commandPalette("command-palette"),
    commandPaletteBackdrop("command-palette-backdrop");

    private final String className;
    Classes(String className) {
        this.className = className;
    }

    public String className() {
        return className;
    }

    public String[] add(Classes ...cs) {
        return toClassnames(this, cs);
    }

    public static String[] toClassnames(Classes ...classes) {
        return Arrays.stream(classes).map(Classes::className).toArray(String[]::new);
    }

    public static String[] toClassnames(String ...classes) {
        return classes.clone();
    }

    public static String[] toClassnames(Classes clazz) {
        return new String[]{clazz.className()};
    }
    public static String[] toClassnames(Classes clazz, Classes ...classes) {
        return Stream.concat(Stream.ofNullable(clazz), Arrays.stream(classes))
                .filter(Objects::nonNull)
                .map(Classes::className)
                .toArray(String[]::new);
    }
}
