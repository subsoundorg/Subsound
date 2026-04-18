package org.subsound.ui.models;

import org.subsound.integration.ServerClient.ObjectIdentifier.SongIdentifier;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.DownloadNotifier;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class GSongStore {
    private final ConcurrentHashMap<String, GSongInfo> store = new ConcurrentHashMap<>();
    private final Function<String, SongInfo> songLoader;
    private final DownloadNotifier downloads;

    public GSongStore(
            Function<String, SongInfo> songLoader,
            DownloadNotifier downloads
    ) {
        this.songLoader = songLoader;
        this.downloads = downloads;
        downloads.subscribe(event -> {
            var existing = store.get(event.songId());
            if (existing != null) {
                var state = event.item()
                        .map(i -> GDownloadState.from(i.status()))
                        .orElse(GDownloadState.NONE);
                existing.setDownloadState(state);
            }
        });
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
        var gsong = store.computeIfAbsent(
                value.id(),
                key -> {
                    var instance = GSongInfo.newInstance(value);
                    downloads.getSongStatus(key)
                            .ifPresent(item -> instance.setDownloadState(GDownloadState.from(item.status())));
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
