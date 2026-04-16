package org.subsound.persistence.database;

import org.subsound.configuration.constants.Constants;
import org.subsound.integration.platform.PortalUtils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sqlite.SQLiteDataSource;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class Database {
    private static final Logger logger = LoggerFactory.getLogger(Database.class);
    private static final String DB_NAME = "subsound.db";
    private final HikariDataSource dataSource;

    public Database() {
        String dataDir = PortalUtils.getUserDataDir();
        File subsoundDir = new File(dataDir, Constants.APP_ID);
        if (!subsoundDir.exists()) {
            subsoundDir.mkdirs();
        }
        File dbFile = new File(subsoundDir, DB_NAME);
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        logger.info("Database URL: {}", url);
        logger.warn("opening database file path={}", dbFile.getAbsolutePath());
        this.dataSource = createDataSource(url);
        initialize();
    }

    // Constructor for testing
    public Database(String url) {
        this.dataSource = createDataSource(url);
        initialize();
    }

    private HikariDataSource createDataSource(String url) {
        org.sqlite.SQLiteConfig sqliteConfig = new org.sqlite.SQLiteConfig();
        sqliteConfig.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(org.sqlite.SQLiteConfig.SynchronousMode.NORMAL);
        SQLiteDataSource ds = new SQLiteDataSource(sqliteConfig);
        ds.setUrl(url);
        var cfg = new HikariConfig();
        //cfg.setJdbcUrl(url);
        cfg.setDataSource(ds);
        // SQLITE only support single connection per db file, so limit at 1 connection in pool:
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setAutoCommit(true);
        //cfg.setTransactionIsolation();
        cfg.setConnectionTimeout(120000);
        return new HikariDataSource(cfg);
    }

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    private void initialize() {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int currentVersion = getCurrentVersion(conn);
                logger.info("Current database version: {}", currentVersion);
                List<Migration> migrations = getMigrations();
                for (Migration migration : migrations) {
                    if (migration.version() > currentVersion) {
                        logger.info("Applying migration to version {}", migration.version());
                        migration.apply(conn);
                        updateVersion(conn, migration.version());
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new RuntimeException("Failed to initialize database", e);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to connect to database", e);
        }
    }

    private int getCurrentVersion(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY)");
            try (ResultSet rs = stmt.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private void updateVersion(Connection conn, int version) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO schema_version (version) VALUES (" + version + ")");
        }
    }

    private List<Migration> getMigrations() {
        List<Migration> migrations = new ArrayList<>();
        migrations.add(new MigrationV1());
        migrations.add(new MigrationV2());
        migrations.add(new MigrationV3());
        migrations.add(new MigrationV4());
        migrations.add(new MigrationV5());
        migrations.add(new MigrationV6());
        migrations.add(new MigrationV7());
        migrations.add(new MigrationV8());
        migrations.add(new MigrationV9());
        migrations.add(new MigrationV10());
        migrations.add(new MigrationV11());
        migrations.add(new MigrationV12());
        migrations.add(new MigrationV13());
        migrations.add(new MigrationV14());
        return migrations;
    }

    public interface Migration {
        int version();
        void apply(Connection conn) throws SQLException;
    }

    private static class MigrationV1 implements Migration {
        @Override
        public int version() {
            return 1;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS servers (
                        id TEXT PRIMARY KEY,
                        is_primary BOOL NOT NULL,
                        server_type TEXT NOT NULL,
                        server_url TEXT NOT NULL,
                        username TEXT NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """);
            }
        }
    }

    private static class MigrationV2 implements Migration {
        @Override
        public int version() {
            return 2;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS artists (
                        id TEXT PRIMARY KEY,
                        server_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        album_count INTEGER NOT NULL,
                        starred_at INTEGER,
                        cover_art_id TEXT,
                        biography BLOB,
                        created_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """);
            }
        }
    }

    private static class MigrationV3 implements Migration {
        @Override
        public int version() {
            return 3;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS albums (
                        id TEXT,
                        server_id TEXT NOT NULL,
                        artist_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        song_count INTEGER,
                        year INTEGER,
                        artist_name TEXT NOT NULL,
                        duration_ms INTEGER,
                        starred_at_ms INTEGER,
                        cover_art_id TEXT,
                        added_at_ms INTEGER NOT NULL,
                        created_at INTEGER DEFAULT (strftime('%s', 'now')),
                        PRIMARY KEY (id, server_id)
                    )
                """);
            }
        }
    }

    private static class MigrationV4 implements Migration {
        @Override
        public int version() {
            return 4;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS songs (
                        id TEXT,
                        server_id TEXT NOT NULL,
                        album_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        year INTEGER,
                        artist_id TEXT NOT NULL,
                        artist_name TEXT NOT NULL,
                        duration_ms INTEGER,
                        starred_at_ms INTEGER,
                        cover_art_id TEXT,
                        created_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (id, server_id)
                    )
                """);
            }
        }
    }

    private static class MigrationV5 implements Migration {
        @Override
        public int version() {
            return 5;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS download_queue (
                        song_id TEXT,
                        server_id TEXT NOT NULL,
                        status TEXT NOT NULL, -- PENDING, DOWNLOADING, COMPLETED, FAILED
                        progress REAL DEFAULT 0.0,
                        added_at INTEGER DEFAULT (strftime('%s', 'now')),
                        error_message TEXT,
                        stream_uri TEXT,
                        stream_format TEXT,
                        original_size INTEGER,
                        original_bitrate INTEGER,
                        estimated_bitrate INTEGER,
                        duration_seconds INTEGER,
                        PRIMARY KEY (song_id, server_id)
                    )
                """);
            }
        }
    }

    private static class MigrationV6 implements Migration {
        @Override
        public int version() {
            return 6;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE download_queue ADD COLUMN checksum TEXT");
            }
        }
    }

    private static class MigrationV7 implements Migration {
        @Override
        public int version() {
            return 7;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS player_config (
                        config_key INTEGER PRIMARY KEY,
                        config_json TEXT NOT NULL,
                        updated_at INTEGER DEFAULT (strftime('%s', 'now'))
                    )
                """);
            }
        }
    }

    private static class MigrationV8 implements Migration {
        @Override
        public int version() {
            return 8;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                // Add missing song fields
                stmt.execute("ALTER TABLE songs ADD COLUMN track_number INTEGER");
                stmt.execute("ALTER TABLE songs ADD COLUMN disc_number INTEGER");
                stmt.execute("ALTER TABLE songs ADD COLUMN bit_rate INTEGER");
                stmt.execute("ALTER TABLE songs ADD COLUMN size INTEGER DEFAULT 0");
                stmt.execute("ALTER TABLE songs ADD COLUMN genre TEXT DEFAULT ''");
                stmt.execute("ALTER TABLE songs ADD COLUMN suffix TEXT DEFAULT ''");

                // Add missing album fields
                stmt.execute("ALTER TABLE albums ADD COLUMN genre TEXT");

                // Playlist tables
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS playlists (
                        id TEXT,
                        server_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        song_count INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        cover_art_id TEXT,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY (id, server_id)
                    )
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        playlist_id TEXT NOT NULL,
                        server_id TEXT NOT NULL,
                        song_id TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        PRIMARY KEY (playlist_id, server_id, song_id)
                    )
                """);
            }
        }
    }

    private static class MigrationV9 implements Migration {
        @Override
        public int version() {
            return 9;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS scrobbles (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        server_id TEXT NOT NULL,
                        song_id TEXT NOT NULL,
                        played_at_ms INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),
                        status TEXT NOT NULL DEFAULT 'PENDING'
                    )
                """);
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_scrobbles_server_status ON scrobbles (server_id, status)");
            }
        }
    }

    private static class MigrationV10 implements Migration {
        @Override
        public int version() {
            return 10;
        }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE songs ADD COLUMN album_name TEXT NOT NULL DEFAULT ''");
            }
        }
    }

    static class MigrationV11 implements Migration {
        @Override
        public int version() { return 11; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS playlist_songs_new (
                            playlist_id TEXT NOT NULL,
                            server_id   TEXT NOT NULL,
                            song_id     TEXT NOT NULL,
                            sort_order  INTEGER NOT NULL,
                            PRIMARY KEY (playlist_id, server_id, sort_order)
                        )
                        """);
                stmt.executeUpdate("INSERT INTO playlist_songs_new SELECT * FROM playlist_songs");
                stmt.executeUpdate("DROP TABLE playlist_songs");
                stmt.executeUpdate("ALTER TABLE playlist_songs_new RENAME TO playlist_songs");
            }
        }
    }

    static class MigrationV12 implements Migration {
        @Override
        public int version() { return 12; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE servers ADD COLUMN tls_skip_verify BOOL NOT NULL DEFAULT 0");
            }
        }
    }

    static class MigrationV13 implements Migration {
        @Override
        public int version() { return 13; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE servers ADD COLUMN audio_format TEXT DEFAULT NULL");
                stmt.execute("ALTER TABLE servers ADD COLUMN audio_bitrate INTEGER DEFAULT NULL");
            }
        }
    }

    static class MigrationV14 implements Migration {
        @Override
        public int version() { return 14; }

        @Override
        public void apply(Connection conn) throws SQLException {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS play_queue_items (
                        server_id TEXT NOT NULL,
                        sort_order INTEGER NOT NULL,
                        song_id TEXT NOT NULL,
                        queue_item_id TEXT NOT NULL,
                        queue_kind TEXT NOT NULL DEFAULT 'AUTOMATIC',
                        original_order INTEGER NOT NULL DEFAULT 0,
                        shuffle_order INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (server_id, sort_order)
                    )
                """);
            }
        }
    }

    public Connection openConnection() throws SQLException {
        return getConnection();
    }

    public void close() {
        dataSource.close();
    }

    public Path getDbFilePath() {
        String dataDir = PortalUtils.getUserDataDir();
        File subsoundDir = new File(dataDir, Constants.APP_ID);
        return new File(subsoundDir, DB_NAME).toPath();
    }
}
