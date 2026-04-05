package org.subsound.integration.platform.secret;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

public sealed interface SecretService permits LibsecretService, NoopSecretService {

    record Credentials(String username, String password) {}

    boolean storeCredentialsSync(String serverId, String username, String password);

    @Nullable Credentials lookupCredentialsSync(String serverId, String username);

    boolean deleteCredentials(String serverId);

    boolean isAvailable();

    static SecretService create() {
        try {
            return new LibsecretService();
        } catch (Exception e) {
            LoggerFactory.getLogger(SecretService.class)
                    .warn("libsecret unavailable, credentials will be stored in config file: {}", e.getMessage());
            return new NoopSecretService();
        }
    }
}
