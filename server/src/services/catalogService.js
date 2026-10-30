/**
 * src/services/catalogService.js
 * ---------------------------------------------------------------------------
 * Catálogos del Home: Cinemeta (metadatos oficiales) + fuentes locales.
 *
 * Por qué un servicio y no un proxy tonto
 * ---------------------------------------
 * Un `GET /catalog/:type/:id.json` de este addon no es un espejo de Cinemeta:
 *
 *   1. **Traduce el tipo publicado al tipo de origen.** Nuestro addon declara
 *      `anime`, que Cinemeta no tiene: la fila "Anime en emisión" es en realidad
 *      el catálogo `series` filtrado por `genre=Animation`.
 *   2. **Mezcla fuentes.** Las filas de TV en vivo y deportes salen de
 *      `config/live-catalog.json` (ids locales tipo `tv:futbolibre:espn-1`), no
 *      de Cinemeta. El cliente ve una sola cartelera coherente.
 *   3. **Aísla la caída de Cinemeta.** Si el upstream falla se sirve la copia
 *      *stale* si existe; si no, una lista vacía con `degraded: true` y un TTL
 *      corto, en lugar de un 502 que dejaría el Home en blanco.
 *   4. **Cachea con dos ventanas.** Las filas cambian despacio (30 min); las
 *      búsquedas, no (5 min).
 *
 * El resultado se devuelve ya normalizado (`{ metas: [...] }`), que es lo que
 * espera Stremio y lo que consume la app Android.
 */

import { readFile } from 'node:fs/promises';

import config from '../config.js';
import { cache } from '../cache/TtlCache.js';
import { runScript } from '../bridge/pythonBridge.js';
import { getProvidersFor } from '../providers/index.js';
import { ensureCatalogs, findCatalog } from '../catalogs/registry.js';
import {
  buildCatalogPath,
  buildMetaPath,
  cinemetaGet,
  normalizeMeta,
  normalizeMetas,
} from '../lib/cinemeta.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('catalogs');

/* Prefijos de caché: permiten invalidar catálogos sin tocar los streams. */
const CATALOG_PREFIX = 'catalog:';
const META_PREFIX = 'meta:';

/* -------------------------------------------------------------------------- */
/* Claves de caché                                                            */
/* -------------------------------------------------------------------------- */

/**
 * Clave determinista de un catálogo.
 * Los extras forman parte de la clave: `skip=100` y `search=x` son respuestas
 * distintas y no deben pisarse entre sí.
 */
export function catalogCacheKey({ type, id, extra = {} }) {
  const parts = [String(type).toLowerCase(), String(id)];
  if (extra.genre) parts.push(`genre=${normalizeText(extra.genre)}`);
  const skip = Number(extra.skip);
  if (Number.isInteger(skip) && skip > 0) parts.push(`skip=${skip}`);
  if (extra.search) parts.push(`search=${normalizeText(extra.search)}`);
  return `${CATALOG_PREFIX}${parts.join(':')}`;
}

/** Clave de la ficha de un título. */
export function metaCacheKey(type, id) {
  return `${META_PREFIX}${String(type).toLowerCase()}:${String(id)}`;
}

/* -------------------------------------------------------------------------- */
/* Catálogo local (TV en vivo y deportes)                                     */
/* -------------------------------------------------------------------------- */

/* El JSON de canales cambia cuando el usuario lo edita, no en cada request:
 * se guarda en memoria con una marca de tiempo y se recarga cada 5 minutos. */
const LIVE_RELOAD_MS = 5 * 60 * 1000;
let liveState = { items: [], loadedAtMs: 0 };

/**
 * Lee `config/live-catalog.json` y lo convierte en metas Stremio.
 *
 * El `id` de cada canal es el id de stream (`tv:futbolibre:espn-1`), así que el
 * cliente puede pedir `/stream/tv/<id>.json` con lo que ya tiene en la tarjeta.
 *
 * @param {object} [options]
 * @param {boolean} [options.force] - ignora la copia en memoria
 * @param {string} [options.path]   - ruta alternativa (tests)
 * @returns {Promise<object[]>} metas normalizadas
 */
export async function loadLiveCatalog({ force = false, path = config.catalogs.liveCatalogPath } = {}) {
  const now = Date.now();
  if (!force && liveState.items.length > 0 && now - liveState.loadedAtMs < LIVE_RELOAD_MS) {
    return liveState.items;
  }

  try {
    const raw = JSON.parse(await readFile(path, 'utf8'));
    const items = Array.isArray(raw) ? raw : raw.items || [];
    const metas = items.map(liveItemToMeta).filter(Boolean);
    liveState = { items: metas, loadedAtMs: now };
    log.debug('live catalog loaded', { path, items: metas.length });
    return metas;
  } catch (error) {
    log.warn('could not read the live catalog', { path, error: error.message });
    /* Se conserva la última copia buena: un JSON a medio guardar no debe
     * vaciar la fila de TV en vivo. */
    return liveState.items;
  }
}

