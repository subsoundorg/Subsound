package org.subsound.integration.platform.secret;

import org.gnome.glib.GLib;
import org.gnome.glib.HashTable;
import org.gnome.secret.Schema;
import org.gnome.secret.SchemaAttributeType;
import org.gnome.secret.SchemaFlags;
import org.gnome.secret.SearchFlags;
import org.gnome.secret.Secret;
import org.gnome.secret.Service;
import org.gnome.secret.ServiceFlags;
import org.javagi.interop.Interop;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

import static java.util.Optional.ofNullable;

public final class LibsecretService implements SecretService {
    private static final Logger log = LoggerFactory.getLogger(LibsecretService.class);
    private final Schema SCHEMA;

    LibsecretService() {
        // we want to make sure this fails on constructor, not on class load at least:
        SCHEMA = buildSchema();
    }

    private static Schema buildSchema() {
        var attributes = new HashTable<String, SchemaAttributeType>(
                GLib::strHash,
                GLib::strEqual,
                Interop::getStringFrom,
                SchemaAttributeType::of
        );
        attributes.insert("server-id", SchemaAttributeType.STRING);
        attributes.insert("username", SchemaAttributeType.STRING);
        return new Schema("io.github.Subsound.Credentials", SchemaFlags.NONE, attributes);
    }

    @Override
    public boolean storeCredentialsSync(String serverId, String username, String password) {
        try {
            var attributes = Secret.attributesBuild(SCHEMA, "server-id", serverId, "username", username, null);
            return Secret.passwordStoreSync(
                    SCHEMA, attributes, Secret.COLLECTION_DEFAULT,
                    "Subsound: " + username, password, null
            );
        } catch (Exception e) {
            log.warn("Failed to store credentials in libsecret for server={}: {}", serverId, e.getMessage());
            return false;
        }
    }

    @Override
    public @Nullable Credentials lookupCredentialsSync(String serverId, String username) {
        var c = lookupCredentialsSyncInner(serverId, username);
        int ss = ofNullable(c).map(Credentials::password).map(String::length).orElse(0);
        log.info("lookupCredentialsSync serverId={}, username={}, credentials.size={}", serverId, username, ss);
        return c;
    }

    public @Nullable Credentials lookupCredentialsSyncInner(String serverId, String username) {
        try {
            var attributes = Secret.attributesBuild(SCHEMA, "server-id", serverId, "username", username, null);
            var service = Service.getSync(ServiceFlags.OPEN_SESSION, null);
            var items = service.searchSync(
                    SCHEMA, attributes,
                    EnumSet.of(SearchFlags.LOAD_SECRETS),
                    null
            );
            if (items == null || items.isEmpty()) {
                return null;
            }
            var item = items.getFirst();
            var itemAttrs = item.getAttributes();
            var itemUsername = itemAttrs.lookup("username");
            var secret = item.getSecret();
            if (itemUsername == null || secret == null) {
                return null;
            }
            var password = secret.getText();
            if (password == null) {
                return null;
            }
            return new Credentials(itemUsername, password);
        } catch (Exception e) {
            log.warn("Failed to lookup credentials in libsecret for server={}: {}", serverId, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteCredentials(String serverId) {
        try {
            var attributes = Secret.attributesBuild(SCHEMA, "server-id", serverId, null);
            return Secret.passwordClearSync(SCHEMA, attributes, null);
        } catch (Exception e) {
            log.warn("Failed to delete credentials in libsecret for server={}: {}", serverId, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
