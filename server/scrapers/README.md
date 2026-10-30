> **Uso dentro del APK:** Gradle incluye estos mismos scripts, sin copiarlos,
> mediante Chaquopy. Android llama a `scrape(payload)` en el intérprete embebido;
> no usa el puente Node, `spawn()` ni stdin. `example_provider.py` y
> `template_provider.py` se excluyen del APK. Más detalles en
> [Modo autónomo Android](../../docs/autonomous-android.md).

# Scrapers (Python)

Cada archivo `.py` de esta carpeta es un **proveedor** independiente que el
servidor Node ejecuta sólo cuando hay un *cache miss*.

## Contrato Node ↔ Python

```
stdin  → {"type":"movie","id":"tt1254207","baseId":"tt1254207","imdbId":"tt1254207",
          "season":null,"episode":null,"isLive":false,"isSports":false,
          "provider":"mi-sitio","referer":"https://sitio/","userAgent":"Mozilla/5.0…",
          "timeoutMs":12000}
stdout ← {"streams":[
            {"name":"Mi Sitio · 1080p","title":"Mi Sitio · 1080p",
             "url":"https://cdn…/index.m3u8","quality":"1080p",
             "referer":"https://sitio/","userAgent":"Mozilla/5.0…",
             "behaviorHints":{"notWebReady":false,"mediaType":"hls"},"isLive":false}
          ],
          "meta":{"cualquier":"dato de debug"}}
```

Reglas:

1. **Sólo JSON en stdout.** Cualquier log va a stderr con `_base.log()`.
2. Si la URL no tiene extensión, manda `behaviorHints.mediaType` (`hls` /
   `dash` / `mp4`): el cliente lo usa para elegir el `MediaSource` correcto.
3. Devuelve `{"streams": []}` cuando el título no exista; no lances excepciones
   para casos esperados. `_base.run()` captura todo de todos modos.
3. Respeta `timeoutMs`: el padre mata el proceso (SIGKILL al grupo) al vencer.
4. Usa `_base.default_headers(payload)` para enviar el `Referer`/`User-Agent`
   correctos al sitio; suelen ser obligatorios para el `.m3u8` final.
5. Un script nunca debe imprimir credenciales ni URLs firmadas en logs.

## Archivos

| Archivo | Uso |
| --- | --- |
| `_base.py` | Helpers compartidos: lectura de stdin, emisión de JSON, `stream()`. |
| `_htmlkit.py` | Kit común de scraping: `Deadline`/`Budget`, `http_get` (gzip, codificación, reintentos), desempaquetado de JS, `media_urls`, `sanitize_media`, `guess_quality`, tokens. |
| `example_provider.py` | Demo con streams públicos (Mux / Google samples). Verifica el pipeline completo. |
| `template_provider.py` | Plantilla comentada para un sitio real (urllib + regex, sin dependencias). |
| `tvenvivo.py` | **Proveedor real** de `www.tvenvivo.org` (TV/deportes en directo, LATAM). Ver la sección siguiente. |
| `deportelibre.py` | **Proveedor real** de `deportelibre.top` (agenda deportiva + canales 24/7). Ver su sección. |
| `futbolibre.py` | **Proveedor real** de `futbolibre.live` (canales 24/7 de México + agenda de partidos con varias señales). Ver su sección. |
| `_hosters.py` | Resolutor de los "servidores" de video que usan los sitios de anime (Streamwish, VOE, Filemoon, Mixdrop, Streamtape, Mp4upload…) → `.m3u8`/`.mp4`. |
| `_animekit.py` | Capa común de anime: ids `proveedor:slug:episodio`, lectura de literales JS, base64, formato de las señales y búsqueda por nombre. |
| `animeflv.py` | **Proveedor real** de `animeflv.net` (VOD). |
| `jkanime.py` | **Proveedor real** de `jkanime.net` (VOD). |
| `monoschinos.py` | **Proveedor real** de `monoschinos.st` (VOD). |
| `_peliskit.py` | Capa común de películas y series: ids `proveedor:slug[:temporada:episodio]`, búsqueda, recogida de servidores y formato de las señales. |
| `_tmdb.py` | Traducción **opcional** de ids IMDb / títulos con TMDB (necesita `TMDB_API_KEY`). |
| `gnula.py` | **Proveedor real** de `ww3.gnulahd.nu` (películas, series y anime). |
| `cuevana.py` | **Proveedor real** de `cue.cuevana3.nu`. |
| `pelisplushd.py` | **Proveedor real** de `pelisplushd.la` (PelisPlusHD). |
| `repelis.py` | **Proveedor real** de Repelis (dominio configurable por entorno). |
| `peliculasflix.py` | **Proveedor real** de `peliculasflix.co` (API GraphQL). |
| `pelisjuanita.py` | **Proveedor real** de `pelisjuanita.org`. |

## Resiliencia compartida (lo que ya no hay que reescribir)

Todos los proveedores pasan por los mismos kits, así que una mejora ahí vale
para los doce. Lo que hay hoy:

