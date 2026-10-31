> **Android autónomo:** este backend ya no es un requisito para la app.
> El APK incluye Python y reutiliza `scrapers/` directamente en el dispositivo.
> Node.js sólo se necesita para utilizar este servidor como addon de Stremio o
> al elegir expresamente el modo servidor de la app.
> Ver [arquitectura y pruebas del modo autónomo](../docs/autonomous-android.md).

# Streamflix Reborn · Stremio Addon Server

Backend ligero en **Node.js (Express)** que implementa el protocolo oficial de
**Stremio Addons** para servir fuentes de video a la app Android TV
(Streamflix Reborn / ExoPlayer) y a cualquier cliente compatible con Stremio.

Incluye:

- `GET /manifest.json` con los tipos `movie`, `series`, `anime` y `tv`.
- `GET /stream/:type/:id.json` que devuelve `{ streams: [...] }` ordenado por
  prioridad de proveedor (failover 1 → 2 → 3 para ExoPlayer).
- **Caché TTL en memoria** (2 h para live/deportes, 6 h para VOD) con
  `stale-while-revalidate`, *single-flight* y caché negativa.
- **Puente Node → Python** (`child_process.spawn`) para ejecutar los scrapers
  sólo cuando hay *cache miss*.
- Endurecimiento básico: CORS, rate limit, timeouts, *graceful shutdown*,
  `/health`, `/admin` protegido y un proxy HLS opcional con guardas anti-SSRF.

> ⚠️ **Uso educativo.** El servidor no aloja ni distribuye contenido: agrega
> enlaces de terceros. Los scrapers incluidos devuelven **streams de prueba
> públicos** (Mux / Google sample bucket). Eres responsable de usar únicamente
> fuentes sobre las que tengas autorización.

---

## 1. Arquitectura

```
┌────────────────────────────┐
│ Cliente Stremio / TV Box   │
│ (ExoPlayer, OkHttp)        │
└─────────────┬──────────────┘
              │ GET /stream/tv/canal.json
              ▼
┌──────────────────────────────────────────────────────────────┐
│ Express                                                      │
│  · CORS + rate limit + request-id                            │
│  · routes/manifest  routes/stream  routes/health  routes/proxy│
└─────────────┬────────────────────────────────────────────────┘
              ▼
┌──────────────────────────────────────────────────────────────┐
│ streamService                                                │
│  1. ¿está en caché y vigente?  ──sí──▶ responde en ~1 ms     │
│  2. ¿está stale?               ──sí──▶ responde + revalida   │
│  3. cache miss / single-flight ──▶ provider pool (paralelo)  │
└─────────────┬────────────────────────────────────────────────┘
              ▼
┌──────────────────────────────────────────────────────────────┐
│ providers/runner  (timeouts + prioridad + fast return)       │
└─────────────┬────────────────────────────────────────────────┘
              ▼
┌──────────────────────────────────────────────────────────────┐
│ bridge/pythonBridge  (spawn, stdin JSON → stdout JSON)       │
│   example_provider.py · site_a.py · site_b.py …              │
└─────────────┬────────────────────────────────────────────────┘
              ▼
      Sitios de terceros (scraping)
```

Estructura de archivos:

```
server/
├── package.json
├── .env.example
├── Dockerfile · docker-compose.yml
├── config/providers.json        # lista priorizada de proveedores
├── public/index.html            # landing con estado del servidor
├── scrapers/
│   ├── _base.py                 # contrato stdin/stdout + helpers
│   ├── _htmlkit.py              # kit común: deadline, unpack JS, media_urls
│   ├── example_provider.py      # demo con streams públicos de prueba
│   ├── template_provider.py     # plantilla para un sitio real
│   ├── tvenvivo.py              # proveedor real: tvenvivo.org (live)
│   ├── deportelibre.py          # proveedor real: deportelibre.top (live)
│   ├── futbolibre.py            # proveedor real: futbolibre.live (live)
│   ├── _hosters.py              # resolutor de servidores de video (Streamwish, VOE…)
│   ├── _animekit.py             # ids/literales JS/base64 comunes a los de anime
│   ├── animeflv.py              # proveedor real: animeflv.net (vod)
│   ├── jkanime.py               # proveedor real: jkanime.net (vod)
│   ├── monoschinos.py           # proveedor real: monoschinos.st (vod)
│   ├── _peliskit.py             # capa común de películas y series
│   ├── _tmdb.py                 # resolución opcional de ids IMDb (TMDB)
│   ├── gnula.py                 # proveedor real: gnulahd.nu (vod)
│   ├── cuevana.py               # proveedor real: cuevana3.nu (vod)
│   ├── pelisplushd.py           # proveedor real: pelisplushd.la (vod)
│   ├── repelis.py               # proveedor real: repelis (vod, dominio por env)
│   ├── peliculasflix.py         # proveedor real: peliculasflix.co (vod, GraphQL)
│   └── pelisjuanita.py          # proveedor real: pelisjuanita.org (vod)
├── scripts/
│   ├── cache-stats.js           # métricas de caché en vivo
│   └── bridge-demo.js           # probar un scraper sin HTTP
├── src/
│   ├── server.js                # bootstrap + graceful shutdown
│   ├── app.js                   # wiring de Express
│   ├── config.js                # configuración validada por env
│   ├── cache/TtlCache.js        # caché TTL + LRU + single-flight
│   ├── bridge/pythonBridge.js   # spawn de Python
│   ├── providers/               # registro y pool de proveedores
│   ├── services/streamService.js# orquestación caché ↔ proveedores
│   ├── routes/                  # manifest, stream, health, admin, proxy
│   ├── middleware/              # errores, request-id, rate limit
│   └── lib/                     # stremio, ids, http, quality, logger
└── test/                        # node:test (unitarias + integración)
```

