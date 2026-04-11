# subsound-gtk

Subsonic compatible player in GTK4 / Adwaita

Best used with [Navidrome](https://github.com/navidrome/navidrome).

## Screenshots

A few samples of what the UI looks like:

![Artists listing](screenshots/artistsv3.png)

![Playlists view](screenshots/starredv7.png)

![Search modal ( Ctrl+K )](screenshots/searchv2.png)

## Installation

Pending a Flatpak release, you can run from source or use the Flatpak beta release:

```bash
# Add remote (one-time)
flatpak remote-add --user --no-gpg-verify subsound-beta https://subsoundorg.github.io/subsound-gtk-repo/

# Install
flatpak install --user subsound-beta io.github.subsoundorg.Subsound

# Run
flatpak run --user io.github.subsoundorg.Subsound

# Update
flatpak update --user io.github.subsoundorg.Subsound
```

## Features

Features:
- [X] Local music cache
- [X] Local artwork cache
- [X] Transcoding music
- [X] Onboarding UI
- [X] Configuration UI
- [X] Starred listing
- [X] Browse albums
- [X] Browse artists
- [X] Fast Search UI with ctrl+k
- [X] MPRIS support
- [X] Offline mode
  - [X] Force Offline/Online mode
  - [X] Offline mode detection/tracking
  - [X] Download songs to local cache
  - [X] Play songs from local cache
  - [X] Download album art to local cache
  - [X] Sync artist/song metadata for offline storage
  - [X] Playlists
  - [X] Scrobble offline, send later
  - [X] Browse from offline storage only
  - [ ] Search from offline storage only or disable search box
  - [X] Download manager for offline available content
    - This kind of already works, but there is no UI that shows status for each item

Later goals:
 - [ ] Internationalize

Potential goals:
 - [ ] Lyrics support
 - [ ] support multiple server types (native Navidrome API, OpenSubsonic, Jellyfish etc)
 - [ ] make it look OK in light mode?
 - [ ] Chromecast support
 - [ ] support the subsonic podcast features
 - [ ] consider using fanart.tv
 - [ ] support embedded image tags? https://github.com/neithern/g4music/blob/bf80b5cad448a57c635f01d0a315671fef045d14/src/gst/tag-parser.vala#L99

Non-goals:
 - Video support  
 - Jukebox support

Possible ideas:
  - Shared remote control, think something like Spotify Connect
  - Chromecast support
  - Player for local media, not just for a streaming server

## Credits

Vectors and icons by <a href="https://www.svgrepo.com" target="_blank">SVG Repo</a>
