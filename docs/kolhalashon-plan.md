# Kol Halashon shiurim in Music Radio - integration plan

Target: play a rav's shiurim inside Music Radio the way the app already plays radio, but with the
things on-demand audio needs and a live stream does not - a position, a seek bar, resume where you
stopped, and a list you can browse.

First rav: **הרב אלימלך בידרמן, `ravId = 674`** (5852 shiurim per
`Ravs/WebSite_GetRavShiurimCount/674/-1`). The design carries more ravs without change; only the
catalog entry is per-rav.

Sources: `C:/dev/kolhalashon-api/API-SPEC.md`, `kolhalashon-kmp` (the client this app will use),
`kolhalashon-node` (same surface, not used here).

---

## 1. What the codebase gives us today, and what it does not

Read before planning; each of these decided something below.

| Fact | Where | Consequence |
| --- | --- | --- |
| `RadioPlayer` has **no position, duration or seek** - "deliberately absent - every source here is a continuous broadcast with neither" | `player/RadioPlayer.kt` | The single largest change. Everything else is additive; this is a change to an interface with three implementations. |
| The backend **can** seek: `seekTo(long)`, `currentPosition()`, `currentDuration()`, `setRate(float)` | `composemediaplayer-audio` 0.11.4, verified with `javap` | No new audio dependency is needed on desktop or web. |
| Android does not use that backend at all - it drives a `MediaController` bound to `PlaybackService` | `player/MediaSessionRadioPlayer.kt` | Android gets seek for free (`controller.seekTo`, `setMediaItem(item, startMs)`), and it is the only backend where the OS notification must also grow a seek bar. |
| `StreamRadioPlayer` already polls the backend every 250 ms | `player/StreamRadioPlayer.kt` | Position reporting rides the existing loop; no second timer. |
| The catalog is static data - `Station` holds a fixed `List<Channel>`, each with a literal `streamUrl` | `domain/Stations.kt` | A rav cannot be a `Station`. It is a different kind of thing and needs its own type. |
| Persistence is one flat `key=value` file rewritten whole on every change | `data/SnapshotCodec.kt`, `data/AppStore.kt` | Per-shiur progress must **not** live there: it changes every few seconds and would rewrite the settings file with it. |
| Single-state MVI - one `AppState`, one `onIntent`, `reduce` is exhaustive over `AppIntent` | `app/AppViewModel.kt` | Every new intent must be added to `reduce` and `afterReduce`, or the build breaks. That is a feature; follow it. |
| Ktor is already a dependency in `commonMain`, with per-platform engines | `shared/build.gradle.kts`, `platform/HttpClientFactory.kt` | `KolHalashonClient` can borrow the app's `HttpClient` instead of opening a second pool. |
| Four targets: android, jvm, js, wasmJs | `shared/build.gradle.kts` | `kolhalashon-kmp` publishes exactly those four. |
| Media keys and the OS flyout already route through `AppIntent` | `bindMediaControls()` | Next/Previous need to mean "next shiur" in shiur mode and "next station" in radio mode - one branch, not a second control path. |
| ICY metadata is polled every 20 s while a stream is active | `watchNowPlaying()` | Must be skipped for shiurim; an MP3 has no ICY and the poll is a wasted request against a Cloudflare-fronted host. |
| Every user-visible string exists in `values`, `values-fr`, `values-he`, `values-iw` | `composeResources/` | Four files per label, `values-iw` generated from `values-he` by the build. |

---

## 2. Concept: a rav is a station of a different kind

A radio station is *one endless URL*. A rav is *a catalog with a cursor into it*. Modelling the
second as the first is what would make this feature ugly, so it is not attempted.

What the user sees:

- In the stations grid, under **Torah**, a card for the rav - same size, same artwork treatment, so
  it reads as part of the app.
- Tapping it does **not** start a stream. It opens the **Rav screen** and, in the background,
  resolves and starts the right shiur (section 4). By the time the screen has drawn, audio is coming.
