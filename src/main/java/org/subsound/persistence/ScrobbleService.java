package org.subsound.persistence;

import org.subsound.app.state.NetworkMonitoring;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ScrobbleRequest;
import org.subsound.persistence.database.DatabaseServerService;
import org.subsound.persistence.database.ScrobbleEntry.ScrobbleStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ScrobbleService {
    private static final Logger log = LoggerFactory.getLogger(ScrobbleService.class);
    private final DatabaseServerService dbService;
    private final Supplier<ServerClient> clientSupplier;
    private final Supplier<NetworkMonitoring.NetworkState> statusSupplier;
    private volatile boolean running = true;
    private volatile CountDownLatch trigger = new CountDownLatch(1);

    public ScrobbleService(
            DatabaseServerService dbService,
            Supplier<ServerClient> clientSupplier,
            Supplier<NetworkMonitoring.NetworkState> statusSupplier
    ) {
        this.dbService = dbService;
        this.clientSupplier = clientSupplier;
        this.statusSupplier = statusSupplier;
        startProcessor();
    }

    private void startProcessor() {
        Thread.startVirtualThread(() -> {
            while (running) {
                try {
                    processPendingScrobbles();
                    trigger.await(60, TimeUnit.SECONDS);
                    trigger = new CountDownLatch(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error in scrobble processor", e);
                }
            }
        });
    }

    public void triggerSubmit() {
        trigger.countDown();
    }

    private void processPendingScrobbles() {
        var client = clientSupplier.get();
        if (client == null) {
            return;
        }

        var pending = dbService.listPendingScrobbles();
        if (pending.isEmpty()) {
            return;
        }

        var status = statusSupplier.get();
        switch (status.status()) {
            case OFFLINE -> {
                log.info("Skipping scrobble submission: {}", status.status());
                return;
            }
        }

        log.info("Processing {} pending scrobbles", pending.size());
        for (var entry : pending) {
            try {
                client.scrobble(new ScrobbleRequest(entry.songId(), Instant.ofEpochMilli(entry.playedAtMs())));
                dbService.updateScrobbleStatus(entry.id(), ScrobbleStatus.SUBMITTED);
                log.info("Scrobble submitted: id={} songId={}", entry.id(), entry.songId());
            } catch (Exception e) {
                dbService.updateScrobbleStatus(entry.id(), ScrobbleStatus.FAILED);
                log.error("Failed to submit scrobble: id={} songId={}", entry.id(), entry.songId(), e);
            }
        }
    }

    public void stop() {
        running = false;
        trigger.countDown();
    }
}
