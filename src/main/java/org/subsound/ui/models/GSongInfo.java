package org.subsound.ui.models;

import org.gnome.glib.Type;
import org.gnome.gobject.GObject;
import org.javagi.gobject.SignalConnection;
import org.javagi.gobject.annotations.Property;
import org.javagi.gobject.types.Types;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.persistence.database.DownloadQueueItem.DownloadStatus;

import java.lang.foreign.MemorySegment;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import static org.subsound.utils.Utils.runOnMainThread;

public class GSongInfo extends GObject {
    public static final Type gtype = Types.register(GSongInfo.class);

    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isFavorite = new AtomicBoolean(false);
    private final Lock lock = new ReentrantLock();

    private volatile GDownloadState downloadState = GDownloadState.NONE;

    public static Type getType() {
        return gtype;
    }

    private SongInfo songInfo;

    // newInstance: package-private, please do not use directly:
    static GSongInfo newInstance(SongInfo value) {
        GSongInfo instance = GObject.newInstance(GSongInfo.getType());
        instance.songInfo = value;
        return instance;
    }

    public GSongInfo(MemorySegment address) {
        super(address);
    }

    public SongInfo getSongInfo() {
        return songInfo;
    }
    @Property
    public String getId() {
        return songInfo.id();
    }

    @Property
    public boolean getIsPlaying() {
        return this.isPlaying.get();
    }
    @Property(skip = true)
    public SignalConnection<NotifyCallback> onIsPlayingChanged(NotifyCallback handler) {
        return onNotify(Signal.IS_PLAYING.getId(), handler);
    }

    @Property
    public void setDownloadState(GDownloadState next) {
        if (this.downloadState != next) {
            this.downloadState = next;
            runOnMainThread(() -> this.notify(Signal.DOWNLOAD_STATE.signal));
        }
    }
    public GDownloadState getDownloadStateEnum() {
        return this.downloadState;
    }

    @Property(skip = true)
    public void setDownloadStateEnum(DownloadStatus next) {
        this.setDownloadState(switch (next) {
            case PENDING -> GDownloadState.PENDING;
            case DOWNLOADING -> GDownloadState.DOWNLOADING;
            case COMPLETED -> GDownloadState.DOWNLOADED;
            case FAILED -> GDownloadState.NONE;
            case CACHED -> GDownloadState.CACHED;
        });
    }

    @Property
    public void setIsPlaying(boolean isPlaying) {
        if (this.isPlaying.compareAndSet(!isPlaying, isPlaying)) {
            runOnMainThread(() -> this.notify(Signal.IS_PLAYING.signal));
        }
    }

    @Property
    public void setIsFavorite(boolean isFavorite) {
        if (this.isFavorite.compareAndSet(!isFavorite, isFavorite)) {
            Optional<Instant> starredAt = Optional.ofNullable(isFavorite ? Instant.now() : null);
            this.mutate(songInfo -> songInfo.withStarred(starredAt));
        }
    }

    public GSongInfo setStarredAt(Optional<Instant> starredAt) {
        if (starredAt.isPresent() != this.songInfo.starred().isPresent()) {
            this.mutate(songInfo -> songInfo.withStarred(starredAt));
        }
        return this;
    }

    public void mutate(Function<SongInfo, SongInfo> modifier) {
        lock.lock();
        try {
            this.songInfo = modifier.apply(this.songInfo);
            boolean isFavorite = this.songInfo.starred().isPresent();
            if (this.isFavorite.get() != isFavorite) {
                this.isFavorite.set(isFavorite);
                // notify always need to happen on the main thread, as it triggers signals in GTK objects that could trigger UI updates...
                runOnMainThread(() -> this.notify(Signal.IS_FAVORITE.signal));
            }
        } finally {
            lock.unlock();
        }
    }

    @Property
    public boolean getIsStarred() {
        return this.isFavorite.get();
    }

    public Optional<Instant> getStarredAt() {
        return this.songInfo.starred();
    }

    public enum Signal {
        NAME("name"),
        DOWNLOAD_STATE("download-state"),
        IS_PLAYING("is-playing"),
        IS_FAVORITE("is-favorite");
        private final String signal;

        Signal(String signal) {
            this.signal = signal;
        }

        public String getId() {
            return this.signal;
        }
    }

    @Property
    public String getTitle() {
        return this.songInfo.title();
    }
}