- The Rav screen is the explorer: continue / all shiurim / folders.
- The player screen is the radio player plus everything a live stream cannot have: a seek bar,
  elapsed and remaining, plus/minus 15 s, previous and next shiur.

What the code sees: `PlaybackSource` becomes a two-case sealed type - `Radio(channelId)` or
`Shiur(ravId, fileId)`. Every existing behaviour keys on `Radio`; nothing about the radio path
changes shape.

---

## 3. Architecture, layer by layer

### 3.1 The client library

`kolhalashon-kmp` is at `dev.kdroid:kolhalashon:0.1.0`, targets android/jvm/js/wasmJs - an exact
match for this app.

**It is missing one call this plan needs**: listing shiurim inside a folder. The DTO is identical to
`ravShiurim`; only the path and the meaning of `GeneralID` change
(`Search/WebSite_GetShiurimUnderNode`, `GeneralID = folderId` - API-SPEC 3.8, 6.1). Add to the
library and cut **0.2.0**:

```kotlin
public suspend fun shiurimUnderFolder(
    folderId: Int,
    fromRow: Int = 0,
    rowsPerPage: Int = 24,
    order: SearchOrder = SearchOrder.NEWEST_FIRST,
    language: ShiurLanguage = options.language,
): ShiurPage
```

Optional, for the language chips in 4.1: `ravSubjects(ravId): List<FilterItem>`, reading
`ResultType == 3` rows as the languages the rav actually has
(`Search/WebSite_GetRavSubjectsIDs`, API-SPEC 6). Without it, hard-code Hebrew + Yiddish + All for
Biderman and add the call in a later milestone.

**How the app depends on it.** Three options, and the honest trade:

| | Works locally | Works in CI | Cost |
| --- | --- | --- | --- |
| `includeBuild("C:/dev/kolhalashon-api/kolhalashon-kmp")` | yes, instant iteration | **no** - the path does not exist on the runner | free |
| `publishToMavenLocal` + `mavenLocal()` | yes | **no** | a publish per change |
| JitPack `com.github.kdroidFilter:kolhalashon-kmp:0.2.0` | yes | yes | needs the repo public and tagged |

Recommendation: **JitPack in the committed build**, with the composite build behind a Gradle
property so local work stays fast:

```kotlin
// settings.gradle.kts
if (providers.gradleProperty("kolhalashon.local").orNull == "true") {
    includeBuild("../../kolhalashon-api/kolhalashon-kmp")
}
```

**Configuration.** One client for the app's lifetime, sharing the app's `HttpClient`:

```kotlin
KolHalashonClient(
    options = KolHalashonOptions(
        minRequestIntervalMillis = 1_000,  // Cloudflare: serialize, do not burst
        maxConcurrency = 1,
        retries = 2,
    ),
    httpClient = appHttpClient,            // one connection pool, keep-alive preserved
)
```

Bound in Metro next to `AppStore` and `RadioPlayer`.

### 3.2 Domain: `domain/Ravs.kt`

```kotlin
@Immutable
data class Rav(
    val id: Int,                                // 674
    val name: StringResource,                   // localised, like Station.name
    val artwork: DrawableResource,              // bundled, see below
    val defaultLanguages: List<ShiurLanguage>,  // the chips shown before facets are fetched
)

object Ravs {
    val all: List<Rav> = listOf(
        Rav(674, Res.string.rav_biderman, Res.drawable.rav_biderman,
            listOf(ShiurLanguage.HEBREW, ShiurLanguage.YIDDISH)),
    )
    fun of(id: Int): Rav? = all.firstOrNull { it.id == id }
}
```