---

## 2. Instalación

Requisitos: **Node.js ≥ 20.12** y **Python ≥ 3.9** (`python3` en el PATH).

```bash
cd server
npm install
cp .env.example .env      # opcional: el server funciona con los defaults
npm start                 # http://localhost:7000
```

Modo desarrollo con recarga:

```bash
npm run dev
```

Vista previa en el navegador (puerto **8899**, atado a `0.0.0.0` para que el
proxy del workspace la alcance):

```bash
npm run preview           # HOST=0.0.0.0 PORT=8899 node src/server.js
```

Instalar en Stremio / la app: usa como URL del addon
`http://<IP-de-tu-servidor>:7000/manifest.json`
(o `stremio://<host>:7000/manifest.json`). Desde la propia landing (`/`) puedes
copiar la URL y ver el estado de la caché en vivo.

---

## 3. Endpoints

| Método | Ruta | Descripción |
| --- | --- | --- |
| `GET` | `/manifest.json` | Manifiesto del addon (`movie`, `series`, `anime`, `tv`) con sus catálogos. |
| `GET` | `/catalog/:type/:id.json` | Catálogo Stremio (Cinemeta + TV en vivo local). Admite `search=`, `genre=`, `skip=`. |
| `GET` | `/meta/:type/:id.json` | Ficha enriquecida; en series incluye los episodios. |
| `GET` | `/catalogs.json` | Qué filas publica el addon (extensión propia para la app Android). |
| `GET` | `/stream/:type/:id.json` | Fuentes de video ordenadas por prioridad, con `subtitles[]` (campo extra). |
| `GET` | `/subtitles/:type/:id.json` | Subtítulos del mismo scrape (recurso `subtitles` del protocolo). |
| `GET` | `/health` · `/healthz` | Estado, métricas de caché y proveedores. |
| `GET` | `/proxy?url=…` | Proxy HLS opcional con `Referer`/`UA` y reescritura de segmentos. |
| `GET` | `/admin/cache` | Estadísticas y claves (requiere `ADMIN_TOKEN`). |
| `DELETE` | `/admin/cache?type=&id=` | Invalida una entrada o toda la caché. |
| `POST` | `/admin/warm` | Precalienta hasta 25 ids. |
| `GET` | `/admin/providers` | Proveedores y prioridades. |

### Manifest

```bash
curl -s http://localhost:7000/manifest.json | jq
```

```json
{
  "id": "org.streamflix.reborn",
  "version": "0.1.0",
  "name": "Streamflix Reborn",
  "types": ["movie", "series", "anime", "tv"],
  "resources": [
    { "name": "stream", "types": ["movie", "series", "anime", "tv"] },
    { "name": "catalog", "types": ["movie", "series", "anime", "tv"] },
    { "name": "meta", "types": ["movie", "series", "anime", "tv"] },
    { "name": "subtitles", "types": ["movie", "series", "anime", "tv"] }
  ],
  "catalogs": [
    {
      "type": "tv",
      "id": "live",
      "name": "TV en Vivo y Deportes",
      "posterShape": "landscape",
      "extra": [
        { "name": "search", "isRequired": false },
        { "name": "skip", "isRequired": false },
        { "name": "genre", "options": ["Deportes", "Canales", "Eventos"], "isRequired": false }
      ]
    },
    {
      "type": "tv",
      "id": "canales",
      "name": "TV en vivo · Canales",
      "posterShape": "landscape",
      "extra": [ …search, skip, genre["Deportes", "Canales"] ]
    },
    {
      "type": "tv",
      "id": "agenda",
      "name": "Agenda deportiva",
      "posterShape": "landscape",
      "extra": [ …search, skip, genre["Deportes", "Eventos"] ]
    }
  ]
}
```

> `idPrefixes` se omite a propósito en el recurso `catalog`: los canales en vivo
> usan ids como `tv:futbolibre:espn-1`, que no empiezan por `tt`. Declarar el
> prefijo escondería esas filas en Stremio.

### TV en vivo y agenda deportiva: tres filas, dos orígenes

| Fila | Origen | Qué enseña |
| --- | --- | --- |
| `tv/live` | `config/live-catalog.json` (`kind: local`) | Los canales **fijados a mano**. Es el respaldo: si todo lo demás falla, sigue ahí. |
| `tv/canales` | `kind: "live"` → `scrapers/_liveshelf.py` | Todos los canales que publican los proveedores de directo, deduplicados. |
| `tv/agenda` | `kind: "live"` → `scrapers/_liveshelf.py` | Los partidos de hoy con liga, equipos, hora, estado y proveedor. |

La estantería se construye una vez por ventana (`CATALOG_LIVE_TTL_MINUTES`, 15
min) y se comparte con el motor del APK, que usa el mismo `_liveshelf.py`. Si el
scrape no trae nada, `tv/canales` cae a la lista curada y `tv/agenda` se queda
vacía a propósito: un partido de ayer pintado como "Hoy" es una tarjeta muerta.

```bash
curl -s "http://localhost:7000/catalog/tv/canales.json" | jq '.metas | length'
python3 scrapers/_liveshelf.py --json --section events | jq '.items[0]'
```

### Catálogos

Las filas se declaran en `config/catalogs.json` (misma idea que
`config/providers.json`: añadir una fila no toca código):

