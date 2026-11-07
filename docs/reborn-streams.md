# Port Streamflix Reborn2: sólo streams

Este proyecto integra el port de scrapers/extractores de
`https://git.gay/streamflix-reborn2/streamflix` como un módulo Android separado
(`reborn-streams`). El objetivo es **entregar únicamente streams reproducibles**:
no publica catálogos, fichas, géneros ni filas de Home procedentes de esas fuentes.

## Procedencia

- Upstream solicitado: `https://git.gay/streamflix-reborn2/streamflix`.
- El sandbox no pudo alcanzar `git.gay`; se usó el mirror accesible
  `https://github.com/pwpp08/Streamable`.
- Commit fijado: `7ae9a33495c70fda56f77a7f7f3123c49c0057c2` (2026-10-26).
- Licencia: Apache-2.0 (`reborn-streams/LICENSE`, `reborn-streams/NOTICE.md`).
- Inventario empaquetado: `assets/reborn-streams.json` dentro del APK.

## Contrato stream-only

- Fachada pública: `com.streamflixreborn.streamflix.streams.StreamOnlyResolver`.
- Entrada: `StreamRequest(type, id, title, year, season, episode, imdbId)`.
- Salida: `List<ResolvedStream>` con URL, cabeceras, tipo de medio y subtítulos.
- La fachada nunca llama `getHome`, `getMovies`, `getTvShows`, `getGenre` ni
  `getPeople`. `search/getTvShow/getEpisodesBySeason` se usan sólo como búsqueda
  interna para localizar el título/episodio que se va a reproducir.
- El decorador `RebornStreamsContentClient` conserva los catálogos/meta del motor
  autónomo existente y fusiona sólo los streams Reborn con los del motor Python.
- La TV en vivo/directo existente no se reemplaza por catálogos Reborn.

## Seguridad y límites

- No se hereda el `trustAll` del upstream: `DnsResolver`/`NetworkClient` usan DNS
  del sistema y TLS con validación normal. `VavooTls` es un no-op seguro.
- No se distribuyen secretos nativos de Uprot; `UprotResolver` sólo conserva
  enlaces MaxStream ya resueltos y falla cerrado en el resto.
- WebView/Cronet: se incluye `WebViewResolver` del upstream para desafíos que lo
  requieran; el cliente Cronet de AnimeOnlineNinja se sustituye por OkHttp.
- CAPTCHA/DRM no se prometen: si una fuente exige un desafío no resoluble o DRM,
  el failover del reproductor pasa a la siguiente fuente.
- TMDB es opcional: sin `TMDB_API_KEY` no se habilita enriquecimiento TMDB.

## Verificación

- Tests JVM: `:reborn-streams:testDebugUnitTest` y `:app:testDebugUnitTest`.
- El verificador de APK exige `assets/reborn-streams.json` con
  `streamOnly=true` y `catalogsPublished=false`.
- El motor Python y el puente Node existentes se conservan como fallback/live.