**Artwork: bundle it, do not fetch it.** The portrait is at
`https://www.kolhalashon.com/imgs/Ravs/0674.jpg`, but fetching it needs a KMP image loader the app
does not have (Coil 3), plus a CORS answer on web, and the OS media center wants a local file
anyway - which is exactly what `mediaArtworkUri(id, DrawableResource)` already produces. A bundled
drawable costs about 40 KB and makes the notification, lock screen, SMTC and MPRIS paths work with
zero new code.

### 3.3 Player: the seekable `RadioPlayer`

The one breaking change, kept as small as it can be. Live streams keep answering `seekable = false`
and `durationMs = 0`, so nothing on the radio path changes on screen.

```kotlin
@Immutable
data class PlaybackProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,     // 0 = unknown or live
    val seekable: Boolean = false,
)

interface RadioPlayer {
    val status: StateFlow<PlaybackStatus>
    val progress: StateFlow<PlaybackProgress>                                // new

    fun play(url: String, startAtMs: Long = 0, seekable: Boolean = false)    // two new params, defaulted
    fun seekTo(positionMs: Long)                                             // new
    fun setRate(rate: Float) = Unit                                          // new, optional, default no-op
    // resume / pause / stop / setVolume / release / setNowPlaying unchanged
}
```

Per backend:

- **`StreamRadioPlayer`** (jvm + web). The 250 ms `sync()` loop already runs; add
  `backend.currentPosition()` / `currentDuration()` to it and publish `progress`. `startAtMs` needs
  a one-shot pending-seek field: Rodio cannot seek before the stream is open, so hold the value and
  fire `seekTo` on the first tick that reports a non-zero duration, then clear it. `seekable` is
  passed in by the caller, not sniffed - the app knows which of the two kinds it asked for.
- **`MediaSessionRadioPlayer`** (android). `startAtMs` maps straight onto
  `setMediaItem(item, startPositionMs)`; `seekTo` onto `controller.seekTo`. Position is not
  push-based in Media3, so add a 500 ms ticker on the main looper, running **only** while
  `seekable && status.active` - the radio path stays as free as it is now. Also make the
  `COMMAND_SEEK_*` commands available so the notification renders a scrubber.
- **`SilentRadioPlayer`** - track a fake position so tests and previews stay honest.

Nucleus `MediaControls` (desktop) also carries position on MPRIS/SMTC; wire it in M5, not before.

### 3.4 Data: progress, in its own file

`state.txt` stays as it is. A second file, `shiurim.txt`, holds the cursor:

```
674/1234567=845000,3612000,0,1755900000
674/1234512=3600000,3612000,1,1755813600
        ^rav/fileId  ^posMs  ^durMs ^finished ^updatedAtEpochSeconds
```

- Same decode discipline as `SnapshotCodec` - a malformed row is dropped, never fatal.
- **LRU-capped at 500 rows**, evicting oldest `updatedAt`. 500 rows is about 20 KB and covers years
  of listening; unbounded growth on a phone is not acceptable.
- Written **debounced**: every 5 s while playing, and immediately on pause, stop, shiur switch and
  `onCleared`. Not on every position tick.
- `state.txt` gains one key, `lastShiur=674/1234567`, so `resumeOnLaunch` covers shiurim too.

```kotlin
data class ShiurProgress(
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val updatedAt: Long,
)

interface ProgressStore {
    fun all(): Map<String, ShiurProgress>       // key = "$ravId/$fileId"
    fun put(key: String, progress: ShiurProgress)
    fun clear()
}
```

**Finished** is `finished == true` once set, or `durationMs > 0 && positionMs >= durationMs - 30_000`.
The flag is stored rather than recomputed because a shiur whose duration never resolved would
otherwise never count as done.

### 3.5 Settings: one shiur-language preference, global

Exactly as asked: the language is chosen once and applies to every rav.

```kotlin
// domain/AppData.kt, on UserSettings
val shiurLanguage: ShiurLanguage? = null,   // null = follow the app/device language
```

