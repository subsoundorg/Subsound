package org.subsound.configuration;

import org.jspecify.annotations.Nullable;
import org.subsound.configuration.Config.ConfigurationDTO.OnboardingState;
import org.subsound.configuration.constants.Constants;
import org.subsound.integration.ServerClient.ServerType;
import org.subsound.integration.ServerClient.TranscodeBitrate;
import org.subsound.integration.ServerClient.TranscodeFormat;
import org.subsound.integration.platform.PortalUtils;
import org.subsound.integration.platform.secret.SecretService;
import org.subsound.utils.Utils;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class Config {
    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private final Path configFilePath;
    private final SecretService secretService;
    public static final int DEFAULT_WINDOW_WIDTH = 1250;
    public static final int DEFAULT_WINDOW_HEIGHT = 950;

    public final boolean isTestpageEnabled = "true".equals(System.getenv("SUBSOUND_TESTPAGE_ENABLED"));
    public Path dataDir = defaultStorageDir();
    public ServerConfig serverConfig;
    public OnboardingState onboarding;
    public int windowWidth = DEFAULT_WINDOW_WIDTH;
    public int windowHeight = DEFAULT_WINDOW_HEIGHT;

    // Server ID loaded from JSON — used by AppManager to look up server from DB
    public @Nullable String serverId;

    // Password loaded from config file — fallback when keyring (libsecret) is unavailable (e.g. macOS)
    public @Nullable String fallbackPassword;

    // Whether credentials were successfully stored in the system keyring
    public boolean credentialsInKeyring = false;

    // Legacy server config data read from old JSON format, used for one-time migration to DB
    public @Nullable LegacyServerConfig legacyServerConfig;

    public Config(Path configFilePath, SecretService secretService) {
        this.configFilePath = configFilePath;
        this.secretService = secretService;
    }

    public void saveToFile() throws IOException {
        // assure parent folder exists:
        this.configFilePath.getParent().toFile().mkdirs();
        var dto = toFileFormat();
        var jsonStr = Utils.toJson(dto);
        log.info("saving config to file: {} width={} height={}", this.configFilePath, dto.windowWidth, dto.windowHeight);
        Files.writeString(this.configFilePath, jsonStr, StandardCharsets.UTF_8);
    }

    public Path getConfigFilePath() {
        return configFilePath;
    }

    public Optional<ServerConfig> getServerConfig() {
        return Optional.ofNullable(this.serverConfig);
    }

    public SecretService getSecretService() {
        return secretService;
    }

    private ConfigurationDTO toFileFormat() {
        var d = new ConfigurationDTO();
        d.onboarding = this.onboarding;
        d.windowWidth = this.windowWidth;
        d.windowHeight = this.windowHeight;
        d.serverId = this.serverId;
        // Store password in config file when keyring is not actually holding the credentials
        if (!this.credentialsInKeyring && this.serverConfig != null) {
            d.password = this.serverConfig.password();
        }
        return d;
    }

    public record ServerConfig(
            // the root of where we store app data
            Path dataDir,
            String id,
            ServerType type,
            String url,
            String username,
            String password,
            TranscodeFormat audioFormat,   // nullable means use mp3
            @Nullable TranscodeBitrate audioBitrate,   // null = SourceQuality
            boolean tlsSkipVerify
    ) {}

    // Holds legacy server fields read from old JSON format for migration
    public record LegacyServerConfig(
            String id,
            ServerType type,
            String url,
            String username,
            @Nullable String password,
            @Nullable String audioFormat,
            @Nullable Integer transcodeBitrate
    ) {}

    public static Config createDefault(SecretService secretService) {
        var configDir = defaultConfigDir();
        var configFilePath = configDir.resolve("config.json");

        Config config = new Config(configFilePath, secretService);
        config.dataDir = defaultStorageDir().toAbsolutePath();
        log.info("config.dataDir={}", config.dataDir);

        readConfigFile(configFilePath)
                .ifPresentOrElse(
                        cfg -> {
                            log.debug("read config file at path={}", configFilePath);
                            config.onboarding = cfg.onboarding;
                            if (cfg.windowWidth != null && cfg.windowWidth > 0) {
                                config.windowWidth = cfg.windowWidth;
                            }
                            if (cfg.windowHeight != null && cfg.windowHeight > 0) {
                                config.windowHeight = cfg.windowHeight;
                            }
                            // New format: serverId field
                            if (cfg.serverId != null && !cfg.serverId.isBlank()) {
                                config.serverId = cfg.serverId;
                                config.onboarding = OnboardingState.DONE;
                                config.fallbackPassword = cfg.password;
                            }
                            // Legacy format: full server object in JSON
                            else if (cfg.server != null) {
                                config.serverId = cfg.server.id;
                                config.onboarding = OnboardingState.DONE;
                                config.legacyServerConfig = new LegacyServerConfig(
                                        cfg.server.id,
                                        cfg.server.type,
                                        cfg.server.url,
                                        cfg.server.username,
                                        cfg.server.password,
                                        cfg.server.audioFormat,
                                        cfg.server.transcodeBitrate
                                );
                            }
                        },
                        () -> log.debug("no config file found at path={}", configFilePath)
                );

        return config;
    }

    public static class ConfigurationDTO {
        // Legacy fields for reading old config format
        public record ServerConfigDTO(
                String id,
                ServerType type,
                String url,
                String username,
                String password,
                String audioFormat,
                Integer transcodeBitrate
        ) {}

        // New format: only stores serverId
        @SerializedName("serverId")
        public String serverId;

        // Fallback password storage when keyring is unavailable (e.g. macOS)
        @SerializedName("password")
        public @Nullable String password;

        // Legacy: full server object (read-only for migration)
        @SerializedName("server")
        public ServerConfigDTO server;

        @SerializedName("onboarding")
        public OnboardingState onboarding;
        @SerializedName("windowWidth")
        public Integer windowWidth;
        @SerializedName("windowHeight")
        public Integer windowHeight;
        public enum OnboardingState {
            DONE,
        }
    }

    private static Optional<ConfigurationDTO> readConfigFile(Path configPath) {
        var configFile = configPath.toFile();
        if (!configFile.exists()) {
            return Optional.empty();
        }
        if (configFile.isDirectory()) {
            throw new IllegalStateException("expected file at path=%s".formatted(configPath.toString()));
        }
        if (!configFile.isFile()) {
            throw new IllegalStateException("expected file at path=%s".formatted(configPath.toString()));
        }
        if (!configFile.canRead()) {
            throw new IllegalStateException("unable to read file at path=%s".formatted(configPath.toString()));
        }
        try {
            String value = Files.readString(configPath, StandardCharsets.UTF_8);
            return parseCfg(value);
        } catch (IOException e) {
            throw new RuntimeException("unable to read file at path=%s".formatted(configPath.toString()), e);
        }
    }

    static Optional<ConfigurationDTO> parseCfg(String value) {
        var cfg = Utils.fromJson(value, ConfigurationDTO.class);
        return Optional.ofNullable(cfg);
    }

    private static Path defaultStorageDir() {
        {
            String userDataDir = PortalUtils.getUserDataDir();
            if (userDataDir != null && !userDataDir.isBlank()) {
                var p = Path.of(userDataDir, Constants.APP_ID).toAbsolutePath();
                var fd = p.toFile();
                if (!fd.exists()) {
                    fd.mkdirs();
                }
                return p;
            }
        }
        {
            var xdg = Utils.firstNotBlank(System.getenv("XDG_DATA_HOME"), System.getenv("XDG_CACHE_HOME"));
            if (!xdg.isBlank()) {
                Path path = java.nio.file.Path.of(xdg, Constants.APP_ID).toAbsolutePath();
                var fHandle = path.toFile();
                if (!fHandle.exists()) {
                    fHandle.mkdirs();
                }
                return path;
            }
        }

        {
            var homeDir = System.getenv("HOME");
            if (homeDir == null || homeDir.isBlank()) {
                throw new IllegalStateException("unable to determine a location for cache dir");
            }
            Path path = Path.of(homeDir, ".cache", Constants.APP_ID).toAbsolutePath();
            var fHandle = path.toFile();
            if (!fHandle.exists()) {
                fHandle.mkdirs();
            }
            return path;
        }
    }

    // https://specifications.freedesktop.org/basedir-spec/latest/index.html#variables
    private static Path defaultConfigDir() {
        var userConfigDir = PortalUtils.getUserConfigDir();
        if (userConfigDir != null && !userConfigDir.isBlank()) {
            Path path = Path.of(userConfigDir, Constants.APP_ID).toAbsolutePath();
            var fHandle = path.toFile();
            if (!fHandle.exists()) {
                fHandle.mkdirs();
            }
            return path;
        }

        var xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            Path path = Path.of(xdg, Constants.APP_ID).toAbsolutePath();
            var fHandle = path.toFile();
            if (!fHandle.exists()) {
                fHandle.mkdirs();
            }
            return path;
        }

        //  If $XDG_CONFIG_HOME is either not set or empty, a default equal to $HOME/.config should be used
        var homeDir = System.getenv("HOME");
        if (homeDir == null || homeDir.isBlank()) {
            throw new IllegalStateException("unable to determine a location for cache dir");
        }
        Path path = Path.of(homeDir, ".config", Constants.APP_ID).toAbsolutePath();
        var fHandle = path.toFile();
        if (!fHandle.exists()) {
            fHandle.mkdirs();
        }
        return path;
    }
}
