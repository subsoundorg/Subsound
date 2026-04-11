# CLAUDE.md

Subsound is a gtk4 only streaming music player.
It is written in Java, and is a client for the subsonic streaming music server, but primarily with Navidrome in mind.
We try to keep as much offline access support as reasonably possible.

GTK4 is used via the Java FFM package java-gi which has bindings to GTK, Glib and GStreamer.

## Database

We only use SQLITE for local database.
All sqlite migrations must be versioned.
Database code is written in the database package.

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Subsound is a GTK4/Adwaita streaming music player written in Java, primarily targeting Navidrome (Subsonic-compatible) servers. It uses java-gi FFM bindings for GTK4, GLib, and GStreamer. Dark mode only. Offline access is a goal.

## Documentation

Subsonic API docs:
 - https://www.subsonic.org/pages/api.jsp#versions
 - XSD Schema: https://www.subsonic.org/pages/inc/api/schema/subsonic-rest-api-1.16.1.xsd
 - https://opensubsonic.netlify.app/docs/api-reference/


## Build & Run Commands

```bash
./gradlew compileJava        # Compile
./gradlew test               # Run all tests
./gradlew test --tests "org.subsound.persistence.database.DatabaseServerServiceTest"  # Single test class
./gradlew run                # Run the application
./gradlew shadowJar          # Build fat JAR (output: build/libs/*-final.jar)
```

Requires Java 25. JVM flag `--enable-native-access=ALL-UNNAMED` is configured automatically for run/test tasks.

No linter or formatter is configured.

## Architecture

### Startup Flow

`Main` → initializes GStreamer, Config, caches, ServerClient, PlaybinPlayer → creates `AppManager` → starts MPRIS D-Bus controller → `MainApplication` builds GTK UI.

### Key Subsystems

**AppManager** (`app/state/AppManager.java`) — Central state container. Holds a `BehaviorSubject<AppState>` (RxJava3) with immutable record-based state. State updates via `setState(old -> old.withX(...))` with 100ms throttled listener notifications. Owns the `ServerClient`, `PlayQueue`, `SongCache`, `ThumbnailCache`, `DownloadManager`, and `DatabaseServerService`. The `handleAction(PlayerAction)` switch is the main dispatch for all UI-initiated actions.

**PlayerAction** (`app/state/PlayerAction.java`) — Sealed interface with record variants for every user action (Play, Pause, Enqueue, Star, AddToDownloadQueue, etc.). UI code dispatches these via `appManager::handleAction`.

**ServerClient** (`integration/ServerClient.java`) — Interface abstracting the Subsonic API. Contains all shared data model records: `SongInfo`, `AlbumInfo`, `ArtistInfo`, `PlaylistSimple`, `CoverArt`, `TranscodeInfo`, etc. `SubsonicClient` implements it using `net.beardbot:subsonic-client`. 
`CachingClient` is a pass-through decorator, but also falls back to the database for offline access if enabled or the server is unreachable.

**PlaybinPlayer** (`sound/PlaybinPlayer.java`) — GStreamer playbin2 wrapper. Manages audio playback, position tracking via virtual thread, and state change notifications.

**Persistence** (`persistence/`) — `SongCache` handles HTTP download + disk caching of audio. `ThumbnailCache` uses Caffeine for in-memory + disk art caching. `DownloadManager` runs a background queue processor (polls every 5s). `Database` uses SQLite with versioned migrations (`MigrationV1` through `MigrationV6`).

**UI** (`ui/`) — GTK4 + Libadwaita via java-gi. `MainApplication` builds the shell: `ViewStack` for tabbed pages (Home, Starred, Playlists, Artists), `PlayerBar` at the bottom, `NavigationView` for drill-down. Views use `FutureLoader` for async data loading with loading spinners. Lists use `ListStore<GObject>` + `ListView` with factory-bound rows.

### Data Flow Pattern

UI creates a `PlayerAction` record → `AppManager.handleAction()` switches on it → updates state / calls server / modifies queue → `BehaviorSubject` emits new `AppState` → listeners update UI on main thread via `Utils.runOnMainThread()`.

### Offline Mode

Offline data is built around the `SongCache`, which caches the actual music to disk, 
and the `CachingClient` decorator which transparently serves stored metadata.
If the server is unreachable, or we are in offline mode, 
the client can load data from database instead of directly from the server.

### Async Patterns

- **Virtual threads**: `Executors.newVirtualThreadPerTaskExecutor()` for I/O (default async executor)
- **`Utils.doAsync()`**: Wraps a supplier in `CompletableFuture.supplyAsync` on the virtual thread executor
- **`Utils.runOnMainThread()`**: Schedules work on the GLib main loop for GTK thread safety
- **`CompletableFuture` chaining**: Used extensively for loading views (`thenApply`, `thenCombine`)

### Key Java Patterns

- **Sealed interfaces + records** for actions, navigation routes, and data models
- **`@RecordBuilder` / `@RecordBuilderFull`** annotation processor generates `.withX()` copy methods on records (from `io.soabase.record-builder`)
- **GObject subclasses** (`GSongInfo`, `GQueueItem`) with `@Property` annotations for GTK list model binding

## Database

SQLite only. All migrations are versioned in `Database.java` as inner classes (`MigrationV1` through `MigrationV6`). New migrations must increment the version number. Database code lives in the `persistence/database/` package.

## Navigation

`AppNavigation.AppRoute` is a sealed interface with record variants (`RouteAlbumInfo`, `RouteArtistInfo`, `RoutePlaylistsOverview`, etc.). UI navigates by calling `appManager.navigateTo(route)`.

## Synthetic Playlists

The playlists view prepends synthetic entries (Starred, Downloaded) to the server's playlist list. These use `PlaylistKind` enum values (`STARRED`, `DOWNLOADED`) and are handled specially in `PlaylistsListView.setSelectedPlaylist()`.

# Code style guide

- primarily google style guide: https://google.github.io/styleguide/javaguide.html
- _ALL_ if/for/while/do/switch statements must always be braced with curly braces

## GTK4

Adwaita theme is used. Adwaita is the default theme in GNOME. Prefer to use Adwaita components and UI elements over plain GTK4 elements when relevant.

The app is designed for dark mode.

Gtk4 helper docs:
https://api.pygobject.gnome.org/Gtk-4.0/classes.html

### References

https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/widget-gallery.html

https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/class.AlertDialog.html

#### Original Subsonic api spec XSD
https://www.subsonic.org/pages/inc/api/schema/subsonic-rest-api-1.16.1.xsd

#### Lyrics API
Uses LRCLIB: https://lrclib.net/docs

# GTK4 Widgets
https://docs.gtk.org/gtk4/

## GTK Constraint Guide
https://docs.gtk.org/gtk4/class.ConstraintGuide.html

## GTK4 Widget Gallery
https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/widget-gallery.html

## Adwaita Styles
https://gnome.pages.gitlab.gnome.org/libadwaita/doc/main/style-classes.html