- `null` (the default) resolves from `settings.uiLanguage`: `he -> HEBREW(1)`, `en -> ENGLISH(2)`,
  `fr -> FRENCH(4)`. The app language is itself already `auto` by default, so a fresh install
  follows the device.
- Picking a chip on any rav screen writes an explicit value; it is global and immediate.
- Key `shiurLanguage` in `SnapshotCodec` (a setting that skips the codec silently forgets itself -
  see AGENTS.MD), plus a row in `SettingsScreen` so it is discoverable outside the rav screen.
- **Fallback rule**: if the resolved language returns zero shiurim for a rav, retry once with
  `ShiurLanguage.ANY` and show an inline note ("no shiurim in <language>, showing all"). Biderman in
  French would otherwise be an empty screen that looks like a bug.

### 3.6 Repository

`data/ShiurRepository.kt` - the only thing that talks to `KolHalashonClient`.

- Paged access keyed on `(ravId, language, folderId?)`, page size 24 (the site's own), prefetch when
  the list is 6 rows from the end.
- In-memory cache of loaded pages per key, dropped on language change.
- **Page 0 also persisted** to `cache/rav-674-1.json` with a 12 h TTL, for one reason worth the code:
  the audio URL is `files/GetMp3FileToPlay/{fileId}`, a pure function of the id, so a cold start with
  a cached page 0 and a progress row can **resume playback with no API call at all**. That is the
  single best hedge against a Cloudflare challenge.
- Errors are surfaced by kind, never flattened: `KolHalashonRateLimitedError` gives "the site is rate
  limiting, try again in a few minutes" and **no automatic retry** (retrying is what keeps a
  challenge alive - API-SPEC 1.3); `KolHalashonLockedError` gives a lock badge; anything else falls
  back to the existing generic failure message.

---

## 4. Behaviour, specified

### 4.1 Entering a rav - which shiur plays

```
onOpenRav(ravId):
  lang  = settings.shiurLanguage ?: fromUiLanguage(settings.uiLanguage)
  page  = repo.page(ravId, lang, fromRow = 0)            # NEWEST_FIRST, 24 rows
  if page.isEmpty && lang != ANY:
      page = repo.page(ravId, ANY, 0); note("falling back to all languages")

  candidates = page.items.filter { it.hasAudio && !it.isLocked }
  target = candidates.firstOrNull { !progress.isFinished(it) }   # newest unfinished
  if target == null and more pages exist:
      walk up to 4 more pages, then give up and take candidates.first()

  saved   = progress[target]
  startAt = if (saved == null) 0 else max(0, saved.positionMs - 10_000)
  player.play(target.audioUrl, startAtMs = startAt, seekable = true)
```

This one walk implements all three rules the feature was asked for:

- nothing heard yet - the newest shiur, from 0;
- started but not finished - that same shiur, **at the saved position minus 10 s**, floored at 0;
- newest already finished - skipped, and the next-newest unfinished one is taken instead, for as
  many as are finished.

**Ordering caveat, must be settled before this is built.** `SearchOrder = 7` is the site's own
default on the rav page and is what `kolhalashon-kmp` calls `NEWEST_FIRST`, but API-SPEC 3.3 is
explicit that *which id means newest is not established*. Verify in M0 by fetching two pages and
comparing `RecordDate`. If 7 is not newest-first, find the id that is, or sort client-side by
`recordDate` within the pages we hold.

### 4.2 Transport

| Control | Meaning |
| --- | --- |
| previous shiur | one step **up** the visible newest-first list, i.e. newer |
| next shiur | one step **down**, i.e. older - the direction "keep going" runs in |
| -15 s / +15 s | `seekTo(position -/+ 15_000)`, clamped to `0..duration` |
| long press on the skip buttons | continuous scrub, below |
| seek bar | drag to scrub, commit on release |
| play/pause | unchanged |

The interval is a constant (`SKIP_STEP_MS = 15_000`) - a setting for it is a later, cheap addition.

**Long press.** Handled entirely in the UI; the player is touched once, not sixty times:

