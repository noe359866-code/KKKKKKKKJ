# Lo que falta

Auditoría del estado del proyecto tras el buscador con teclado propio (§19) y
la retirada del TV en directo (§26). Todo lo de abajo está **verificado en el código**, no
recordado: cada punto dice dónde mirar. Lo que depende de las webs (selectores,
dominios caídos) no se puede comprobar desde aquí y queda marcado como tal.

Estado: CI verde (`testDebugUnitTest`, `lintDebug`, `lintRelease`,
`assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`), Node
**209/209**, Python **83**.

> **Actualización (§24):** CI en verde otra vez. El rojo de `main` no era la
> app: `PlayerTracksOptionsTest` metía una pista en español y otra en inglés en
> el mismo `TrackGroup`, Media3 lo reporta con `Log.e` y en la JVM de los tests
> `android.util.Log` devuelve `null` (`returnDefaultValues`), así que el logger
> de Media3 lanzaba `NullPointerException` antes de cualquier assert. El test ya
> usa dos pistas del mismo idioma (se distinguen por `label`) y el diagnóstico
> del workflow vuelca ahora los XML de JUnit al check-run. También queda hecho
> `hls_variants` (P2 del servidor), el último punto abierto de esa lista.

> **Actualización (§26):** la app pasa a ser **sólo VOD**. Se retiró todo el
> contenido de TV en directo —los ocho proveedores de canales/deportes,
> `_livekit`/`_liveshelf`, `live-catalog.json`, las filas `tv/*`, el zapeo
> (`CH+`/`CH−`, guía, rótulo), la EPG, las tarjetas de marcador y el chip EN
> VIVO— con sus pruebas y su documentación. **No** se tocó Android TV como
> plataforma: leanback, foco D-Pad y `DeviceProfile` siguen igual.
>
> Cierre de la retirada, verificado en CI: los dos workflows (`APK build` y
> `Autonomous app checks`) están en verde con `testDebugUnitTest`,
> `lintDebug`, `lintRelease`, `assembleDebug`, `assembleRelease` y
> `assembleDebugAndroidTest`, más `verify-embedded-apk.py` sobre el APK
> debug y release. El APK publicado lleva 20 módulos Python —los diez
> proveedores VOD y sus ayudas— y **ninguno** de directo, y ya no empaqueta
> `live-catalog.json`. En el camino se corrigieron tres restos que sólo
> aparecían al compilar: `canSeekPlayback` seguía recibiendo `isLive`,
> `ChaquopyRuntime` abría el asset borrado `live-catalog.json` y
> `CatalogFeedTest` usaba una variable `live` inexistente.
>
> **Actualización (§23):** el buscador de P2 está hecho (acentos, búsqueda en
> vivo con retardo, historial y el panel derecho con su regla probada). También
> caen `cuevana.SITE["base"]` y las pruebas de `HomeViewModel.suggestions`.

> **Actualización (§21):** los dos primeros puntos de P1 —subtítulos y pista de
> audio— ya están implementados, y `loadLiveShelf` ya comparte una sola corrida
> entre peticiones simultáneas. Lo que sigue se conserva tal como se escribió
> en la auditoría, con la nota de qué quedó hecho.

---

## Arreglado en esta pasada

| Qué faltaba | Dónde estaba |
| --- | --- |
| **El teclado del buscador se salía de la pantalla** | `ui/home/CatalogSearchDialog.kt`: estaba calculado para 720 dp de alto, pero un panel 1080p informa **960 × 540 dp** (densidad 2). `BUSCAR` quedaba cortado. Ahora teclas de 48 dp y todo cabe con ~90 dp de holgura. |
| **No se podían buscar títulos con números** (1917, 2001, 24, 1984) | `TvKeyboardLayout` no tenía dígitos y el mando no los da. Fila `1…0` siempre visible. |
| **Los resultados de búsqueda no se distinguían del catálogo** | Tras buscar, el Home seguía igual (mismas filas, mismos títulos): sólo el chip *Inicio* lo delataba. Ahora hay cabecera «RESULTADOS PARA «x» · N títulos». |
| **El hero reproducía sin parrilla** | `Spotlight` no llevaba los títulos de su fila, así que *Reproducir* desde el hero abría un canal sin `CH+`/`CH−` y sin guía. Ahora `Spotlight.siblings` viaja hasta el reproductor. |

