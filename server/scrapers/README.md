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
| `example_provider.py` | Demo con streams públicos (Mux / Google samples). Verifica el pipeline completo. |
| `template_provider.py` | Plantilla comentada para un sitio real (urllib + regex, sin dependencias). |
| `tvenvivo.py` | **Proveedor real** de `www.tvenvivo.org` (TV/deportes en directo, LATAM). Ver la sección siguiente. |

## Añadir un proveedor

```bash
cp scrapers/template_provider.py scrapers/mi_sitio.py
# edita mi_sitio.py: url de búsqueda, parseo del player, calidad…
```

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
