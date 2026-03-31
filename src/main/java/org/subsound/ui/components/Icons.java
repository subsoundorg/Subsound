package org.subsound.ui.components;

// https://specifications.freedesktop.org/icon-naming-spec/latest/
// https://gitlab.gnome.org/GNOME/adwaita-icon-theme/-/tree/master/Adwaita/symbolic?ref_type=heads
public enum Icons {
    GoHome("go-home"),
    GoHomeSymbolic("go-home-symbolic"),
    UserHome("user-home"),
    UserHomeSymbolic("user-home-symbolic"),
    ContentLoading("content-loading"),
    ContentLoadingSymbolic("content-loading-symbolic"), // looks like three horizontal dots
    Starred("starred"),
    StarredSymbolic("starred-symbolic"),
    AddStar("star-new"),
    AddStarSymbolic("star-new-symbolic"),
    NetworkServer("network-server"),
    FolderRemote("folder-remote"),
    PLAY("media-playback-start-symbolic"),
    PAUSE("media-playback-pause-symbolic"),
    SkipBackward("media-skip-backward-symbolic"),
    SkipForward("media-skip-forward-symbolic"),
    PlaylistRepeat("media-playlist-repeat-symbolic"),
    PlaylistRepeatSong("media-playlist-repeat-song-symbolic"),
    PlaylistShuffle("media-playlist-shuffle-symbolic"),
    PlaylistConsecutive("media-playlist-consecutive-symbolic"),
    VolumeHigh("audio-volume-high-symbolic"),
    VolumeMedium("audio-volume-medium-symbolic"),
    VolumeLow("audio-volume-low-symbolic"),
    VolumeMuted("audio-volume-muted-symbolic"),
    VolumeControl("multimedia-volume-control-symbolic"),
    NetworkOffline("network-offline-symbolic"),
    RefreshView("view-refresh-symbolic"),
    Search("system-search-symbolic"),
    SearchEdit("edit-find-symbolic"),
    Artist("system-users-symbolic"),
    Albums("drive-multidisk-symbolic"),
    Playlists("view-list-symbolic"),
    ARTIST_ALBUM("avatar-default-symbolic"),
    Music("folder-music-symbolic"),
    Recent("document-open-recent-symbolic"),
    AlbumPlaceholder("media-optical-cd-audio-symbolic"),
    SettingsOld("settings-symbolic"),
    Settings("emblem-system-symbolic"),
    OpenMenu("view-more-symbolic"),
    ListAdd("list-add-symbolic"),
    ListRemove("list-remove-symbolic"),
    FolderDownload("folder-download-symbolic"),
    NetworkError("network-error-symbolic"),
    CheckmarkCircle("checkmark-circle-symbolic"),
    ;

    private final String iconName;
    Icons(String iconName) {
        this.iconName = iconName;
    }

    public String getIconName() {
        return iconName;
    }
}
