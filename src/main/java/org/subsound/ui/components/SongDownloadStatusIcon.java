package org.subsound.ui.components;

import org.subsound.ui.models.GDownloadState;
import org.gnome.gtk.Image;

public class SongDownloadStatusIcon extends Image {
    private static final String ICON_DOWNLOADED = Icons.CheckmarkCircle.getIconName();
    private static final String ICON_PENDING = Icons.PAUSE.getIconName();
    private static final String ICON_DOWNLOADING = Icons.ContentLoadingSymbolic.getIconName();

    private volatile GDownloadState currentState;

    public SongDownloadStatusIcon() {
        super();
        this.setFromIconName(ICON_DOWNLOADED);
        this.setPixelSize(16);
        this.setOpacity(0);
    }

    public void updateDownloadState(GDownloadState state) {
        if (state == this.currentState) {
            return;
        }
        this.currentState = state;
        this.removeCssClass(Classes.colorSuccess.className());
        switch (state) {
            case DOWNLOADED -> {
                this.setFromIconName(ICON_DOWNLOADED);
                this.setTooltipText("Available offline");
                this.addCssClass(Classes.colorSuccess.className());
                this.setOpacity(1);
            }
            case CACHED -> {
                this.setFromIconName(ICON_DOWNLOADED);
                this.setTooltipText("Cached - available offline");
                this.setOpacity(1);
            }
            case PENDING -> {
                this.setFromIconName(ICON_PENDING);
                this.setTooltipText("Download pending");
                this.setOpacity(1);
            }
            case DOWNLOADING -> {
                this.setFromIconName(ICON_DOWNLOADING);
                this.setTooltipText("Downloading...");
                this.setOpacity(1);
            }
            case NONE -> {
                this.setTooltipText("");
                this.setOpacity(0);
            }
        }
    }
}