```
on press:
  after 400 ms held, start ticking every 100 ms
  each tick: preview += step * direction
  step grows 1s -> 2s -> 5s -> 10s per full second held, capped at 10s
  (so about 10 minutes of audio in 6 seconds of holding)
  the scrubber and the time label follow `preview` live
on release:
  player.seekTo(preview); preview = null
```

Committing on release rather than per tick is what keeps this smooth on all three backends - sixty
`seekTo` calls a second would stutter on Rodio and thrash the buffer on ExoPlayer.

**Media keys and the OS notification.** `MediaCommand.Next` / `Previous` already route through
`AppIntent`; branch on `PlaybackSource` so they mean next/previous *shiur* in shiur mode and
next/previous *station* in radio mode. One branch in `bindMediaControls`.

### 4.3 Position bookkeeping

- Every `progress` emission with `seekable = true` updates in-memory state; the store is written on
  the debounce described in 3.4.
- On natural end (`position >= duration - 1s` with the backend idle), mark **finished**, then
  auto-advance to the next shiur down the list. That is what makes the rav feel like a station: it
  keeps playing.
- Switching shiur, pausing, stopping and `onCleared` all flush first.
- ICY polling is gated on `source is Radio`.

---

## 5. UI

### 5.1 Rav screen - `AppKey.Rav(ravId)`

```
+---------------------------------------------+
| <-  [portrait]  הרב אלימלך בידרמן            |
|                 5,852 שיעורים                |
|                 [ עברית ] [ אידיש ] [ הכל ]  |  <- language chips, global preference
+---------------------------------------------+
|   [ המשך ]     [ שיעורים ]     [ תיקיות ]    |  <- segmented
+---------------------------------------------+
|  +---------------------------------------+  |
|  | > continue: <title>                   |  |  <- the 4.1 pick, big card
|  |   14:05 / 60:12   #####.........      |  |
|  +---------------------------------------+  |
|  recently listened ...                      |
+---------------------------------------------+
```

- **שיעורים** tab: paged `LazyColumn`. Row = title, `RecordDate` as a Hebrew date, duration, a thin
  progress bar when partly heard, a check when finished, a lock badge when `isLocked`.
- **תיקיות** tab: `ravFolders(674)`, tap a folder, then `shiurimUnderFolder(folderId)` with the same
  row and a breadcrumb back. Folders that are `hiddenFromPhone` / `hiddenFromWeb` are filtered per
  platform - the fields are already on `RavFolder`.
- Loading is a shimmer over the list, not a spinner over the screen; the continue card is drawn from
  cache the moment it is known.

### 5.2 Player screen

Extend `NowPlayingScreen` rather than fork it - the artwork, title block and volume control are the
same, and forking means two places to fix a layout bug. Gate on `progress.seekable`:

```
              [ artwork ]
            הרב אלימלך בידרמן
              <shiur title>
            <date> - <folder>

    #######.....................
    14:05                 -46:07

     |<<   -15   play   +15   >>|
                [ 1.0x ]
```

Radio keeps exactly the layout it has now; every element above appears only under `seekable`.

### 5.3 Strings

Every new label lands in `values/`, `values-fr/`, `values-he/` (`values-iw` is generated). Roughly:
rav name, the three tab labels, language chip labels, "continue listening", "finished",
"locked content", "no shiurim in this language", the rate-limit message.

---

## 6. Milestones

