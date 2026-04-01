package org.subsound.persistence.database;

import org.subsound.integration.ServerClient.ServerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);
    private final Database database;

    private static final String ALL_COLUMNS = "id, is_primary, server_type, server_url, username, created_at, tls_skip_verify, audio_format, audio_bitrate";

    public DatabaseService(Database database) {
        this.database = database;
    }

    public void insert(Server server) {
        String sql = "INSERT INTO servers (" + ALL_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            setServerParams(pstmt, server);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert server", e);
            throw new RuntimeException("Failed to insert server", e);
        }
    }

    public void upsert(Server server) {
        String sql = """
            INSERT INTO servers (%s) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                server_type = excluded.server_type,
                server_url = excluded.server_url,
                username = excluded.username,
                tls_skip_verify = excluded.tls_skip_verify,
                audio_format = excluded.audio_format,
                audio_bitrate = excluded.audio_bitrate
            """.formatted(ALL_COLUMNS);
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            setServerParams(pstmt, server);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to upsert server", e);
            throw new RuntimeException("Failed to upsert server", e);
        }
    }

    public Optional<Server> getDefaultServer() {
        String sql = "SELECT " + ALL_COLUMNS + " FROM servers WHERE is_primary = 1 LIMIT 1";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapResultSetToServer(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to get default server", e);
            throw new RuntimeException("Failed to get default server", e);
        }
        return Optional.empty();
    }

    public Optional<Server> getServerById(String id) {
        String sql = "SELECT " + ALL_COLUMNS + " FROM servers WHERE id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapResultSetToServer(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get server by id: {}", id, e);
            throw new RuntimeException("Failed to get server by id", e);
        }
        return Optional.empty();
    }

    public List<Server> listServers() {
        List<Server> servers = new ArrayList<>();
        String sql = "SELECT " + ALL_COLUMNS + " FROM servers";
        try (Connection conn = database.openConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                servers.add(mapResultSetToServer(rs));
            }
        } catch (SQLException e) {
            logger.error("Failed to list servers", e);
            throw new RuntimeException("Failed to list servers", e);
        }
        return servers;
    }

    private static void setServerParams(PreparedStatement pstmt, Server server) throws SQLException {
        pstmt.setString(1, server.id().toString());
        pstmt.setBoolean(2, server.isPrimary());
        pstmt.setString(3, server.serverType().name());
        pstmt.setString(4, server.serverUrl());
        pstmt.setString(5, server.username());
        pstmt.setLong(6, server.createdAt().toEpochMilli());
        pstmt.setBoolean(7, server.tlsSkipVerify());
        if (server.audioFormat() != null) {
            pstmt.setString(8, server.audioFormat());
        } else {
            pstmt.setNull(8, Types.VARCHAR);
        }
        if (server.audioBitrate() != null) {
            pstmt.setInt(9, server.audioBitrate());
        } else {
            pstmt.setNull(9, Types.INTEGER);
        }
    }

    private Server mapResultSetToServer(ResultSet rs) throws SQLException {
        int audioBitrateVal = rs.getInt("audio_bitrate");
        Integer audioBitrate = rs.wasNull() ? null : audioBitrateVal;
        return new Server(
                UUID.fromString(rs.getString("id")),
                rs.getBoolean("is_primary"),
                ServerType.valueOf(rs.getString("server_type")),
                rs.getString("server_url"),
                rs.getString("username"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.getBoolean("tls_skip_verify"),
                rs.getString("audio_format"),
                audioBitrate
        );
    }
}