| Pieza | Qué hace |
| --- | --- |
| `http_get()` | Descomprime gzip/deflate **aunque la cabecera mienta** o falte, resuelve la codificación con BOM → UTF-8 estricto → `charset` declarado → `<meta charset>` → cp1252 (los títulos latinos con `Ã©` rompían las búsquedas) y **reintenta una vez** los 429/5xx y los cortes de conexión. El reintento no alarga el presupuesto: si el primer intento ya gastó la mitad del `timeout`, no se repite. Los 4xx de verdad y los timeouts no se reintentan. |
| `is_media_url()` | Acepta manifiestos que viajan en la consulta (`/player.php?id=7&file=master.m3u8`), no sólo rutas que acaban en `.m3u8`. |
| `typed_media_urls()` | Lee las fuentes que declaran su tipo (`sources: [{file: "…", type: "application/x-mpegURL"}]`, `<source type="video/mp4" src="…">`) **aunque la URL no tenga extensión**, y `relative_media_urls()` resuelve las rutas relativas (`/hls/master.m3u8`). Antes, jwplayer sin extensión = "sin fuentes". |
| `probe_media_kind()` | Para las URL que no delatan el tipo, lee los primeros 2 KB (`Range`) y devuelve `hls` / `dash` / `mp4`; si lo que hay es una página HTML, descarta el candidato en vez de ofrecerlo. Máximo dos sondas por servidor y con el deadline. |
| `behaviorHints.mediaType` | Cada stream sale con `hls`, `dash` o `mp4` cuando se sabe: es la única pista que permite a ExoPlayer reproducir un manifiesto en `https://cdn/v/abc123`. |
| `guess_quality()` + `stream()` | Deduce `4K` / `1440p` / `1080p` / `720p` / `480p` / `SD` / `CAM` de la etiqueta del servidor y de la URL, y `stream()` normaliza lo que mande el proveedor (`FullHD` → `1080p`). Si no hay pista, el campo se omite: el cliente prefiere "sin dato" a una etiqueta falsa. |
| `best_match()` | Elige la ficha correcta **por tokens**, no por caracteres: ignora `latino`, `HD`, `1080p`, `subtitulado`, el año…, exige coincidencia de palabra (`up` ya no casa con `Upload`) y desempata por el título con menos palabras de más. Devuelve la puntuación, y `search_site()` sigue probando buscadores mientras el resultado sea flojo. |
| `resolve_all()` | Segunda pasada para los embeds que fallaron (sólo si queda tiempo) y `meta.resolved_hosts` con las familias que sí resolvieron. |
| `resolve_signals()` | Si el sitio publica más servidores que el tope, los de familias que sabemos resolver (`streamwish`, `filemoon`, `voe`, `streamtape`…) se conservan al final como último failover en vez de perderse. |
| `fallback_queries()` | Si el título completo no aparece en el buscador, reintenta sin año ni paréntesis ("Spider-Man: No Way Home (2021)" → "Spider-Man: No Way Home") y con las primeras palabras. |
| `expand_player_pages()` | Cuando un "servidor" es una página del propio sitio (`/player/<id>`, `/reproductor/<hash>`), se abre una vez más y se leen los embeds de dentro: sin esto `_hosters` sólo veía una página sin `.m3u8` y la daba por irresoluble. Sólo el propio sitio, sólo las primeras páginas y con el deadline. |
| `fill()` | Además de `{slug}`, `{season}`, `{episode}` y `{query}`, las plantillas admiten `{season2}` / `{episode2}` para los sitios que numeran con dos dígitos (`/serie/x/1x03`). |
| `scrape_site()` | **404 blando**: si la ficha directa responde 200 pero no trae ni un reproductor, prueba el buscador del sitio y reintenta una vez (`meta.resolved_by = "search_retry"`). |
| `page_for()` | Ordena las plantillas del sitio como *capítulos probables → ficha → fichas extra (`ficha_paths`) → variantes que quedaban fuera*, con un presupuesto de 7 pedidos. Antes el tope se agotaba con el primer slug y las variantes del id (`slug-2026`) no se probaban nunca. |
| `ficha_paths` | Fichas adicionales del mismo sitio: PelisPlus y PelisJuanita abren también `/anime/<slug>` cuando la de serie no existe (el anime estaba en `search_hints` pero no en las plantillas). |
| `candidate_episode_links()` | Reconoce el número **al final** del enlace (`/ver/one-piece-1180`, `episodio/1180`) con `(?![0-9])`, para que el episodio `1` no case con `1180`; además de `1x04`, `temporada/1/capitulo/4`, `Capitulo 4`… |
| `fallback_episode_servers()` | Si la página del capítulo no trae ni un reproductor, abre la **ficha del anime** y, si ahí está el enlace bueno del capítulo, sigue hasta la página que sí lo tiene. Es lo que rescata el capítulo de anime que el sitio publica en otra ruta. Dos páginas como mucho y con el deadline. |
| `episode_link_servers()` | Ficha de serie → página del capítulo: prueba **cinco** enlaces (antes tres) y con el `extras()` del sitio, así que los reproductores que vienen por su API también cuentan. |

Pruebas de todo esto, offline y sin tocar la red:

```bash
python3 -m unittest discover -s app/src/test/python -v   # incluye test_scraper_kit.py
cd server && npm test                                    # regresiones por proveedor
```

## Añadir un proveedor

```bash
cp scrapers/template_provider.py scrapers/mi_sitio.py
# edita mi_sitio.py: url de búsqueda, parseo del player, calidad…
```

Si el sitio es como los de arriba (página → iframe/player → `.m3u8`), no
escribas la plomería otra vez: `_htmlkit.py` ya trae los presupuestos
(`Deadline`, `Budget`), `http_get` con timeout, el desempaquetado de JS
(`unpack_js`), `media_urls()` / `player_links()` y `sanitize_media()`.
`futbolibre.py` es el ejemplo más corto de un proveedor completo.

```jsonc
// server/config/providers.json
{
  "id": "mi-sitio",
  "label": "Mi Sitio",
  "script": "mi_sitio.py",
  "priority": 15,        // menor = primero en el array de streams
  "kind": "vod",         // vod | live | any
  "enabled": true,
  "referer": "https://mi-sitio.example/",
  "options": { "locale": "es" }   // llega al payload de Python
}
```

Prueba sin arrancar el servidor:

```bash
node scripts/bridge-demo.js mi_sitio.py '{"type":"movie","id":"tt0133093","provider":"mi-sitio"}'
```

## Proveedor incluido: `tvenvivo.py`

Scraper real de **https://www.tvenvivo.org/** (canales de TV y deportes en
directo, señales de LATAM). Viene habilitado en `config/providers.json` con
`kind: "live"` y prioridad **5** (antes que los `demo-*`).

### Cómo publica el sitio la señal

```
/espn-2-en-vivo-online.php          ← 1. página del canal (la lista el home)
      │  const SOURCES = {1:"/live/core.php?canal=espn-2", 2:"…"}
      ▼
/live/core.php?canal=espn-2         ← 2. wrapper PHP
      │  <iframe src="/embed/player.php…">
      ▼
embed…/player.php                   ← 3. reproductor JW/Clappr
      │  file: "https:\/\/cdn…\/index.m3u8"
      ▼
https://cdn…/index.m3u8            ← señal final
```

El scraper busca el mapa `SOURCES`, sigue wrappers e iframes (máximo
`TVENVIVO_MAX_DOCS` documentos y `TVENVIVO_MAX_DEPTH` saltos), desescapa las
URLs que vienen en JavaScript (`https:\/\/…`, `\u0026`) y descarta hosts de
publicidad. Devuelve **una entrada por opción** para que ExoPlayer haga
failover opción 1 → 2 → 3.

### IDs aceptados

Todos estos resuelven el mismo canal:

| Forma | Ejemplo |
| --- | --- |
| id de Stremio (recomendado) | `tv:tvenvivo:espn-2` |
| prefijo del proveedor | `tvenvivo:espn-2` |
| slug | `espn-2` |
| página del canal | `espn-2-en-vivo-online` / `espn-2-en-vivo-online.php` |
| URL completa | `https://www.tvenvivo.org/espn-2-en-vivo-online.php` |
| nombre libre | `ESPN 2`, `liga 1` (se resuelve contra el índice del home) |

```bash
curl -s "http://localhost:7000/stream/tv/tv:tvenvivo:espn-2.json" | jq
```

### Listar los canales disponibles

```bash
cd server
npm run channels:tvenvivo                  # tabla nombre + id listo para Stremio
npm run channels:tvenvivo -- --query espn  # filtrar
npm run channels:tvenvivo -- --json        # JSON (slug, name, url, id)
npm run channels:tvenvivo -- --refresh     # ignorar la caché del índice
```

El índice del home se cachea en disco (`/tmp/streamflix-tvenvivo-index.json`)
durante 6 h para no descargarlo en cada consulta.

### Variables de entorno (todas opcionales)

| Variable | Default | Descripción |
| --- | --- | --- |
| `TVENVIVO_BASE` | `https://www.tvenvivo.org` | Base del sitio; los tests la apuntan a un fixture local. |
| `TVENVIVO_INDEX_TTL_SECONDS` | `21600` | TTL del índice de canales (`0` = sin caché). |
| `TVENVIVO_INDEX_CACHE` | `/tmp/streamflix-tvenvivo-index.json` | Ruta del índice. |
| `TVENVIVO_MAX_DOCS` | `8` | Documentos máximos por consulta. |
| `TVENVIVO_MAX_DEPTH` | `3` | Saltos máximos wrapper → iframe → player. |

### Pruebas

`test/tvenvivo.test.js` reproduce el sitio con un fixture HTTP local (página →
`SOURCES` → wrapper → iframe → `.m3u8` escapado), así que valida el parseo y el
recorrido **sin red externa**:

```bash
node --test test/tvenvivo.test.js
```

Ante cualquier fallo (canal inexistente, sitio caído, timeout) el script
responde `{"streams":[]}` con `exit 0`: el pool de proveedores sigue con el
resto y el addon nunca devuelve un 500.

> ⚠️ Este scraper sólo lee páginas públicas del sitio y no aloja ni
> redistribuye contenido. Úsalo únicamente si tienes autorización para ello y
> respetando los términos del sitio y la ley de tu país.

## Proveedor incluido: `deportelibre.py`

Scraper real de **https://deportelibre.top/** (agenda deportiva y canales 24/7).
Viene habilitado con `kind: "live"` y prioridad **4**.

### Cómo publica el sitio la señal

```
/stream/                              ← 1. catálogo de canales 24/7
      │  <a href="/total/stream-01.php">▶ Ver canal</a>   … ESPN
      ▼
/total/stream-01.php                  ← 2. página del canal: opciones
      │  /en-vivo-online/espn/embed2.php  (Opción 1)
      │  /en-vivo-online/espn/embed3.php  (Opción 2)
      │  /en-vivo-online/espn/embed.php   ((VPN AR))
      ▼
reproductor Clappr                    ← 3. la URL del CDN va en el JS
      │  var src="https://host:8443/hls/<ref>.m3u8?s=<token>&e=<caducidad>"
      ▼
https://host:8443/hls/<ref>.m3u8?s=…&e=…   ← señal final (HLS con token)
```

Dos detalles importantes del sitio:

1. **El JS viene empaquetado** con `eval(function(p,a,c,k,e,d){…})`, y el
   empaquetador sustituye el esquema por un índice de su tabla, así que en el
   HTML se lee `46://host/…`. El scraper **desempaqueta** el bloque (y, si la
   tabla no se puede recuperar, normaliza `46://` → `https://`).
2. Algunas variantes del mismo backend publican la señal como JSON
   (`{"stream_url": …, "stream_url_nop2p": …, "p2p": true}`). Ambas formas se
   reconocen, igual que los tokens con `&amp;` y las barras escapadas.

