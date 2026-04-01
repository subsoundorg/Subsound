package org.subsound.persistence.database;

import org.subsound.integration.ServerClient.ServerType;
import org.assertj.core.api.Assertions;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class DatabaseServiceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testServerOperations() throws Exception {
        File dbFile = folder.newFile("test_service.db");
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        Database db = new Database(url);
        DatabaseService service = new DatabaseService(db);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Server server1 = new Server(
                UUID.randomUUID(),
                true,
                ServerType.SUBSONIC,
                "http://server1.com",
                "user1",
                now,
                false,
                null,
                null
        );

        Server server2 = new Server(
                UUID.randomUUID(),
                false,
                ServerType.SUBSONIC,
                "http://server2.com",
                "user2",
                now,
                false,
                null,
                null
        );

        // Test insert
        service.insert(server1);
        service.insert(server2);

        // Test listServers
        List<Server> servers = service.listServers();
        Assertions.assertThat(servers).hasSize(2);
        Assertions.assertThat(servers).containsExactlyInAnyOrder(server1, server2);

        // Test getDefaultServer
        Optional<Server> defaultServer = service.getDefaultServer();
        Assertions.assertThat(defaultServer).isPresent();
        Assertions.assertThat(defaultServer.get()).isEqualTo(server1);

        // Test getServerById
        Optional<Server> foundServer = service.getServerById(server2.id().toString());
        Assertions.assertThat(foundServer).isPresent();
        Assertions.assertThat(foundServer.get()).isEqualTo(server2);

        // Test getServerById with non-existent id
        Optional<Server> notFoundServer = service.getServerById("non-existent");
        Assertions.assertThat(notFoundServer).isEmpty();
    }
}
