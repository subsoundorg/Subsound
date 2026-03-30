package org.subsound.ui.models;

import org.subsound.integration.ServerClient.ObjectIdentifier.SongIdentifier;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.database.DownloadQueueItem;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class GSongStore {
    private final ConcurrentHashMap<String, GSongInfo> store = new ConcurrentHashMap<>();
    private final Function<String, Optional<DownloadQueueItem>> downloadManager;
    private final Function<String, SongInfo> songLoader;

    public GSongStore(
            Function<String, SongInfo> songLoader,
            Function<String, Optional<DownloadQueueItem>> downloadManager
    )
    {
        this.downloadManager = downloadManager;
        this.songLoader = songLoader;
    }

    public GSongInfo getSongById(SongIdentifier id) {
        return store.computeIfAbsent(
                id.songId(), key -> {
                    var song = songLoader.apply(key);
                    return this.newInstance(song);
                }
        );
    }

    public GSongInfo getSongById(String songId) {
        return getSongById(new SongIdentifier(songId));
    }


    public Optional<GSongInfo> getExisting(String songId) {
        return Optional.ofNullable(store.get(songId));
    }

    public GSongInfo get(SongInfo songInfo) {
        return newInstance(songInfo);
    }

    public GSongInfo newInstance(SongInfo value) {
        // TODO: replace with the updated SongInfo data
        var gsong = store.computeIfAbsent(
                value.id(),
                key -> {
                    GSongInfo instance = GSongInfo.newInstance(value);
                    var downloadStatus = this.downloadManager.apply(key);
                    downloadStatus.ifPresent(item -> instance.setDownloadStateEnum(item.status()));
                    return instance;
                }
        );
        gsong.mutate(_ -> value);
        return gsong;
    }

    public int size() {
        return store.size();
    }
}
