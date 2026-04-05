package org.subsound.integration.platform.secret;

import org.jspecify.annotations.Nullable;

public final class NoopSecretService implements SecretService {

    @Override
    public boolean storeCredentialsSync(String serverId, String username, String password) {
        return false;
    }

    @Override
    public @Nullable Credentials lookupCredentialsSync(String serverId, String username) {
        return null;
    }

    @Override
    public boolean deleteCredentials(String serverId) {
        return false;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
