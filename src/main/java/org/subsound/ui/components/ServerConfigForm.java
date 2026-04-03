package org.subsound.ui.components;

import org.gnome.adw.ButtonRow;
import org.gnome.adw.Clamp;
import org.gnome.adw.EntryRow;
import org.gnome.adw.PasswordEntryRow;
import org.gnome.adw.SwitchRow;
import org.gnome.adw.Toast;
import org.gnome.gtk.Align;
import org.gnome.gtk.Box;
import org.gnome.gtk.Label;
import org.gnome.gtk.ListBox;
import org.gnome.gtk.SelectionMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.configuration.Config.ServerConfig;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ServerType;
import org.subsound.integration.ServerClient.TranscodeFormat;
import org.subsound.utils.Utils;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.gnome.gtk.Orientation.VERTICAL;
import static org.subsound.ui.components.Classes.boxedList;
import static org.subsound.ui.components.Classes.colorError;
import static org.subsound.ui.components.Classes.colorSuccess;
import static org.subsound.ui.components.Classes.colorWarning;
import static org.subsound.ui.components.Classes.suggestedAction;
import static org.subsound.ui.components.Classes.title1;
import static org.subsound.ui.components.Classes.titleLarge;
import static org.subsound.utils.Utils.borderBox;
import static org.subsound.utils.javahttp.TextUtils.capitalize;

public class ServerConfigForm extends Box {
    private static final Logger log = LoggerFactory.getLogger(ServerConfigForm.class);

    private final Path dataDir;
    private final Function<PlayerAction, CompletableFuture<Void>> onAction;
    private final Clamp clamp;
    private final Box centerBox;

    final ListBox listBox;
    private final Label serverTypeInfoLabel;
    private final EntryRow serverUrlEntry;
    private final SwitchRow tlsSwitchEntry;
    private final EntryRow usernameEntry;
    private final PasswordEntryRow passwordEntry;
    private final ButtonRow testButton;
    private final ButtonRow saveButton;

    public record SettingsInfo(
            ServerType type,
            String serverUrl,
            String username,
            String password,
            boolean tlsSkipVerify
    ) {
        @Override
        public @NonNull String toString() {
            return "SettingsInfo[type=%s, serverUrl=%s, username=%s, password=[REDACTED], tlsSkipVerify=%s]".formatted(
                    type,
                    serverUrl,
                    username,
                    tlsSkipVerify
            );
        }
    }

    public ServerConfigForm(
            @Nullable SettingsInfo settingsInfo,
            Path dataDir,
            Function<PlayerAction, CompletableFuture<Void>> onAction
    ) {
        super(VERTICAL, 0);
        this.dataDir = dataDir;
        this.onAction = onAction;
        this.setValign(Align.CENTER);
        this.setHalign(Align.CENTER);

        this.serverTypeInfoLabel = Label.builder().setLabel("New Server").setCssClasses(titleLarge.add()).build();
        this.serverUrlEntry = EntryRow.builder().setTitle("Server URL").setText("https://").build();
        this.tlsSwitchEntry = SwitchRow.builder().setTitle("Skip TLS verification").setSubtitle("Accept self-signed certificates").build();
        this.usernameEntry = EntryRow.builder().setTitle("Username").build();
        this.passwordEntry = PasswordEntryRow.builder().setTitle("Password").build();

        this.testButton = ButtonRow.builder().setTitle("Test").build();
        this.testButton.onActivated(() -> this.testConnection());
        this.saveButton = ButtonRow.builder().setTitle("Save").setSensitive(false).build();
        this.saveButton.onActivated(() -> this.saveForm());
        this.saveButton.addCssClass(suggestedAction.className());

        this.listBox = new ListBox();
        this.listBox.addCssClass(boxedList.className());
        this.listBox.setSelectionMode(SelectionMode.NONE);

        this.listBox.append(serverUrlEntry);
        this.listBox.append(tlsSwitchEntry);
        this.listBox.append(usernameEntry);
        this.listBox.append(passwordEntry);
        this.listBox.append(testButton);
        this.listBox.append(saveButton);

        this.centerBox = borderBox(VERTICAL, 8).setSpacing(8).build();
        this.centerBox.append(serverTypeInfoLabel);
        this.centerBox.append(this.listBox);
        this.clamp = Clamp.builder().setMaximumSize(600).setChild(this.centerBox).build();
        this.append(clamp);
        this.setSettingsInfo(settingsInfo);
    }

