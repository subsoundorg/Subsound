package org.subsound.persistence.database;

import org.subsound.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Service for managing player configuration in the database.
 * Stores JSON configuration data keyed by an integer config_key.
 */
public class PlayerConfigService {
    private static final Logger logger = LoggerFactory.getLogger(PlayerConfigService.class);
    private static final int CONFIG_KEY_V1 = 1;
    private static final int CONFIG_KEY_QUEUE_STATE = 2;

    private final Database database;

    public PlayerConfigService(Database database) {
        this.database = database;
    }

    /**
     * Save player configuration (always uses CONFIG_KEY_V1).
     */
    public void savePlayerConfig(PlayerConfig config) {
        saveConfig(CONFIG_KEY_V1, Utils.toJson(config));
    }

    /**
     * Load player configuration (always uses CONFIG_KEY_V1).
     */
    public Optional<PlayerConfig> loadPlayerConfig() {
        return loadConfig(CONFIG_KEY_V1)
                .map(json -> Utils.fromJson(json, PlayerConfig.class));
    }

    /**
     * Delete player configuration.
     */
    public void deletePlayerConfig() {
        deleteConfig(CONFIG_KEY_V1);
    }

    /**
     * Save play queue state metadata (position, play mode, play context).
     */
    public void saveQueueState(PlayQueueStateJson state) {
        saveConfig(CONFIG_KEY_QUEUE_STATE, Utils.toJson(state));
    }

    /**
     * Load play queue state metadata.
     */
    public Optional<PlayQueueStateJson> loadQueueState() {
        return loadConfig(CONFIG_KEY_QUEUE_STATE)
                .map(json -> Utils.fromJson(json, PlayQueueStateJson.class));
    }

    private void saveConfig(int configKey, String configJson) {
        String sql = """
            INSERT INTO player_config (config_key, config_json)
            VALUES (?, ?)
            ON CONFLICT(config_key) DO UPDATE SET
                config_json = excluded.config_json,
                updated_at = (strftime('%s', 'now'))
            """;
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, configKey);
            pstmt.setString(2, configJson);
            pstmt.executeUpdate();
            logger.debug("Saved player config: config_key={}", configKey);
        } catch (SQLException e) {
            logger.error("Failed to save player config: config_key={}", configKey, e);
            throw new RuntimeException("Failed to save player config", e);
        }
    }

    private Optional<String> loadConfig(int configKey) {
        String sql = "SELECT config_json FROM player_config WHERE config_key = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, configKey);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    logger.debug("Loaded player config: config_key={}", configKey);
                    return Optional.of(rs.getString("config_json"));
                }
            }
            return Optional.empty();
        } catch (SQLException e) {
            logger.error("Failed to load player config: config_key={}", configKey, e);
            throw new RuntimeException("Failed to load player config", e);
        }
    }

    private void deleteConfig(int configKey) {
        String sql = "DELETE FROM player_config WHERE config_key = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, configKey);
            pstmt.executeUpdate();
            logger.debug("Deleted player config: config_key={}", configKey);
        } catch (SQLException e) {
            logger.error("Failed to delete player config: config_key={}", configKey, e);
            throw new RuntimeException("Failed to delete player config", e);
        }
    }
}