---

## P1 — Falta en el reproductor

### ✅ (hecho) Subtítulos: los proveedores los traen, la app no los usa

> **Implementado.** `server/src/lib/subtitles.js` normaliza los `meta.subtitles`
> de los proveedores; `getStreams()` los devuelve en el cuerpo de `/stream/...`
> (campo extra) y en el recurso nuevo `/subtitles/:type/:id.json`, declarado en
> el manifiesto. El motor embebido hace lo mismo (`dispatch("subtitles")` y
> `streams()` con la lista dentro). En la app: `SubtitleTrack` +
> `MediaItem.SubtitleConfiguration` en `player/MediaSources.kt` y un panel
> **Audio y subtítulos** (tvOS) en `PlayerControls`/`PlayerScreen`.

`server/scrapers/novahd.py` extrae `subtitles[]` (`url`, `label`, `lang`) y lo
deja en `meta["subtitles"]`; el servidor **no** publica el recurso `subtitles`
(`server/src/routes/` sólo tiene `catalog`, `stream`, `manifest`, `proxy`,
`admin`, `health`) y `StreamSource` (`data/model/StreamSource.kt`) no tiene
campo de subtítulos. Hoy el botón `CC` sólo enciende/apaga **la pista que ya
viene en el manifiesto HLS**.

Falta, para tenerlo de verdad:

1. ruta `GET /subtitles/:type/:id.json` en el servidor que devuelva los subtítulos
   de los proveedores (NOVA ya los tiene; los demás tendrían que exponerlos);
2. `SubtitlesResponse` + `subtitleTracks` en `StreamSource`;
3. en el reproductor, `MediaItem.SubtitleConfiguration` al construir el
   `ExoPlayer` (`player/MediaSources.kt`) y una lista de pistas para elegir
   idioma con el mando (no basta con un booleano).

### ✅ (hecho) Audio multi-idioma: el idioma no se puede cambiar

> **Implementado.** El mismo panel lista los grupos `C.TRACK_TYPE_AUDIO` con
> `TrackSelectionOverride`; «Automático» limpia los overrides y deja la pista
> principal del rip. `player/PlayerTracks.kt` tiene la regla (y sus tests).

NOVA ordena las fuentes por idioma preferido y escribe el idioma en el `name`
del stream (`novahd.py:437`), pero `StreamSource` no lleva `language` y el
reproductor **no toca** `TRACK_TYPE_AUDIO` (`grep TRACK_TYPE_AUDIO` en
`app/src/main/java` = 0 resultados). Si la única fuente que responde es la
inglesa, el usuario no puede cambiar de pista.

Falta: exponer `language` en `StreamSource`, y en los controles un selector de
pista de audio (mismo patrón que el selector de fuentes).

### ✅ (hecho) El canal actual no se recuerda

> **Implementado.** `PlaybackRestore` guarda en `onSaveInstanceState` la
> petición **en curso** (con la fuente elegida) y el segundo exacto;
> `PlayerActivity` restaura primero lo guardado y sólo cae al `Intent` si no hay
> nada. El reproductor reporta el avance cada 15 s, así que la posición guardada
> no es la de la última salida limpia. `PlaybackRestoreTest` y
> `PlayerViewModelTest`.

---

## ✅ (hecho) P2 — El buscador

Las cuatro cosas que faltaban, resueltas sin tocar el presupuesto de pantalla
(960 × 540 dp, teclas de 48 dp):

- ✅ **Acentos.** La fila de símbolos tiene dos páginas —`123` ⇄ `ÁÉÍ`— que se
  conmutan con una tecla del mismo tamaño: `Á É Í Ó Ú Ü Ñ ¿ ? ¡`. Antes no había
  forma de escribir "¿Qué pasó ayer?" con el mando. Diez teclas a cada lado para
  que el teclado mida lo mismo en las dos páginas (con la tecla de conmutación
  metida en la fila de `ESPACIO`/`BUSCAR`: 210 + 6 + 108 + 6 + 150 = 480 dp).
  `TvKeyboardAccentTest` (5 casos).