    private void testConnection() {
        this.saveButton.setSensitive(false);
        var dataOpt = getFormData();
        dataOpt.ifPresent(data -> {
            log.info("testConnection: url={} username={} passwordLength={} tlsSkipVerify={}",
                    data.serverUrl, data.username, data.password != null ? data.password.length() : -1, data.tlsSkipVerify);
            Utils.doAsync(() -> {
            ServerClient serverClient = ServerClient.create(new ServerConfig(
                    this.dataDir,
                    AppManager.SERVER_ID,
                    data.type,
                    data.serverUrl,
                    data.username,
                    data.password,
                    TranscodeFormat.source,
                    null,
                    data.tlsSkipVerify
            ));
            boolean success = serverClient.testConnection();
            if (success) {
                Toast build = Toast.builder()
                        .setTimeout(1)
                        .setCustomTitle(Label.builder().setLabel("Connection OK!").setCssClasses(title1.add(colorSuccess)).build())
                        .build();
                this.onAction.apply(new PlayerAction.Toast(build));
            } else {
                Toast build = Toast.builder()
                        .setTimeout(2)
                        .setCustomTitle(Label.builder().setLabel("Connection failed!").setCssClasses(title1.add(colorWarning)).build())
                        .build();
                this.onAction.apply(new PlayerAction.Toast(build));
            }
            Utils.runOnMainThread(() -> {
                this.saveButton.setSensitive(success);
                Classes color = success ? colorSuccess : colorError;
                this.testButton.addCssClass(color.className());
            });
        });
        });
    }

    public void setSettingsInfo(@Nullable SettingsInfo s) {
        if (s == null) {
            return;
        }
        Utils.runOnMainThread(() -> {
            this.serverTypeInfoLabel.setLabel("%s server".formatted(capitalize(s.type.name())));
            this.serverUrlEntry.setText(s.serverUrl);
            this.tlsSwitchEntry.setActive(s.tlsSkipVerify);
            this.usernameEntry.setText(s.username);
            this.passwordEntry.setText(s.password);
            this.testButton.setSensitive(true);
            this.saveButton.setSensitive(false);
        });
    }

    private void saveForm() {
        var dataOpt = getFormData();
        log.info("saveForm: data={}", dataOpt);
        if (dataOpt.isEmpty()) {
            Toast toast = Toast.builder()
                    .setTimeout(5)
                    .setCustomTitle(Label.builder().setLabel("Error saving configuration!").setCssClasses(title1.add(colorError)).build())
                    .build();
            this.onAction.apply(new PlayerAction.Toast(toast));
            return;
        }
        var data = dataOpt.get();
        onAction.apply(new PlayerAction.SaveConfig(data))
                .handle((success, throwable) -> {
                    if (throwable != null) {
                        log.error("saveForm: data={} error: ", data, throwable);
                        Toast toast = Toast.builder()
                                .setTimeout(5)
                                .setCustomTitle(Label.builder().setLabel("Error saving configuration!").setCssClasses(title1.add(colorError)).build())
                                .build();
                        return new PlayerAction.Toast(toast);
                    } else {
                        log.info("saveForm: data={} success!", data);
                        Toast toast = Toast.builder()
                                .setTimeout(4)
                                .setCustomTitle(Label.builder().setLabel("Configuration saved!").setCssClasses(title1.add(colorSuccess)).build())
                                .build();
                        return new PlayerAction.Toast(toast);
                    }
                })
                .thenAccept(this.onAction::apply);
    }

    private Optional<SettingsInfo> getFormData() {
        var url = this.serverUrlEntry.getText();
        if (!url.startsWith("http")) {
            url = "https://" + url;
        }
        try {
            var parsed = URI.create(url);
            this.serverUrlEntry.setText(url);

            return Optional.of(new SettingsInfo(
                    ServerType.SUBSONIC,
                    parsed.toString(),
                    this.usernameEntry.getText(),
                    this.passwordEntry.getText(),
                    this.tlsSwitchEntry.getActive()
            ));
        } catch (IllegalArgumentException e) {
            this.serverUrlEntry.setText(url);
            return Optional.empty();
        }
    }
}