| | What | Why it is its own step |
| --- | --- | --- |
| **M0** | **Verification spike.** Live-check: does `SearchOrder = 7` really return newest-first (compare `RecordDate`)? Does the JSON API answer a **browser** request with CORS headers (this decides whether wasmJs can have the feature at all)? Does `GetMp3FileToPlay` serve `Range` and play in an `<audio>` element? What languages does 674 actually have? Throwaway script, nothing committed. | Four unknowns, each capable of invalidating a milestone. Cheap to answer, expensive to discover in M4. |
| **M1** | **Seekable player.** `PlaybackProgress`, `startAtMs`, `seekTo` across `StreamRadioPlayer`, `MediaSessionRadioPlayer`, `SilentRadioPlayer`. A seek bar that only appears when `seekable`. Radio behaviour unchanged. | The only interface change in the plan; land it alone, verify the radio did not regress on all three platforms. |
| **M2** | **Data.** Library dependency wired, `Ravs.kt`, `ShiurRepository` with paging plus memory and disk cache, `ProgressStore` (`shiurim.txt`), `shiurLanguage` through `UserSettings` to `SnapshotCodec` to `SettingsScreen`. No UI beyond the settings row. | Testable without a screen; `ShiurRepository` against a `MockEngine`, `ProgressStore` and the LRU cap as plain unit tests. |
| **M3** | **Auto-resume.** `PlaybackSource`, the 4.1 walk, the minus-10-seconds rule, finished-detection, auto-advance at the end. Entered from a temporary debug button. | The core of the request. Correct before it is pretty. |
| **M4** | **Explorer.** Rav card in the grid, Rav screen, three tabs, paging, folders, empty and error states. | Where the API's real-world roughness shows up; wants the data layer already trustworthy. |
| **M5** | **Full transport.** Skip buttons with long-press scrub, prev/next shiur, media-key and notification mapping, Android notification scrubber, desktop MPRIS/SMTC position, `resumeOnLaunch` for shiurim. | Cross-platform polish, one platform at a time. |
| **M6** | **Optional.** Playback rate, sleep timer, search within a rav, more ravs, download for offline. | Each independently droppable. |

---

## 7. Risks, honestly

1. **CORS on web is unverified.** API-SPEC 7.1 leaves it `UNKNOWN`. Media playback itself is
   probably fine - an `<audio>` element loads cross-origin without CORS - but the JSON calls from
   wasmJs need `Access-Control-Allow-Origin` and may not get it. GitHub Pages is static, so there is
   no proxy to hide behind. **If M0 says no, the web build hides the rav card** and the feature ships
   on desktop and Android. Decide this in M0, not in M4.
2. **Cloudflare.** A dozen requests in a minute trips a challenge that does not clear on its own
   (API-SPEC 1.3). Mitigations are already in the plan - one shared client, 1 s minimum interval,
   concurrency 1, page-0 disk cache, resume without any API call, no auto-retry on a challenge - but
   the ceiling is real and paging fast through 5852 shiurim will hit it. Prefetch conservatively.
3. **`SearchOrder = 7` may not mean newest.** See 4.1. M0 settles it.
4. **Locked and women-only content.** `isLocked` shiurim are excluded from the auto-pick and shown
   with a badge, never played around - the library refuses to hand out their URL and that is correct.
   `isWomenOnly` needs a product decision: the site suppresses their previews entirely.
5. **Duration before playback.** `ShiurDuration` arrives with the list, so progress rings render
   without opening the file. Treat the backend's `currentDuration()` as authoritative once it is
   known and reconcile - they will occasionally disagree by a second or two.
6. **Scope.** M1 touches an interface with three implementations on three platforms, one of which
   (web) has no easy debugger. Budget for it accordingly; the rest of the plan is additive.

---

## 8. Decisions worth making before M1

- **Placement**: a card in the Torah row of the stations grid (recommended - it reads as part of the
  app and costs no new navigation), or a fourth top-level destination next to Stations / Favorites?
- **Web**: ship it if CORS allows, or scope the feature to desktop and Android from the start?
- **Dependency**: JitPack for the committed build (recommended), or keep `kolhalashon-kmp` as a
  composite build and accept that CI cannot build the app until it is published?
- **Favorites**: should a shiur be starrable? `AppData.favorites` is a `Set<String>` that already
  mixes station and channel ids by prefix convention, so `674/1234567` fits without a migration.
