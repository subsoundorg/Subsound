package org.subsound.persistence;

import org.subsound.persistence.database.DownloadQueueItem;

import java.util.Optional;
import java.util.function.Consumer;

public interface DownloadNotifier {
    void subscribe(Consumer<DownloadManager.DownloadManagerEvent> listener);
    Optional<DownloadQueueItem> getSongStatus(String songId);
}
