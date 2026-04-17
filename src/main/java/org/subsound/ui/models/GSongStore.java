package org.subsound.ui.models;

import org.subsound.integration.ServerClient.ObjectIdentifier.SongIdentifier;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.DownloadManager;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class GSongStore {
    private final ConcurrentHashMap<String, GSongInfo> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, GDownloadState> downloadState = new ConcurrentHashMap<>();
    private final Function<String, SongInfo> songLoader;

    public GSongStore(
            Function<String, SongInfo> songLoader,
            DownloadManager downloadManager
    ) {
        this.songLoader = songLoader;
        downloadManager.subscribe(event ->
                setDownloadState(event.item().songId(), GDownloadState.from(event.item().status()))
        );
        downloadManager.listDownloads(true).forEach(item ->
                setDownloadState(item.songId(), GDownloadState.from(item.status()))
        );
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
                    var downloadStatus = this.downloadState.get(key);
                    if (downloadStatus != null) {
                        instance.setDownloadState(downloadStatus);
                    }
                    return instance;
                }
        );
        gsong.mutate(_ -> value);
        return gsong;
    }

    public int size() {
        return store.size();
    }

    public void setDownloadState(String songId, GDownloadState state) {
        downloadState.put(songId, state);
        var existing = store.get(songId);
        if (existing != null) {
            existing.setDownloadState(state);
        }
    }
}
