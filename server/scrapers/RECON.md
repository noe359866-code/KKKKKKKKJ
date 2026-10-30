# Reconocimiento de las webs (qué se comprobó y cuándo)

Este documento es la evidencia detrás de las rutas y los valores por defecto de
cada proveedor. Se rellenó **abriendo las webs de verdad** el **2026-10-30**,
una por una, y anotando lo que respondió cada una.

> Cómo se leyó: se pidieron las páginas públicas (portada, ficha, capítulo,
> buscador) y se comparó la URL final, el título y los enlaces que publica el
> sitio. No se descargó ni se alojó contenido; los proveedores sólo resuelven
> la URL del reproductor para que la app la reproduzca.

## Resumen

| Sitio | Dominio probado | Estado el 2026-10-30 | Decisión |
| --- | --- | --- | --- |
| JkAnime | `jkanime.net` | **Vivo** | Rutas confirmadas; se añadió su host de descargas a la lista negra |
| MonosChinos | `monoschinos.st` | **Vivo** | Ficha y capítulo **no** comparten slug: normalización nueva |
| PelisPlus | `pelisplushd.la` | **Vivo** | `/anime/<slug>` y el capítulo por temporada, confirmados |
| Gnula | `ww3.gnulahd.nu` | **Vivo** | Sin cambios: `/ver/<slug>/` y fichas de serie, como estaba |
| PelisJuanita | `pelisjuanita.org` | **Vivo** (fichas por JS) | Sin cambios de rutas |
| AnimeFLV | `www3.animeflv.net` | **Muerto como reproductor** | Nuevo dominio por defecto + rotación de espejos |
| Cuevana | `cue.cuevana3.nu` | **No responde** | Nuevo dominio por defecto + familia de espejos |
| Repelis | `repelis.live` | **No responde** | Nuevo dominio por defecto + familia de espejos |
| PeliculasFlix | `peliculasflix.co` | **No responde** | Se mantiene: su API GraphQL es otro host |

## JkAnime — `https://jkanime.net` (vivo)

- Portada: lista "Ver ahora" con `https://jkanime.net/<slug>/<episodio>`
  (el sitio redirige a la forma con barra final).
- Capítulo: `https://jkanime.net/<slug>/<episodio>/` → **confirma** la plantilla
  por defecto `JKANIME_EPISODE_PATH=/{slug}/{episode}/`.
- Reproductores del capítulo: botones `#option0…#option9` con Desu, Magi, Mega,
  Streamwish, Vidhide, VOE, Filemoon, Mixdrop, Streamtape, Doodstream. La página
  dice "Obteniendo servidores" hasta que el JS los pinta, así que el `var
  servers = [...]` sigue siendo la única fuente estática fiable: el proveedor ya
  lo lee y, si no hay, cae a los `<iframe>`.
- Descargas del propio sitio: `https://c1.jkplayers.com/d/<id>/` (Mediafire,
  Mega, 1Fichier…). **No** son streams: se añadió `jkplayers` a
  `_hosters.DOWNLOAD_ONLY` para que nunca se ofrezcan como reproducción.
- Buscador: `https://jkanime.net/buscar/<consulta>/` → **confirma**
  `JKANIME_SEARCH_PATH=/buscar/{query}/`. Devuelve fichas `/<slug>/`.
- Ficha del anime: `https://jkanime.net/<slug>/` → confirma
  `JKANIME_ANIME_PATH=/{slug}/` (es la que usa el respaldo de episodio).

## MonosChinos — `https://monoschinos.st` (vivo)

- Capítulo: `https://monoschinos.st/ver/<slug>-episodio-<n>` ✔
- Ficha: `https://monoschinos.st/anime/<slug>-sub-espanol` — **siempre** con el
  sufijo `-sub-espanol`.
