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
             "behaviorHints":{"notWebReady":false},"isLive":false}
          ],
          "meta":{"cualquier":"dato de debug"}}
```

Reglas:

1. **Sólo JSON en stdout.** Cualquier log va a stderr con `_base.log()`.
2. Devuelve `{"streams": []}` cuando el título no exista; no lances excepciones
   para casos esperados. `_base.run()` captura todo de todos modos.
3. Respeta `timeoutMs`: el padre mata el proceso (SIGKILL al grupo) al vencer.
4. Usa `_base.default_headers(payload)` para enviar el `Referer`/`User-Agent`
   correctos al sitio; suelen ser obligatorios para el `.m3u8` final.
5. Un script nunca debe imprimir credenciales ni URLs firmadas en logs.

## Archivos

| Archivo | Uso |
| --- | --- |
| `_base.py` | Helpers compartidos: lectura de stdin, emisión de JSON, `stream()`. |
| `_htmlkit.py` | Kit común de scraping: `Deadline`/`Budget`, `http_get`, desempaquetado de JS, `media_urls`, `sanitize_media`, tokens. |
| `example_provider.py` | Demo con streams públicos (Mux / Google samples). Verifica el pipeline completo. |
| `template_provider.py` | Plantilla comentada para un sitio real (urllib + regex, sin dependencias). |
| `tvenvivo.py` | **Proveedor real** de `www.tvenvivo.org` (TV/deportes en directo, LATAM). Ver la sección siguiente. |
| `deportelibre.py` | **Proveedor real** de `deportelibre.top` (agenda deportiva + canales 24/7). Ver su sección. |
| `futbolibre.py` | **Proveedor real** de `futbolibre.live` (canales 24/7 de México + agenda de partidos con varias señales). Ver su sección. |

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
