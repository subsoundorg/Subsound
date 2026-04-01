package org.subsound.ui.components;

import org.gnome.adw.HeaderBar;
import org.gnome.adw.ToolbarView;
import org.gnome.gdk.Texture;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Image;
import org.gnome.gtk.Justification;
import org.gnome.gtk.Label;
import org.gnome.gtk.Orientation;
import org.gnome.gtk.Overlay;
import org.gnome.gtk.Widget;
import org.subsound.app.state.AppManager;
import org.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import org.subsound.utils.Utils;

public class OnboardingOverlay extends Overlay {
    private final AppManager appManager;
    private final ServerConfigForm serverConfigForm;
    private final Widget child;
    private final Box centerBox;
    private final Box settingsBox;
    private final HeaderBar headerBar;
    private final ToolbarView toolbarView;
    private final Box contentBox;
    private final Image logo;

    public OnboardingOverlay(AppManager appManager, Widget child) {
        super();
        this.appManager = appManager;
        this.serverConfigForm = buildServerConfigForm(appManager);
        this.child = child;
        this.centerBox = Utils.borderBox(Orientation.VERTICAL, 10).build();
        this.centerBox.addCssClass(Classes.transparent.className());
        this.centerBox.addCssClass(Classes.rounded.className());
        this.settingsBox = Utils.borderBox(Orientation.VERTICAL, 14).setHalign(Align.CENTER).setValign(Align.CENTER).setHexpand(false).setVexpand(true).build();
        this.settingsBox.addCssClass(Classes.shadow.className());
        this.settingsBox.addCssClass(Classes.background.className());
        this.settingsBox.addCssClass(Classes.rounded.className());
        this.centerBox.append(this.settingsBox);
        this.logo = image(ImageIcons.SubsoundLarge);
        this.logo.setMarginBottom(20);
        this.headerBar = new HeaderBar();
        this.headerBar.setTitleWidget(Label.builder().setLabel("").setCssClasses(Classes.title1.add()).build());
        this.contentBox = Box.builder().setOrientation(Orientation.VERTICAL).setSpacing(0).build();
        this.contentBox.append(Label.builder().setLabel("Welcome").setJustify(Justification.CENTER).setCssClasses(Classes.titleLarge.add()).setMarginBottom(20).build());
        this.contentBox.append(this.logo);
        var bodyTextLabel = new Label();
        bodyTextLabel.setLabel("Login to your server to get started");
        bodyTextLabel.setJustify(Justification.CENTER);
        bodyTextLabel.addCssClass(Classes.bodyText.className());
        bodyTextLabel.setMarginBottom(10);
        this.contentBox.append(bodyTextLabel);
        this.contentBox.append(this.serverConfigForm);
        this.toolbarView = new ToolbarView();
        this.toolbarView.addTopBar(this.headerBar);
        this.toolbarView.setContent(this.contentBox);

        this.settingsBox.append(this.toolbarView);
        this.addOverlay(this.centerBox);
        this.child.addCssClass(Classes.blurred.className());
        this.setChild(child);
        this.setChildVisible(true);
    }

    private ServerConfigForm buildServerConfigForm(AppManager appManager) {
        var cfg = appManager.getConfig();
        var info = cfg.serverConfig == null ? null : new ServerConfigForm.SettingsInfo(
                cfg.serverConfig.type(),
                cfg.serverConfig.url(),
                cfg.serverConfig.username(),
                cfg.serverConfig.password(),
                cfg.serverConfig.tlsSkipVerify()
        );
        return new ServerConfigForm(
                info,
                cfg.dataDir,
                action -> appManager
                        .handleAction(action)
                        .thenApply(aVoid -> {
                            if (this.appManager.getConfig().onboarding == OnboardingState.DONE) {
                                this.setOnboardingFinished();
                            }
                            return null;
                        })
        );
    }

    private void setOnboardingFinished() {
        Utils.runOnMainThread(() -> {
            this.removeOverlay(this.centerBox);
            this.child.removeCssClass(Classes.blurred.className());
            this.setChildVisible(true);
        });
    }

    private Image image(ImageIcons imageIcons) {
        Texture texture = imageIcons.getTexture();
        var image = Image.fromPaintable(texture);
        image.setPixelSize(64);
        return image;
    }
}
