# Lo que falta

Auditoría del estado del proyecto tras el zapeo de canales (§18) y el buscador
con teclado propio (§19). Todo lo de abajo está **verificado en el código**, no
recordado: cada punto dice dónde mirar. Lo que depende de las webs (selectores,
dominios caídos) no se puede comprobar desde aquí y queda marcado como tal.

Estado: CI verde (`testDebugUnitTest`, `lintDebug`, `lintRelease`,
`assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`), Node
**281/281**, Python **108**, 27 archivos de test en JVM.

> **Actualización (§23):** el buscador de P2 está hecho (acentos, búsqueda en
> vivo con retardo, historial y el panel derecho con su regla probada) y la
> **guía por canal** ya funciona con la única fuente de horarios que existe (la
> agenda deportiva); falta la rejilla de los canales de TV, que ningún proveedor
> publica. También caen `cuevana.SITE["base"]` y las pruebas de
> `HomeViewModel.suggestions`.

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

### ✅ (parcial) Guía de programación por canal

> **Hecho lo que se puede sostener con datos reales.** `EpgEntry` viaja dentro de
> la parrilla (`PlaybackChannel.epg`, por el mismo extra del `Intent`), así que
> zapear no pide nada: la guía y el rótulo ya saben qué se emite al llegar.
> `Epg.from()` la llena con lo único que publica horarios hoy —la **agenda
> deportiva**: un partido es una emisión con hora, competición y estado— y
> `Epg.line()` la escribe como «AHORA · 19:00 · Liga 1», en la tarjeta de la guía
> y en el rótulo del cambio de canal. Sin programación no hay línea: la tarjeta
> se queda como estaba en vez de enseñar un hueco. `EpgTest` (7 casos) incluye
> el viaje por el `Intent`.
>
> Lo que **no** hay, y no se inventa: rejilla horaria de los canales de TV.
> Ninguno de los proveedores del proyecto publica EPG (ni XMLTV ni una API de
> programas) y desde aquí no se puede verificar un sitio nuevo. Cuando alguno lo
> publique basta con llenar `epg` en la fila del catálogo: el transporte, el tope
> (`Epg.MAX_ENTRIES`), el dibujo y las pruebas ya están.

### ✅ (hecho) El canal actual no se recuerda

> **Implementado.** `PlaybackRestore` guarda en `onSaveInstanceState` la
> petición **en curso** (con la parrilla y la fuente elegida) y el segundo
> exacto; `PlayerActivity` restaura primero lo guardado y sólo cae al `Intent`
> si no hay nada. El reproductor reporta el avance cada 15 s, así que la
> posición guardada no es la de la última salida limpia. Un directo no guarda
> posición (pediría un segmento fuera de la ventana del vivo), pero sí el
> canal. `PlaybackRestoreTest` (5 casos) y `PlayerViewModelTest` (7).

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
- **`hls_variants` sólo prueba `/m3u8/<id>`** (`server/scrapers/_hosters.py:209`
  reescribe `/play/<id>`): no prueba las variantes `/e/`, `/embed/`, `/v/` que
  usan varios hosters.
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
  carga, subtítulos en el mismo estado, «sin fuentes», fallo de red, zapeo con
  la imagen anterior en pantalla (`tuning`), mismo canal sin repetir petición,
  deshacer con `restore()` y reintento.
- ✅ **`HomeViewModel.suggestions` sin tests**: **hecho**.
  `HomeViewModelSuggestionsTest` (3 casos) comprueba que salen de las filas sin
  pedir nada más, que buscar **no** las borra y que refrescar las rehace.
- **Nada de UI**: la guía de canales y el teclado del buscador están pensados
  para el foco del D-Pad y no hay ningún test instrumentado que compruebe el
  recorrido del foco (`app/src/androidTest` sólo tiene el smoke test del motor y
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
- La cola de canales que viaja al reproductor está topada en 60
  (`MAX_CHANNEL_QUEUE`): una fila de 200 canales se zapea sólo por los 60
  primeros.

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
