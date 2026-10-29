/**
 * src/lib/cinemeta.js
 * ---------------------------------------------------------------------------
 * Cliente de **Cinemeta**, el addon oficial de metadatos de Stremio.
 *
 * Cinemeta es la fuente canónica de cartelera para cualquier cliente Stremio:
 * expone los catálogos `top`, `year` e `imdbRating` de películas y series, y
 * una ficha completa en `/meta/{type}/{id}.json`.
 *
 *   GET {base}/catalog/movie/top.json
 *   GET {base}/catalog/series/top/skip=100.json
 *   GET {base}/catalog/movie/top/genre=Action.json
 *   GET {base}/catalog/movie/top/search=matrix.json
 *   GET {base}/meta/movie/tt0133093.json
 *
 * Este módulo es **sin estado**: sólo construye URLs, normaliza la respuesta y
 * pide por HTTP con timeout. La caché, el proxy y la mezcla con las fuentes
 * locales viven en `services/catalogService.js`, para que un fallo de Cinemeta
 * no obligue a tocar la capa de red.
 *
 * Qué se normaliza y por qué
 * --------------------------
 * Cinemeta devuelve objetos Stremio "casi" estándar, pero con matices que
 * rompen clientes ingenuos:
 *   - `releaseInfo` es "2010" pero también "2019–2023" (serie en emisión);
 *   - `imdbRating` viene como string;
 *   - los episodios están en `videos[]` con `season`/`episode` numéricos y un
 *     `id` ya listo para `/stream/series/<id con :S:E>`.
 * Se dejan tal cual los campos que ya son estándar y se añaden dos derivados
 * propios (`year`, `rating`) que la app Android consume directamente.
 */

import config from '../config.js';

/** Tipos que Cinemeta sirve de verdad. `anime` no existe: se pide `series`. */
export const CINEMETA_TYPES = Object.freeze(['movie', 'series']);

/** `true` si Cinemeta puede responder a este tipo. */
export function isCinemetaType(type) {
  return CINEMETA_TYPES.includes(String(type || '').toLowerCase());
}

/** URL base efectiva (configurable con CINEMETA_BASE para tests/mirrors). */
export function cinemetaBase() {
  return config.catalogs.cinemetaBase;
}

/**
 * Construye la ruta de un catálogo con los *extras* del protocolo Stremio.
 *
 * Stremio concatena los extras al path, no como query string:
 *   /catalog/movie/top/skip=100/genre=Action.json
 *
 * @param {object} input
 * @param {string} input.type  movie | series
 * @param {string} input.id    top | year | imdbRating
 * @param {object} [input.extra]
 * @param {number|string} [input.extra.skip]
 * @param {string} [input.extra.genre]
 * @param {string} [input.extra.search]
 * @returns {string} path relativo (sin base, sin host)
 */
export function buildCatalogPath({ type, id, extra = {} }) {
  let path = `/catalog/${encodeSegment(type)}/${encodeSegment(id)}`;

  /* El orden de los extras no importa para Cinemeta, pero mantenerlo fijo
   * hace que la URL sea cacheable y comparable en los tests. */
  if (extra.genre) path += `/genre=${encodeSegment(extra.genre)}`;
  const skip = Number(extra.skip);
  if (Number.isInteger(skip) && skip > 0) path += `/skip=${skip}`;
  if (extra.search) path += `/search=${encodeSegment(extra.search)}`;

  return `${path}.json`;
}

/** Ruta de la ficha de un título: `/meta/movie/tt0133093.json`. */
export function buildMetaPath({ type, id }) {
  return `/meta/${encodeSegment(type)}/${encodeSegment(id)}.json`;
}

/** Encoda un segmento sin romper ids con `:` (temporadas/episodios). */
function encodeSegment(value) {
  return encodeURIComponent(String(value ?? '').trim())
    .replace(/%3A/gi, ':')
    .replace(/%2F/gi, '');
}

/**
 * Normaliza una meta de Cinemeta al contrato que consumen nuestro cliente y
 * nuestros propios endpoints `/meta`.
 *
 * @param {object} raw
 * @param {{ type?: string }} [fallback]
 * @returns {object|null}
 */
export function normalizeMeta(raw, fallback = {}) {
  if (!raw || typeof raw !== 'object') return null;

  const id = String(raw.id || raw.imdb_id || '').trim();
  if (!id) return null;

  const type = String(raw.type || fallback.type || '').toLowerCase() || undefined;
  const releaseInfo = raw.releaseInfo ?? raw.year ?? undefined;
  const year = parseYear(releaseInfo);
  const rating = parseRating(raw.imdbRating ?? raw.rating);

  const meta = {
    id,
    ...(type ? { type } : {}),
    name: String(raw.name || raw.title || id).trim(),
  };

  /* Arte. Cinemeta usa `poster` (2:3) y `background` (16:9); el cliente pinta
   * el hero con `background` y la tarjeta con `poster`. */
  assignIf(meta, 'poster', raw.poster);
  assignIf(meta, 'background', raw.background ?? raw.backdrop);
  assignIf(meta, 'logo', raw.logo);
  assignIf(meta, 'description', raw.description);
  assignIf(meta, 'releaseInfo', releaseInfo);
  assignIf(meta, 'runtime', raw.runtime);
  assignIf(meta, 'imdb_id', raw.imdb_id ?? (id.startsWith('tt') ? id : undefined));

  if (Number.isFinite(year)) meta.year = year;
  if (Number.isFinite(rating)) meta.rating = rating;

  const genres = toStringArray(raw.genres ?? raw.genre);
  if (genres.length > 0) meta.genres = genres;

  const videos = normalizeVideos(raw.videos);
  if (videos.length > 0) meta.videos = videos;

  /* `trailerStreams` y `links` no los usa el cliente; se descartan a propósito
   * para no engordar la respuesta del Home (100 metas × 5 URL extra). */
  return meta;
}

