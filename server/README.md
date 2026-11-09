# Backend de catálogos Streamflix

Backend Node.js que publica catálogos y metadatos siguiendo el protocolo Stremio. **No incluye scrapers, runtime Python, proveedores de reproducción ni endpoints `stream`/`subtitles`.** Las fuentes reproducibles las obtiene Streamflix de los addons Stremio que el usuario instala en la app.

## Alcance

El backend conserva:

- `GET /manifest.json` — manifiesto de catálogo/metadatos.
- `GET /catalog/:type/:id.json` — filas de Cinemeta o de addons de catálogo declarados en `config/catalogs.json`.
- `GET /meta/:type/:id.json` — ficha y episodios desde Cinemeta.
- `GET /catalogs.json` — feed auxiliar que usa el cliente Android.
- `GET /health` y `/healthz` — estado del servicio y caché.
- `/proxy?url=...` — proxy HTTP opcional para una URL ya conocida; no busca ni descubre fuentes.

El manifiesto sólo anuncia los recursos `catalog` y `meta`. La petición a `/stream/...` o `/subtitles/...` devuelve 404. Un cliente que use este backend para catálogo debe configurar sus addons Stremio reproductores en Streamflix para obtener fuentes.

## Ejecutar

Requiere Node.js 20.12 o superior. No se necesita Python.

```bash
cd server
cp .env.example .env
npm ci
npm test
npm start
```

El servicio escucha en `0.0.0.0:7000`. Abre `http://<host>:7000/` para ver el estado y copiar el manifiesto. Configura la URL `http://<host>:7000` en Streamflix si quieres usar este backend para catálogos y metadatos.

### Docker

```bash
cd server
docker compose up -d --build
```

La imagen es Node + tini; no instala Python ni copia módulos de scraping.

## Configuración

Las variables se listan en [`.env.example`](.env.example). Las más usadas:

| Variable | Predeterminado | Uso |
| --- | --- | --- |
| `HOST` / `PORT` | `0.0.0.0` / `7000` | Dirección y puerto HTTP. |
| `PUBLIC_URL` | vacío | Origen público del manifiesto detrás de proxy inverso. |
| `CATALOGS_CONFIG` | `./config/catalogs.json` | Filas Cinemeta y addons externos que aportan **catálogos**. |
| `CINEMETA_BASE` | `https://v3-cinemeta.strem.io` | Upstream de metadatos y filas oficiales. |
| `CATALOG_TTL_MINUTES` | `30` | Caché de catálogos. |
| `CATALOG_SEARCH_TTL_MINUTES` | `5` | Caché de búsquedas. |
| `CATALOG_META_TTL_HOURS` | `24` | Caché de fichas. |
| `PROXY_ENABLED` | `true` | Habilita el proxy genérico para URLs conocidas. |
| `ADMIN_TOKEN` | vacío | Habilita `GET` y `DELETE /admin/cache` con bearer token. |

No hay variables `SCRAPER_*`, failover de providers ni lista de scrapers en este backend.

## Catálogos de terceros

El bloque `addons` de `config/catalogs.json` permite republicar filas de addons Stremio que expongan manifiestos/catálogos. Sólo se consulta `manifest` y `catalog`; el backend no solicita ni procesa sus streams. El cache local del manifiesto remoto ayuda a publicar las filas al reiniciar sin conexión.

## Pruebas

```bash
npm test
```

Las pruebas de Node cubren catálogos, metadatos, caché, manifiesto, proxy y endpoints activos. No hay pruebas ni ejecución de scrapers en este servidor.
