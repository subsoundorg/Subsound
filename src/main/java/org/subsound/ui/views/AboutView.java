package org.subsound.ui.views;

import org.gnome.adw.AboutDialog;
import org.gnome.gtk.License;
import org.gnome.gtk.Widget;
import org.subsound.configuration.constants.Constants;

public class AboutView {
    public static void show(Widget parent) {
        //AboutDialog.fromAppdata()
        var dialog = AboutDialog.builder()
                .setApplicationName("Subsound")
                .setApplicationIcon(Constants.APP_ID)
                .setVersion(Constants.Application.VERSION)
                .setDevelopers(new String[] { "Eivind Larsen" })
                .setDeveloperName("Eivind Larsen")
                .setLicenseType(License.GPL_3_0)
                .setWebsite("https://github.com/subsoundorg/subsound-gtk")
                .setIssueUrl("https://github.com/subsoundorg/subsound-gtk/issues")
                .setComments("A GTK4/Adwaita streaming music player for Navidrome / Subsonic servers.")
                //.setDebugInfo("")
                //.setCopyright("© 2026 Subsound contributors")
                .build();
        dialog.present(parent);
    }
}