- ✅ **Búsqueda mientras se escribe.** `HomeViewModel.preview(query)` espera
  350 ms desde la última tecla (`searchDebounceMs`) y carga una sola vez: la
  consulta que gana queda en la caché del repositorio, así que pulsar `BUSCAR`
  después es instantáneo. Se mantiene en un `StateFlow` aparte (`SearchPreview`:
  `Idle`/`Loading`/`Empty`/`Ready`) para que teclear **no** vacíe el Home ni
  pinte una pantalla de error detrás del teclado; una búsqueda sin resultados es
  una lista vacía, no un fallo. `HomeViewModelSearchPreviewTest` (6 casos).
- ✅ **Historial.** `SearchHistory` (regla pura) + `SearchHistoryStore`
  (`SharedPreferences`, JSON con `kotlinx.serialization`) + `ServiceLocator`:
  ocho consultas, la más reciente primero, sin duplicados aunque cambien las
  mayúsculas, y las de una sola letra no se guardan. Con el campo vacío el panel
  derecho enseña primero el historial y después las recomendaciones de Cinemeta;
  una consulta anterior se repite con una pulsación, y «Borrar historial» está
  al final de la lista. `SearchHistoryTest` (8 casos).
- ✅ **La consulta anterior ya no se queda a ciegas**: al reabrir el buscador el
  campo trae la búsqueda activa (para poder corregirla), pero el panel derecho
  dice exactamente qué está pasando —resultados, "buscando", "sin resultados"—
  y borrar el campo devuelve el historial. La regla de qué se enseña vive en
  `searchPanelItems()` (`ui/home/SearchPanel.kt`), fuera de Compose y probada:
  `SearchPanelTest` (7 casos).

---

## P2 — Falta en el servidor

- ✅ **`loadLiveShelf` sin single-flight** (antes en
  `server/src/services/catalogService.js:131`): **hecho**. `scrapeLiveShelf()`
  se comparte entre peticiones simultáneas (`liveShelfInflight`) e
  `invalidateCatalogs()` tira también la estantería. Dos pruebas nuevas en
  `server/test/liveShelf.test.js`.
- ✅ **`hls_variants` sólo probaba `/m3u8/<id>`**: **hecho**. Estaba en
  `server/scrapers/_hosters.py` reescribiendo `/play/<id>` a una sola ruta. Ahora
  hay dos funciones, porque no todas las variantes son lo mismo:
  - `hls_variants()` devuelve los manifiestos —`/m3u8/<id>` y `/hls/<id>`, con
    y sin prefijo en el id (`/m3u8/e-<id>`)— como señales HLS directas.
  - `hls_embed_pages()` devuelve las rutas que **siguen siendo una página**
    (`/e/`, `/embed/`, `/v/`). Ésas entran en la cola de descarga del embed y
    el video se busca en el HTML: entregarlas como HLS sería darle HTML a
    ExoPlayer, que es peor que no ofrecer la fuente.
  El tope `HLS_MAX_EMBED_PAGES = 2` es corto a propósito: el presupuesto de
  documentos se **comparte** entre todos los servidores del episodio
  (`Budget(len(servers) * 2)`), así que una variante de más dejaría sin turno a
  un hoster sano. El embed original va primero en la cola, de modo que si el
  presupuesto se agota se pierde una variante, nunca el documento bueno.
  `HlsVariantsTest` y `HlsEmbedQueueTest` (8 casos) cubren el orden de las
  rutas, los prefijos, la query string, que la URL original no se repita, que
  ninguna ruta de embed salga como señal, y que un `/play/` caído con un `/e/`
  vivo siga dando el `.m3u8`.
- ✅ **TTL para URLs firmadas**: **hecho**. `server/src/lib/signedUrls.js` lee el
  vencimiento que lleva la propia URL (`?e=`, `?expires=`, `token=…~exp=…`) y
  `streamService` recorta el TTL al enlace más corto (con 15 s de margen, tanto
  en el scrape como en el refresh en segundo plano). Antes un `.m3u8` firmado a
  20 minutos se cacheaba 6 h y al reintentar daba 403: «no reproduce nada» sin
  ningún proveedor caído. `signedUrls.test.js` (6 casos, incluido el TTL real
  que devuelve `/stream`).