```json
{
  "type": "anime",
  "id": "top",
  "name": "Anime en emisión",
  "posterShape": "poster",
  "order": 5,
  "source": { "kind": "cinemeta", "type": "series", "id": "top", "genre": "Animation" }
}
```

- `source.kind: "cinemeta"` → se pide a Cinemeta (`CINEMETA_BASE`) traduciendo
  el tipo publicado al real: `anime` en realidad es `series` filtrado por
  `Animation`. Las metas se normalizan (`releaseInfo` → `year`,
  `imdbRating` → `rating`), se deduplican por id y conservan `sourceType`.
- `source.kind: "local"` → sale de `config/live-catalog.json`. El `id` de cada
  canal **es** el id de stream (`tv:futbolibre:espn-1`), así que tocar una
  tarjeta va directo a `/stream/tv/…`. Se sirven con
  `posterShape: "landscape"` (16:9).
- `posterShape` decide cómo pinta las tarjetas el cliente: `poster` = 2:3
  (películas/series/anime), `landscape` = 16:9 (TV en vivo y deportes).

#### Catálogos de addons de terceros

El bloque `addons` del mismo archivo republica los catálogos de otro addon de
Stremio sin copiar su código ni su catálogo de datos. Por defecto viene
configurado **[Streaming Catalogs](https://github.com/powerwork-stremio)** —
`pw.ers.netflix-catalog`, el addon que publica las filas de Netflix, Disney+,
Prime Video, HBO Max, Apple TV+, Hulu, Paramount+, Peacock, Crunchyroll…:

```json
{
  "addons": [
    {
      "id": "streaming",
      "name": "Streaming Catalogs",
      "manifestUrl": "https://…/manifest.json",
      "only": ["nfx", "dnp", "amp", "hbm", "atp"],
      "types": ["movie", "series"],
      "limit": 40,
      "order": 100
    }
  ]
}
```

- `only` elige qué servicios se publican **y en qué orden** (ids del manifest
  remoto: `nfx`
  Netflix, `dnp` Disney+, `amp` Prime Video, `hbm` HBO Max, `atp` Apple TV+,
  `hlu` Hulu, `pmp` Paramount+, `pcp` Peacock, `cru` Crunchyroll, `stz` Starz,
  `sha` Shahid, `iqi` iQIYI, `mbi` Mubi…). **Vacío = los 41 catálogos del
  addon.**
- `types` limita qué tipos se republican; `limit` recorta metas por fila
  (por defecto `CATALOG_PAGE_SIZE`).
- El id publicado se prefija (`streaming_nfx`) para no chocar con los ids
  propios (`top`, `live`); el id remoto se conserva en `source.id`.
- Sólo se anuncian los *extras* que el manifest remoto declara: si no anuncia
  `search`, no se le manda y el cliente no enseña buscador (ver el `extra` del
  manifest).
- Las metas se normalizan igual que las de Cinemeta y se etiquetan con
  `sourceName`. Como sus ids son de IMDb (`tt…`), la ficha
  (`/meta/{type}/{id}.json`, sinopsis y episodios) la sigue sirviendo Cinemeta y
  las fuentes, nuestro `/stream`.
- El **manifest** del addon remoto se lee una vez por arranque y se cachea 6 h
  en `.cache/remote-catalogs.json`: un reinicio sin red sigue publicando las
  filas. Si el addon está caído y no hay caché, sus filas no se publican y el
  resto del Home funciona igual.

```bash
# tras reiniciar con el bloque `addons`
curl -s http://localhost:7000/catalogs.json | jq '.catalogs[] | select(.source=="remote") | .name'
curl -s http://localhost:7000/catalog/movie/streaming_nfx.json | jq '.metas[0]'
```

Extras del protocolo, como segmentos de path:

```bash
curl -s "http://localhost:7000/catalog/movie/top/search=matrix.json" | jq '.metas[0]'
curl -s "http://localhost:7000/catalog/tv/live/genre=Deportes.json" | jq '.metas[].name'
curl -s "http://localhost:7000/catalog/tv/canales.json" | jq '.metas[].name'   # todos los canales
curl -s "http://localhost:7000/catalog/tv/agenda.json" | jq '.metas[0]'        # partidos de hoy
curl -s "http://localhost:7000/meta/series/tt0944947:1:2.json" | jq '.meta.selectedVideo'
```

Respuesta (contrato Stremio + dos campos propios que ignora cualquier otro
cliente):

```json
{
  "metas": [
    {
      "id": "tt0133093",
      "type": "movie",
      "name": "The Matrix",
      "poster": "https://images.metahub.space/poster/medium/tt0133093/img",
      "background": "https://images.metahub.space/background/medium/tt0133093/img",
      "description": "Un hacker descubre la verdad…",
      "releaseInfo": "1999",
      "year": 1999,
      "rating": 8.7,
      "genres": ["Action", "Sci-Fi"]
    }
  ],
  "cacheMaxAge": 1800,
  "staleRevalidate": 1800,
  "staleError": 60,
  "catalog": { "type": "movie", "id": "top", "name": "Películas populares", "posterShape": "poster" }
}
```

Política de fallos:

- **Cinemeta caída** → se sirve la copia *stale* si existe; si no, `200` con
  `metas: []`, `degraded: true` y `X-Catalog-Degraded: 1` (nunca un `502` que
  deje el Home en blanco).
- **Catálogo no publicado** → `404` con `available: ["top", "imdbRating"]`.
- **`HEAD /catalog/…`** → `200` sin tocar el upstream (probes de los clientes).
- TTLs: filas `CATALOG_TTL_MINUTES` (30), búsquedas
  `CATALOG_SEARCH_TTL_MINUTES` (5), fichas `CATALOG_META_TTL_HOURS` (24) y
  modo degradado `CATALOG_MISS_SECONDS` (60). Todo con *single flight* y
  *stale-while-revalidate* sobre la misma `TtlCache` que los streams.

### Streams

```bash
curl -s "http://localhost:7000/stream/tv/demo-live.json" | jq
```

```json
{
  "streams": [
    {
      "name": "Demo TV · 1080p",
      "title": "Demo TV · 1080p · demo",
      "url": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
      "behaviorHints": {
        "requestHeaders": {
          "Referer": "https://demo.local/",
          "User-Agent": "Mozilla/5.0 (Linux; Android 12; Android TV) …"
        },
        "isLive": true,
        "mediaType": "hls"
      }
    }
  ],
  "cacheMaxAge": 7200,
  "staleRevalidate": 7200,
  "staleError": 120
}
```

> Nota: Stremio define oficialmente `movie`, `series`, `channel`, `tv` y
> `events`; `anime` se declara como tipo personalizado (requisito del
> proyecto). Los clientes que validan tipos estrictamente pueden ignorarlo;
> el resto lo muestra como una fila más.

Ida y vuelta con la app Android:

- `behaviorHints.requestHeaders` → ExoPlayer los aplica vía
  `DefaultHttpDataSource`/`OkHttpDataSource` (necesario para providers con
  *hotlink protection*).
- `behaviorHints.mediaType` (`hls` / `dash` / `mp4`) → cuando la URL no tiene
  extensión, es lo que decide si se abre como `HlsMediaSource`,
  `DashMediaSource` o `ProgressiveMediaSource`. Sin esa pista un `.m3u8` en
  `/v/abc123` se intentaría reproducir como MP4 y no se vería nada.
- El **orden del array es la lista de failover**: el cliente intenta
  `streams[0]`, y si falla pasa a `streams[1]`, etc.
- Cabeceras útiles de diagnóstico: `X-Cache: HIT|STALE|MISS|COALESCED`,
  `X-Response-Time` (ms), `X-Request-Id`.

---

## 4. Caché TTL inteligente

`src/cache/TtlCache.js` (sin dependencias externas) ofrece:

| Característica | Detalle |
| --- | --- |
| TTL por entrada | live/deportes `CACHE_TTL_LIVE_HOURS` (2 h, ventana **2–4 h**), VOD `CACHE_TTL_VOD_HOURS` (6 h, ventana **2–6 h**). Fuera de rango el servidor no arranca. |
| Hot path | Un *hit* de un `.m3u8` live responde en **1–3 ms** sin lanzar Python. |
| `stale-while-revalidate` | Pasado el TTL, sirve el valor viejo al instante y refresca en segundo plano (hasta `ttl × CACHE_STALE_FACTOR`). |
| *Single-flight* | N peticiones simultáneas del mismo canal = **un solo** proceso Python (`X-Cache: COALESCED` en las demás). |
| Caché negativa | Si ningún proveedor devuelve nada, se cachea `[]` por `CACHE_TTL_MISS_SECONDS` (120 s) para no martillear sitios caídos. |
| LRU + sweep | Cota `CACHE_MAX_ENTRIES` y limpieza periódica de expirados: memoria estable en TV boxes y VPS pequeños. |
| Métricas | `hits`, `staleHits`, `misses`, `coalesced`, `evictions`, `hitRate`, `size` en `/health` y `npm run cache:stats`. |

Comprobación rápida del requisito "milisegundos" (con el proveedor demo, que
simula 350 ms de scraping):

```bash
curl -s -o /dev/null -w "cold %{time_total}s\n" http://localhost:7000/stream/tv/demo-live.json
curl -s -o /dev/null -w "warm %{time_total}s\n" http://localhost:7000/stream/tv/demo-live.json
# cold 0.40s
# warm 0.002s
```

La clave de caché es determinista: `streams:<type>:<id>`. Se puede invalidar
puntualmente:

```bash
curl -X DELETE "http://localhost:7000/admin/cache?type=tv&id=demo-live" \
     -H "Authorization: Bearer $ADMIN_TOKEN"
```

---

## 5. Puente con Python

Contrato (documentado también en `scrapers/_base.py`):

```
stdin  → {"type":"tv","id":"demo-live","isLive":true,"provider":"demo-live",
          "referer":"https://sitio/","userAgent":"Mozilla/5.0…","timeoutMs":12000}
stdout ← {"streams":[{"name":"…","title":"…","url":"https://…/index.m3u8",
                      "quality":"1080p","referer":"https://sitio/"}],
          "meta":{"debug":"opcional"}}
```

Detalles de implementación (`src/bridge/pythonBridge.js`):

- `spawn()` (nunca `exec`) → sin shell, sin inyección de comandos.
- Timeout duro por script ⇒ `SIGKILL` al **grupo de procesos** (mata también a
  yt-dlp/playwright hijos).
- Semáforo `SCRAPER_MAX_CONCURRENCY` para no saturar la CPU.
- `stdout` limitado a 1 MB y `stderr` recortado: un scraper roto no puede
  tumbar el proceso Node.
- JSON inválido, exit code ≠ 0 o timeout ⇒ `{ ok: false }`; el pool sigue con
  los demás proveedores (degradación parcial, nunca 500).

Probar un scraper aislado:

```bash
npm run bridge:demo -- example_provider.py '{"type":"movie","id":"tt0133093","provider":"demo-vod"}'
npm run bridge:demo -- tvenvivo.py '{"type":"tv","id":"tv:tvenvivo:espn-2","isLive":true}'
```

### Proveedor real incluido: `tvenvivo.py`

`scrapers/tvenvivo.py` scrapea **https://www.tvenvivo.org/** (TV y deportes en
directo). Está habilitado en `config/providers.json` con prioridad **5**, así
que sus señales encabezan el array de failover:

