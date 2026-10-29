<h1 align="center">Streamflix Reborn</h1>

<p align="center">
  <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" height="100px" />
  <br />
  <strong>🔄 Reborn Version</strong> - Community continuation of the original Streamflix project
  <br />
  An open-source Android TV and mobile app for educational streaming interface, made with Android Studio, in Kotlin
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

Streamflix Reborn is an open-source Android TV and mobile app that provides a user interface for accessing publicly available streaming content from various third-party providers.

This app is designed for educational purposes and personal use only. Users are responsible for ensuring they have proper authorization to access any content they view through this application.

The interface aggregates content from multiple sources and provides a convenient way to browse available streaming options.

### Features

- Open-source and ad-free interface
- Aggregates content from multiple third-party providers
- No account required for the app interface
- Educational and personal use only
- One APK with an adaptive UI: D-Pad-first on Android TV, touch-first on phones
- Multiple providers, with automatic silent failover between their streams
- Home with horizontal carousels for Películas, Series, Anime and TV en Vivo

### Built with

- [Android Studio](https://developer.android.com/studio)
- [Kotlin](https://kotlinlang.org) + Coroutines
- [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)
- [OkHttp](https://square.github.io/okhttp) + kotlinx.serialization
- MVVM architecture (`ViewModel` + `StateFlow`)
- One APK for phones and Android TV


## Stremio addon backend (Node.js)

`server/` contains a lightweight Node.js + Express backend that exposes this
catalogue to any Stremio-compatible client (including the Android TV app)
through the official Addon protocol:

- `GET /manifest.json` — declares `movie`, `series`, `anime` and `tv`.
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

### One APK, two form factors

`core/DeviceProfile.kt` decides once (in `Application.onCreate`) whether the app
is running on a TV:

```text
UI_MODE_TYPE_TELEVISION               -> TV
FEATURE_LEANBACK or FEATURE_TELEVISION and no touchscreen -> TV
otherwise                             -> touch
```

The result is published through `LocalDeviceProfile`, so every composable reads
it without touching the `Context`:

- **TV** — larger cards (196×292 dp), 48 dp side margins, typography scaled
  ×1.15–1.25, and every card draws a red focus ring plus an 8 % scale-up when
  the D-Pad lands on it. The first card of the top row grabs focus on purpose:
  without it a remote does nothing until the user guesses to press a key. The
  player overlay chips (Fuentes / Volver) get the same treatment.
- **Touch** — compact cards (128×192 dp), 16 dp margins, ripple instead of a
  focus ring.

`AndroidManifest.xml` declares `android.software.leanback` and
`android.hardware.touchscreen` as `required="false"` and registers both
`LAUNCHER` and `LEANBACK_LAUNCHER`, so the same APK shows up on a phone and in
the Android TV home row.

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
| tap (phone) | show / hide the controls |

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

`10.0.2.2:7000` (the emulator's view of the host) is the default. It is also
editable at runtime from **Servidor** in the home header; the value is stored in
`SharedPreferences` and normalised (`192.168.1.50:7000` → `http://…`).

Cleartext HTTP is allowed because a LAN address cannot be known in advance; the
video CDNs themselves are HTTPS.

### Home catalogue

The addon publishes no catalogs (`manifest.catalogs = []`), so the home screen
is fed by `app/src/main/assets/catalog/seed.json`: four rows (Películas, Series,
Anime, TV en Vivo) pointing at real provider ids. Edit that JSON to change what
shows up — nothing is hardcoded in Kotlin. Remote catalogs are supported but
opt-in (`Settings.remoteCatalogs`, off by default).

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
mapping), `DurationsTest` (clock formatting) and `StreamsRepositoryTest` (URL
shape, header parsing, HTTP failures via `MockWebServer`).

Smoke test with the real backend: run `npm start`, point the app at it, open a
title, then stop the first provider (or block a CDN) and watch the player hop to
the next source without losing your position.

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