- **El bug real**: `https://monoschinos.st/ver/black-clover-sub-espanol-episodio-1`
  devuelve **404** ("Página no encontrada"); el capítulo vive en
  `/ver/black-clover-episodio-1`. Como el id normalmente sale de la ficha (o del
  buscador, que enlaza fichas con sufijo), **ningún** capítulo abría: la URL se
  construía con el sufijo de la ficha.
- Arreglo: `episode_slug()` quita `-sub-espanol`/`-latino`/`-castellano` para el
  capítulo y `ficha_slug()` lo añade para la ficha. `meta.episode_slug` y
  `meta.ficha` publican los dos slugs.
- Buscador: `https://monoschinos.st/buscar?q=<consulta>` ✔ (`/anime/...` en los
  resultados). También devuelve películas y cortos, que son fichas válidas.

## PelisPlus — `https://pelisplushd.la` (vivo)

- Película: `/pelicula/<slug>` ✔
- Serie: `/serie/<slug>` y capítulo
  `/serie/<slug>/temporada/<n>/capitulo/<m>` ✔ (confirmado en
  `/serie/la-mesa-final` → `/serie/la-mesa-final/temporada/1/capitulo/1`).
- **Sección de anime**: el buscador devuelve `/anime/<slug>` junto a
  `/serie/<slug>` y `/pelicula/<slug>` (comprobado con "black clover"). Antes
  `/anime/` sólo estaba en `search_hints`: el buscador encontraba el anime y
  luego no había plantilla para abrirlo. Ahora está en `movie_paths`,
  `series_paths` y `ficha_paths`.
- Buscador: `/search?s=<consulta>` ✔ (devuelve 200 con resultados; los otros
  estilos `?q=` quedan como respaldo).
- Nota: los slugs de película a veces llevan el año (`spider-man-un-nuevo-dia-2026`);
  `slug_variants()` y TMDB ya prueban ambas formas.

## Gnula — `https://ww3.gnulahd.nu` (vivo)

- Portada con `https://ww3.gnulahd.nu/ver/<slug>/` para películas, series y
  anime ✔ (confirma `movie_paths` y `series_paths` = `/ver/{slug}/`).
- Las etiquetas de la portada (`LAT`, `SUB`, `CAST`, `CAM`…) son las que ya se
  usaban para deducir idioma y calidad.
- Sin cambios de estructura; el desofuscado `GNRD` sigue siendo el punto frágil.

## PelisJuanita — `https://pelisjuanita.org` (vivo, fichas por JS)

- Portada: `/pelicula/<slug>` (las películas llevan año: `…-2026`) y
  `/serie/<slug>` (`anatomia-segun-grey-2005`) ✔.
- Las fichas se hidratan en el cliente: al pedirlas se recibe el mismo
  `index.html` con otro `<title>`. Por eso el proveedor no depende de un
  selector del DOM: lee `og:video`, literales JS, `data-*`, iframes y abre la
  página de reproductor del propio sitio (`/player/<id>`).
- Sin cambios de rutas; ya cubría `/anime/` y `/wwe/`.

## AnimeFLV — el dominio histórico ya no reproduce

- `www3.animeflv.net`, `animeflv.net`, `www4`, `m.` **no responden** a las
  peticiones de páginas de episodio. Fuentes públicas (TorrentFreak vía
  peepstune, 14/07/2026; natchecker 25/10/2026) coinciden: el sitio dejó de
  servir vídeo entre abril y junio de 2026 y las páginas de episodio muestran
  "Actualmente no hay vídeos".
- El espejo que **sí** entrega episodios con la estructura clásica
  (`/ver/<slug>-<n>`, `/anime/<slug>`) es `https://vww.animeflv.one`
  (comprobado con `/ver/one-piece-1` → "One Piece episodio 1", lista de
  servidores y enlace a la ficha `/anime/one-piece`).