```bash
curl -s "http://localhost:7000/stream/tv/tv:tvenvivo:espn-2.json" | jq
curl -s "http://localhost:7000/stream/tv/tv:tvenvivo:espn-2.json" -D- -o/dev/null | grep X-Cache
```

Formas de id admitidas: `tv:tvenvivo:espn-2`, `tvenvivo:espn-2`, `espn-2`,
`espn-2-en-vivo-online(.php)`, la URL completa de la página del canal, o el
nombre libre `ESPN 2` (se busca en el índice del sitio).

Listar canales e ids listos para Stremio:

```bash
npm run channels:tvenvivo                 # tabla nombre → tv:tvenvivo:<slug>
npm run channels:tvenvivo -- --query espn
npm run channels:tvenvivo -- --json
```

Cómo lo resuelve: página del canal → mapa JavaScript `SOURCES` →
`/live/core.php?canal=…` → `<iframe>` → `.m3u8` del CDN (desescapando
`https:\/\/…`). Sigue como máximo `TVENVIVO_MAX_DOCS` documentos /
`TVENVIVO_MAX_DEPTH` saltos, ignora hosts de publicidad y devuelve **una
entrada por opción**. Detalles y variables de entorno en
[`scrapers/README.md`](scrapers/README.md#proveedor-incluido-tvenvivopy).

### Proveedor real incluido: `deportelibre.py`

`scrapers/deportelibre.py` scrapea **https://deportelibre.top/** (agenda
deportiva y canales 24/7). Habilitado con prioridad **4**:

