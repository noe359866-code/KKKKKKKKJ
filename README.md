<h1 align="center">Streamflix Reborn</h1>

<p align="center">
  <img src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" height="100px" />
  <br />
  <strong>🔄 Reborn Version</strong> - Community continuation of the original Streamflix project
  <br />
  An open-source **Android TV** app for an educational streaming interface, made with Android Studio, in Kotlin
  <br />
  <a href="https://github.com/noe359866-code/KKKKKKKKJ/actions/workflows/autonomous-checks.yml">
    <strong>Autonomous APK builds (this fork) »</strong>
  </a>
  <br />
  <br />
  <a href="https://github.com/noe359866-code/KKKKKKKKJ/issues">Report Bug</a>
  ·
  <a href="https://github.com/noe359866-code/KKKKKKKKJ/issues">Request Feature</a>
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
- [Autonomous Android mode](docs/autonomous-android.md)
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
  <img src="./app/src/main/res/drawable-nodpi/tv_banner.png" alt="Streamflix para Android TV" width="640">
</p>

**Streamflix Reborn** is an independent continuation of the original Streamflix project created by [Lory-Stan TANASI](https://github.com/stantanasi). This reborn version maintains the same educational purpose and functionality while ensuring continued development and support.

### 🔄 What is Streamflix Reborn?

- **Independent Continuation**: This is an independent continuation of the original Streamflix project
- **Same Vision**: Maintains the original educational and open-source philosophy
- **Enhanced Support**: Continued development and bug fixes by an independent developer
- **Respectful Fork**: Built with full respect for the original creator's work

Streamflix Reborn is an open-source Android TV app that provides a user interface for accessing publicly available streaming content from various third-party providers.

This app is designed for educational purposes and personal use only. Users are responsible for ensuring they have proper authorization to access any content they view through this application.

The interface aggregates content from multiple sources and provides a convenient way to browse available streaming options.

### APK downloads for this fork

The link above opens this repository's Android checks, **not an upstream app**.
Open a successful run and download **`autonomous-debug-apk`** under Artifacts
(GitHub sign-in may be required), extract the ZIP and install `app-debug.apk`.
It is a signed, installable **test build**; artifacts are retained for 14 days.
For a production build, configure your own release signing as described below.

### Features

- **Autonomous by default:** Python 3.11 and the twelve real provider scrapers run inside the APK; no Node.js server, IP address or backend deployment required
- Cinemeta catalogues **on by default** (real 2:3 posters and 16:9 backdrops), followed by the open
  live channels, local episode discovery and on-device cache
- The twelve scrapers are **stream resolvers only**: they publish no catalogue rows, they just
  resolve the video when a title or episode is opened
- *Streaming Catalogs* (Netflix, Disney+, HBO Max, Prime Video…) installed automatically on first launch
- Optional external-server mode remains available in Settings
- Open-source and ad-free interface
- Aggregates content from multiple third-party providers
- No account required for the app interface
- Educational and personal use only
- **Android TV only**: D-Pad-first UI, no phone layout, no touch gestures
- **Perfiles** al estilo Apple TV: avatares, hasta 6, y un "Seguir viendo" distinto por perfil
- Multiple providers, with automatic silent failover between their streams
- Home with horizontal carousels, starting with open live channels that play with no setup (iptv-org)
- **Área de deportes** al estilo Apple TV: partidos de hoy con liga, equipos, hora y EN VIVO
  (agenda de los scrapers en vivo + canales deportivos), pintados como marcador, no como póster

### Built with

- [Android Studio](https://developer.android.com/studio)
- [Kotlin](https://kotlinlang.org) + Coroutines
- [Jetpack Compose](https://developer.android.com/jetpack/compose) + Material 3
- [Media3 ExoPlayer](https://developer.android.com/media/media3/exoplayer)
- [OkHttp](https://square.github.io/okhttp) + kotlinx.serialization
- MVVM architecture (`ViewModel` + `StateFlow`)
- Android TV (Leanback) only, `LEANBACK_LAUNCHER`


## Stremio addon backend (Node.js)

`server/` is **optional for the Android app**. It contains a lightweight Node.js + Express backend that exposes this
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

`app/` now includes the scrapers from `server/scrapers/` and an embedded Python
runtime (Chaquopy). The default path is Compose → local engine → provider →
Media3, all on the device. It does not start Node, a shell or an HTTP server.
The existing addon remains available only when explicitly selected in Settings.
See [Autonomous Android mode](docs/autonomous-android.md) for architecture,
requirements, offline tests and limitations.

| Concern | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3 (Compose BOM `2025.06.00`) |
| Player | Media3 ExoPlayer `1.7.1` + `media3-exoplayer-hls` |
| HTTP | OkHttp `4.12.0` + kotlinx.serialization |
| Posters | Coil |
| DI | hand-written `ServiceLocator` (no annotation processor) |
| Embedded scraping | Chaquopy `17.1.0`, Python `3.11`, standard library only |
| SDK | `minSdk 24`, `targetSdk 35`, `compileSdk 36`, JVM 17 |

### Arte: cero imágenes de relleno

La app **no empaqueta ninguna foto, póster ni fondo de demostración**. Todo lo
que se ve viene de la red:

| Qué | De dónde sale | Si falta |
| --- | --- | --- |
| Póster 2:3 | `poster` del catálogo (Cinemeta, addon, página del proveedor) | tarjeta generada: degradado determinista + iniciales + título (`ui/components/Images.kt`) |
| Backdrop 16:9 | `backdrop` del catálogo | póster desenfocado; si tampoco hay, tarjeta generada |
| Fondo del hero | backdrop → póster desenfocado | tarjeta generada a pantalla completa |
| Logo de canal | `tvg-logo` de la lista, o el póster del proveedor | tarjeta generada |

Regla dura: **nunca una foto de stock**. `ArtworkFallback` dibuja el degradado y
el título con la semilla del propio título, así que una fila sin arte remoto se
sigue leyendo y ninguna tarjeta queda en gris. Los cuatro JPEG de muestra que
traía el proyecto (`art_movies`, `art_series`, `art_anime`, `art_live`), el
`Artwork.kt` que los mapeaba y la captura de diseño del README se eliminaron.

### Diseño: Apple TV, de principio a fin

La interfaz copia el lenguaje de la app **Apple TV (tvOS)**, no el de Netflix.
Cuatro reglas que se ven en todas las pantallas:

| Regla | Cómo se aplica |
| --- | --- |
| **Negro puro** | `#000000` de fondo (no un gris azulado): la foto es lo único que brilla |
| **El acento es el blanco** | botones primarios en cápsula blanca con texto negro; el resto, vidrio translúcido al 12 % |
| **Un solo rojo** | sólo para "EN VIVO"; ni el foco ni los títulos usan rojo |
| **Foco fino** | anillo blanco de 2 dp + halo mínimo + escala 1,05 (tvOS no usa neón) |

Geometría y jerarquía:

- **Hero** a sangre, anclado abajo a la izquierda: estado, título en 44 sp con
  interletraje negativo, línea de metadatos (`★ 8.2 · 4K · 2026 · 1 h 52 min`),
  sinopsis de tres líneas y dos cápsulas — **▶ Reproducir** (blanca, la única
  clara de la pantalla) y *Más información*. No hay botón de *Fuentes*: el
  reproductor elige servidor por su cuenta.
- **Deriva Ken Burns** del backdrop (escala 1 → 1,06 en 20 s) en una animación
  infinita sobre `graphicsLayer`: no recompone, así que un TV box barato no sufre.
- **Filas** con encabezado mínimo (sólo el nombre, 22 sp). Se eliminaron la barra
  de acento, el contador de títulos y la etiqueta "16:9" que sólo añadían ruido.
- **Tarjetas** de 10 dp de radio; el título debajo siempre se lee (atenuado al
  62 % y blanco pleno al enfocar), en vez de aparecer de la nada.
- **Canales en vivo**: el logotipo se pinta con `ContentScale.Fit` para no
  recortar la marca; el cine se recorta a sangre.

### Ficha del título: página de película de Apple TV

Al abrir una tarjeta no se elige servidor: se ve la ficha y se pulsa
**Reproducir**. El reproductor pide las fuentes y salta solo a la siguiente si
una falla.

```
┌──────────────────────────────────────────────────────────────┐
│              [backdrop a sangre, dos velos]                  │
│  SERIE · 2011 · 8,9 · 4K                                     │
│  Juego de tronos                                             │
│  Sinopsis de tres líneas…                                    │
│  ( ▶ Reproducir · T1 E2 )    ( Cerrar )                      │
│  Buscamos la mejor fuente; si falla, pasamos a la siguiente. │
├──────────────────────────────────────────────────────────────┤
│  Episodios                             ( T1 )( T2 )( T3 )   │
│  ┌────────┐ ┌────────┐ ┌────────┐                            │
│  │ [img]  │ │ [img]  │ │ [img]  │  ← 16:9 con T1 E2 y título │
│  └────────┘ └────────┘ └────────┘                            │
└──────────────────────────────────────────────────────────────┘
```

- **Botón principal**: cápsula blanca con el triángulo dibujado en `Canvas` y el
  capítulo elegido detrás (`Reproducir · T1 E2`); el foco arranca ahí.
- **Episodios**: tarjetas 16:9 con miniatura real de Cinemeta (si no hay, se
  dibuja el número, nunca una imagen de otro capítulo), etiqueta `T1 E2`, nombre
  y sinopsis corta. La elegida lleva borde blanco y ✓; la fila se desplaza sola
  al cambiar de temporada.
- **Temporadas** en píldoras a la derecha del encabezado (sólo si hay más de una).
- **Sin lista publicada**: dos contadores `− 1 +` para temporada y episodio y una
  cápsula *Ver este episodio* — nada de teclados en la TV.
- Se eliminaron la lista de servidores, el chip "elegido" y el `AlertDialog` de
  reintento: la ficha no pregunta nada que el reproductor pueda resolver.

Referencias de otros proyectos abiertos de TV (Jellyfin Android TV, SmartTube,
Kodi) para lo que no se ve: foco inicial siempre definido, `Back` predecible,
ningún gesto táctil y ningún menú contextual inalcanzable con el mando.

Todo es Compose (sin layouts XML): `ui/home/HeroBanner.kt`,
`ui/components/MediaCards.kt`, `ui/components/MediaRow.kt`,
`ui/components/Badges.kt`, `ui/details/DetailsPanel.kt`, `ui/theme/`.

### Perfiles: ¿quién está viendo?

En una tele del salón no ve lo mismo un adulto que un niño. La app tiene
perfiles propios (no hace falta cuenta):

- **Selector al arrancar** (`ui/profile/ProfileGate.kt`): avatares redondos de
  128 dp con las iniciales y un degradado de la paleta de Apple, "Añadir perfil"
  y **Editar** para borrar. El foco es el mismo anillo blanco que en las tarjetas.
- **Hasta 6 perfiles**; el nombre se escribe con el teclado del sistema (Android
  TV lo pone a pantalla completa), sin reinventarlo con una cuadrícula letra a letra.
- **"Seguir viendo" por perfil**: al salir del reproductor se guarda la posición
  (`WatchProgressStore`, clave por perfil) y esa fila — 16:9, con barra de
  progreso y "1 h 00 min restantes" — aparece **la primera** en el Home del perfil
  que entra. Por debajo de 15 s no se guarda nada; a falta de 90 s se da por vista.
- **Cambiar de perfil** desde el avatar de la barra superior, sin cerrar la app.
- El último perfil usado se recuerda: al encender la tele se entra directo a él.

### Sólo Android TV

`core/DeviceProfile.kt` es TV-only: `isTelevision = true`, `hasTouchScreen =
false`, `requiresDpadFocus = true`. No hay ramas de móvil, ni hero de teléfono,
ni `pointerInput`, ni rotaciones especiales.

- Tarjetas grandes (200×300 dp póster / 360×203 dp 16:9), 48 dp de margen
  lateral y tipografía ×1.15–1.25.
- Toda tarjeta dibuja anillo de foco rojo y escala 8 % al enfocarse; el foco
  inicial de cada pantalla está definido (hero → primera fila → rejilla).
- `AndroidManifest.xml`: `android.software.leanback` **required="true"**,
  `android.hardware.touchscreen` **required="false"** y un único intent-filter,
  `LEANBACK_LAUNCHER`. El APK no aparece en el cajón de apps de un teléfono.
- `PlayerActivity` fuerza inmersivo con `DpadKeys.applyImmersive` (rama API 30+
  con `WindowInsetsController` y rama legacy) y el reproductor no tiene gestos
  táctiles: play/pausa, avance, retroceso, fuentes y subtítulos por D-Pad.

### Canales abiertos sin configurar nada (iptv-org)

Para que la app reproduzca algo **el primer día, sin claves ni addons**, se
sincroniza la lista pública [iptv-org/iptv](https://github.com/iptv-org/iptv)
(emisión libre, HLS) desde el motor Python:

- Filas: `iptvorg` (abiertos del mundo + México/España/Argentina/Colombia/Chile/
  Perú) y, debajo, `bundled_live` con los canales guardados del servidor.
- Ids `iptvorg:<base64url de la URL>`: `streams()` los decodifica y devuelve la
  URL tal cual, con `isLive` y el `User-Agent` del reproductor. **No pasan por
  ningún proveedor ni scraper**, así que no dependen de una web caída.
- Sólo se aceptan `.m3u8`/`.mpd`, se descartan los marcados `[Geo-blocked]` y se
  deduplica por URL (tope 120 por lista). Caché de 6 h y respaldo de 7 días.
- Si la red del televisor no alcanza `api.github.com`, quedan siete canales
  empaquetados (ADN 40, Azteca Internacional, Imagen TV, Las Estrellas,
  TeleFormula, Teleritmo, Popcorn Central) con su URL de HLS verificada.

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

### Reproductor: diseñado sólo para Android TV

El reproductor no usa el controlador de Media3 (`useController = false`):
`ui/player/PlayerControls.kt` dibuja el suyo, con el lenguaje de la app Apple TV
y **sin una sola línea pensada para el dedo** (no hay `pointerInput` ni barra que
se arrastre).

Tres zonas sobre el vídeo, con velos en degradado (no cajas):

| Zona | Contenido |
| --- | --- |
| Arriba | título en 26 sp, chip **EN VIVO** y `Fuente 2 de 5 · Gnula` |
| Centro | botones redondos: **−10**, **▶/⏸** (blanco, 108 dp), **+10**, **Fuentes** y **CC** |
| Abajo | barra de progreso con tiempos `0:42 / 1:58:30` y la chuleta de teclas |

Decisiones de salón:

- **El icono de play/pausa se dibuja en `Canvas`** (triángulo y dos barras): sin
  `material-icons-extended` y sin glifos `▶`/`❚❚`, que algunos TV boxes pintan
  como cajas.
- **Botones circulares con anillo blanco** y escala 1,08 al enfocar: el mismo
  foco que las tarjetas del Home, así que el salto del catálogo al vídeo no
  cambia de lenguaje.
- **Todo fade a los 4 s** mientras se reproduce y el foco sigue en el vídeo. Si
  el usuario está recorriendo los botones, se quedan.
- **Subtítulos**: el botón `CC` sólo aparece si el manifiesto trae pistas de
  texto; activa/desactiva `TRACK_TYPE_TEXT` en el propio `ExoPlayer`.
- **Directo**: se ocultan −10/+10 y la barra de progreso (una ventana HLS no
  tiene duración que enseñar); queda el chip EN VIVO y "Señal en directo".
- **Reanuda donde se quedó**: la tarjeta de "Seguir viendo" viaja con
  `PlaybackRequest.startPositionMs` y el `FailoverController` arranca ahí; si
  el usuario cambia de capítulo en la ficha, empieza desde el principio.
- **Selector de fuentes como panel lateral de tvOS**: entra por la derecha
  (donde tvOS pone "Información"), deja el vídeo visible a la izquierda y cada
  fuente es una tarjeta enfocable con número de failover, proveedor, `HLS · 1080p`
  y una marca ✓ en la que se está usando. El foco arranca en la **fuente en
  uso**, no en la primera. Sin `AlertDialog` de Material: en una TV los botones
  de 40 dp y las listas que no se desplazan no se pueden usar.
- **Cuando todo falla**, panel de vidrio con *Reintentar* (foco inicial) ·
  *Elegir fuente* · *Volver*. ATRÁS sale del reproductor, no del aviso.

Teclas:

| Tecla | Acción |
| --- | --- |
| `OK` / `ENTER` / `SPACE` / gamepad `A` | play / pausa |
| `◀` / `▶` | −10 s / +10 s (sólo VOD; con el foco en un botón mueven el foco) |
| `▼` / `▲` | sacar los controles y llevar el foco a ellos |
| `MEDIA_PLAY_PAUSE` / `MEDIA_FAST_FORWARD` / `MEDIA_REWIND` | **siempre** funcionan, aunque el foco esté en un botón o en la lista de fuentes |
| `BACK` | cierra la lista de fuentes, luego oculta los controles, luego sale |

La aritmética del salto vive en `player/SeekPolicy.kt` (acotada a
`0..duration`, duración desconocida = sin tope) y la tabla de teclas en
`player/PlayerKeyMap.kt`; ambas se prueban en JVM, sin emulador.

### Per-source HTTP headers

Streamwish, Filemoon, VOE and friends reject a `.m3u8` without the `Referer` of
the page that published it. The addon already ships those headers in
`behaviorHints.requestHeaders`; `MediaSources.dataSourceFactory()` copies them
into a `DefaultHttpDataSource.Factory` (adding a derived `Origin` and allowing
cross-protocol redirects), and each source gets its own factory, since the
`Referer` changes hop by hop.

### Autonomous mode and optional server

**Ajustes → Autónomo · En este dispositivo** is the default, including after an
upgrade from the old server-only build. First launch does not ask for a URL.
Catalogue rows come from Cinemeta (explicit opt-in) and the open live channels;
the bundled scrapers only resolve streams. Cinemeta is optional, not a
requirement for watching anything.

An external server can still be selected in **Ajustes → Servidor externo**.
Only that mode uses `DEFAULT_ADDON_BASE_URL` / the saved URL. Returning to local
mode never silently contacts that backend as a fallback.

Internet and the original content hosts are still required. Autonomous does
**not** mean offline video, bundled films or independence from the source sites.
A changed domain, anti-bot challenge or dead video host can still break a source.

### Home catalogue

- **Canales abiertos primero.** La fila `iptvorg` (iptv-org, HLS libre) es la
  primera del Inicio y se reproduce sin cuenta, sin addon y sin proveedor; ver
  *Canales abiertos sin configurar nada*.
- The twelve provider definitions and curated live-channel IDs are packaged
  from `server/config/`; the demo Python providers and server-only third-party
  addon configuration are excluded.
- **Los scrapers no publican filas.** Su portada no tiene orden, arte ni
  sinopsis, así que sólo resuelven streams: al abrir un título o episodio, el
  motor busca esa ficha por nombre en cada scraper y devuelve los enlaces. Un
  pedido de catálogo con su id responde `{"metas": []}` (contrato, no error).
- Native Python calls scrape the provider search pages for movies, series,
  anime, live channels and sports. Posters and episode lists come from those
  same pages where available. No private TMDB key is required.
- The saved live-channel shelf is available immediately without a metadata
  service.
- **Buscar** filtra las filas de Cinemeta y los canales en vivo; **Inicio**
  limpia la búsqueda.
- Catalogue metadata is cached on disk for one hour (live: ten minutes), with
  a bounded stale fallback for titles/artwork. Signed stream URLs have a short
  cache only and are never served stale after its expiry.
- The details sheet discovers numbered episodes and shows them as 16:9 cards
  with thumbnails, season pills and a white **Reproducir** capsule (no server
  picker: playback starts at source 0 and the player fails over on its own); if
  no list is published, series/anime offer explicit manual season/episode
  selection rather than inventing a list or playing episode one silently.
- Per-source Referer, User-Agent, Origin and Cookie headers survive into Media3.
  Both HLS and DASH modules are included.

In optional server mode, the original `/catalogs.json`, `/catalog`, `/meta` and
`/stream` protocol and server configuration continue to work.

### Build, run, test

Requirements: JDK 17, Android SDK 36, **Python 3.11 on the build machine**.
The TV/phone needs **Android 7.0 / API 24+**. The universal APK supports
`armeabi-v7a`, `arm64-v8a` and `x86_64`; there is no device-side Python install.

```bash
# No npm start, ADDON_BASE_URL or server setup required:
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
python3 scripts/verify-embedded-apk.py app/build/outputs/apk/debug/app-debug.apk

# Offline tests of the actual engine + existing Python scrapers:
python3 -m unittest discover -s app/src/test/python -v

# Device/emulator smoke test: native Python + all twelve imports, no backend:
./gradlew :app:connectedDebugAndroidTest

# Optional backend regressions (separate from the Android runtime):
(cd server && npm ci && npm test)
```

`AutonomousContentTest` covers URL-free repository calls, mode selection, no
implicit remote fallback, complete playback headers, search/episode IDs and
cancellation. Existing player, repository and addon tests remain in place.
`EmbeddedEngineSmokeTest` executes the bundled Python runtime on Android.
`verify-embedded-apk.py` checks the actual APK's script archive and native
libraries; a small file size is never used as proof of completeness.

### Release build (APK + AAB)

One signed artifact installs on an Android TV box or TV stick (see
[Sólo Android TV](#sólo-android-tv)).

```bash
./scripts/release-android.sh       # autonomous APK + AAB + package verification
```

The script is a thin wrapper; the same build by hand is:

```bash
./gradlew :app:assembleRelease :app:bundleRelease
```

Outputs:

| Artifact | Path |
| --- | --- |
| APK (sideload to TV/phone) | `app/build/outputs/apk/release/app-release.apk` |
| AAB (Google Play) | `app/build/outputs/bundle/release/app-release.aab` |
| R8 mapping (crash de-obfuscation) | `app/build/outputs/mapping/release/mapping.txt` |

Without a keystore the APK is written as `app-release-unsigned.apk`: **it will
not install** until it is signed. That is deliberate — silently signing with the
debug key produces an app that cannot be updated later.

#### Optional backend URL

No URL is required to build debug or release. If supplied, the default for the
optional server mode is resolved from `ADDON_BASE_URL`,
`-PreleaseAddonBaseUrl=…`, or `releaseAddonBaseUrl` in `keystore.properties`.
It does not change the default autonomous mode. The old
`allowEmptyAddonBaseUrl` escape hatch is no longer necessary.

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
boxes and on current phones.

#### What the release build changes

- **R8 full mode**: obfuscation + tree shaking + resource shrinking
  (`android.enableR8.fullMode=true`). `proguard-rules.pro` keeps only what is
  resolved by name (manifest components, `kotlinx.serialization` metadata and
  `$$serializer`s) plus `-assumenosideeffects` on `Log.v/d/i`, so debug logging
  from Kotlin is removed. Python diagnostics retain the scrapers' existing
  behaviour. No blanket
  `-keep class androidx.**`.
- **Embedded runtime and real scrapers**: the Python code and native libraries
  are packaged with the application. Demo/template providers are excluded.
  Chaquopy supplies its own JNI/reflection R8 consumer rules.
- **No tooling**: Compose preview/tooling is `debugImplementation`; `DEBUG_BUILD`
  is `false` and `AppLog.d/i` are stripped.
- **Smaller, faster**: `shrinkResources`, `resConfigs` not needed (single
  locale), packaging excludes, and `versionCode`/`versionName` overridable with
  `-PVERSION_CODE=…  -PVERSION_NAME=…` for store uploads.

#### Install and verify

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell pm list packages | grep tanasi      # com.tanasi.streamflix
```

On a TV: `adb connect <box-ip>:5555` first, then the same `install`. The leanback
launcher entry and the TV banner come from the manifest, and `uses-feature
leanback/touchscreen` are both `required="false"`, so a single APK is offered to
phones and TVs alike (API 24+). Smoke test: leave every Node backend stopped,
install the APK, open the Home without entering a URL, browse/search a provider,
choose an episode and play an authorized source. Disconnecting Internet must
still report unavailable playback; the saved cards are not offline videos.

#### CI

`.github/workflows/android-release.yml` builds the same artifacts on a runner
with JDK 17 + Python 3.11 + Android SDK, runs the offline engine tests,
Kotlin tests and `lintRelease`, verifies the signature with `apksigner` and
checks the embedded Python payload before uploading APK, AAB and R8 mapping.
`autonomous-checks.yml` additionally builds the debug APK, runs the Android TV
emulator smoke test and checks the existing backend for regressions.

- **manual**: *Run workflow* with the backend URL empty for a fully local engine;
  a supplied URL is only a default for the optional server mode;
- **tag**: pushing `v1.0.0` uses the `PRODUCTION_ADDON_BASE_URL` repository
  variable.

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
git clone --branch arena/80426586-kkkkkkkkj https://github.com/noe359866-code/KKKKKKKKJ.git
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