Cada opción se descarga en paralelo (máx. `DEPORTELIBRE_MAX_OPTIONS`), se
deduplica y se devuelve **una entrada por opción**; los tokens caducados
(`e=<epoch>` en el pasado) se marcan `· token caducado` y van al final.

### IDs aceptados

| Forma | Ejemplo |
| --- | --- |
| id de Stremio (recomendado) | `tv:deportelibre:stream-01` |
| prefijo del proveedor | `deportelibre:stream-387` |
| id del catálogo | `stream-387` |
| slug del sitio | `espn-premium` |
| página del canal | `https://deportelibre.top/total/stream-387.php` |
| nombre libre | `ESPN Premium`, `liga 1 max` (se busca en el catálogo) |

```bash
curl -s "http://localhost:7000/stream/tv/tv:deportelibre:stream-01.json" | jq
```

### Listar los canales disponibles

```bash
cd server
npm run channels:deportelibre                  # nombre + id listo para Stremio
npm run channels:deportelibre -- --query espn  # filtrar
npm run channels:deportelibre -- --json        # JSON
npm run channels:deportelibre -- --refresh     # ignorar la caché del catálogo
```

El catálogo se cachea en disco (`/tmp/streamflix-deportelibre-index.json`) 6 h.

### Variables de entorno (opcionales)

| Variable | Default | Descripción |
| --- | --- | --- |
| `DEPORTELIBRE_BASE` | `https://deportelibre.top` | Base del sitio (los tests usan un fixture local). |
| `DEPORTELIBRE_CHANNELS_PATH` | `/stream/` | Ruta del catálogo de canales. |
| `DEPORTELIBRE_INDEX_TTL_SECONDS` | `21600` | TTL del catálogo (`0` = sin caché). |
| `DEPORTELIBRE_INDEX_CACHE` | `/tmp/streamflix-deportelibre-index.json` | Ruta del catálogo. |
| `DEPORTELIBRE_MAX_OPTIONS` | `4` | Opciones de reproducción por canal. |
| `DEPORTELIBRE_MAX_DEPTH` | `2` | Saltos dentro de cada reproductor. |
| `DEPORTELIBRE_MAX_DOCS` | `12` | Documentos totales por consulta. |

### Pruebas

`test/deportelibre.test.js` reproduce el sitio con un fixture HTTP local
(catálogo → página del canal → reproductor empaquetado / JSON → `.m3u8`) y no
usa la red:

```bash
node --test test/deportelibre.test.js
```

> ⚠️ Igual que `tvenvivo.py`, sólo lee páginas públicas y no aloja ni
> redistribuye contenido. Úsalo con autorización y respetando los términos del
> sitio y la ley de tu país.

## Proveedor incluido: `futbolibre.py`

Scraper real de **https://futbolibre.live/** ("Fútbol Libre" / "Pelota Libre TV").
Viene habilitado con `kind: "live"` y prioridad **3** (antes que `deportelibre`
y `tvenvivo`, así que sus señales aparecen primero en el array de Stremio).

Cubre dos catálogos distintos:

* **canales 24/7** de México (`/en-vivo/<slug>`): ESPN 1/2/3, FOX Sports 1/2/3,
  FOX Sports Premium y TUDN;
* **agenda de partidos** del backend (`la18hd.su/eventos/json/agenda123.json`),
  donde cada partido trae **varias señales** (una por proveedor).

### Cómo publica el sitio la señal

```
/ y /deportes/                        ← 1. tarjetas de canal
      │  <a href="/en-vivo/espn-1">ESPN 1 · Ver Canal</a>
      ▼
/en-vivo/espn-1/                      ← 2. la página sólo embebe el backend
      │  <a href="https://la18hd.su/vivo/canal.php?stream=espnmx">Disabled</a>
      │  (en otros canales: <iframe src="…/sw3.html?get=<reproductor>">)
      ▼
/vivo/canal.php?stream=espnmx         ← 3. reproductor: la URL va en el JS
      │  var src="https://host/hls/<ref>/index.m3u8?s=<token>&e=<caducidad>&ip=<ip>"
      ▼
https://host/hls/<ref>/index.m3u8?s=…&e=…   ← señal final (HLS con token)
```

```
la18hd.su/eventos/json/agenda123.json ← 1. agenda del día (JSON)
      │  [{"title":"Liga 1: Cienciano vs Atlético Grau","category":"futbol",
      │    "time":"19:00","status":"En vivo",
      │    "link":"https://la18hd.su/vivo/canales.php?stream=liga1max"}, …]
      ▼
mismo reproductor (o tarjetarojita.xyz/sw3.html?get=…)
      ▼
una señal por enlace → failover dentro del mismo partido
```

Detalles que el scraper resuelve:

1. **Un partido, varias señales**: el JSON repite el mismo título con enlaces de
   distintos proveedores. Se agrupan por título (`slug`), se deduplican y se
   devuelve **una entrada por señal**, en el orden de la agenda.
2. **Parámetro `ip`**: el CDN añade `&ip=<ip del servidor>`. Se elimina, porque
   está atado a quien descargó la página y no al televisor.
3. **Saltos intermedios**: `sw3.html?get=https://…/repro/x.html` se desenvuelve y
   se siguen los iframes (máx. `FUTBOLIBRE_MAX_DEPTH`).
4. **Tokens caducados** (`e=<epoch>` en el pasado) se marcan `· token caducado`
   y se devuelven al final, para que ExoPlayer no empiece por una señal muerta.
5. El nombre de cada señal es `Canal · Señal N` (más el host del CDN cuando las
   señales vienen de backends distintos), así el usuario ve el orden de failover.

### IDs aceptados

