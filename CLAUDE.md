# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

OpenTV is a free, GPL-3.0 IPTV player for Android TV, Fire TV, phones and tablets: one APK, one Gradle
module (`:app`), Kotlin + Jetpack Compose, Room, Media3/ExoPlayer, OkHttp, coroutines. D-pad first, but
it must also work by touch.

## Commands

JDK 17 and Android SDK API 35 are required.

```bash
./gradlew test                     # all unit tests (what CI runs), no device needed
./gradlew :app:testDebugUnitTest --tests 'app.opentv.M3uParserTest'          # one class
./gradlew :app:testDebugUnitTest --tests 'app.opentv.M3uParserTest.duplicate*'  # methods by pattern (names are backticked sentences)
./gradlew assembleDebug            # APK in app/build/outputs/apk/debug/, applicationId app.opentv.debug
```

CI (`.github/workflows/build.yml`) runs only `./gradlew test` and `assembleDebug`. No linter or
formatter is configured beyond `kotlin.code.style=official`. Releases are cut by pushing a `v*` tag
(`release.yml`). Before tagging, bump `versionCode`/`versionName` in `app/build.gradle.kts` and add a
`CHANGELOG.md` entry. See `docs/RELEASING.md` for signing.

## Hard rules from the project

- **Clean-room only** (CONTRIBUTING.md): never copy from or decompile another IPTV app. Code comes from
  public specs (Xtream request format, M3U, XMLTV, Kodi catch-up tags) and observed behaviour.
- **No server of ours, ever.** Nothing may depend on a backend the project would have to run. Sync
  between devices is LAN-to-LAN or through the user's own NAS; the phone setup and web channel manager
  are HTTP servers running *on the TV* (`pairing/`). Rationale in `docs/ARCHITECTURE.md`.
- **Never log or surface provider credentials.** Xtream/M3U URLs embed username and password (an M3U
  `Source.url` is often `get.php?username=…&password=…`).
- **`XtreamApi` parses `JsonElement` by hand on purpose.** Panels return the same field as number,
  string, empty or missing. Do not convert it to `@Serializable` models.
- Comment style: explain *why* — especially why the obvious implementation was rejected (see
  `EpgRepository`, `PlayerController`, `CatalogRepository`). Don't restate the code.

## Architecture

`docs/ARCHITECTURE.md` covers the founding decisions: no backend, the EPG that never deletes before it
has a replacement, UTC-only times, and the SQLite bound-variable workarounds. It predates Stalker, VOD
from playlists and sync. The current shape:

- **`core/ServiceLocator`**: the whole dependency graph in one file (no Hilt, deliberately).
  - Screens get `ServiceLocator.get(app)` in `AndroidViewModel`s, almost all in `ui/OpenTvViewModels.kt`.
  - There are two OkHttp clients. The catalogue client has a 5-minute call timeout; the streaming
    client has none, because a live stream is an endless response.
- **`core/AppSettings`**: SharedPreferences mirrored into `StateFlow`s, process-wide singleton.
  - Includes the content-type toggles (`liveEnabled` / `moviesEnabled` / `seriesEnabled`), which both
    hide tabs (`ui/MainScreen.kt`) and skip syncing that type (`CatalogRepository.sync`).
- **`core/StatusBus`**: app-wide "what's happening" line plus progress. Background jobs announce
  themselves through it; the main screen shows it.
- **Sources** (`data/model/Source`, `SourceKind`): `XTREAM`, `M3U`, `STALKER`.
  - Xtream and Stalker use API clients in `data/remote/`.
  - Stalker channels store a `cmd`, which `CatalogRepository.resolvePlaybackUrl` turns into a
    short-lived URL at tune time.
  - TMDB and Stremio clients are optional, used only when the user supplies a key or add-on URL.
- **`CatalogRepository`**: channels, categories, movies, series, episodes.
  - `syncLive` is the fast path at onboarding. Xtream VOD loads lazily when the Movies/Shows tab
    opens (`VodViewModel.ensureVodLoaded`, 12-hour TTL); Xtream episodes load when a show is opened.
  - An M3U is one download, so `syncM3u` stores everything at once.
    - `M3uParser.kindOf` sorts entries by Xtream path (`/movie/…`, `/series/…`, otherwise live).
    - Episodes are grouped into shows by `SxxEyy`.
    - `get.php?type=m3u` URLs are fetched as `m3u_plus` first, with a fallback to the URL as given.
- **User state survives refreshes by stable keys, not row ids.**
  - `ChannelDao.replaceCatalogue` carries favourites, hidden, order, custom names and EPG overrides
    by `streamId`; M3U ids come from `tvg-id`, or an FNV-1a hash of the URL.
  - VOD `replaceForSource` keeps row ids, because resume positions are keyed `movie:<id>` / `ep:<id>`.
  - Stale rows are pruned by a sync stamp (`lastSeenMillis`) or by id lists in chunks, never
    `NOT IN (:hugeList)`.
