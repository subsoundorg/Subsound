package org.subsound.persistence.database;

import org.subsound.integration.ServerClient.ServerType;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

public class DatabaseTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testDatabaseInitializationAndMigration() throws Exception {
        File dbFile = folder.newFile("test.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        Database db = new Database(url);

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement()) {

            // Check if schema_version table exists
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='schema_version'")) {
                Assertions.assertThat(rs.next()).isTrue();
            }

            // Check if servers table exists
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='servers'")) {
                Assertions.assertThat(rs.next()).isTrue();
            }

            // Check version
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                Assertions.assertThat(rs.next()).isTrue();
                Assertions.assertThat(rs.getInt(1)).isEqualTo(13);
            }

            // Check if artists table exists
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='artists'")) {
                Assertions.assertThat(rs.next()).isTrue();
            }

            // Check if songs table exists
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='songs'")) {
                Assertions.assertThat(rs.next()).isTrue();
            }

            // Verify columns in songs table
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(songs)")) {
                boolean hasCreatedAtMs = false;
                while (rs.next()) {
                    String name = rs.getString("name");
                    if ("created_at_ms".equals(name)) {
                        hasCreatedAtMs = true;
                    }
                }
                Assertions.assertThat(hasCreatedAtMs).isTrue();
            }

            // Verify columns in download_queue table
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(download_queue)")) {
                boolean hasChecksum = false;
                while (rs.next()) {
                    String name = rs.getString("name");
                    if ("checksum".equals(name)) {
                        hasChecksum = true;
                    }
                }
                Assertions.assertThat(hasChecksum).isTrue();
            }

            // Verify columns in artists table
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(artists)")) {
                boolean hasServerId = false;
                boolean starredAtIsInteger = false;
                while (rs.next()) {
                    String name = rs.getString("name");
                    String type = rs.getString("type");
                    if ("server_id".equals(name)) {
                        hasServerId = true;
                    }
                    if ("starred_at".equals(name) && "INTEGER".equals(type)) {
                        starredAtIsInteger = true;
                    }
                }
                Assertions.assertThat(hasServerId).isTrue();
                Assertions.assertThat(starredAtIsInteger).isTrue();
            }
        }
    }

    @Test
    public void testInsertAndRetrieveArtist() throws Exception {
        File dbFile = folder.newFile("test_artist.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        String id = "artist-1";
        String serverId = "server-1";
        String name = "The Artist";
        int albumCount = 5;
        long starredAt = System.currentTimeMillis();
        String coverArtId = "cover-1";
        String biography = "{\"original\":\"Long bio\",\"cleaned\":\"Short bio\",\"link\":\"http://link\"}";

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(String.format(
                "INSERT INTO artists (id, server_id, name, album_count, starred_at, cover_art_id, biography) VALUES ('%s', '%s', '%s', %d, %d, '%s', '%s')",
                id, serverId, name, albumCount, starredAt, coverArtId, biography
            ));
        }

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM artists WHERE id = '" + id + "'")) {
            Assertions.assertThat(rs.next()).isTrue();
            Assertions.assertThat(rs.getString("server_id")).isEqualTo(serverId);
            Assertions.assertThat(rs.getString("name")).isEqualTo(name);
            Assertions.assertThat(rs.getInt("album_count")).isEqualTo(albumCount);
            Assertions.assertThat(rs.getLong("starred_at")).isEqualTo(starredAt);
            Assertions.assertThat(rs.getString("cover_art_id")).isEqualTo(coverArtId);
            Assertions.assertThat(rs.getString("biography")).isEqualTo(biography);
        }
    }

    @Test
    public void testInsertAndRetrieveServer() throws Exception {
        File dbFile = folder.newFile("test_insert.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);

        String id = UUID.randomUUID().toString();
        String type = ServerType.SUBSONIC.value();
        String serverUrl = "http://localhost:4040";
        String username = "user";

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(String.format(
                "INSERT INTO servers (id, is_primary, server_type, server_url, username) VALUES ('%s', %d, '%s', '%s', '%s')",
                id, 1, type, serverUrl, username
            ));
        }

        try (Connection conn = db.openConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM servers WHERE id = '" + id + "'")) {
            Assertions.assertThat(rs.next()).isTrue();
            Assertions.assertThat(rs.getString("server_type")).isEqualTo(type);
            Assertions.assertThat(rs.getString("server_url")).isEqualTo(serverUrl);
            Assertions.assertThat(rs.getString("username")).isEqualTo(username);
        }
    }
}