/* -------------------------------------------------------------------------- */
/* Canales y agenda recogidos de los proveedores de directo                   */
/* -------------------------------------------------------------------------- */

/* Un scrape cada `CATALOG_LIVE_TTL_MINUTES` para las dos filas: los canales de
 * los sitios cambian poco y la agenda, cada hora. Pedirlo por request sería
 * castigar a los proveedores y a la vez al usuario. */
let liveShelfState = { data: null, loadedAtMs: 0, failedAtMs: 0 };

/**
 * Canales y agenda que publican los proveedores de directo.
 *
 * Se delega en `scrapers/_liveshelf.py` (el mismo constructor que usa el motor
 * del APK) para no tener dos listas distintas en el servidor y en la app.
 *
 * @param {object} [options]
 * @param {object} [options.liveShelf] - inyección para las pruebas
 * @returns {Promise<{channels: object[], events: object[]}>}
 */
export async function loadLiveShelf(options = {}) {
  if (options.liveShelf) return options.liveShelf;

  const now = Date.now();
  if (liveShelfState.data && now - liveShelfState.loadedAtMs < config.catalogs.liveTtlMs) {
    return liveShelfState.data;
  }
  /* Tras un fallo no se reintenta en cada request: 1 minuto de calma. */
  if (!liveShelfState.data && now - liveShelfState.failedAtMs < 60_000) {
    return { channels: [], events: [] };
  }

  const providers = getProvidersFor(true)
    .map(({ id, label, referer, userAgent }) => ({ id, label, referer, userAgent }));

  const result = await runScript('_liveshelf.py', {
    providers,
    channelLimit: config.catalogs.pageSize,
    eventLimit: config.catalogs.pageSize,
    timeoutMs: config.catalogs.liveTimeoutMs,
  }, { timeoutMs: config.catalogs.liveTimeoutMs * 2 + 4_000 });

  const data = result.ok && result.data && typeof result.data === 'object'
    ? {
        channels: Array.isArray(result.data.channels) ? result.data.channels : [],
        events: Array.isArray(result.data.events) ? result.data.events : [],
      }
    : { channels: [], events: [] };

  if (data.channels.length === 0 && data.events.length === 0) {
    liveShelfState = { ...liveShelfState, failedAtMs: Date.now() };
    log.warn('live shelf came back empty', { providers: providers.length, reason: result.meta?.reason });
    return { channels: [], events: [] };
  }

  liveShelfState = { data, loadedAtMs: Date.now(), failedAtMs: 0 };
  log.debug('live shelf loaded', { channels: data.channels.length, events: data.events.length });
  return data;
}

/** Fila `live`: canales o agenda, con los filtros de Stremio y respaldo curado. */
async function liveMetas(definition, extra, options) {
  const section = definition.source.section === 'events' ? 'events' : 'channels';
  let metas = [];
  try {
    const shelf = await loadLiveShelf(options);
    metas = Array.isArray(shelf[section]) ? shelf[section] : [];
  } catch (error) {
    log.warn('live shelf unavailable', { error: error.message });
  }

  /* Si el scrape no trae nada, la fila curada sigue en pie: una Home sin
   * canales sería peor que una con los de siempre. La agenda es distinto: un
   * partido de ayer pintado como "Hoy" es una tarjeta muerta, así que se queda
   * vacía en vez de mentir. */
  if (metas.length === 0) {
    if (section === 'events') return [];
    return localMetas(definition, extra);
  }

  metas = metas.map((meta) => ({
    ...meta,
    type: definition.type,
    posterShape: 'landscape',
  }));
  return filterLiveMetas(metas, extra);
}

/** Género, búsqueda y `skip` sobre las tarjetas de la estantería. */
function filterLiveMetas(metas, extra) {
  let result = metas;

  if (extra.genre) {
    const wanted = normalizeText(extra.genre);
    result = result.filter((meta) =>
      (meta.genres || []).some((genre) => normalizeText(genre) === wanted));
  }

  if (extra.search) {
    const query = normalizeText(extra.search);
    result = result.filter((meta) =>
      normalizeText(meta.name).includes(query) ||
      normalizeText(meta.league || '').includes(query) ||
      normalizeText(meta.description || '').includes(query) ||
      (meta.genres || []).some((genre) => normalizeText(genre).includes(query)));
  }

  const skip = Number(extra.skip);
  if (Number.isInteger(skip) && skip > 0) result = result.slice(skip);
  return result;
}

