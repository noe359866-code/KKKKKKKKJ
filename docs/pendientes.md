# Lo que falta

Auditoría del estado del proyecto tras el zapeo de canales (§18) y el buscador
con teclado propio (§19). Todo lo de abajo está **verificado en el código**, no
recordado: cada punto dice dónde mirar. Lo que depende de las webs (selectores,
dominios caídos) no se puede comprobar desde aquí y queda marcado como tal.

Estado: CI verde (`testDebugUnitTest`, `lintDebug`, `lintRelease`,
`assembleDebug`, `assembleRelease`, `assembleDebugAndroidTest`), Node
**260/260**, Python **102**, 18 clases de test en JVM.

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

### Subtítulos: los proveedores los traen, la app no los usa

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

### Audio multi-idioma: el idioma no se puede cambiar

NOVA ordena las fuentes por idioma preferido y escribe el idioma en el `name`
del stream (`novahd.py:437`), pero `StreamSource` no lleva `language` y el
reproductor **no toca** `TRACK_TYPE_AUDIO` (`grep TRACK_TYPE_AUDIO` en
`app/src/main/java` = 0 resultados). Si la única fuente que responde es la
inglesa, el usuario no puede cambiar de pista.

Falta: exponer `language` en `StreamSource`, y en los controles un selector de
pista de audio (mismo patrón que el selector de fuentes).

### Guía de programación por canal

Hay agenda deportiva (`tv/agenda`), pero **no hay EPG por canal**: la guía de
canales del reproductor (§18) muestra nombre y carátula, no «ahora / después».
Se podría derivar del mismo `_liveshelf` si los proveedores publican horarios;
hoy no se intenta.

### El canal actual no se recuerda

`PlayerActivity` reconstruye el `PlaybackRequest` del `Intent`
(`toPlaybackRequest()`): si el sistema mata el proceso mientras se ve una
cadena zapeada, al volver se abre el canal del `Intent`, no el que estaba
viendo. No hay `onSaveInstanceState`.

---

## P2 — Falta en el buscador

- **Sin acentos** (`Á É Í Ó Ú Ü`): el teclado sólo lleva `Ñ`. Se decidió así
  porque las búsquedas no distinguen mayúsculas y los títulos suelen
  encontrarse sin tilde, pero un título que *sólo* se encuentre con tilde no se
  puede escribir.
- **No hay búsqueda mientras se escribe**: los resultados salen al pulsar
  `BUSCAR`; no hay sugerencias en vivo (habría que pedir al repositorio con
  debounce y pintar resultados en el panel derecho en lugar de recomendaciones).
- **La consulta anterior se queda**: al reabrir, el campo trae el último texto
  en vez de vacío.
- **Sin historial de búsquedas**.

---

## P2 — Falta en el servidor

- **`loadLiveShelf` sin single-flight** (`server/src/services/catalogService.js:131`):
  tiene TTL y 60 s de calma tras un fallo, pero si `tv/canales` y `tv/agenda`
  se piden a la vez con la caché fría se lanzan **dos** `_liveshelf.py`. Los
  streams ya se coalescen (`streamService`, `result.coalesced`); la estantería de
  directo no.
- **`hls_variants` sólo prueba `/m3u8/<id>`** (`server/scrapers/_hosters.py:209`
  reescribe `/play/<id>`): no prueba las variantes `/e/`, `/embed/`, `/v/` que
  usan varios hosters.
- **TTL por proveedor para URLs firmadas por IP**: el TTL de caché es único para
  todas las fuentes; un enlace firmado por IP caduca antes de lo que dice el TTL.
- **`cuevana.SITE["base"]` se evalúa al importar** (`cuevana.py:150` con
  `_bases()[0]`): `scrape()` y `main()` lo sobrescriben, así que hoy no rompe,
  pero el `SITE` del módulo puede quedar con un dominio viejo si alguien lo usa
  directamente.
- **Agenda vacía sin explicación**: `tv/agenda` devuelve `[]` a propósito cuando
  no hay eventos (nunca se inventan partidos), y la fila desaparece del Home.
  Correcto, pero el usuario no sabe si «no hay partidos» o «falló el proveedor»:
  el diagnóstico sólo está en `/health`.

---

## P2 — Falta en pruebas

- **`PlayerViewModel` sin tests**: el zapeo depende de `tune()`, `restore()` y
  del ticket que descarta respuestas viejas; hoy sólo se prueba la aritmética
  (`ChannelZappingTest`) y la cola (`PlaybackQueueTest`).
- **`HomeViewModel.suggestions` sin tests** (la selección pura sí está probada en
  `CinemetaSuggestionsTest`).
- **Nada de UI**: la guía de canales y el teclado del buscador están pensados
  para el foco del D-Pad y no hay ningún test instrumentado que compruebe el
  recorrido del foco (`app/src/androidTest` sólo tiene el smoke test del motor y
  el de almacenamiento de perfiles).
- **No hay emulación de TV en CI**: compila y pasa lint, pero nadie «pulsa» las
  teclas. Lo que se rompa de foco se descubre en el sofá.

---

## P3 — Limpieza

- `PlaybackRequest.startIndex` sólo lo usa un test: la ficha siempre manda 0
  (no hay selección manual de servidor). O se usa para «recordar la fuente
  buena» o se borra.
- `WatchProgressStore.remove()` existe pero ninguna pantalla lo llama: no se
  puede quitar un título de «Seguir viendo» a mano (se cae solo al terminar).
- `MetadataCatalogs` viene **activado de fábrica** (`Settings.kt:46`, default
  `true`) y el docstring de `embedded_engine.catalogs()` sigue diciendo
  «opt-in». Sólo el comentario está mal.
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