- Arreglo: `ANIMEFLV_BASE=https://vww.animeflv.one` por defecto y **rotación**:
  si un dominio no responde, se prueba el siguiente de
  `_bases()` (configurado → `ANIMEFLV_MIRRORS` → históricos
  `animeflv.one`, `animeflv.or.at`, `animeflv.ba`, `animeflv.my`,
  `www3.animeflv.net`). Se anota `meta.base` y `meta.base_rotated`.
- Si el usuario fija `ANIMEFLV_BASE`/`ANIMEFLV_MIRRORS`, se usan **sólo** esos
  (nada de fugas a la red ni a dominios históricos).

## Cuevana — `cue.cuevana3.nu` no responde

- `https://cue.cuevana3.nu/` no respondió (tampoco en peticiones anteriores).
- `https://cuevana3.gs/peliculas/spider-man-de-regreso-a-casa-2017/` **sí**
  responde con la estructura de siempre: ficha `/peliculas/<slug>-<año>/`,
  pestañas "Latino / Descargar", relacionados y `<title>` por título.
- Arreglo: `BASE = https://cuevana3.gs` y familia de espejos
  (`cuevana-3.fun`, `cuevana.gs`, `cue.cuevana3.nu`) cuando nadie configura
  `CUEVANA_BASE`/`CUEVANA_MIRRORS`.
- Ojo: al ser un nombre replicado, cada clon puede usar `showEmbed` (base64) o
  `player.php?t=` (token). El proveedor soporta los dos y, si no, cae a los
  iframes de la ficha.

## Repelis — `repelis.live` no responde

- `repelis.live` no respondió. `https://www.repelis.net` se anuncia como
  "oficial" y responde (Cloudflare), así que pasa a ser el valor por defecto,
  con `repelishd.run`, `repelis.ink` y `repelis.live` como espejos.
- Sigue siendo el proveedor más frágil: si todos fallan, `meta.hint` lo dice y
  `meta.tried_bases` lista lo intentado.

## PeliculasFlix — `peliculasflix.co` no responde

- La web no respondió, pero el proveedor **no** depende de ella: todo pasa por
  su API GraphQL (`PELICULASFLIX_API`, por defecto `fluxcedene.net/api/gql`).
  El dominio sólo se usa como `Origin`/`Referer`, así que se deja configurable
  (`PELICULASFLIX_BASE`/`_MIRRORS`) y se sigue usando el que haya.
- Recuerda: las series se resuelven con `getSerieLinks`/`getEpisodeLinks`, y la
  página/Referer es `/serie/<slug>/<n>x<mm>`.

## Qué **no** se pudo comprobar

- El HTML crudo (atributos `data-*`, contenido de `<script>`): la herramienta de
  lectura devuelve texto, no el DOM. Por eso los selectores nuevos se apoyan en
  lo que el sitio publica como enlaces visibles y en los fixtures.
- Ninguno de los hosts de video (Streamwish, Filemoon, VOE…) desde esta red: la
  resolución de embeds se validó offline con fixtures que replican su JS.

# Directo (TV y deporte): los cuatro proveedores nuevos

Reconocimiento del **2026-10-30**, el mismo día del commit. Se miraron las
portadas y una ficha interior de cada sitio, y se cruzó con implementaciones
open-source que ya existen para ellos (el proyecto pide apoyarse en otros
repos):