/**
 * Un canal del JSON local → meta Stremio.
 *
 * `posterShape: 'landscape'` es la señal estándar para que cualquier cliente
 * (Stremio web incluido) pinte la tarjeta en 16:9.
 */
export function liveItemToMeta(item) {
  if (!item || typeof item !== 'object') return null;
  const id = String(item.id || '').trim();
  if (!id) return null;

  const genres = Array.isArray(item.genres) ? item.genres.filter(Boolean) : [];
  const meta = {
    id,
    type: 'tv',
    name: String(item.name || id).trim(),
    posterShape: 'landscape',
    releaseInfo: String(item.releaseInfo || 'En directo'),
    genres: genres.length > 0 ? genres : ['Deportes'],
  };

  if (item.description) meta.description = String(item.description);
  if (item.logo) meta.poster = String(item.logo);
  if (item.poster) meta.poster = String(item.poster);
  if (item.background) meta.background = String(item.background);
  if (item.category) meta.category = String(item.category);

  return meta;
}

/* -------------------------------------------------------------------------- */
/* Catálogos                                                                  */
/* -------------------------------------------------------------------------- */

/**
 * Devuelve un catálogo publicado.
 *
 * @param {object} request
 * @param {string} request.type - tipo Stremio publicado (movie|series|anime|tv)
 * @param {string} request.id   - id del catálogo declarado en catalogs.json
 * @param {object} [request.extra] - { search, skip, genre }
 * @param {object} [options]
 * @param {typeof fetch} [options.fetchImpl] - inyectable en tests
 * @returns {Promise<null|{
 *   metas: object[], catalog: { type: string, id: string, name: string, posterShape: string },
 *   source: 'cinemeta'|'local', degraded: boolean, cached: boolean, stale: boolean,
 *   coalesced: boolean, ttlMs: number, durationMs: number
 * }>} null cuando el catálogo no está declarado
 */
export async function getCatalog({ type, id, extra = {} }, options = {}) {
  const startedAt = process.hrtime.bigint();
  await ensureCatalogs();

  const definition = findCatalog(type, id);
  if (!definition) return null;

  const key = catalogCacheKey({ type, id, extra });
  /* Los canales y la agenda se recogen con su propia ventana: cachear 30 min una
   * agenda que cambia cada hora dejaría partidos ya jugados en la fila. */
  const ttlMs = extra.search
    ? config.catalogs.searchTtlMs
    : definition.source.kind === 'live'
      ? config.catalogs.liveTtlMs
      : config.catalogs.ttlMs;

  const { value, source, coalesced } = await cache.fetch(
    key,
    () => fetchCatalog(definition, extra, options),
    ttlMs,
    { kind: 'catalog', catalog: `${definition.type}/${definition.id}` },
  );

  return {
    ...value,
    catalog: publicCatalog(definition),
    cached: source === 'hit' || source === 'stale',
    stale: source === 'stale',
    coalesced,
    ttlMs,
    durationMs: elapsedMs(startedAt),
  };
}

/**
 * Obtiene las metas según el origen declarado.
 * Nunca lanza: un fallo se traduce en `degraded: true`.
 */
async function fetchCatalog(definition, extra, options) {
  try {
    const metas = await metasFor(definition, extra, options);

    return {
      metas: metas.slice(0, config.catalogs.pageSize),
      source: definition.source.kind,
      degraded: false,
    };
  } catch (error) {
    log.warn('catalog fetch failed', {
      catalog: `${definition.type}/${definition.id}`,
      error: error.message,
    });
    return { metas: [], source: definition.source.kind, degraded: true };
  }
}

/** Despacha según el origen declarado en el registro. */
function metasFor(definition, extra, options) {
  switch (definition.source.kind) {
    case 'cinemeta':
      return cinemetaMetas(definition, extra, options);
    case 'remote':
      return remoteMetas(definition, extra, options);
    case 'live':
      return liveMetas(definition, extra, options);
    default:
      return localMetas(definition, extra);
  }
}

/**
 * Catálogo de un addon de terceros (p. ej. *Streaming Catalogs*, el addon que
 * publica las filas de Netflix, Disney+, Prime Video…).
 *
 * Sólo se le mandan los extras que su manifest declara: si no anuncia
 * `search`, mandarle `search=` devuelve 404 en algunos addons. Las metas se
 * normalizan igual que las de Cinemeta porque el protocolo es el mismo, y se
 * etiquetan con `sourceName` para que la ficha sepa de dónde salieron.
 */
