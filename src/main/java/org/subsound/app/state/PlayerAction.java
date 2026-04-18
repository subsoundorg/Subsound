package org.subsound.app.state;

import org.jspecify.annotations.NonNull;
import org.subsound.integration.ServerClient;
import org.subsound.integration.ServerClient.ObjectIdentifier;
import org.subsound.integration.ServerClient.SongInfo;
import org.subsound.ui.components.ServerConfigForm.SettingsInfo;
import org.subsound.ui.models.GSongInfo;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public sealed interface PlayerAction {
    record Play() implements PlayerAction {}
    record Pause() implements PlayerAction {}
    record PlayPrev() implements PlayerAction {}
    record PlayNext() implements PlayerAction {}
    enum PlayMode {
        NORMAL,
        SHUFFLE,
        REPEAT_ONE,
        //REPEAT_ALL,
    }
    record SetPlayMode(PlayMode mode) implements PlayerAction {}
    record SeekTo(Duration position) implements PlayerAction {}
    record SeekRelative(Duration offset) implements PlayerAction {}
    record QueueSlot(String id, SongInfo song) {}

    record PlayAndReplaceQueue(ObjectIdentifier playContext, List<QueueSlot> queue, int position) implements PlayerAction {
        @Override
        public @NonNull String toString() {
            return "PlayAndReplaceQueue(position=%d, size=%d)".formatted(position, this.queue.size());
        }
    }
    record PlayPositionInQueue(int position) implements PlayerAction {}
    record PlaySong(SongInfo song, boolean startPaused) implements PlayerAction {
        public PlaySong(SongInfo song) {
            this(song, false);
        }
    }
    record Enqueue(SongInfo song) implements PlayerAction {}
    record EnqueueLast(SongInfo song) implements PlayerAction {}
    record RemoveFromQueue(int position) implements PlayerAction {}
    record Star(SongInfo song) implements PlayerAction {}
    record Star2(GSongInfo song) implements PlayerAction {}
    record StarRefresh(boolean forced) implements PlayerAction {}
    record Unstar(SongInfo song) implements PlayerAction {}
    record AddToPlaylist(SongInfo song, String playlistId, String playlistName) implements PlayerAction {}
    record RemoveFromPlaylist(SongInfo song, int originalPosition, String playlistId, String playlistName) implements PlayerAction {}
    record AddManyToPlaylist(List<GSongInfo> songs, String playlistId, String playlistName) implements PlayerAction {}
    record AddToDownloadQueue(SongInfo song) implements PlayerAction {}
    record AddManyToDownloadQueue(List<GSongInfo> songs) implements PlayerAction {}
    record CreatePlaylist(String playlistName, List<GSongInfo> songs) implements PlayerAction {}
    record DeletePlaylist(String playlistId) implements PlayerAction {}
    record RenamePlaylist(String playlistId, String newName) implements PlayerAction {}
    record RefreshPlaylists() implements PlayerAction {}
    record TriggerServerScan() implements PlayerAction {}

    // not strictly player actions:
    record SaveConfig(SettingsInfo next) implements PlayerAction {}
    record SaveTranscodeFormat(ServerClient.TranscodeSettings audioFormat) implements PlayerAction {}
    record Toast(org.gnome.adw.Toast toast, Duration timeout) implements PlayerAction {
        public Toast(org.gnome.adw.Toast toast) {
            this(toast, Duration.ofMillis(2000));
        }
    }
    record SyncDatabase() implements PlayerAction {}
    record ClearSongCache() implements PlayerAction {}
    record ClearThumbnailCache() implements PlayerAction {}
    record OverrideNetworkStatus(Optional<NetworkMonitoring.NetworkStatus> overrideStatus) implements PlayerAction {}
    record Quit() implements PlayerAction {}
    record Logout() implements PlayerAction {}
}