| Forma | Ejemplo |
| --- | --- |
| canal (recomendado) | `tv:futbolibre:espn-1` |
| partido (recomendado) | `tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau` |
| prefijo del proveedor | `futbolibre:tudn-mx` |
| slug del canal | `fox-sports-premium`, `espn-3` |
| id del partido | `evento:liga-1-cienciano-vs-atletico-grau` |
| página del canal | `https://futbolibre.live/en-vivo/espn-1` |
| nombre libre | `TUDN`, `Cienciano` (se busca en canales y en la agenda) |

```bash
curl -s "http://localhost:7000/stream/tv/tv:futbolibre:espn-1.json" | jq
curl -s "http://localhost:7000/stream/tv/tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau.json" | jq
```

### Listar canales y partidos

```bash
cd server
npm run channels:futbolibre                    # canales + agenda de hoy
npm run channels:futbolibre -- --channels      # sólo canales
npm run channels:futbolibre -- --events        # sólo partidos
npm run channels:futbolibre -- --query liga    # filtrar
npm run channels:futbolibre -- --json          # JSON
npm run channels:futbolibre -- --refresh       # ignorar caché
```

El catálogo de canales se cachea 6 h y la agenda 10 min
(`/tmp/streamflix-futbolibre-index.json` y `…-agenda.json`).

### Variables de entorno (opcionales)

| Variable | Default | Descripción |
| --- | --- | --- |
| `FUTBOLIBRE_BASE` | `https://futbolibre.live` | Base del sitio (los tests usan un fixture local). |
| `FUTBOLIBRE_AGENDA_URL` | `https://la18hd.su/eventos/json/agenda123.json` | JSON con la agenda y sus señales. |
| `FUTBOLIBRE_INDEX_TTL_SECONDS` | `21600` | TTL del catálogo de canales (`0` = sin caché). |
| `FUTBOLIBRE_AGENDA_TTL_SECONDS` | `600` | TTL de la agenda. |
| `FUTBOLIBRE_INDEX_CACHE` | `/tmp/streamflix-futbolibre-index.json` | Ruta del catálogo. |
| `FUTBOLIBRE_AGENDA_CACHE` | `/tmp/streamflix-futbolibre-agenda.json` | Ruta de la agenda. |
| `FUTBOLIBRE_MAX_SIGNALS` | `6` | Señales que se siguen por canal/partido. |
| `FUTBOLIBRE_MAX_DEPTH` | `2` | Saltos dentro de cada reproductor. |
| `FUTBOLIBRE_MAX_DOCS` | `12` | Documentos totales por consulta. |

### Pruebas

`test/futbolibre.test.js` reproduce el sitio con un fixture HTTP local (home →
página del canal → reproductor → `.m3u8`, más la agenda con señales duplicadas,
un salto `sw3.html?get=` y un token caducado) y no usa la red:

```bash
node --test test/futbolibre.test.js
```

> ⚠️ Igual que los otros proveedores, sólo lee páginas públicas y no aloja ni
> redistribuye contenido. Úsalo con autorización y respetando los términos del
> sitio y la ley de tu país.

## Proveedores de anime (VOD)

Estos tres proveedores son **VOD** (`kind: "vod"`): no emiten en directo, sirven
episodios concretos. Comparten la misma forma y por eso la plomería está en dos
módulos en vez de repetirse:

| Módulo | Responsabilidad |
| --- | --- |
| `_animekit.py` | id `proveedor:slug[:temporada]:episodio`, lectura de los literales JS (`var videos = […]`), base64 de los embeds, etiquetas de los streams y búsqueda por nombre. |
| `_hosters.py` | convertir la URL de un servidor (Streamwish, VOE, Filemoon, Mixdrop, Streamtape, Mp4upload, YourUpload, Okru, Doodstream…) en un `.m3u8`/`.mp4` reproducible, incluido el desempaquetado del JS empaquetado. |

### Formato de los ids

```
animeflv:one-piece:1180               episodio
animeflv:one-piece:1:1180             temporada:episodio (lo que manda Stremio)
monoschinos:one-piece-sub-espanol:1   slug del sitio + episodio
https://www3.animeflv.net/ver/one-piece-1180
```

Se piden en `GET /stream/anime/<id>.json`. La respuesta trae **un stream por
servidor resuelto**, en el orden del sitio (que es el que va de mejor a peor),
así que ExoPlayer hace failover de servidor a servidor sin cambiar de episodio:

```
"AnimeFLV · One Piece · Ep. 1180 · Streamwish"     ← Señal 1
"AnimeFLV · One Piece · Ep. 1180 · Filemoon"       ← Señal 2
"AnimeFLV · One Piece · Ep. 1180 · VOE"            ← Señal 3
```

Los servidores que sólo alojan archivos (Mega, Mediafire, 1fichier) no son
reproducibles: se descartan y se listan en `meta.unresolved`, junto con los que
estaban caídos. Si la página no expone nada, la respuesta es
`{"streams": [], "meta": {"reason": …}}` y el addon sigue con el proveedor
siguiente.

## Proveedor incluido: `animeflv.py`

Scraper de **https://www3.animeflv.net/**. Prioridad **6**.

```
/ver/one-piece-1180                   ← 1. página del episodio
      │  var videos = [{server:"stape", code:"aHR0cHM6…"}, …]   (embed en base64)
      ▼
https://streamtape.com/e/…            ← 2. embed del servidor
      ▼
https://cdn…/index.m3u8?s=…           ← 3. señal (.m3u8 o .mp4)
```

* La lista viene en `var videos`, con el embed **en base64** en `code`; también
  se aceptan `url`, `embed` y `file`.
* `var episodes = [[n, "slug-n"]]` se lee para `--list` y para el respaldo.
* Si el slug del id no existe, se resuelve por la búsqueda del sitio
  (`/browse?q=…`).
