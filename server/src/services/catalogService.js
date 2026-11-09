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
 *      los catálogos de VOD (Cinemeta y addons de terceros), no
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

import config from '../config.js';
import { cache } from '../cache/TtlCache.js';
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

/* Prefijos de caché para invalidar catálogos y metadatos por separado. */
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
  const ttlMs = extra.search ? config.catalogs.searchTtlMs : config.catalogs.ttlMs;

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
    default:
      /* No queda ninguna fuente local: los catálogos son Cinemeta o addons
       * de terceros. Un `source.kind` desconocido devuelve vacío en vez de
       * romper el Home. */
      return [];
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
   * URL, aunque Cinemeta lo haya servido como serie. Se conserva `sourceType`
   * para que los clientes puedan interpretar el tipo original de los metadatos. */
  return metas.map((meta) => ({
    ...meta,
    type: definition.type,
    ...(definition.type !== definition.source.type
      ? { sourceType: definition.source.type, posterShape: 'poster' }
      : {}),
  }));
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
 * Es la pieza que convierte un id de Cinemeta (`tt0133093`) en metadatos
 * completos. También devuelve la lista de episodios para clientes compatibles
 * con el protocolo Stremio; este backend no publica el recurso `stream`.
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

/* -------------------------------------------------------------------------- */
/* Utilidades                                                                 */
/* -------------------------------------------------------------------------- */

/** Invalida todas las entradas de catálogo y metadatos. */
export function invalidateCatalogs() {
  let removed = 0;
  for (const key of cache.keys(Infinity)) {
    if (key.startsWith(CATALOG_PREFIX) || key.startsWith(META_PREFIX)) {
      cache.delete(key);
      removed += 1;
    }
  }
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
  catalogCacheKey,
  metaCacheKey,
  invalidateCatalogs,
  normalizeText,
};
