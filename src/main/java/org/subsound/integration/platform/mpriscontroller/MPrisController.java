package org.subsound.integration.platform.mpriscontroller;

import com.softwaremill.jox.Channel;
import com.softwaremill.jox.ChannelDoneException;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.DBusListType;
import org.freedesktop.dbus.types.DBusMapType;
import org.freedesktop.dbus.types.Variant;
import org.jspecify.annotations.Nullable;
import org.mpris.MediaPlayer2.MediaPlayer2;
import org.mpris.MediaPlayer2.MediaPlayer2Player;
import org.mpris.MediaPlayer2.TrackList.TrackId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.subsound.app.state.AppManager;
import org.subsound.app.state.PlayerAction;
import org.subsound.configuration.constants.Constants;
import org.subsound.sound.PlaybinPlayer;
import org.subsound.utils.OsUtil;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.subsound.integration.platform.mpriscontroller.MPrisController.MPRISPlayerState.interfaceName;
import static org.subsound.integration.platform.mpriscontroller.MPrisController.MPRISPlayerState.toListVariant;
import static org.subsound.integration.platform.mpriscontroller.MPrisController.MPRISPlayerState.toMicroseconds;

public class MPrisController implements MediaPlayer2, MediaPlayer2Player, AppManager.StateListener, Properties {
    private final static Logger log = LoggerFactory.getLogger(MPrisController.class);
    private final AppManager appManager;
    private final ArtworkHttpServer artworkServer;
    private final Channel<PropertiesChanged> dbusMessageChannel = Channel.newBufferedDefaultChannel();
    private final MprisApplicationProperties mprisApplicationProperties;
    private final AtomicReference<MPRISPlayerState> playerState = new AtomicReference<>();
    private final String playerBusName = "org.mpris.MediaPlayer2.%s".formatted(Constants.APP_ID);

    public MPrisController(AppManager appManager, ArtworkHttpServer artworkServer) {
        this.appManager = appManager;
        this.artworkServer = artworkServer;
        this.mprisApplicationProperties = new MprisApplicationProperties();
    }