- ✅ **`cuevana.SITE["base"]` se evalúa al importar**: **hecho**. `SITE` ya no
  lleva `base`; el dominio se resuelve en `cuevana.site()` con `CUEVANA_BASE` y
  `CUEVANA_MIRRORS` **en cada llamada**, y `scrape()`/`main()` lo usan. Un
  `SITE["base"]` congelado en el import se quedaba con el host (o el espejo) del
  arranque y engañaba a cualquier consumidor que leyera el diccionario.
  `ProviderSiteTest` (3 casos en `test_scraper_kit.py`).
- ✅ **Agenda vacía sin explicación**: **hecho**. `/health` publica
  `liveShelf: { loaded, ageMs, ttlMs, channels, events, failedAgoMs, inflight }`,
  así que se distingue «no hay partidos hoy» de «la estantería no cargó».

---

## P2 — Falta en pruebas

- ✅ **`PlayerViewModel` sin tests**: **hecho**. `PlayerViewModelTest` cubre
  carga, subtítulos en el mismo estado, «sin fuentes», fallo de red y reintento
  con generación nueva.
- ✅ **`HomeViewModel.suggestions` sin tests**: **hecho**.
  `HomeViewModelSuggestionsTest` (3 casos) comprueba que salen de las filas sin
  pedir nada más, que buscar **no** las borra y que refrescar las rehace.
- **Nada de UI**: el teclado del buscador y los paneles del reproductor están
  pensados para el foco del D-Pad y no hay ningún test instrumentado que
  compruebe el recorrido del foco (`app/src/androidTest` sólo tiene el smoke test del motor y
  el de almacenamiento de perfiles).
- **No hay emulación de TV en CI**: compila y pasa lint, pero nadie «pulsa» las
  teclas. Lo que se rompa de foco se descubre en el sofá.

---

## P3 — Limpieza

- **`PlaybackRequest.startIndex`**: **se queda**, con contrato explícito. No es
  código muerto: viaja del `Intent` (`EXTRA_START_INDEX`) a
  `FailoverController.start(startIndex)` y `PlaybackRestore` lo conserva, así
  que es el punto por el que se recuerda la fuente que funcionó. La ficha manda
  **0** a propósito: no hay selección manual de servidor y pulsar *Play* tiene
  que bastar. Si algún día se recuerda la fuente buena por título, se cambia el
  valor, no la plomería.
- ✅ ~~`WatchProgressStore.remove()` sin pantalla~~: **hecho**. La ficha muestra
  «Dejar de seguir viendo» cuando hay progreso y borra el título **y sus
  episodios** (`removeTitle` + `WatchProgress.sameTitle`).
- ✅ ~~Comentario «opt-in»~~: **hecho**. `embedded_engine.catalogs()` y
  `CatalogRepository` dicen ahora que Cinemeta viene activado de fábrica y se
  puede apagar en Ajustes.
- ✅ ~~La cola de canales topada en 60~~: **retirado**. La parrilla de canales y
  su códec (`PlaybackQueueCodec`) se borraron con el TV en directo (§26); el
  reproductor sólo abre el título que le piden.

---

## Verificado y correcto (no tocar)

- **Sin fondos demo**: `app/src/main/res` sólo tiene iconos y `tv_banner.png`.
- **Sólo TV**: `leanback` requerida, `touchscreen` no requerida, sin layouts de
  móvil ni rotaciones.
- **Cinemeta de fábrica**: `Settings.metadataCatalogs` por defecto `true`, así
  que las filas y las recomendaciones del buscador salen sin configurar nada.
- **Perfiles**: varios perfiles con su propio «Seguir viendo»
  (`ProfileStore`, `WatchProgressStore` con tope de entradas y borrado al
  terminar).
- **Failover**: probado en `FailoverPlannerTest` / `FailoverRulesTest`; la
  fuente en uso se marca y el usuario puede cambiarla a mano.
- **Reproducción dentro de la app**: los scrapers devuelven HLS/MP4; no hay
  «abrir la web».

## Lo que no se puede verificar aquí

Los selectores y dominios de las webs de origen (Cuevana, Repelis, MonosChinos,
AnimeFLV, Gnula, PelisPlusHD, PelisJuanita, PelículasFlix, JKAnime, NOVA,
futbolibre, tvenetvivo, deportelibre, damitv, ntv, streamcorner, livelive24):
el sandbox no tiene salida a esas webs. Cualquier cambio ahí exige abrir la
página real primero, como se hizo en §14.