```bash
curl -s "http://localhost:7000/stream/tv/tv:deportelibre:stream-01.json" | jq
npm run channels:deportelibre      # catálogo + ids tv:deportelibre:<id>
npm run bridge:demo -- deportelibre.py '{"type":"tv","id":"tv:deportelibre:stream-01","isLive":true}'
```

Cadena de resolución: catálogo `/stream/` → `/total/stream-NN.php` → opciones
`/en-vivo-online/<slug>/embedN.php` → reproductor **Clappr**. La URL del CDN
vive dentro de un JS empaquetado (`eval(function(p,a,c,k,e,d)…)`), donde el
esquema aparece como índice de su tabla (`46://host/…`): el scraper lo
**desempaqueta** y, si la tabla no se puede recuperar, normaliza el esquema.
También entiende la variante JSON (`stream_url` / `stream_url_nop2p`) y los
tokens con caducidad (`?s=…&e=<epoch>`), que marca y deja al final. Detalles en
[`scrapers/README.md`](scrapers/README.md#proveedor-incluido-deportelibrep).

### Proveedor real incluido: `futbolibre.py`

`scrapers/futbolibre.py` scrapea **https://futbolibre.live/** (canales
deportivos de México y agenda de partidos). Habilitado con prioridad **3**, la
más alta de los proveedores reales, así que sus señales encabezan el failover:

```bash
curl -s "http://localhost:7000/stream/tv/tv:futbolibre:espn-1.json" | jq
curl -s "http://localhost:7000/stream/tv/tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau.json" | jq
npm run channels:futbolibre        # canales + agenda de hoy, con sus ids
npm run channels:futbolibre -- --events --query liga
npm run bridge:demo -- futbolibre.py '{"type":"tv","id":"tv:futbolibre:espn-1","isLive":true}'
```

