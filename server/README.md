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
│   ├── example_provider.py      # demo con streams públicos de prueba
│   ├── template_provider.py     # plantilla para un sitio real
│   └── tvenvivo.py              # proveedor real: tvenvivo.org (live)
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

Instalar en Stremio / la app: usa como URL del addon
`http://<IP-de-tu-servidor>:7000/manifest.json`
(o `stremio://<host>:7000/manifest.json`). Desde la propia landing (`/`) puedes
copiar la URL y ver el estado de la caché en vivo.

---

## 3. Endpoints

| Método | Ruta | Descripción |
| --- | --- | --- |
| `GET` | `/manifest.json` | Manifiesto del addon (`movie`, `series`, `anime`, `tv`). |
| `GET` | `/stream/:type/:id.json` | Fuentes de video ordenadas por prioridad. |
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
  "resources": [{ "name": "stream", "types": ["movie", "series", "anime", "tv"] }],
  "catalogs": []
}
```

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
        "isLive": true
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
- El **orden del array es la lista de failover**: el cliente intenta
  `streams[0]`, y si falla pasa a `streams[1]`, etc.
- Cabeceras útiles de diagnóstico: `X-Cache: HIT|STALE|MISS|COALESCED`,
  `X-Response-Time` (ms), `X-Request-Id`.

---

## 4. Caché TTL inteligente

`src/cache/TtlCache.js` (sin dependencias externas) ofrece:

| Característica | Detalle |
| --- | --- |
| TTL por entrada | live/deportes `CACHE_TTL_LIVE_HOURS` (2 h), VOD `CACHE_TTL_VOD_HOURS` (6 h). Validado entre **2 y 6 h** al arrancar. |
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
  `PROVIDER_TIMEOUT_MS` (12 s por defecto).
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
| `RATE_LIMIT_MAX` | `120` | Peticiones/min/IP en `/stream` y `/proxy` (0 = off). |
| `CACHE_TTL_LIVE_HOURS` | `2` | TTL live/deportes (2–6). |
| `CACHE_TTL_VOD_HOURS` | `6` | TTL VOD/anime/series (2–6). |
| `CACHE_TTL_MISS_SECONDS` | `120` | Caché negativa. |
| `CACHE_MAX_ENTRIES` | `5000` | Cota LRU. |
| `CACHE_STALE_FACTOR` | `1.25` | Ventana de `stale-while-revalidate`. |
| `SCRAPER_PYTHON` | `python3` | Intérprete (ej. `.venv/bin/python`). |
| `SCRAPER_DIR` | `./scrapers` | Carpeta de scripts. |
| `SCRAPER_TIMEOUT_MS` | `15000` | Timeout por script. |
| `SCRAPER_MAX_CONCURRENCY` | `4` | Procesos Python simultáneos. |
| `PROVIDER_TIMEOUT_MS` | `12000` | Presupuesto por proveedor. |
| `MIN_PROVIDERS_FOR_FAST_RETURN` | `2` | Respuesta temprana. |
| `FAST_RETURN_GRACE_MS` | `1500` | Ventana extra antes de responder. |
| `ADMIN_TOKEN` | *(vacío)* | Activa `/admin/*`; sin él devuelven 404. |
| `PROXY_ENABLED` | `true` | Habilita `/proxy`. |
| `LOG_LEVEL` / `LOG_JSON` | `info` / `false` | Logging. |
| `TVENVIVO_BASE` | `https://www.tvenvivo.org` | Base del sitio (los tests usan un fixture local). |
| `TVENVIVO_INDEX_TTL_SECONDS` | `21600` | TTL del índice de canales (`0` = sin caché). |
| `TVENVIVO_INDEX_CACHE` | `…/streamflix-tvenvivo-index.json` | Ruta del índice en disco. |
| `TVENVIVO_MAX_DOCS` / `TVENVIVO_MAX_DEPTH` | `8` / `3` | Topes de documentos y saltos al resolver un reproductor. |

Todas se pueden poner en `server/.env` (se carga automáticamente, sin
dependencias) o inyectarlas desde el orquestador.

---

## 8. Tests

```bash
npm test
```

41 pruebas con `node:test`:

- caché: TTL, expiración, stale, LRU, *single-flight*, caché negativa;
- protocolo: manifest, forma del stream, cabeceras, ids, calidades, HLS, SSRF;
- pool de proveedores: ejecución en paralelo, orden por prioridad y deadline;
- integración: arranca Express en un puerto efímero y ejecuta los scrapers
  Python reales para validar `MISS → HIT` y la coalescencia de peticiones;
- `tvenvivo.py`: fixture HTTP local que imita el sitio (página → `SOURCES` →
  wrapper → iframe → `.m3u8` escapado), ids aceptados, búsqueda por nombre,
  descarte de anuncios, degradación a `{"streams":[]}` y caché del índice.

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
  cuando conectes fuentes reales (`tvenvivo` ya es un proveedor real).

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
