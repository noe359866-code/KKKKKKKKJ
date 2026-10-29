<h1 align="center">Streamflix Reborn</h1>

<p align="center">
  <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" height="100px" />
  <br />
  <strong>🔄 Reborn Version</strong> - Community continuation of the original Streamflix project
  <br />
  An open-source Android TV / Google TV app for educational streaming interface, made with Android Studio, in Kotlin
  <br />
  <a href="https://github.com/streamflix-reborn/streamflix/releases/latest">
    <strong>Download app »</strong>
  </a>
  <br />
  <br />
  <a href="https://github.com/streamflix-reborn/streamflix/issues">Report Bug</a>
  ·
  <a href="https://github.com/streamflix-reborn/streamflix/issues">Request Feature</a>
</p>

<details>
  <summary>Table of Contents</summary>

- [About the project](#about-the-project)
  - [What is Streamflix Reborn?](#-what-is-streamflix-reborn)
  - [Features](#features)
  - [Built with](#built-with)
- [Getting started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Setup](#setup)
- [Stremio addon backend (Node.js)](#stremio-addon-backend-nodejs)
- [Android client (Kotlin + Compose)](#android-client-kotlin--compose)
- [Development](#development)
- [Contributing](#contributing)
- [Legal Disclaimer](#legal-disclaimer)
- [Credits & Authors](#credits--authors)
- [License](#license)
</details>

## About the project

<p align="center">
  <img src="./.github/docs/screenshot.png" alt="Streamflix Preview">
</p>

**Streamflix Reborn** is an independent continuation of the original Streamflix project created by [Lory-Stan TANASI](https://github.com/stantanasi). This reborn version maintains the same educational purpose and functionality while ensuring continued development and support.

### 🔄 What is Streamflix Reborn?

- **Independent Continuation**: This is an independent continuation of the original Streamflix project
- **Same Vision**: Maintains the original educational and open-source philosophy
- **Enhanced Support**: Continued development and bug fixes by an independent developer
- **Respectful Fork**: Built with full respect for the original creator's work

Streamflix Reborn is an open-source Android TV / Google TV app that provides a user interface for accessing publicly available streaming content from various third-party providers.

This app is designed for educational purposes and personal use only. Users are responsible for ensuring they have proper authorization to access any content they view through this application.

The interface aggregates content from multiple sources and provides a convenient way to browse available streaming options.

### Features

- Open-source and ad-free interface
- Aggregates content from multiple third-party providers
- No account required for the app interface
- Educational and personal use only
- Android TV-only APK: Leanback launcher, D-Pad navigation and TV-sized UI
- Multiple providers, with automatic silent failover between their streams
- Home with horizontal carousels for Películas, Series, Anime and TV en Vivo

### Built with

- [Android Studio](https://developer.android.com/studio)
- [Kotlin](https://kotlinlang.org) + Coroutines
- [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)
- [OkHttp](https://square.github.io/okhttp) + kotlinx.serialization
- MVVM architecture (`ViewModel` + `StateFlow`)
- Android TV / Google TV only (Leanback required)


## Stremio addon backend (Node.js)

`server/` contains a lightweight Node.js + Express backend that exposes this
catalogue to any Stremio-compatible client (including the Android TV app)
through the official Addon protocol:

- `GET /manifest.json` — declares `movie`, `series`, `anime`, `tv` and the
  published catalogs (`catalog` + `meta` resources).
- `GET /catalog/:type/:id.json` — Stremio catalog protocol, backed by
  **[Cinemeta](https://github.com/Stremio/stremio-addon-sdk)** (the official
  metadata addon), merged with the local live TV/sports rows and with the
  catalogs of a third-party Stremio addon:
  `top`, `year`, `imdbRating` for movies and series, `anime/top`
  (Cinemeta `series` + `genre=Animation`), `tv/live` from
  `server/config/live-catalog.json`, and **`streaming_<service>`** rows
  (Netflix, Disney+, Prime Video, HBO Max, Apple TV+…) republished from
  *Streaming Catalogs* — any other Stremio addon can be added the same way
  through the `addons` block (no code, no copying their data).
  Accepts the standard extras as path segments — `/catalog/movie/top/search=matrix.json`,
  `/catalog/tv/live/genre=Deportes/skip=20.json`.
- `GET /meta/:type/:id.json` — rich metadata (synopsis, poster, year, rating)
  and, for series, the episode list (`tt0944947:1:2`), so a Cinemeta poster
  becomes a playable request.
- `GET /stream/:type/:id.json` — returns `streams` ordered by provider
  priority so ExoPlayer can fail over (source 1 → 2 → 3).
- In-memory TTL cache (2 h live / 6 h VOD) with single-flight and
  stale-while-revalidate: a cached live `.m3u8` is served in a few milliseconds.
- Python scraper bridge (`child_process.spawn`) invoked only on cache misses.
- Twelve real providers ship in `server/scrapers/`, ordered by priority.

  **Live** (`kind: "live"`, requested as `tv:<id>`):

  - `futbolibre.py` — [futbolibre.live](https://futbolibre.live/) (priority 3):
    Mexican sports channels (`tv:futbolibre:espn-1`) plus the daily match agenda
    where every match yields several signals
    (`tv:futbolibre:evento:<slug>`);
  - `deportelibre.py` — [deportelibre.top](https://deportelibre.top/) (4):
    24/7 channel catalogue (`tv:deportelibre:stream-01`);
  - `tvenvivo.py` — [tvenvivo.org](https://www.tvenvivo.org/) (5): live
    TV/sports (`tv:tvenvivo:espn-2`).

  **Anime** (`kind: "vod"`, requested as `anime:<id>`):

  - `animeflv.py` — [animeflv.net](https://www3.animeflv.net/) (6):
    `animeflv:one-piece:1180`;
  - `jkanime.py` — [jkanime.net](https://jkanime.net/) (7):
    `jkanime:one-piece:1180`;
  - `monoschinos.py` — [monoschinos.st](https://monoschinos.st/) (8):
    `monoschinos:one-piece-sub-espanol:1`.

  **Movies & series** (`kind: "vod"`, requested as `movie:<id>` or
  `series:<id>:<season>:<episode>`):

  - `gnula.py` — [gnulahd.nu](https://ww3.gnulahd.nu/) (11):
    `gnula:street-fighter` (decodes the site's `GNRD` player API);
  - `cuevana.py` — [cuevana3.nu](https://cue.cuevana3.nu/) (12):
    `cuevana:spider-man-de-regreso-a-casa` (`showEmbed` base64 payloads);
  - `pelisplushd.py` — [pelisplushd.la](https://www.pelisplushd.la/) (13):
    `pelisplushd:the-last-of-us:1:2`;
  - `repelis.py` — Repelis (14): `repelis:<slug>`, domain set with
    `REPELIS_BASE` / `REPELIS_MIRRORS`;
  - `peliculasflix.py` — [peliculasflix.co](https://peliculasflix.co/) (15):
    `peliculasflix:<slug>` (own GraphQL API);
  - `pelisjuanita.py` — [pelisjuanita.org](https://pelisjuanita.org/) (16):
    `pelisjuanita:spider-man-un-nuevo-dia-2026`;

  The anime and movie providers return **one stream per resolved server**
  (Streamwish, VOE, Filemoon, Streamtape…), in the site's order, so the
  ExoPlayer failover also covers a dead file host. Movie ids accept a site slug,
  `season:episode`, an IMDb id (`gnula:tt0145487`, with `TMDB_API_KEY`) or free
  text that gets searched on the site.

```bash
cd server
npm install
npm start
# manifest → http://localhost:7000/manifest.json
# catalog rows → http://localhost:7000/catalog/tv/live.json
#               http://localhost:7000/catalog/movie/top.json
# search →      http://localhost:7000/catalog/movie/top/search=matrix.json
# metadata →    http://localhost:7000/meta/series/tt0944947.json
# live channel → http://localhost:7000/stream/tv/tv:futbolibre:espn-1.json
# anime episode → http://localhost:7000/stream/anime/animeflv:one-piece:1180.json
npm run channels:futbolibre   # list channels + matches with ready-to-use ids
npm run channels:deportelibre
npm run channels:tvenvivo
npm run anime:animeflv        # list episodes + ids (also jkanime, monoschinos)
npm run movies:gnula -- "street fighter"   # search + ids (also cuevana, pelisplushd…)
```

These scrapers only read public pages: nothing is hosted or redistributed here.
Use them with authorization and in line with each site's terms and your local
law.

See [`server/README.md`](server/README.md) for the full documentation
(architecture, environment variables, cache internals, adding providers,
tests and Docker).

## Android client (Kotlin + Compose)

`app/` is the client. It contains **no scraping logic**: it asks the Node addon
for `GET /stream/{type}/{id}.json` and plays whatever ordered list comes back.

| Concern | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3 (Compose BOM `2025.06.00`) |
| Player | Media3 ExoPlayer `1.7.1` + `media3-exoplayer-hls` |
| HTTP | OkHttp `4.12.0` + kotlinx.serialization |
| Posters | Coil |
| DI | hand-written `ServiceLocator` (no annotation processor) |
| SDK | `minSdk 23`, `targetSdk 35`, `compileSdk 36`, JVM 17 |

### Visual design

Three references, each doing one job:

| Reference | What is borrowed |
| --- | --- |
| **Apple TV+** | a cinematic hero whose backdrop follows the focus, with a slow Ken Burns drift and three actions (Reproducir / Más información / Cambiar servidor) |
| **Netflix** | fluid horizontal carousels, 2:3 poster cards, focus scale 1.08x and a floating "Continuar viendo" row |
| **Prime Video** | floating state badges on the artwork (EN VIVO, quality, provider) and a quick-look details panel before playback |

Everything is Compose (no XML layouts): `ui/home/HeroBanner.kt`,
`ui/components/MediaCards.kt`, `ui/components/MediaRow.kt`,
`ui/components/Badges.kt`, `ui/details/DetailsPanel.kt`.

![Home design preview: Android TV](docs/design-preview.jpg)

*(Static render of the same colours, sizes and artwork the Compose code uses —
useful as a reference while the APK cannot be built in this environment.)*

**Hero geometry (TV).** Two different numbers: the backdrop box is 0.78 of the
screen height, but the text block is anchored at a 0.62 baseline, and that is
exactly where the first shelf starts. That leaves the CTAs clear, one full 16:9
row visible and the next row's title peeking — the visual cue to press DOWN.

**Hero.** `HomeCatalog` + `HomeViewModel` keep a `Spotlight`: the card currently
focused. Focusing a card swaps the backdrop
with a 420 ms crossfade, so the hero reads like it is following intent rather
than looping slides. The backdrop is one of four 1280×720 stills shipped in
`res/drawable-nodpi` (`art_movies`, `art_series`, `art_anime`, `art_live`), used
when a title has no 16:9 artwork of its own — which is the normal case for
provider catalogues (they publish 2:3 posters only).

**Cards.** One shared `ContentCard` skeleton renders both proportions, and the
row decides which one it wants (`aspect: "poster" | "landscape"` in the
catalogue JSON):

| Row | Aspect | Content |
| --- | --- | --- |
| Continuar viendo | 16:9 | progress bar under the artwork |
| Películas populares | 2:3 | poster |
| Series que enganchan | 2:3 | poster |
| Anime en emisión | 2:3 | poster |
| Deportes en directo | 16:9 | live channel tile |

Focus in TV = scale 1.08 + elevation + the neon ring (`Modifier.tvFocusGlow`,
cyan on purpose: red is already "live/brand"). The title under a card fades in
with the focus, so an unfocused shelf stays clean.

**Badges.** `LiveBadge` (pulsing dot), `TrendingBadge` (TOP 10),
`QualityBadge` (4K in neon), provider chip at the bottom and a progress bar for
resumed titles. During playback the player shows `FailoverBadge` with the source
actually on screen ("Fuente 2 de 5"), which is where that number is truthful.

**Details before playback.** Selecting a card with the D-Pad opens `DetailsDialog`, a `Dialog`
(its own window, so the D-Pad cannot escape to the shelf behind): backdrop
header with rating/quality/genres, synopsis, and the **server selector** — the
list of sources the addon returned, in its priority order, already loaded
through `StreamsRepository`. The chosen index travels to the player as
`PlaybackRequest.startIndex`, and the silent failover continues downward from
there. "Cambiar servidor" in the hero opens this same panel with the focus
already on the list.

### Android TV only

`core/DeviceProfile.kt` always uses TV dimensions and D-Pad focus. It detects
whether the device is Android TV / Google TV (`UI_MODE_TYPE_TELEVISION` or
`FEATURE_LEANBACK`) so manual installs on phones/tablets can be blocked before
loading the catalogue or player. A touchscreen does not disqualify a TV.

The profile is published through `LocalDeviceProfile`, so every composable reads
it without touching the `Context`:

- 220 dp poster cards and 400 dp landscape cards, with 48 dp side margins.
- Typography scaled ×1.15–1.25 for viewing from the sofa.
- Cyan focus ring, elevation and 8 % scale-up when the D-Pad lands on a card.
- The first card of the top row grabs focus on purpose; the player overlay chips
  (Fuentes / Volver) get the same treatment.

`AndroidManifest.xml` declares `android.software.leanback` as `required="true"`,
`android.hardware.touchscreen` as `required="false"`, and registers only
`LEANBACK_LAUNCHER` with a TV banner. There is no phone `LAUNCHER` entry.

### Player and automatic failover

`player/` holds the whole policy, and it is plain Kotlin (no Android imports)
except for `MediaSources`, which is the only place that knows about Media3:

1. `StreamsRepository` normalises the addon response: drops unplayable URLs and
   duplicates, keeps the server's priority order **untouched**.
2. `FailoverPlanner` walks that list: index 0 is the best source, each source is
   tried once, and the list never wraps around (no infinite loops).
3. `FailoverController` reacts to `onPlayerError` and to silent stalls (a
   12 s watchdog while buffering with `playWhenReady` — a user pause never
   triggers a hop), and swaps the `MediaSource` on the **same** `ExoPlayer`
   instance, resuming at the last known position. No black flash, no dialog, no
   relaunch: at most a brief "Cambiando de servidor…" chip.
4. DRM errors are the one exception: another CDN cannot fix a license, so the
   user is told instead of being bounced around (`FailoverRules`).

### Player controls (D-Pad first)

The player doesn't use Media3's built-in controller (`useController = false`);
`ui/player/PlayerControls.kt` draws its own, because the stock one documents no
behaviour for the direction keys and can't share the app's focus language.

| Key | Action |
| --- | --- |
| `OK` / `ENTER` / `SPACE` / gamepad `A` | play / pause |
| `◀` / `▶` (and `MEDIA_REWIND` / `MEDIA_FAST_FORWARD`) | −10 s / +10 s (VOD only) |
| `▼` / `▲` | show the controls and move the focus into them |
| `BACK` | hide the controls; a second press leaves the player |

Details that matter on a sofa:

- everything fades out after **4 s** while playing, and only while the focus is
  still on the video surface — if you are walking the buttons they stay put;
- while the controls are hidden, `OK` pauses and the arrows seek **without**
  bringing the UI back, so the failover and normal playback stay unobtrusive;
- `LEFT`/`RIGHT` never steal the event when a button is focused: there they move
  the focus between buttons, as expected;
- the seek arithmetic lives in `player/SeekPolicy.kt` (clamped to `0..duration`,
  unknown duration = no upper bound) and is unit-tested;
- live channels hide the seek buttons and the progress bar: an HLS live window
  has no duration to show, and seeking it only triggers a rebuffer.

The red ring + halo is `Modifier.tvFocusGlow` (`ui/theme/Focus.kt`), the Compose
equivalent of the classic pair of XML state drawables:

```text
res/color/card_focus.xml + android:focusable="true"   →  Modifier.tvFocusGlow(progress)
```

Both the home cards and the player buttons use it, so the focus style is
identical in the catalogue and on top of the video.

### Per-source HTTP headers

Streamwish, Filemoon, VOE and friends reject a `.m3u8` without the `Referer` of
the page that published it. The addon already ships those headers in
`behaviorHints.requestHeaders`; `MediaSources.dataSourceFactory()` copies them
into a `DefaultHttpDataSource.Factory` (adding a derived `Origin` and allowing
cross-protocol redirects), and each source gets its own factory, since the
`Referer` changes hop by hop.

### Server URL

The backend runs on your laptop or a TV box, so its address cannot be baked into
the APK:

```bash
./gradlew :app:assembleDebug -PaddonBaseUrl=http://192.168.1.50:7000
```

`10.0.2.2:7000` (the emulator's view of the host) is the default **only for debug
builds**. A release build takes its URL from the build environment (see
[Release build](#release-build-apk--aab)) and refuses to be packaged without
one, so a production APK never ships pointing at the emulator loopback.

The address stays editable at runtime from **Servidor** in the home header — a TV
box in another subnet must be fixable without a laptop and a rebuild. The value
is stored in `SharedPreferences` and normalised (`192.168.1.50:7000` →
`http://…`). A release APK with no URL configured opens that dialog on first
launch instead of showing an error.

Cleartext HTTP is allowed because a LAN address cannot be known in advance; the
video CDNs themselves are HTTPS.

### Home catalogue (server-driven)

The home screen is built from the **backend catalog config**, not from Kotlin and
not from a bundled JSON. On launch the app asks `GET /catalogs.json` which rows
the addon publishes and then loads every row in parallel from
`GET /catalog/{type}/{id}.json`:

- **card shape comes from the server** — `posterShape: "poster"` renders 2:3
  posters (movies, series, anime), `posterShape: "landscape"` renders 16:9
  cards (live TV and sports);
- rows are `movie/top`, `movie/imdbRating`, `series/top`, `anime/top`,
  `anime/imdbRating` (Cinemeta, enriched with synopsis/poster/year/rating),
  `tv/live` (curated channels from `server/config/live-catalog.json`, whose ids
  are exactly the stream ids — `tv:futbolibre:espn-1`) and the per-service
  rows republished from the *Streaming Catalogs* addon
  (`movie/streaming_nfx` Netflix, `movie/streaming_dnp` Disney+,
  `series/streaming_amp` Prime Video…, chosen in the `addons` block of
  `server/config/catalogs.json`; empty `only` publishes all 41);
- adding, removing or reordering a row is editing
  `server/config/catalogs.json` and restarting Node: no APK rebuild, no
  reinstall on the TV;
- each row fails on its own: if Cinemeta is down the movie rows disappear but
  the live row (served from a local JSON) still plays. Rows have their own
  timeout, so a slow catalog never blocks the Home.

There is **no bundled catalogue**: the release APK contains no mock cards. If the
server is not configured or unreachable, the screen states it and offers
*Reintentar* / *Configurar servidor* instead of showing placeholder content.

Tapping a Cinemeta card keeps its **IMDb id** (`tt0944947`). For a series the
details sheet asks `GET /meta/{type}/{id}.json` for the episode list, picks one
(`tt0944947:1:2`) and only then requests
`GET /stream/series/tt0944947:1:2.json`; the chosen source index still flows
into `PlaybackRequest.startIndex`, so ExoPlayer starts on the server the user
picked and fails over downward exactly like any other row.

### Build, run, test

```bash
cd server && npm start                 # addon on :7000
./gradlew :app:assembleDebug           # → app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest       # JVM tests, no emulator needed
```

Unit tests live in `app/src/test/` and cover the parts where a bug costs a
movie: `FailoverPlannerTest` (order, one attempt per source, no wrap-around),
`FailoverRulesTest` (which error codes hop, which do not), `SeekPolicyTest`
(seeking at the edges of the timeline), `PlayerKeyMapTest` (the remote-control
mapping), `DurationsTest` (clock formatting), `StreamsRepositoryTest` (URL
shape, header parsing, HTTP failures via `MockWebServer`) and
`CatalogRepositoryTest` (the Home against a fake backend: no local catalogue,
server-decided card shape, per-row isolation, unreachable host vs. server with
no rows).

Smoke test with the real backend: run `npm start`, point the app at it, move the
D-Pad across a shelf (the hero must follow), open a card, pick the third server
in the panel, press Reproducir, then stop that provider and watch the player hop
to the next source without losing your position.

### Release build (APK + AAB)

One signed artifact targets Android TV / Google TV only (see
[Android TV only](#android-tv-only)).

```bash
export ADDON_BASE_URL=https://addon.tu-dominio.com    # required
./scripts/release-android.sh                          # APK + AAB + summary
```

The script is a thin wrapper; the same build by hand is:

```bash
./gradlew :app:assembleRelease :app:bundleRelease \
    -PreleaseAddonBaseUrl=https://addon.tu-dominio.com
```

Outputs:

| Artifact | Path |
| --- | --- |
| APK (sideload to Android TV) | `app/build/outputs/apk/release/app-release.apk` |
| AAB (Google Play) | `app/build/outputs/bundle/release/app-release.aab` |
| R8 mapping (crash de-obfuscation) | `app/build/outputs/mapping/release/mapping.txt` |

Without a keystore the APK is written as `app-release-unsigned.apk`: **it will
not install** until it is signed. That is deliberate — silently signing with the
debug key produces an app that cannot be updated later.

#### Where the production URL comes from

First match wins; the build **fails with instructions** if none is set:

1. environment variable `ADDON_BASE_URL` (what CI uses);
2. Gradle property `-PreleaseAddonBaseUrl=…`;
3. `releaseAddonBaseUrl=…` in `keystore.properties` (not versioned).

Validation rejects anything that is not `http(s)://…`, and both debug and
release strip trailing slashes before `BuildConfig.DEFAULT_ADDON_BASE_URL` is
written, so `https://host/` and `https://host` produce identical requests.

There is one explicit escape hatch for packaging before you have a domain:

```bash
./gradlew :app:assembleRelease -PallowEmptyAddonBaseUrl=true
```

The APK then carries **no** default URL and the app asks for the server on first
launch (the same dialog you get when the address is wrong). It never falls back
to `10.0.2.2` and never ships placeholder content; CI uses this mode when it is
dispatched without a URL.

#### Signing

```bash
keytool -genkeypair -v -keystore release.keystore -alias streamflix \
    -keyalg RSA -keysize 4096 -validity 10000
cp keystore.properties.example keystore.properties   # then fill it in
```

`keystore.properties` and every `*.keystore` / `*.jks` are git-ignored. The four
values can also come from the environment (`RELEASE_STORE_FILE`,
`RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`), which is
how `.github/workflows/android-release.yml` signs in CI (keystore base64 in a
secret). Signing enables v1 + v2 + v3, so the same APK installs on old Android TV
boxes and on current Google TV devices.

#### What the release build changes

- **R8 full mode**: obfuscation + tree shaking + resource shrinking
  (`android.enableR8.fullMode=true`). `proguard-rules.pro` keeps only what is
  resolved by name (manifest components, `kotlinx.serialization` metadata and
  `$$serializer`s) plus `-assumenosideeffects` on `Log.v/d/i`, so debug logging
  and signed URLs do not survive into the APK. No blanket
  `-keep class androidx.**`.
- **No demo data**: `app/src/main/assets/` is gone; the catalogue is whatever
  the backend publishes.
- **No tooling**: Compose preview/tooling is `debugImplementation`; `DEBUG_BUILD`
  is `false` and `AppLog.d/i` are stripped.
- **Smaller, faster**: `shrinkResources`, `resConfigs` not needed (single
  locale), packaging excludes, and `versionCode`/`versionName` overridable with
  `-PVERSION_CODE=…  -PVERSION_NAME=…` for store uploads. The current release
  defaults to **0.9.9** (version code **3**); CI checks both values, verifies the
  TV-only manifest, and names the APK `streamflix-0.9.9.apk`.

#### Install and verify

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm list packages | grep tanasi      # com.tanasi.streamflix
```

On a TV: `adb connect <box-ip>:5555` first, then the same `install`. The manifest
requires `android.software.leanback`, marks `android.hardware.touchscreen` as not
required, and registers only `LEANBACK_LAUNCHER`. Google Play therefore offers the
app only to Android TV / Google TV devices; manual installs on phones show a
TV-only exit screen. Smoke test: open the app, check the header shows the
production host, play a live channel, then kill the backend and confirm the Home
degrades to *Reintentar* / *Configurar servidor* instead of fake content.

#### CI

`.github/workflows/android-release.yml` builds the same artifacts on a runner
with JDK 17 + Android SDK, runs the unit tests and `lintRelease`, verifies the
TV-only manifest and signature with `apksigner`, and uploads the APK, the AAB and the R8 mapping.

- **manual**: *Run workflow* with the backend URL (leave it empty to get an APK
  that asks for the server on first launch);
- **tag**: pushing `v0.9.9` uses the `PRODUCTION_ADDON_BASE_URL` repository
  variable and attaches the APK, AAB and SHA-256 digests to a GitHub Release
  for direct downloads (artifacts are also retained for 30 days).

Signing: if the `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets exist, that keystore is
used. Otherwise the workflow generates a **sideload** key, signs with it, and
publishes it as the `sideload-keystore` artifact — download it and keep it, or
the next build will not install over this one (Android requires the same
signature). That key is not valid for Google Play.

## Getting started

### Prerequisites

Install [Android Studio](https://developer.android.com/studio)

### Setup

1. Clone the project to your local machine

```bash
git clone https://github.com/streamflix-reborn/streamflix.git
```

2. Open the project in Android Studio

## Development

1. Select the device that you want to run the app

2. Click **Run**

## Contributing

Contributions are what make the open source community such an amazing place to learn, inspire, and create. Any contributions you make are **greatly appreciated**.

1. Fork the project
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'feat: add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a pull request

## Legal Disclaimer

**IMPORTANT: This application is for educational and personal use only.**

- Streamflix does not host, store, or distribute any copyrighted content
- All content is sourced from third-party providers and websites
- Users are solely responsible for ensuring they have legal rights to access any content
- The developers do not endorse or encourage copyright infringement
- Users must comply with all applicable laws in their jurisdiction
- Any legal issues should be directed to the actual content providers
- This app functions as a search engine aggregator only
- No copyrighted material is stored on our servers

## Legal Notice

This application is provided "as is" for educational purposes. The developers:
- Do not claim ownership of any content
- Do not profit from copyrighted material
- Do not control third-party content providers
- Encourage users to support content creators through legal means
- Recommend using official streaming services when available

## Credits & Authors

### Original Creator
- **[Lory-Stan TANASI](https://github.com/stantanasi)** - Original Streamflix project creator

### Reborn Development
- **Independent Developer** - Streamflix Reborn maintainer
- **Special thanks** to the original creator for the excellent foundation

## License

This project is licensed under the `Apache-2.0` License - see the [LICENSE](LICENSE) file for details

### Original Project
<p align="center">
  <br />
  © 2022 Lory-Stan TANASI. All rights reserved
</p>

### Reborn Project
<p align="center">
  <br />
  © 2025 Streamflix Reborn. Built with respect for the original work.
</p>