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