async function remoteMetas(definition, extra, options) {
  const { source, capabilities } = definition;
  const path = buildCatalogPath({
    type: source.type,
    id: source.id,
    extra: {
      genre: capabilities?.genre ? extra.genre : undefined,
      skip: capabilities?.skip ? extra.skip : undefined,
      search: capabilities?.search ? extra.search : undefined,
    },
  });

  const payload = await cinemetaGet(path, {
    ...options,
    base: source.baseUrl,
    timeoutMs: config.catalogs.remoteTimeoutMs,
  });

  if (payload === null) throw new Error(`remote addon ${source.addonName || source.baseUrl} did not answer`);

  const metas = normalizeMetas(payload.metas, { type: source.type });

  return metas
    .slice(0, source.limit || config.catalogs.pageSize)
    .map((meta) => ({
      ...meta,
      type: definition.type,
      ...(definition.posterShape === 'landscape' ? { posterShape: 'landscape' } : {}),
      sourceName: source.addonName || definition.name,
    }));
}

/** Cinemeta: traduce tipo publicado → tipo real y aplica extras. */
async function cinemetaMetas(definition, extra, options) {
  const genre = extra.genre || definition.source.genre;
  const path = buildCatalogPath({
    type: definition.source.type,
    id: definition.source.id,
    extra: {
      genre,
      skip: extra.skip,
      search: extra.search,
    },
  });

  const payload = await cinemetaGet(path, options);

  /* `cinemetaGet` devuelve null ante timeout/HTTP != 2xx/JSON roto. Eso no es
   * "catálogo vacío": es upstream caído, y el cliente debe poder distinguirlo
   * (`degraded: true`) para reintentar en lugar de cachear el vacío 30 min. */
  if (payload === null) throw new Error('cinemeta did not answer');

  const rawMetas = Array.isArray(payload.metas) ? payload.metas : [];

  const metas = normalizeMetas(rawMetas, { type: definition.source.type });

  /* El tipo publicado manda: un anime sigue siendo un catálogo de anime en la
   * URL, aunque Cinemeta lo haya servido como serie. Se conserva `type` real en
   * `sourceType` para que el cliente sepa a qué endpoint de stream preguntar. */
  return metas.map((meta) => ({
    ...meta,
    type: definition.type,
    ...(definition.type !== definition.source.type
      ? { sourceType: definition.source.type, posterShape: 'poster' }
      : {}),
  }));
}

/** Fuente local: filtros por categoría, género y búsqueda por nombre. */
async function localMetas(definition, extra) {
  const items = await loadLiveCatalog();
  let metas = items;

  if (definition.source.category) {
    metas = metas.filter((meta) => String(meta.category || '').toLowerCase() === definition.source.category);
  }

  if (extra.genre) {
    /* Un canal coincide por género ("Deportes") o por categoría ("deportes"):
     * el JSON curado usa categorías internas y el manifest anuncia géneros
     * legibles, y ambos deben filtrar igual. */
    const wanted = normalizeText(extra.genre);
    metas = metas.filter(
      (meta) =>
        (meta.genres || []).some((genre) => normalizeText(genre) === wanted) ||
        normalizeText(meta.category || '') === wanted,
    );
  }

  if (extra.search) {
    const query = normalizeText(extra.search);
    metas = metas.filter(
      (meta) =>
        normalizeText(meta.name).includes(query) ||
        normalizeText(meta.description || '').includes(query) ||
        (meta.genres || []).some((genre) => normalizeText(genre).includes(query)),
    );
  }

  /* La fila local es corta y curada: el `skip` de Stremio se respeta, pero no
   * tiene sentido paginar por encima de la lista. */
  const skip = Number(extra.skip);
  if (Number.isInteger(skip) && skip > 0) metas = metas.slice(skip);

  return metas;
}

/** Definición → lo que se puede contar al cliente (sin `source` interno). */
function publicCatalog(definition) {
  return {
    type: definition.type,
    id: definition.id,
    name: definition.name,
    posterShape: definition.posterShape,
    genres: definition.genres,
  };
}

/* -------------------------------------------------------------------------- */
/* Fichas (/meta)                                                             */
/* -------------------------------------------------------------------------- */

/**
 * Ficha completa de un título.
 *
 * Es la pieza que convierte un id de Cinemeta (`tt0133093`) en algo que los
 * scrapers entienden: título y año. También devuelve la lista de episodios, que
 * es lo que permite pedir `/stream/series/tt0944947:1:2`.
 *
 * @param {object} request
 * @param {string} request.type - movie|series|anime|tv
 * @param {string} request.id   - id del título (puede incluir :season:episode)
 * @param {object} [options]
 * @returns {Promise<null|{
 *   meta: object, cached: boolean, stale: boolean, ttlMs: number, durationMs: number
 * }>}
 */