* Si el sitio devuelve un reto anti-bot, el motivo es `blocked` (no se insiste:
  reintentar empeora el bloqueo).

```bash
npm run anime:animeflv                      # últimos episodios + ids
npm run anime:animeflv -- --query "one piece"
npm run anime:animeflv -- --query "one piece" --json
curl -s "http://localhost:7000/stream/anime/animeflv:one-piece:1180.json" | jq
```

## Proveedor incluido: `jkanime.py`

Scraper de **https://jkanime.net/**. Prioridad **7**.

```
/one-piece/1180/                      ← 1. página del episodio
      │  var servers = [{server:"Desu", remote:"aHR0cHM6…", lang:"sub"}, …]
      │  video[0] = '<iframe src="…">'
      ▼
https://voe.sx/e/…                    ← 2. embed (VOE redirige por JavaScript)
      ▼
https://cdn…/index.m3u8               ← 3. señal
```

* `remote` viene en base64; `var remote = "https://c1.jkplayers.com"` son los
  enlaces de **descarga** (`/d/<slug>`) y se ignoran a propósito.
* Los reproductores sueltos (`video[N] = '<iframe …>'`) también se leen.
* El `<h1>` es `Episodio 1180 Japones - One Piece`: se limpia hasta `One Piece`.

```bash
npm run anime:jkanime
npm run anime:jkanime -- --query "one piece"
curl -s "http://localhost:7000/stream/anime/jkanime:one-piece:1180.json" | jq
```

## Proveedor incluido: `monoschinos.py`

Scraper de **https://monoschinos.st/** (antes `monoschinos2.com`; el dominio
cambia, por eso `MONOSCHINOS_BASE` es configurable). Prioridad **8**.

```
/ver/one-piece-episodio-1             ← 1. página del episodio
      │  <button data-player="tok…">voe</button>   (un botón por servidor)
      ▼
/reproductor?url=tok…                 ← 2. el sitio canjea el token
      │  → <script>window.location = atob("aHR0cHM6…")</script>
      ▼
https://voe.sx/e/…                    ← 3. embed → .m3u8 / .mp4
```

* `data-player` puede ser un token opaco (se canjea en `/reproductor`) o ya una
  URL/base64 de la URL: se aceptan los tres casos.
* Los botones de descarga (Mega, Mediafire, 1fichier) se filtran antes de
  resolver.

```bash
npm run anime:monoschinos
npm run anime:monoschinos -- --query "one piece"
curl -s "http://localhost:7000/stream/anime/monoschinos:one-piece-sub-espanol:1.json" | jq
```

### Qué servidores se pueden resolver

| Servidor | Cómo |
| --- | --- |
| HLS (`/play/<id>`) | se pide `/m3u8/<id>` |
| Streamwish, Vidhide, VOE, Filemoon (variante plana) | `sources`/`file` en el HTML, también si vienen empaquetados con `eval(function(p,a,c,k,e,d)…)` |
| Mixdrop | `MDCore.wurl` tras desempaquetar |
| Streamtape | `id="ideoooolink"` + `token=` |
| Mp4upload, Uqload, Obeywish | `src:`/`file:` con `.mp4` |
| YourUpload | `<meta property="og:video">` |
| Okru | `data-options` → `hlsMasterPlaylistUrl` |
| Doodstream | mejor esfuerzo (`window.location`, `/pass_md5/`) |
| Mega, Mediafire, 1fichier | **no**: no son reproducibles, se descartan |

### Variables de entorno comunes (opcionales)

| Variable | Default | Descripción |
| --- | --- | --- |
| `ANIMEFLV_BASE` / `JKANIME_BASE` / `MONOSCHINOS_BASE` | los dominios oficiales | Base del sitio (los tests usan un fixture local). |
| `*_EPISODE_PATH` | `/ver/{slug}-{episode}`, `/{slug}/{episode}/`, `/ver/{slug}-episodio-{episode}` | Plantilla de la página del episodio. |
| `*_SEARCH_PATH` | `/browse?q={query}`, `/buscar/{query}/`, `/buscar?q={query}` | Búsqueda por nombre. |
| `*_MAX_SERVERS` | `6` | Servidores que se intentan por episodio. |
| `*_PARALLEL` | `4` | Resoluciones simultáneas. |

Las plantillas se pueden sobreescribir sin tocar código: si el sitio renombra
`/ver/` o cambia el buscador, basta con ajustar la variable.

### Pruebas

Cada proveedor tiene su fixture HTTP local (página del episodio → embeddings →
`.m3u8`/`.mp4`), sin red:

```bash
node --test test/animeflv.test.js
node --test test/jkanime.test.js
node --test test/monoschinos.test.js
```

Los fixtures cubren base64, literales JS con claves sin comillas, reproductores
empaquetados, redirects por JavaScript, servidores caídos y de descarga, el
respaldo por búsqueda y la degradación a `{"streams": []}`.

Si la página del capítulo llega **sin** reproductores, los tres abren la ficha
del anime y, cuando ahí aparece el enlace bueno del capítulo
(`/ver/one-piece-1180-latino`, `episodio-1180`…), siguen hasta la página que sí
lo tiene: entonces `meta.servers_via = "anime_page"` y `meta.servers_from` dice
de dónde salieron. Es la diferencia entre `no_servers` y ver el episodio.

> ⚠️ Los tres leen páginas públicas y no alojan ni redistribuyen contenido.
> Úsalos con autorización y respetando los términos de cada sitio y la ley de
> tu país. Los dominios y los servidores de estos sitios cambian a menudo: si un
> proveedor devuelve `no_servers`, lo primero es abrir el episodio en el
> navegador y comprobar la ruta/variables de entorno.