- **Channel identity** (`data/parser/ChannelNameNormalizer`): turns `UK| BBC ONE FHD` into
  `displayName`, `groupKey`, quality rank/label and region.
  - `groupKey` both folds quality variants into one row and is the EPG join key.
  - These fields are computed at import. When the normaliser changes, bump
    `CatalogRepository.NORMALIZER_VERSION`; `OpenTvApp` re-normalises stored rows once, locally.
  - When playlist import changes what lands where, bump `PLAYLIST_LAYOUT_VERSION`; that re-downloads
    playlists once.
- **`EpgRepository`**: feeds of three kinds (provider, built-in free, user URL) merged into one guide.
  - `syncAll` runs on every launch: `ensureFeeds`, then auto-enabling a regional built-in by sampling
    channel names, then the download, then `runMatcher`.
  - `EpgMatcher` joins provider channels to guide channels by normalised name and prefers *no* match
    to a wrong one; ambiguous prefixes are refused.
  - A channel's guide ids are tried in this order: user override, then provider `tvg-id`, then
    matcher result (`Channel.epgCandidates`). The UI uses the first id that has programmes.
- **Background work**: `data/work/SyncWorker` refreshes the catalogue and guide every 6 hours
  (WorkManager); `refreshNow` runs a one-shot refresh.
  - Sync (`sync/`): `SyncEngine` is the single mapping between tables and the portable `SyncBundle`.
    Positions are newest-wins; curation is applied additively. `NasSync` swaps bundles through a
    folder on the user's own SMB share (`SmbClient`, connection stored in `AppSettings.smb*`).
- **Playback**: `PlayerController` owns one ExoPlayer per screen and swaps media items.
  - Channel switches are debounced and `stop()` the player instead of releasing it, so fast zapping
    costs one tune.
  - 403, 429 and 5xx are retried with backoff; 401, 404 and 410 are not.
  - `PlaybackErrors` turns failures into sentences that tell the user what to do.
- **UI**: a single activity with a Navigation-Compose graph (`Routes` in `MainActivity.kt`).
  - `MainScreen` is a nav rail plus tabs: `HomeScreen` (live guide), `MoviesScreen`/`SeriesScreen`
    (`ui/vod/`).
  - The activity is edge-to-edge. The players apply their own insets; `MainScreen` pads by
    `safeDrawing`.
  - Self-update (`update/UpdateChecker`) polls GitHub releases of `REPO_SLUG` (`opentvproject/opentv`).

## Scale pitfalls (real playlists reach 300,000+ entries on weak TV boxes)

- Android fills a cursor in 2 MB windows and re-runs the query from row 0 for each window, so reading
  a huge table in one query is quadratic.
  - Page by id with narrow projections: `WHERE id > :afterId ORDER BY id LIMIT :n` (see
    `ChannelDao.matchKeysAfter`).
  - `ChannelDao.allForMatching()` loads every column of every channel; avoid adding callers.
- Every write transaction invalidates the table and re-runs every Flow query on it. Batch writes in
  `@Transaction` chunks (`BATCH_SIZE`/`UPSERT_CHUNK` = 500) instead of writing row by row.
- A Flow read spanning several cursor windows can hit `SQLiteBlobTooBigException` when a refresh
  shrinks the table mid-read. List flows in `CatalogRepository` wrap DAO flows in `retryIfTableShrank()`.
- Parsers stream (`M3uParser` line by line, `XmltvParser` via `XmlPullParser`); never load a playlist
  or guide into one String.
- Bulk VOD writes use `@Insert(REPLACE)` with ids already set. `@Upsert` pays a caught constraint
  exception for every row that already exists.

## Database

Room, `OpenTvDatabase`, WAL mode. Exported schemas are committed in `app/schemas/`.
- A schema change needs a version bump and a `Migration` added to the list in `OpenTvDatabase.build`.
  `fallbackToDestructiveMigration()` is still on (pre-1.0 policy) but slated for removal.
- Domain models double as entities (`data/model/Models.kt`). Room stores enums by name.

## Tests

Unit tests live in `app/src/test/java/app/opentv/`, using JUnit4 and Truth.
- Parsers and pure logic are plain JVM tests.
- Anything that needs Room or Android uses Robolectric with
  `@Config(sdk = [34], application = android.app.Application::class)`.
  - There is no SDK 35 Robolectric image.
  - The plain `Application` keeps `OpenTvApp` from scheduling WorkManager.
  - Build an in-memory Room database with `allowMainThreadQueries()`.
- HTTP is faked with an OkHttp interceptor (see `PlaylistSyncTest`); there is no MockWebServer
  dependency.
- `AppSettings` is a process singleton, so tests that change a toggle must restore it.

## Strings

English is in `res/values/strings.xml`, with 30 translated `values-xx` folders. Translations lag the
English file, and missing keys fall back to English, so a new string only has to be added to `values/`.