export async function getMeta({ type, id }, options = {}) {
  const startedAt = process.hrtime.bigint();
  const normalizedType = String(type || '').toLowerCase();
  const normalizedId = String(id || '').replace(/\.json$/i, '').trim();
  if (!normalizedId) return null;

  /* `tt0944947:1:2` → la ficha es la de la serie, y el episodio se busca dentro
   * de `videos[]`. Cinemeta no tiene fichas por episodio. */
  const [baseId, ...rest] = normalizedId.split(':');
  const season = rest.length >= 2 ? Number(rest[rest.length - 2]) : null;
  const episode = rest.length >= 1 && Number.isInteger(Number(rest[rest.length - 1]))
    ? Number(rest[rest.length - 1])
    : null;

  if (normalizedType === 'tv') {
    const items = await loadLiveCatalog();
    const item = items.find((meta) => meta.id === normalizedId) || null;
    return item
      ? { meta: item, cached: true, stale: false, ttlMs: config.catalogs.metaTtlMs, durationMs: elapsedMs(startedAt) }
      : null;
  }

  /* Las filas de terceros también acaban aquí: sus metas llevan ids de IMDb,
   * así que la ficha (sinopsis, año, episodios) la sirve Cinemeta aunque el
   * addon remoto sólo publique el recurso `catalog`. */

  /* Anime se resuelve como serie: los ids de Cinemeta son IMDb, sin tipo propio. */
  const upstreamType = normalizedType === 'anime' ? 'series' : normalizedType;
  const key = metaCacheKey(upstreamType, baseId);

  const { value, source } = await cache.fetch(
    key,
    async () => {
      const payload = await cinemetaGet(buildMetaPath({ type: upstreamType, id: baseId }), options);
      const meta = normalizeMeta(payload?.meta, { type: upstreamType });
      if (!meta) return null;
      /* El tipo publicado vuelve a mandar, igual que en los catálogos. */
      return { ...meta, type: normalizedType };
    },
    config.catalogs.metaTtlMs,
    { kind: 'meta' },
  );

  if (!value) return null;

  const meta = { ...value };
  if (season !== null && episode !== null) {
    const video = (meta.videos || []).find(
      (candidate) => candidate.season === season && candidate.episode === episode,
    );
    if (video) meta.selectedVideo = video;
  }

  return {
    meta,
    cached: source === 'hit' || source === 'stale',
    stale: source === 'stale',
    ttlMs: config.catalogs.metaTtlMs,
    durationMs: elapsedMs(startedAt),
  };
}

/**
 * Atajo que usa `streamService`: título y año de un id, para poder buscar en
 * los sitios cuando el cliente sólo manda un IMDb id.
 *
 * @param {object} request
 * @param {string} request.type
 * @param {string} request.id
 * @returns {Promise<{ title: string, year: number|null, imdbId: string|null }|null>}
 */
export async function resolveTitle({ type, id }) {
  const result = await getMeta({ type, id });
  if (!result?.meta) return null;

  const meta = result.meta;
  return {
    title: meta.name || '',
    year: Number.isFinite(meta.year) ? meta.year : null,
    imdbId: meta.imdb_id || (String(meta.id).startsWith('tt') ? meta.id : null),
  };
}

/* -------------------------------------------------------------------------- */
/* Utilidades                                                                 */
/* -------------------------------------------------------------------------- */

/** Invalida catálogos y fichas (los streams se invalidan por su cuenta). */
export function invalidateCatalogs() {
  let removed = 0;
  for (const key of cache.keys(Infinity)) {
    if (key.startsWith(CATALOG_PREFIX) || key.startsWith(META_PREFIX)) {
      cache.delete(key);
      removed += 1;
    }
  }
  liveState = { items: [], loadedAtMs: 0 };
  return removed;
}

/** Minúsculas + sin acentos: "Deportes" y "deportés" son lo mismo al buscar. */
export function normalizeText(value) {
  return String(value ?? '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .trim();
}

function elapsedMs(startedAt) {
  return Number((Number(process.hrtime.bigint() - startedAt) / 1e6).toFixed(2));
}

export default {
  getCatalog,
  getMeta,
  resolveTitle,
  loadLiveCatalog,
  loadLiveShelf,
  liveItemToMeta,
  catalogCacheKey,
  metaCacheKey,
  invalidateCatalogs,
  normalizeText,
};