    private final CountDownLatch countDownLatch = new CountDownLatch(1);
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    public void stop() {
        if (isShutdown.compareAndSet(false, true)) {
            // Silence dbus-java's IncomingMessageThread: closing the DBusConnection below
            // makes it read EOF on the socket and log a misleading "FatalException".
            silenceDbusShutdownNoise();
            // remove receiving state updates:
            this.appManager.removeOnStateChanged(this);
            this.dbusMessageChannel.done();
            try {
                // wait for the run() method to exit:
                this.countDownLatch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void silenceDbusShutdownNoise() {
        var ctx = (ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();
        ctx.getLogger("org.freedesktop.dbus.connections.base.IncomingMessageThread")
                .setLevel(ch.qos.logback.classic.Level.OFF);
        ctx.getLogger("org.freedesktop.dbus.connections.base.AbstractConnectionBase")
                .setLevel(ch.qos.logback.classic.Level.OFF);
    }

    public void run() {
        if (OsUtil.getOSPlatform() != OsUtil.OS.LINUX) {
            log.info("can not start dbus on platform={}", OsUtil.getOSPlatform());
            countDownLatch.countDown();
            return;
        }
        var builder = DBusConnectionBuilder.forSessionBus().withShared(false);
        // Set all thread handlers to single threads. See also:
        // https://github.com/hypfvieh/dbus-java/issues/220
        // https://github.com/flatpak/xdg-dbus-proxy/issues/46
        builder.receivingThreadConfig().withErrorHandlerThreadCount(1);
        builder.receivingThreadConfig().withSignalThreadCount(1);
        builder.receivingThreadConfig().withMethodReturnThreadCount(1);
        builder.receivingThreadConfig().withMethodCallThreadCount(1);
        try (DBusConnection conn = builder.build()) {
            this.appManager.addOnStateChanged(this);
            conn.exportObject("/org/mpris/MediaPlayer2", this);
            conn.requestBusName(this.playerBusName);
            while (!isShutdown.get()) {
                var msg = dbusMessageChannel.receive();
                if (msg != null) {
                    conn.sendMessage(msg);
                }
            }
        } catch (ChannelDoneException e) {
            if (!this.isShutdown.get()) {
                throw new RuntimeException(e);
            }
        } catch (DBusException | IOException | InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            countDownLatch.countDown();
            this.appManager.removeOnStateChanged(this);
            this.stop();
        }
    }

    @Override
    public void Raise() {
        log.info("Raise");
    }

    @Override
    public void Quit() {
        log.info("Quit");
    }

    @Override
    public String getObjectPath() {
        return "/org/mpris/MediaPlayer2";
    }

    @Override
    public void Next() {
        log.info("Next");
        this.appManager.next();
    }

    @Override
    public void Previous() {
        log.info("Previous");
        this.appManager.prev();
    }

    @Override
    public void Pause() {
        log.info("Pause");
        this.appManager.pause();
    }

    @Override
    public void PlayPause() {
        log.info("PlayPause");
        this.appManager.playPause();
    }

    @Override
    public void Stop() {
        log.info("Stop");
        this.appManager.pause();
    }

    @Override
    public void Play() {
        log.info("Play");
        this.appManager.play();
    }

    @Override
    public void Seek(long microseconds) {
        var pos = Duration.ofNanos(microseconds * 1000L);
        log.info("Seek to pos={}", pos.getSeconds());
    }

    @Override
    public void OpenUri(String uri) {
        log.info("OpenUri");
    }

    @Override
    public void SetPosition(String trackId, long position) {
        log.info("SetPosition trackId={} position={}", trackId, position);
    }

    @Override
    public void onStateChanged(AppManager.AppState next) {
        var prev = this.playerState.get();
        MPRISPlayerState newState = toMprisState(next);
        Optional<PropertiesChanged> changed = newState.diff(prev, true);
        if (changed.isPresent()) {
            // only update the internal view of the state when we actually send any changes
            this.playerState.set(newState);
            try {
                log.info("posting changes: {} {}", changed.get().getPropertiesChanged().size(), changed.get().getPropertiesChanged().keySet());
                this.dbusMessageChannel.sendOrClosed(changed.get());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private MPRISPlayerState toMprisState(AppManager.AppState next) {
        var metadata = next.nowPlaying().map(this::toMprisMetadata).orElse(null);
        var mode = next.queue().playMode();
        var loopStatus = switch (mode) {
            case REPEAT_ONE -> LoopStatus.Track;
            default -> LoopStatus.None;
        };
        return new MPRISPlayerState(
                next.player().state().toMpris(),
                loopStatus,
                1.0,
                mode == PlayerAction.PlayMode.SHUFFLE,
                metadata,
                next.player().volume(),
                // mpris: it should be fine to publish the position here, mpris clients that render a position,
                // should manage their own updates by calling with Get('playerid', Variant[Position])) for updates.
                next.player().source().flatMap(PlaybinPlayer.Source::position).orElse(Duration.ZERO),
                1.0,
                1.0,
                true,
                true,
                true,
                true,
                true,
                true
        );
    }

    private MPRISMetadata toMprisMetadata(AppManager.NowPlaying np) {
        Long discNumber = np.song().discNumber().map(Long::valueOf).orElse(null);
        var artUrl = np.song().coverArt().flatMap(artworkServer::getArtUrl).orElse(null);
        return new MPRISMetadata(
                new TrackId(np.song().id()),
                np.song().duration(),
                List.of(np.song().artist()),
                List.of(np.song().artist()),
                np.song().album(),
                np.song().title(),
                discNumber,
                artUrl,
                null
        );
    }


    public static <T> Variant<T> ofVariant(T value) {
        return new Variant<>(value);
    }


    @Override
    public <A> A Get(String _interfaceName, String _propertyName) {
        log.info("MprisController.Get {} {}", _interfaceName, _propertyName);
        return switch (_interfaceName) {
            case MprisApplicationProperties.dbusInterfaceName ->
                    this.mprisApplicationProperties.Get(_interfaceName, _propertyName);
            case MPRISPlayerState.interfaceName -> (A)this.getPlayerProperty(_interfaceName, _propertyName).getValue();
            default -> throw new IllegalArgumentException("Get: Unexpected value: " + _interfaceName);
        };
    }

    private Variant<?> getPlayerProperty(String _interfaceName, String propertyName) {
        if (propertyName.equals("Position")) {
            var position = this.appManager.getPlayerPosition().orElse(Duration.ZERO);
            return ofVariant(toMicroseconds(position));
        }
        return this.playerState.get().Get(_interfaceName, propertyName);
    }

    @Override
    public <A> void Set(String _interfaceName, String _propertyName, A _value) {
        log.info("MprisController.Set {} {}", _interfaceName, _propertyName);
        if (MprisApplicationProperties.dbusInterfaceName.equals(_interfaceName)) {
            this.mprisApplicationProperties.Set(_interfaceName, _propertyName, _value);
        }
    }

    @Override
    public Map<String, Variant<?>> GetAll(String _interfaceName) {
        return switch (_interfaceName) {
            case MprisApplicationProperties.dbusInterfaceName -> this.mprisApplicationProperties.GetAll(_interfaceName);
            case MPRISPlayerState.interfaceName -> this.playerState.get().GetAll(_interfaceName);
            default -> throw new IllegalArgumentException("GetAll: Unexpected value: " + _interfaceName);
        };
    }

    // See https://specifications.freedesktop.org/mpris-spec/2.2/Player_Interface.html
    public record MPRISPlayerState(
            PlaybackStatus playbackStatus,
            LoopStatus loopStatus,
            double rate,
            boolean shuffle,
            MPRISMetadata metadata,
            double volume,
            Duration position,
            double minimumRate,
            double maximumRate,
            boolean canGoNext,
            boolean canGoPrevious,
            boolean canPlay,
            boolean canPause,
            boolean canSeek,
            boolean canControl,
            Map<String, Variant<?>> variants
    ) implements Properties {
        public static final String objectPath = "/org/mpris/MediaPlayer2";
        public static final String interfaceName = "org.mpris.MediaPlayer2.Player";

        public MPRISPlayerState(
                PlaybackStatus playbackStatus,
                LoopStatus loopStatus,
                double rate,
                boolean shuffle,
                MPRISMetadata metadata,
                double volume,
                Duration position,
                double minimumRate,
                double maximumRate,
                boolean canGoNext,
                boolean canGoPrevious,
                boolean canPlay,
                boolean canPause,
                boolean canSeek,
                boolean canControl
        ) {
            this(
                    playbackStatus,
                    loopStatus,
                    rate,
                    shuffle,
                    metadata,
                    volume,
                    position,
                    minimumRate,
                    maximumRate,
                    canGoNext,
                    canGoPrevious,
                    canPlay,
                    canPause,
                    canSeek,
                    canControl,
                    asVariant(
                            playbackStatus,
                            loopStatus,
                            rate,
                            shuffle,
                            metadata,
                            volume,
                            position,
                            minimumRate,
                            maximumRate,
                            canGoNext,
                            canGoPrevious,
                            canPlay,
                            canPause,
                            canSeek,
                            canControl
                    )
            );
        }

        private static Map<String, Variant<?>> asVariant(
                PlaybackStatus playbackStatus,
                LoopStatus loopStatus,
                double rate,
                boolean shuffle,
                MPRISMetadata metadata,
                double volume,
                Duration position,
                double minimumRate,
                double maximumRate,
                boolean canGoNext,
                boolean canGoPrevious,
                boolean canPlay,
                boolean canPause,
                boolean canSeek,
                boolean canControl
        ) {
            return Map.ofEntries(
                    Map.entry("PlaybackStatus", playbackStatus.variant()),
                    Map.entry("LoopStatus", loopStatus.variant()),
                    Map.entry("Rate", ofVariant(rate)),
                    Map.entry("Shuffle", ofVariant(shuffle)),
                    Map.entry("Metadata", toMapVariant(metadata)),
                    Map.entry("Volume", ofVariant(volume)),
                    Map.entry("Position", ofVariant(toMicroseconds(position))),
                    Map.entry("MinimumRate", ofVariant(minimumRate)),
                    Map.entry("MaximumRate", ofVariant(maximumRate)),
                    Map.entry("CanGoNext", ofVariant(canGoNext)),
                    Map.entry("CanGoPrevious", ofVariant(canGoPrevious)),
                    Map.entry("CanPlay", ofVariant(canPlay)),
                    Map.entry("CanPause", ofVariant(canPause)),
                    Map.entry("CanSeek", ofVariant(canSeek)),
                    Map.entry("CanControl", ofVariant(canControl))
            );
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MPRISPlayerState that)) return false;

            return playbackStatus == that.playbackStatus &&
                    position.equals(that.position) &&
                    // dont crash if nothing is playing yet
                    (metadata == null ? that.metadata == null : metadata.equals(that.metadata)) &&
                    Double.compare(rate, that.rate) == 0 &&
                    Double.compare(volume, that.volume) == 0 &&
                    shuffle == that.shuffle &&
                    canPlay == that.canPlay &&
                    canSeek == that.canSeek &&
                    canPause == that.canPause &&
                    canGoNext == that.canGoNext &&
                    Double.compare(minimumRate, that.minimumRate) == 0 &&
                    Double.compare(maximumRate, that.maximumRate) == 0 &&
                    canControl == that.canControl &&
                    canGoPrevious == that.canGoPrevious &&
                    loopStatus == that.loopStatus;
        }

        @Override
        public int hashCode() {
            int result = playbackStatus.hashCode();
            result = 31 * result + loopStatus.hashCode();
            result = 31 * result + Double.hashCode(rate);
            result = 31 * result + Boolean.hashCode(shuffle);
            result = 31 * result + metadata.hashCode();
            result = 31 * result + Double.hashCode(volume);
            result = 31 * result + position.hashCode();
            result = 31 * result + Double.hashCode(minimumRate);
            result = 31 * result + Double.hashCode(maximumRate);
            result = 31 * result + Boolean.hashCode(canGoNext);
            result = 31 * result + Boolean.hashCode(canGoPrevious);
            result = 31 * result + Boolean.hashCode(canPlay);
            result = 31 * result + Boolean.hashCode(canPause);
            result = 31 * result + Boolean.hashCode(canSeek);
            result = 31 * result + Boolean.hashCode(canControl);
            return result;
        }

        public static Variant<?> toMapVariant(@Nullable MPRISMetadata metadata) {
            if (metadata == null) {
                return toMapVariant(Map.of());
            }
            return toMapVariant(metadata.variant());
        }

        public static final DBusMapType VARIANT_MAP_STRING_VARIANT_TYPE = new DBusMapType(String.class, Variant.class);

        public static Variant<?> toMapVariant(Map<String, Variant<?>> map) {
            return new Variant<>(map, VARIANT_MAP_STRING_VARIANT_TYPE);
        }

        public static final DBusListType VARIANT_LIST_STRING_TYPE = new DBusListType(String.class);

        public static Variant<?> toListVariant(@Nullable List<String> list) {
            if (list == null) {
                return new Variant<>(List.of(), VARIANT_LIST_STRING_TYPE);
            }
            return new Variant<>(list, VARIANT_LIST_STRING_TYPE);
        }

        public static long toMicroseconds(Duration position) {
            return position.toNanos() / 1000;
        }

        public Optional<PropertiesChanged> diff(@Nullable MPRISPlayerState old, boolean skipPosition) {
            if (old == null) {
                try {
                    var changes = new PropertiesChanged(objectPath, interfaceName, this.variants, List.of());
                    return Optional.of(changes);
                } catch (DBusException e) {
                    throw new RuntimeException(e);
                }
            }
            if (this.equals(old)) {
                return Optional.empty();
            }
            var oldVariant = old.variants();
            var newVariant = this.variants();
            var diffs = diff(oldVariant, newVariant);
            if (skipPosition) {
                diffs.remove("Position");
            }
            if (diffs.isEmpty()) {
                return Optional.empty();
            } else {
                try {
                    var changes = new PropertiesChanged(objectPath, interfaceName, diffs, List.of());
                    return Optional.of(changes);
                } catch (DBusException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        // Returns the map with the entries that were added or changed
        public static Map<String, Variant<?>> diff(
                Map<String, Variant<?>> oldMap,
                Map<String, Variant<?>> newMap
        ) {
            var map = new HashMap<String, Variant<?>>();
            for (Map.Entry<String, Variant<?>> newValEntry : newMap.entrySet()) {
                var oldVal = oldMap.get(newValEntry.getKey());
                var newVal = newValEntry.getValue();
                var newValue = newVal.getValue();
                if (newValue == null) {
                    map.put(newValEntry.getKey(), newValEntry.getValue());
                } else if (!oldMap.containsKey(newValEntry.getKey()) || !newVal.equals(oldVal)) {
                    map.put(newValEntry.getKey(), newValEntry.getValue());
                }
            }
            return map;
        }

        @Override
        public <A> A Get(String _interfaceName, String _propertyName) {
            log.info("MprisController.Get<> {} {}", _interfaceName, _propertyName);
            //noinspection unchecked
            return (A) this.variants.get(_propertyName);
        }

        @Override
        public <A> void Set(String _interfaceName, String _propertyName, A _value) {
            log.warn("MPRISPlayerState: ignoring Set {} {} {} ", _interfaceName, _propertyName, _value);
        }

        @Override
        public Map<String, Variant<?>> GetAll(String _interfaceName) {
            return this.variants;
        }

        @Override
        public String getObjectPath() {
            return objectPath;
        }
    }

    // See https://specifications.freedesktop.org/mpris-spec/2.2/Track_List_Interface.html#Mapping:Metadata_Map
    public record MPRISMetadata(
            TrackId trackId,
            Duration length,
            List<String> artist,
            List<String> albumArtist,
            String album,
            String title,
            @Nullable Long discNumber,
            @Nullable URI artUrl,
            @Nullable String lyrics
    ) {

        public Map<String, Variant<?>> variant() {
            var map = new HashMap<String, Variant<?>>();
            map.put("mpris:trackid", ofVariant(trackId.trackid()));
            map.put("mpris:length", ofVariant(toMicroseconds(length)));
            map.put("xesam:artist", toListVariant(artist));
            map.put("xesam:albumArtist", toListVariant(albumArtist));
            map.put("xesam:album", ofVariant(album));
            map.put("xesam:title", ofVariant(title));
            if (discNumber != null) {
                map.put("xesam:discNumber", ofVariant(discNumber));
            }
            if (artUrl != null) {
                var artUri = artUrl.toString();
                if (artUri.startsWith("file:" ) && !artUri.startsWith("file://")) {
                    artUri = artUri.replace("file:", "file://");
                };
                map.put("mpris:artUrl", ofVariant(artUri));
            }
            if (lyrics != null) {
                map.put("xesam:asText", ofVariant(lyrics));
            }
            // TODO: xesam:url is a url back to e.g the youtube video or a direct link to the media content
            //map.put("xesam:url", ofVariant(lyrics));
            return map;
        }
    }

    public static class MprisApplicationProperties implements Properties {
        private static final String dbusInterfaceName = "org.mpris.MediaPlayer2";
        private final boolean canQuit = true;
        private final boolean fullscreen = false;
        private final boolean canSetFullscreen = false;
        private final boolean canRaise = true;
        private final boolean hasTrackList = false;
        private final String identity = "Subsound";
        private final String desktopEntry = Constants.APP_ID;
        private final List<String> supportedUriSchemes = List.of(
                //"file", "https" // TODO(mpris)
        );
        private final List<String> supportedMimeTypes = List.of(
                //"audio/mpeg", "audio/mp3"// TODO(mpris)
        );
        private final Map<String, Variant<?>> variants;

        public MprisApplicationProperties() {
            this.variants = Map.of(
                    "CanQuit", ofVariant(canQuit),
                    "Fullscreen", ofVariant(fullscreen),
                    "CanSetFullscreen", ofVariant(canSetFullscreen),
                    "CanRaise", ofVariant(canRaise),
                    "HasTrackList", ofVariant(hasTrackList),
                    "Identity", ofVariant(identity),
                    "DesktopEntry", ofVariant(desktopEntry),
                    "SupportedUriSchemes", toListVariant(supportedUriSchemes),
                    "SupportedMimeTypes", toListVariant(supportedMimeTypes)
            );
        }

        @Override
        public <A> A Get(String _interfaceName, String _propertyName) {
            log.info("{}: Get {}/{}", interfaceName, _interfaceName, _propertyName);
            //noinspection unchecked
            return (A) GetAll(_interfaceName).get(_propertyName).getValue();
        }

        @Override
        public <A> void Set(String _interfaceName, String _propertyName, A _value) {
            log.info("{}: Set {}/{} value={}", interfaceName, _interfaceName, _propertyName, _value);
        }

        @Override
        public Map<String, Variant<?>> GetAll(String interfaceName) {
            return this.variants;
        }

        @Override
        public String getObjectPath() {
            return "/org/mpris/MediaPlayer2";
        }
    }
}