Cadena de resolución: tarjeta del canal → `/en-vivo/<slug>` → reproductor del
backend (`la18hd.su/vivo/canal.php?stream=…`, a veces tras un salto
`sw3.html?get=…`) → `.m3u8` dentro del JS. La agenda
(`/eventos/json/agenda123.json`) agrupa **varias señales por partido**: se
devuelve una entrada por señal, se descarta el parámetro `ip` del CDN y los
tokens caducados van al final marcados `· token caducado`. Detalles en
[`scrapers/README.md`](scrapers/README.md#proveedor-incluido-futbolibrep).

### Proveedores reales de anime (VOD)

Tres proveedores `kind: "vod"` sirven episodios de anime, en este orden de
prioridad: **`animeflv` 6 → `jkanime` 7 → `monoschinos` 8**.

| Proveedor | Sitio | Id de ejemplo |
| --- | --- | --- |
| `animeflv` | [animeflv.net](https://www3.animeflv.net/) | `animeflv:one-piece:1180` |
| `jkanime` | [jkanime.net](https://jkanime.net/) | `jkanime:one-piece:1180` |
| `monoschinos` | [monoschinos.st](https://monoschinos.st/) | `monoschinos:one-piece-sub-espanol:1` |

Se piden como cualquier contenido de Stremio:

```bash
curl -s "http://localhost:7000/stream/anime/animeflv:one-piece:1180.json" | jq
curl -s "http://localhost:7000/stream/anime/jkanime:one-piece:1180.json" | jq
curl -s "http://localhost:7000/stream/anime/monoschinos:one-piece-sub-espanol:1.json" | jq
npm run anime:animeflv -- --query "one piece"   # buscar e imprimir ids
npm run anime:jkanime
npm run anime:monoschinos
```

Cada episodio devuelve **un stream por servidor resuelto** (Streamwish, VOE,
Filemoon, Streamtape, Mp4upload…), en el orden del sitio, así que el failover de
ExoPlayer también sirve cuando un servidor está caído:

```
"AnimeFLV · One Piece · Ep. 1180 · Streamwish"   ← Señal 1
"AnimeFLV · One Piece · Ep. 1180 · Filemoon"     ← Señal 2
```

La plomería compartida vive en `scrapers/_animekit.py` (ids, literales JS,
base64, etiquetas) y `scrapers/_hosters.py` (resolución de cada servidor). Los
hosting de archivos (Mega, Mediafire) no son reproducibles: se descartan y se
anotan en `meta.unresolved`. Detalles y variables de entorno en
[`scrapers/README.md`](scrapers/README.md#proveedores-de-anime-vod).

### Proveedores reales de películas y series (VOD)

Siete sitios `kind: "vod"`, del 11 al 17 en prioridad:

| Prioridad | Proveedor (`id`) | Sitio | Id de ejemplo |
| --- | --- | --- | --- |
| 11 | `gnula` | [gnulahd.nu](https://ww3.gnulahd.nu/) | `gnula:street-fighter` |
| 12 | `cuevana` | [cuevana3.nu](https://cue.cuevana3.nu/) | `cuevana:spider-man-de-regreso-a-casa` |
| 13 | `pelisplushd` | [pelisplushd.la](https://www.pelisplushd.la/) | `pelisplushd:the-last-of-us:1:2` |
| 14 | `repelis` | dominio por `REPELIS_BASE` | `repelis:spider-man-de-regreso-a-casa` |
| 15 | `peliculasflix` | [peliculasflix.co](https://peliculasflix.co/) | `peliculasflix:spider-man-de-regreso-a-casa` |
| 16 | `pelisjuanita` | [pelisjuanita.org](https://pelisjuanita.org/) | `pelisjuanita:spider-man-un-nuevo-dia-2026` |
| 17 | `novahd` | [novahd.cc](https://novahd.cc/) | `novahd:155` · `novahd:show:1396:1:1` |

```bash
curl -s "http://localhost:7000/stream/movie/gnula:street-fighter.json" | jq
curl -s "http://localhost:7000/stream/series/pelisplushd:the-last-of-us:1:2.json" | jq
npm run movies:gnula -- "street fighter"    # buscar e imprimir ids
npm run movies:pelisjuanita -- "reacher"
npm run movies:novahd -- "dark knight"
```

Cada película devuelve **un stream por servidor resuelto** (Streamwish, VOE,
Filemoon, Streamtape, Mixdrop…), en el orden del sitio:

```
"Gnula · Street Fighter · Latino - Streamwish"   ← Señal 1
"Gnula · Street Fighter · Latino - Filemoon"     ← Señal 2
```

Los ids aceptan el slug del sitio, `temporada:episodio`, un id IMDb
(`gnula:tt0145487`, con `TMDB_API_KEY`) o texto libre, que se busca en el sitio.
La plomería compartida vive en `scrapers/_peliskit.py` (ids, búsqueda, recogida
de servidores) y `scrapers/_hosters.py` (resolución de cada servidor). Detalles,
cadenas por sitio y variables en
[`scrapers/README.md`](scrapers/README.md#proveedores-de-películas-y-series-vod).

Agregar un proveedor real:

1. Copia `scrapers/template_provider.py` a `scrapers/mi_sitio.py` y ajusta la
   lógica (usa `_base.default_headers(payload)` para el `Referer`/`UA`).
2. Añádelo en `config/providers.json`:

```json
{
  "id": "mi-sitio",
  "label": "Mi Sitio",
  "script": "mi_sitio.py",
  "priority": 15,
  "kind": "vod",
  "enabled": true,
  "referer": "https://mi-sitio.example/",
  "options": { "locale": "es" }
}
```

3. Reinicia (o `POST /admin/warm` para precalentar) y verifica con
   `npm run bridge:demo`.

---

## 6. Failover / prioridad

- Los proveedores se lanzan **en paralelo**, cada uno con
  `PROVIDER_TIMEOUT_MS` (8 s por defecto): al vencer, el proceso Python se
  SIGKILL-ea y su hueco lo ocupan las fuentes secundarias que sí respondieron.
- Los resultados se ordenan por `priority` ascendente y, dentro del mismo
  proveedor, por calidad (`4K > 1080p > 720p > 480p > SD`).
- Se eliminan URLs duplicadas conservando la del proveedor con más prioridad.
- Cuando `MIN_PROVIDERS_FOR_FAST_RETURN` proveedores ya respondieron, el pool
  espera sólo `FAST_RETURN_GRACE_MS` (1,5 s) y devuelve lo que hay: la respuesta
  no queda rehén del sitio más lento.
- El cliente recibe la lista ya ordenada y hace el failover sin lógica extra.

`GET /health` expone el estado de cada proveedor; el log estructurado muestra
`durationMs` por script para detectar el que siempre falla.

---

## 7. Variables de entorno

| Variable | Default | Descripción |
| --- | --- | --- |
| `PORT` / `HOST` | `7000` / `0.0.0.0` | Bind HTTP. `0.0.0.0` es necesario en Docker/TV box. |
| `PUBLIC_URL` | *(derivada)* | Origen público para el manifest detrás de proxy. |
| `CORS_ORIGINS` | `*` | Orígenes permitidos (lista separada por comas). |
| `RATE_LIMIT_MAX` | `120` | Peticiones/min/IP en `/stream`, `/proxy`, `/catalog` y `/meta` (0 = off). |
| `CACHE_TTL_LIVE_HOURS` | `2` | TTL live/deportes. Ventana válida **2–4 h** (fuera de rango no arranca). |
| `CACHE_TTL_VOD_HOURS` | `6` | TTL VOD/anime/series (2–6). |
| `CACHE_TTL_MISS_SECONDS` | `120` | Caché negativa. |
| `CACHE_MAX_ENTRIES` | `5000` | Cota LRU. |
| `CACHE_STALE_FACTOR` | `1.25` | Ventana de `stale-while-revalidate`. |
| `SCRAPER_PYTHON` | `python3` | Intérprete (ej. `.venv/bin/python`). |
| `SCRAPER_DIR` | `./scrapers` | Carpeta de scripts. |
| `SCRAPER_TIMEOUT_MS` | `15000` | Timeout por script. |
| `SCRAPER_MAX_CONCURRENCY` | `4` | Procesos Python simultáneos. |
| `PROVIDER_TIMEOUT_MS` | `8000` | Presupuesto por proveedor; lo que tarde más se mata y se sirve el failover. |
| `MIN_PROVIDERS_FOR_FAST_RETURN` | `2` | Respuesta temprana. |
| `FAST_RETURN_GRACE_MS` | `1500` | Ventana extra antes de responder. |
| `ADMIN_TOKEN` | *(vacío)* | Activa `/admin/*`; sin él devuelven 404. |
| `PROXY_ENABLED` | `true` | Habilita `/proxy`. |
| `LOG_LEVEL` / `LOG_JSON` | `info` / `false` | Logging. |
| `TVENVIVO_BASE` | `https://www.tvenvivo.org` | Base del sitio (los tests usan un fixture local). |
| `TVENVIVO_INDEX_TTL_SECONDS` | `21600` | TTL del índice de canales (`0` = sin caché). |
| `TVENVIVO_INDEX_CACHE` | `…/streamflix-tvenvivo-index.json` | Ruta del índice en disco. |
| `TVENVIVO_MAX_DOCS` / `TVENVIVO_MAX_DEPTH` | `8` / `3` | Topes de documentos y saltos al resolver un reproductor. |
| `DEPORTELIBRE_BASE` | `https://deportelibre.top` | Base del sitio (los tests usan un fixture local). |
| `DEPORTELIBRE_CHANNELS_PATH` | `/stream/` | Catálogo de canales 24/7. |
| `DEPORTELIBRE_INDEX_TTL_SECONDS` | `21600` | TTL del catálogo en disco (`0` = sin caché). |
| `DEPORTELIBRE_MAX_OPTIONS` / `DEPORTELIBRE_MAX_DEPTH` | `4` / `2` | Opciones por canal y saltos por reproductor. |
| `FUTBOLIBRE_BASE` | `https://futbolibre.live` | Base del sitio de canales (los tests usan un fixture local). |
| `FUTBOLIBRE_AGENDA_URL` | `https://la18hd.su/eventos/json/agenda123.json` | Agenda de partidos con sus señales. |
| `FUTBOLIBRE_INDEX_TTL_SECONDS` / `FUTBOLIBRE_AGENDA_TTL_SECONDS` | `21600` / `600` | TTL del catálogo de canales y de la agenda. |
| `FUTBOLIBRE_MAX_SIGNALS` / `FUTBOLIBRE_MAX_DEPTH` | `6` / `2` | Señales por contenido y saltos por reproductor. |
| `ANIMEFLV_BASE` / `JKANIME_BASE` / `MONOSCHINOS_BASE` | dominios oficiales | Base de cada sitio de anime (los tests usan un fixture local). |
| `ANIMEFLV_MAX_SERVERS` / `JKANIME_MAX_SERVERS` / `MONOSCHINOS_MAX_SERVERS` | `6` | Servidores que se intentan por episodio. |
| `GNULA_BASE` / `CUEVANA_BASE` / `PELISPLUSHD_BASE` / `REPELIS_BASE` / `PELICULASFLIX_BASE` / `PELISJUANITA_BASE` | dominios oficiales | Base de cada sitio de cine (los tests usan un fixture local). |
| `*_MIRRORS` | vacío | Dominios alternativos separados por comas, probados si la base no responde. |
| `PELICULASFLIX_API` / `PELICULASFLIX_ACCESS` | API y clave del sitio | Endpoint GraphQL y `x-access-platform`. |
| `TMDB_API_KEY` | vacío | Opcional: traduce ids IMDb a título/año para acertar el slug. |
| `CINEMETA_BASE` | `https://v3-cinemeta.strem.io` | Addon oficial de metadatos (los tests usan un mock local). |
| `CINEMETA_TIMEOUT_MS` | `6000` | Timeout por petición a Cinemeta. |
| `CATALOG_TTL_MINUTES` | `30` | TTL de las filas del Home. |
| `CATALOG_SEARCH_TTL_MINUTES` | `5` | TTL de las búsquedas (`search=`). |
| `CATALOG_META_TTL_HOURS` | `24` | TTL de `/meta` (sinopsis + episodios). |
| `CATALOG_MISS_SECONDS` | `60` | Ventana del modo degradado cuando Cinemeta no responde. |
| `CATALOG_PAGE_SIZE` | `100` | Tope de metas por catálogo. |
| `CATALOGS_CONFIG` | `./config/catalogs.json` | Filas publicadas (tipo, id, nombre, `posterShape`, origen). |
| `LIVE_CATALOG_PATH` | `./config/live-catalog.json` | Canales de TV en vivo y deportes curados. |
| `CATALOG_REMOTE_TIMEOUT_MS` | `8000` | Timeout al leer el manifest de un addon de terceros. |
| `CATALOG_REMOTE_TTL_HOURS` | `6` | Vigencia del manifest cacheado en disco. |
| `CATALOG_REMOTE_CACHE` | `./.cache/remote-catalogs.json` | Caché de manifests remotos. |

Todas se pueden poner en `server/.env` (se carga automáticamente, sin
dependencias) o inyectarlas desde el orquestador.

---

## 8. Tests

```bash
npm test
```

161 pruebas con `node:test`:

- caché: TTL, expiración, stale, LRU, *single-flight*, caché negativa;
- protocolo: manifest, forma del stream, cabeceras, ids, calidades, HLS, SSRF;
- pool de proveedores: ejecución en paralelo, orden por prioridad y deadline;
- integración: arranca Express en un puerto efímero y ejecuta los scrapers
  Python reales para validar `MISS → HIT` y la coalescencia de peticiones;
- `tvenvivo.py`: fixture HTTP local que imita el sitio (página → `SOURCES` →
  wrapper → iframe → `.m3u8` escapado), ids aceptados, búsqueda por nombre,
  descarte de anuncios, degradación a `{"streams":[]}` y caché del índice;
- `deportelibre.py`: fixture con el reproductor empaquetado (`46://…` → `https`),
  variante JSON `stream_url`/`stream_url_nop2p`, tokens caducados al final,
  opciones offline descartadas, resolución por nombre y catálogo en caché;
- `futbolibre.py`: fixture canal → `/en-vivo/<slug>` → reproductor del backend,
  salto `sw3.html?get=`, agenda con señales duplicadas (agrupadas y
  deduplicadas), parámetro `ip` descartado, token caducado al final, agenda
  inalcanzable sin romper y `--list` de canales + partidos;
- `src/bridge/pythonBridge.js`: payload por stdin, ids con caracteres raros sin
  pasar por un shell, timeout que mata el proceso, salida no-JSON, script que
  falla, script inexistente, intento de salir de `SCRAPER_DIR` y liberación del
  semáforo en todos los desenlaces;
- `src/config.js`: el presupuesto de 8 s y las ventanas de TTL (live 2–4 h, VOD
  2–6 h), incluido el arranque rechazado cuando el TTL se sale de rango;
- `animeflv.py` / `jkanime.py` / `monoschinos.py`: fixtures por sitio (página del
  episodio → lista de servidores en base64 → embed → `.m3u8`/`.mp4`), incluidos
  el JS empaquetado, el redirect por JavaScript de VOE, los servidores de
  descarga o caídos, el respaldo por búsqueda y la degradación a `streams: []`.
- `gnula.py` / `cuevana.py` / `pelisplushd.py` / `repelis.py` /
  `peliculasflix.py` / `pelisjuanita.py`: fixtures por sitio
  (ficha → servidores → `.m3u8`), incluidos el objeto `GNRD` y su cadena de
  desofuscado, `showEmbed` en base64, el token legado `player.php?t=`, la API
  GraphQL de PeliculasFlix, la caída a los espejos `*_MIRRORS`, el seguimiento
  del enlace del capítulo y la degradación a `streams: []`.

---

## 9. Docker

```bash
cd server
docker compose up -d --build
# o
docker build -t streamflix-addon .
docker run -d --name streamflix-addon -p 7000:7000 --env-file .env streamflix-addon
```

La imagen incluye `python3` y `tini` (señales correctas para el apagado
ordenado). El `HEALTHCHECK` consulta `/health`.

---

## 10. Producción y seguridad

- Ponlo detrás de HTTPS (Caddy/Nginx/Cloudflare) y define `PUBLIC_URL`.
- Activa `ADMIN_TOKEN`; los endpoints de administración quedan deshabilitados
  sin token.
- `/proxy` bloquea loopback, rangos privados, link-local y metadatos cloud, y
  valida la URL final tras redirecciones. Aun así, si no lo necesitas,
  `PROXY_ENABLED=false`.
- Los timeouts y el rate limit evitan que un sitio lento agote el proceso.
- El caché es en memoria: se pierde al reiniciar. Para un clúster con varias
  instancias, sustituye `TtlCache` por Redis (`GET/SET EX` + `SET NX` para el
  *single-flight*) manteniendo la misma interfaz.
- Desactiva o elimina los proveedores `demo-*` de `config/providers.json`
  cuando conectes fuentes reales (`futbolibre`, `deportelibre` y `tvenvivo` ya lo son).

### Siguientes pasos sugeridos

1. `catalog` + `meta` handlers para navegar contenido desde Stremio.
2. Subtítulos (`/subtitles/:type/:id.json`) y pistas de audio.
3. Proxy automático opcional de los `.m3u8` (`PROXY_STREAMS`) para Stremio Web.
4. Persistencia del caché en Redis y métricas Prometheus.
5. Integración con la app Kotlin: `AddonRepository` en `app/` consumiendo
   `/manifest.json` y `/stream/...`.

---

## 11. Licencia y descargo

Apache-2.0, igual que el proyecto Streamflix Reborn.
Este backend es una herramienta educativa de agregación: no aloja, almacena ni
distribuye contenido audiovisual. Respeta los términos de uso de cada sitio y
la legislación de tu país.