- [`Ziorub96/M3u8-extractor-`](https://github.com/Ziorub96/M3u8-extractor-):
  `damitv_extractor.py` (de ahí salen `/papi/api/streams`,
  `/papi/matches/all` y `/papi/extract-url/`) y `streamcorner_extractor.py`
  (SPA, botones `.stream-source-btn`/`[data-source]`, "click para que pida el
  m3u8").
- [`rajhodedara/live-sport-plugin`](https://github.com/rajhodedara/live-sport-plugin):
  `DamiTvProvider.js` confirma las dos fuentes (`/papi/api/streams` y
  `/papi/matches/all`), que el feed **no** da `.m3u8` crudos y que los `embed`
  son el contrato.
- [`guillosan21/streamcorner-tv-feed`](https://github.com/guillosan21/streamcorner-tv-feed):
  rutas `/stream/…` y espejos del sitio.

| Sitio | Qué respondió | Entrada | Cómo se saca la señal | Confianza |
| --- | --- | --- | --- | --- |
| `livelive24.com` | Portada con "Live Sport 654" y "Live TV 434", filtro por país, origen por tarjeta (`Football24`, `Tennis24`, `HotHD🔥`, `PeleZidaneTV`) | Ficha de la tarjeta | `harvest()` sobre la ficha: iframe/literal JS/`data-*` → `.m3u8` | media (índice con tarjetas; ficha no inspeccionable en crudo) |
| `damitv.st` | **API pública**: `/papi/api/streams` (partidos por deporte) y `/papi/matches/all` (24/7), JSON completo | `id` del feed | `sources[].embed` trae `?url=%2Fc%2F346.m3u8` → se decodifica; si no, se abre `/embed/?id=…` | **alta** (JSON real leído) |
| `ntv.st` | `/matches/{raptor,falcon,phoenix,titan}` con tarjetas `Live…CategoríaN` → `/watch/{servidor}/ppv-{id}`; ficha con "Source 1 (247CHANNEL)" y iframe "Loading stream" | `ppv-{id}` | Los cuatro servidores sirven el mismo id: se prueban en orden y se publica la primera señal | alta en rutas, media en el reproductor |
| `streamcorner.fun` | SPA (React Router): `/stream/{liga}/{slug|hex}`, `/extra`, "AVAILABLE SOURCES" con varias señales por evento, reproductor Bitmovin | ruta `/stream/…` | `harvest()` sobre la ficha (botones `[data-source]`, iframes, config Bitmovin) | media (si el manifiesto se pide sólo por JS, se informa sin streams) |

Detalles que cambiaron el diseño:

- **DAMITV no necesita navegador.** Su API es el contrato real; el `embed` ya
  lleva la ruta del manifiesto (`/c/346.m3u8`, `/dl/353.m3u8`). Los `.m3u8`
  llevan `Referer: https://damitv.st/`.
- **NTV son cuatro espejos, no cuatro sitios.** El mismo `ppv-18172` responde
  en raptor y falcon; el scraper usa eso como failover (y deduplica el índice,
  que si no devuelve cada evento cuatro veces).
- **Los nombres vienen pegados.** El índice de NTV concatena canal + categoría
  + contador (`LiveNFL NetworkAmerican Football1`), así que `ntv.channel_name`
  recorta la categoría del final en vez de adivinar; `Liverpool TV` no se toca
  (la etiqueta `Live` sólo se quita si va seguida de mayúscula o separador).
- **La tarjeta de LiveLive24 trae la fuente delante** (`Football24 ESPN`):
  se descarta el `<span class="src|source|badge">` y, si no hay markup, la
  etiqueta conocida (`\w{3,}\d{2}`, `HotHD`, `PeleZidaneTV`).

## Qué **no** se pudo comprobar en el directo

- El **HTML crudo** de las fichas (atributos del reproductor, `src` del iframe):
  esta red sólo tiene salida a GitHub y a los registros de paquetes, así que
  todo lo que se miró fue con el lector de páginas (texto, sin DOM). Por eso la
  resolución genérica (`_livekit.harvest`) cubre iframes, `data-*`, literales
  JS, base64 y hostings conocidos, y se prueba con fixtures que replican cada
  caso en `server/test/{damitv,ntv,streamcorner,livelive24}.test.js`.
- Que los CDN acepten el `Referer` del reproductor en todos los casos: se
  publica el `referer` del documento donde apareció el manifiesto, que es lo
  que envía el navegador.
- Ids concretos de `livelive24` (no se pudo leer una ficha): el proveedor queda
  registrado y resuelve por nombre, pero **no** se añadieron canales suyos a
  `config/live-catalog.json`.