## Proveedores de películas y series (VOD)

Seis sitios con el mismo esqueleto: ficha por título, buscador y una lista de
"servidores" (Streamwish, VOE, Filemoon, Streamtape, Mixdrop, Mp4upload…) que
esconden el `.m3u8`/`.mp4`. Lo compartido vive en dos módulos:

| Módulo | Responsabilidad |
| --- | --- |
| `_peliskit.py` | id → ficha (directa o por búsqueda), recogida de servidores (iframes, `data-*`, literales JS, `og:video`, `showEmbed`), etiquetas y flujo completo (`scrape_site`). |
| `_hosters.py` | convertir la URL de cada servidor en un medio reproducible, incluido el JS empaquetado. |
| `_tmdb.py` | opcional: IMDb/título → título en español + año → slug. |

### Formato de los ids

```
gnula:street-fighter                 slug del sitio
gnula:visionquest:1:2                temporada:episodio (lo que manda Stremio)
movie:gnula:street-fighter           con prefijo de tipo del addon
gnula:tt0145487                      IMDb → TMDB (necesita TMDB_API_KEY)
gnula:street fighter                 texto libre → buscador del sitio
https://ww3.gnulahd.nu/ver/street-fighter/
```

La respuesta trae **un stream por servidor resuelto**, en el orden del sitio, así
que ExoPlayer hace failover de servidor a servidor sin cambiar de película:

```
"Gnula · Street Fighter · Latino - Streamwish"     ← Señal 1
"Gnula · Street Fighter · Latino - Filemoon"       ← Señal 2
```

Mega, Mediafire y 1fichier no son reproducibles: se descartan y se listan en
`meta.download_only`. Si no queda nada, `{"streams": [], "meta": {"reason": …}}`
y el addon sigue con el proveedor siguiente. Motivos posibles: `not_vod`,
`no_id`, `not_found`, `page_unavailable`, `no_servers`, `no_streams`.

### Prioridades