/**
 * Normaliza la lista de un catálogo.
 * Descarta entradas sin id (Stremio rompe el cliente si ocurre).
 */
export function normalizeMetas(metas, fallback = {}) {
  if (!Array.isArray(metas)) return [];
  const out = [];
  const seen = new Set();
  for (const raw of metas) {
    const meta = normalizeMeta(raw, fallback);
    if (!meta || seen.has(meta.id)) continue;
    seen.add(meta.id);
    out.push(meta);
  }
  return out;
}

/**
 * Normaliza los episodios de una serie.
 *
 * El `id` que trae Cinemeta (`tt0944947:1:2`) es exactamente lo que hay que
 * pedir a `/stream/series/…`, así que se conserva sin recomponerlo.
 */
export function normalizeVideos(videos) {
  if (!Array.isArray(videos)) return [];
  const out = [];
  for (const video of videos) {
    if (!video || typeof video !== 'object') continue;
    const season = Number(video.season);
    const episode = Number(video.episode);
    const id = String(video.id || '').trim();
    if (!id && !(Number.isInteger(season) && Number.isInteger(episode))) continue;

    out.push({
      id: id || `${season}:${episode}`,
      ...(Number.isInteger(season) ? { season } : {}),
      ...(Number.isInteger(episode) ? { episode } : {}),
      ...(video.name || video.title ? { name: String(video.name || video.title) } : {}),
      ...(video.overview ? { overview: String(video.overview) } : {}),
      ...(video.released ? { released: String(video.released) } : {}),
      ...(video.thumbnail ? { thumbnail: String(video.thumbnail) } : {}),
    });
  }
  return out.sort((a, b) => (a.season ?? 0) - (b.season ?? 0) || (a.episode ?? 0) - (b.episode ?? 0));
}

/** "2010" → 2010 · "2019–2023" → 2019 · "2019-" → 2019. */
export function parseYear(releaseInfo) {
  const match = String(releaseInfo ?? '').match(/(\d{4})/);
  return match ? Number(match[1]) : undefined;
}

/** "8.8" → 8.8 (Cinemeta lo manda como string). */
export function parseRating(value) {
  const rating = Number.parseFloat(value);
  return Number.isFinite(rating) && rating > 0 ? Number(rating.toFixed(1)) : undefined;
}

function assignIf(target, key, value) {
  if (value === undefined || value === null || value === '') return;
  target[key] = typeof value === 'string' ? value.trim() : value;
}

function toStringArray(value) {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => String(item ?? '').trim())
    .filter(Boolean);
}

/**
 * GET + JSON con timeout, compartido por catálogos y fichas.
 *
 * No usa `fetchWithTimeout` de lib/http.js porque aquél inyecta el
 * `Referer`/`User-Agent` de los proveedores de vídeo: a Cinemeta hay que
 * hablarle como un cliente Stremio más, sin cabeceras de hotlink.
 *
 * @param {string} path - ruta relativa devuelta por build*Path()
 * @param {object} [options]
 * @param {typeof fetch} [options.fetchImpl] - inyectable en tests
 * @param {number} [options.timeoutMs]
 * @returns {Promise<object|null>} JSON parseado o null si falla
 */
export async function cinemetaGet(path, options = {}) {
  const {
    fetchImpl = globalThis.fetch,
    timeoutMs = config.catalogs.timeoutMs,
    base = cinemetaBase(),
  } = options;

  const url = `${base}${path}`;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error(`timeout after ${timeoutMs}ms`)), timeoutMs);

  try {
    const response = await fetchImpl(url, {
      signal: controller.signal,
      headers: {
        Accept: 'application/json',
        /* Cinemeta devuelve gzip; el UA identifica el addon y no molesta. */
        'User-Agent': `StreamflixReborn/${config.addon.version}`,
      },
    });

    if (!response.ok) return null;
    return await response.json();
  } catch {
    /* Red caída, DNS, timeout, JSON inválido: para el addon es "sin datos". */
    return null;
  } finally {
    clearTimeout(timer);
  }
}

export default {
  CINEMETA_TYPES,
  isCinemetaType,
  cinemetaBase,
  buildCatalogPath,
  buildMetaPath,
  normalizeMeta,
  normalizeMetas,
  normalizeVideos,
  parseYear,
  parseRating,
  cinemetaGet,
};