| Prioridad | Proveedor | Sitio |
| --- | --- | --- |
| 11 | `gnula` | [ww3.gnulahd.nu](https://ww3.gnulahd.nu/) |
| 12 | `cuevana` | [cue.cuevana3.nu](https://cue.cuevana3.nu/) |
| 13 | `pelisplushd` | [pelisplushd.la](https://www.pelisplushd.la/) |
| 14 | `repelis` | dominio configurable |
| 15 | `peliculasflix` | [peliculasflix.co](https://peliculasflix.co/) |
| 16 | `pelisjuanita` | [pelisjuanita.org](https://pelisjuanita.org/) |

```bash
curl -s "http://localhost:7000/stream/movie/gnula:street-fighter.json" | jq
curl -s "http://localhost:7000/stream/series/pelisplushd:the-last-of-us:1:2.json" | jq
npm run movies:gnula -- "street fighter"      # buscar e imprimir ids
npm run movies:cuevana -- "the last of us"
```

### Los siete sitios, uno por uno

#### `gnula.py` — Gnula (prioridad 11)

```
/ver/street-fighter/                       ficha
   │  window.GNRD={pid:1234, tok:"…", k:"…"}
   ▼
/wp-json/gnrd/v1/player?id=1234&t=tok      API del reproductor
   │  {"p":"<carga ofuscada>"}
   ▼
ROT13 → "_" → base64 → −3 por carácter → invertir → base64 → JSON
   │  [{"label":"Latino","servers":[{"src":"https://streamwish.to/e/…"}]}]
   ▼
_hosters.py                                .m3u8 / .mp4
```

Es el único que necesita criptografía: el desofuscado está en
`gnula.decrypt_player()` y se prueban varias variantes (cadena completa, sin
ROT13 y base64 plano) porque el sitio y sus clones no siempre coinciden. En las
series, cada capítulo es un `<a class="gnrd-epc" data-s data-e data-id data-t>`
y se llama a la API con **su** `id`/`t` sin abrir otra página. El buscador
también es JSON (`/wp-json/gnrd/v1/search?q=`).

#### `cuevana.py` — Cuevana (prioridad 12)

```
/peliculas/<slug>                          ficha
   │  <iframe src="…/embed-page?showEmbed=<base64>">
   ▼
https://vimeos.net/embed-XXXXXXXX.html     base64 → URL directa del servidor
```

Variante antigua: `<iframe src="…/player.php?t=<token>">`, que responde con un
`<iframe src="…">` al embed real (se sigue un salto). Un mismo título suele tener
varias pestañas de idioma: cada una es un servidor más.

Los capítulos se prueban en las rutas que usa el sitio y sus clones:
`/series/<slug>/season/<n>/episode/<m>`, `/series/<slug>/temporada/<n>/capitulo/<m>`,
`/series/<slug>/<n>x<mm>`, `/series/<slug>/<n>/<m>` y `/serie/<slug>/<n>/<m>`, y
por último la ficha de la serie (`meta.episode_page` si el capítulo salió de un
enlace suyo).

#### `pelisplushd.py` — PelisPlus / PelisPlusHD (prioridad 13)

```
/pelicula/<slug>                                   película
/serie/<slug>/temporada/<temporada>/capitulo/<ep>   capítulo
/anime/<slug>/temporada/<temporada>/capitulo/<ep>   anime (sección propia)
   │  <li class="player-option" data-player="https://streamwish.to/e/…">
   │  <a data-option="aHR0cHM6…">            (otras veces en base64)
   ▼
_hosters.py
```

El sitio tiene tres secciones y el anime usa su propio esquema de capítulos: por
eso `movie_paths` incluye `/anime/<slug>` y `series_paths` la variante de anime.
Antes `/anime/` sólo aparecía en `search_hints`, así que un id de anime que el
buscador sí encontraba no abría nunca su ficha.

#### `repelis.py` — Repelis (prioridad 14)

Repelis es un nombre replicado: cambia de dominio cada pocos meses y conviven
varios clones con el mismo motor. El proveedor **no lleva el dominio grabado**:

```bash
REPELIS_BASE=https://repelis.ejemplo
REPELIS_MIRRORS=https://repelis2.ejemplo,https://repelis3.ejemplo
```

Prueba la base y luego los espejos hasta que una ficha responda, y lo anota en
`meta.base`. Con `repelis.live` (el histórico) ya no responde: hay que poner el
que esté vivo. Si el resultado es vacío y no hay ningún espejo configurado, el
`meta.hint` lo dice en vez de dejar un `not_found` opaco. Los capítulos se prueban
en `temporada/<n>/capitulo/<m>`, `<n>x<mm>` y `<n>/<m>`.

#### `peliculasflix.py` — PeliculasFlix (prioridad 15)

No publica reproductores en el HTML: todo pasa por su API GraphQL.

```graphql
query searchAll($input: String!) { searchMovie(input: $input, limit: 10) { _id slug name_es } }
query getMovieLinks($id: MongoID!, $slug: String!) { getMovieLinks(id: $id, slug: $slug) { links_online } }
```

`links_online` trae `{server, lang, link}`; `lang` 37 = Español, 192 =
Subtitulado, resto Latino, y `link` puede ser directamente un
`<iframe src="…">` en texto (se extrae el `src`). La API se puede mover con
`PELICULASFLIX_API` y la clave `x-access-platform` con `PELICULASFLIX_ACCESS`.

Las series necesitan su propia operación: si `getMovieLinks` no devuelve enlaces
y el id trae temporada y capítulo, se prueban `getSerieLinks` y después
`getEpisodeLinks(season, episode)`. Antes la temporada y el episodio del id no
llegaban nunca al sitio y **toda serie** terminaba en `no_servers`. La página y
el `Referer` también cambian: `/serie/<slug>/<n>x<mm>` en series (antes siempre
`/pelicula/<slug>`) y el dominio sale de `PELICULASFLIX_BASE`/`_MIRRORS`, no de la
constante (`meta.page` y el `Referer` preferían el dominio grabado a fuego).

#### `pelisjuanita.py` — PelisJuanita (prioridad 16)

```
/pelicula/<slug>                  película
/anime/<slug>                     anime (su propia sección)
/wwe/<slug>                       eventos WWE
/ver/<slug>                       página "ver" (último recurso)
   │  el reproductor se inyecta en el cliente: iframes, data-*, literales JS, og:video
   ▼
/player/<id>  ·  /reproductor/<hash>        ← página del PROPIO sitio
   │  `expand_players` la abre una vez (meta.opened_players)
   ▼
streamwish / voe / filemoon / streamtape …  → `_hosters`
```

- Un solo pedido cuando el capítulo existe: `/serie/<slug>/1x03` (`{episode2}`).
  Si esa ruta no está, se cae a `temporada/<n>/capitulo/<m>`, a `1x3` sin relleno
  y, por último, al enlace del capítulo que liste la ficha (`meta.episode_page`).
- Las fichas de anime y WWE no están en `/pelicula/`: sin las rutas por sección
  esos ids nunca abrían nada (era el fallo real del proveedor).
- La página intermedia del reproductor **no** se publica como stream: se
  sustituye por los embeds que contiene, en su mismo hueco del failover.
- `PELISJUANITA_BASE` / `PELISJUANITA_MIRRORS` siguen sirviendo para dominios
  espejo.

### TMDB (opcional)

Sin `TMDB_API_KEY` los ids IMDb (`gnula:tt0145487`) no se pueden traducir a un
slug; los slugs y el texto libre funcionan igual. Con clave, `_tmdb.py` resuelve
IMDb → título/año y prueba las variantes de slug (`titulo-año`, `titulo`,
`titulo-original-año`). Todo es best-effort: si TMDB falla, el proveedor sigue
por su cuenta.

### Pruebas

Cada uno de los seis tiene su fixture HTTP local (ficha → servidores → `.m3u8`)
y su propio archivo:

```bash
node --test test/gnula.test.js
node --test test/cuevana.test.js
node --test test/pelisplushd.test.js
node --test test/repelis.test.js
node --test test/peliculasflix.test.js
node --test test/pelisjuanita.test.js
```

Los fixtures (`testsupport/pelis.js`) cubren `showEmbed` en base64, el token
legado `player.php?t=`, la API GNRD con su cadena de desofuscado, los literales
JS, los servidores empaquetados, los hostings de descarga, la caída al espejo y
la degradación a `{"streams": []}`.

> ⚠️ Los seis leen páginas públicas y no alojan ni redistribuyen contenido.
> Úsalos con autorización y respetando los términos de cada sitio y la ley de tu
> país. Los dominios cambian a menudo: si un proveedor devuelve `not_found`,
> abre la ficha en el navegador y ajusta `*_BASE` / `*_MIRRORS`.

## Dependencias Python

Los scripts incluidos usan sólo la librería estándar. Para sitios con reproductor
JavaScript instala en un virtualenv y apunta `SCRAPER_PYTHON` a él:

```bash
python3 -m venv .venv
.venv/bin/pip install requests beautifulsoup4 playwright
.venv/bin/playwright install chromium
# .env → SCRAPER_PYTHON=.venv/bin/python
```

## Aviso legal

No incluyas scrapers de sitios que distribuyan contenido sin autorización.
Este repositorio es educativo y no aloja ni redistribuye material audiovisual.
