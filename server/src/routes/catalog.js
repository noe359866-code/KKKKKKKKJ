/**
 * routes/catalog.js
 * ---------------------------------------------------------------------------
 * Recursos `catalog` y `meta` del protocolo Stremio.
 *
 *   GET /catalog/:type/:id.json
 *   GET /catalog/:type/:id/search=<q>.json      (barra de búsqueda del cliente)
 *   GET /catalog/:type/:id/skip=100.json        (paginación)
 *   GET /catalog/:type/:id/genre=Action.json    (filtro por género)
 *   GET /meta/:type/:id.json                    (ficha completa)
 *
 * Respuesta de catálogo:
 *
 *   {
 *     "metas": [ { id, type, name, poster, background, description,
 *                  releaseInfo, year, rating, genres, posterShape } ],
 *     "cacheMaxAge": 1800,       // segundos
 *     "staleRevalidate": 1800,
 *     "staleError": 60,
 *     "catalog": { type, id, name, posterShape }   // extra propio, no estándar
 *   }
 *
 * Detalle importante sobre los extras: Stremio los manda como **segmentos de
 * path**, no como query string, y pueden aparecer en cualquier orden y en
 * número variable (`/genre=Action/skip=100.json`). Por eso la ruta se resuelve
 * con un middleware que parsea el path a mano en lugar de con patrones de
 * Express: así se aceptan extras nuevos del protocolo sin tocar el router.
 */

import { Router } from 'express';

import config from '../config.js';
import { getCatalog, getMeta, catalogCacheKey } from '../services/catalogService.js';
import { catalogsForType, ensureCatalogs, getCatalogs } from '../catalogs/registry.js';
import { isSupportedType } from '../lib/stremio.js';
import { asyncHandler } from '../middleware/errors.js';
import { markCacheOutcome } from '../middleware/requestContext.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('route:catalog');

export const catalogRouter = Router();

/* Claves de extra que entiende este addon. El resto se ignora en silencio:
 * Stremio puede mandar `streamingService=` o similares según el cliente. */
const KNOWN_EXTRA_KEYS = new Set(['search', 'skip', 'genre']);

/**
 * Parte un path de catálogo en `{ type, id, extras }`.
 *
 *   /catalog/movie/top.json                    -> { type:'movie', id:'top', extras:{} }
 *   /catalog/movie/top/skip=100.json           -> { ..., extras:{ skip:'100' } }
 *   /catalog/movie/top/search=mat:rix.json     -> idem (los `:` son legales)
 *
 * @param {string} pathname - req.path, ya sin query string
 * @returns {{ type: string, id: string, extras: Record<string,string> }|null}
 */
export function parseCatalogPath(pathname) {
  const clean = String(pathname || '').split('?')[0].replace(/\/+$/, '');
  const match = clean.match(/^\/catalog\/([^/]+)\/([^/]+)$/);
  if (match) {
    return { type: decode(match[1]), id: decode(match[2].replace(/\.json$/i, '')), extras: {} };
  }

  const segments = clean.split('/').filter(Boolean);
  if (segments.length < 4 || segments[0] !== 'catalog') return null;

  const type = decode(segments[1]);
  const id = decode(segments[2]);
  if (!type || !id) return null;

  const extras = {};
  const tail = segments.slice(3);
  tail.forEach((segment, index) => {
    /* Sólo el último segmento puede acabar en `.json`. */
    const isLast = index === tail.length - 1;
    const raw = isLast ? segment.replace(/\.json$/i, '') : segment;
    const separator = raw.indexOf('=');
    if (separator <= 0) return;
    const key = raw.slice(0, separator).toLowerCase();
    if (!KNOWN_EXTRA_KEYS.has(key)) return;
    extras[key] = decode(raw.slice(separator + 1));
  });

  return { type, id, extras };
}

/* -------------------------------------------------------------------------- */
/* GET /catalog/:type/:id.json                                                */
/* -------------------------------------------------------------------------- */

async function handleCatalog(req, res) {
  const parsed = parseCatalogPath(req.path);
  if (!parsed) {
    return res.status(400).json({ err: 'malformed catalog path', code: 'BAD_REQUEST' });
  }

  const { type, id } = parsed;
  const extras = { ...parsed.extras, ...extrasFromQuery(req.query) };

  if (!isSupportedType(type)) {
    return res.status(404).json({
      err: `unsupported type "${type}"`,
      code: 'UNSUPPORTED_TYPE',
      supported: config.addon.types,
    });
  }

  const result = await getCatalog({ type, id, extra: extras });

  if (!result) {
    /* El catálogo no está declarado: se responde 404 con la lista de ids
     * válidos para ese tipo, que es mucho más útil que un 404 seco al depurar
     * un cliente Stremio. */
    return res.status(404).json({
      err: `catalog "${type}/${id}" is not published by this addon`,
      code: 'CATALOG_NOT_FOUND',
      available: catalogsForType(type).map((catalog) => catalog.id),
    });
  }

  markCacheOutcome(
    res,
    result.degraded ? 'miss:degraded' : result.stale ? 'stale' : result.cached ? 'hit' : result.coalesced ? 'coalesced' : 'miss',
  );

  const cacheMaxAge = Math.floor(result.ttlMs / 1000);
  res.setHeader('Cache-Control', `public, max-age=${cacheMaxAge}`);
  res.setHeader('X-Cache', result.degraded ? 'DEGRADED' : result.stale ? 'STALE' : result.cached ? 'HIT' : 'MISS');
  res.setHeader('X-Response-Time', `${result.durationMs.toFixed(1)}ms`);
  if (result.degraded) res.setHeader('X-Catalog-Degraded', '1');

  log.debug('catalog response', {
    type,
    id,
    metas: result.metas.length,
    source: result.source,
    degraded: result.degraded,
    cached: result.cached,
    stale: result.stale,
    extras,
    ms: result.durationMs,
  });

  return res.json({
    metas: result.metas,
    cacheMaxAge,
    staleRevalidate: cacheMaxAge,
    staleError: Math.floor(config.catalogs.missTtlMs / 1000),
    /* Campos propios (Stremio los ignora): permiten que nuestra app Android
     * pinte la fila correcta sin adivinar el tipo de contenido. */
    catalog: result.catalog,
    ...(result.degraded ? { degraded: true } : {}),
  });
}

/** Acepta también `?search=`/`?q=`/`?genre=`/`?skip=`: algunos clientes usan
 * query string en lugar de segmentos de path. */
function extrasFromQuery(query = {}) {
  const extras = {};
  const search = query.search ?? query.q;
  if (typeof search === 'string' && search.trim()) extras.search = search.trim().slice(0, 120);
  if (typeof query.genre === 'string' && query.genre.trim()) extras.genre = query.genre.trim();
  const skip = Number(query.skip);
  if (Number.isInteger(skip) && skip > 0) extras.skip = skip;
  return extras;
}

/* -------------------------------------------------------------------------- */
/* GET /meta/:type/:id.json                                                   */
/* -------------------------------------------------------------------------- */

async function handleMeta(req, res) {
  const type = String(req.params.type || '').toLowerCase();
  const id = String(req.params.id || '').replace(/\.json$/i, '').trim();

  if (!type || !id) {
    return res.status(400).json({ err: 'type and id are required', code: 'BAD_REQUEST' });
  }

  if (!isSupportedType(type)) {
    return res.status(404).json({
      err: `unsupported type "${type}"`,
      code: 'UNSUPPORTED_TYPE',
      supported: config.addon.types,
    });
  }

  const result = await getMeta({ type, id });

  if (!result) {
    markCacheOutcome(res, 'miss:empty');
    res.setHeader('Cache-Control', `public, max-age=${Math.floor(config.catalogs.missTtlMs / 1000)}`);
    return res.status(404).json({ err: `no metadata for ${type}/${id}`, code: 'META_NOT_FOUND' });
  }

  markCacheOutcome(res, result.stale ? 'stale' : result.cached ? 'hit' : 'miss');

  const cacheMaxAge = Math.floor(result.ttlMs / 1000);
  res.setHeader('Cache-Control', `public, max-age=${cacheMaxAge}`);
  res.setHeader('X-Cache', result.stale ? 'STALE' : result.cached ? 'HIT' : 'MISS');
  res.setHeader('X-Response-Time', `${result.durationMs.toFixed(1)}ms`);

  return res.json({
    meta: result.meta,
    cacheMaxAge,
    staleRevalidate: cacheMaxAge,
    staleError: Math.floor(config.catalogs.missTtlMs / 1000),
  });
}

/* -------------------------------------------------------------------------- */
/* Rutas                                                                      */
/* -------------------------------------------------------------------------- */

/* HEAD de catálogo: responde sin tocar Cinemeta. Va ANTES del middleware de
 * GET para que un probe de disponibilidad nunca dispare 100 llamadas upstream. */
catalogRouter.head(/^\/catalog\//, (req, res) => {
  res.setHeader('X-Cache', 'BYPASS');
  res.status(200).end();
});

/* Catálogo: middleware porque los extras son segmentos de path de longitud
 * variable (ver el comentario de cabecera). */
catalogRouter.use((req, res, next) => {
  if (req.method !== 'GET') return next();
  if (!/^\/catalog\//.test(req.path)) return next();
  return asyncHandler(handleCatalog)(req, res, next);
});

catalogRouter.get('/meta/:type/:id.json', asyncHandler(handleMeta));
catalogRouter.get('/meta/:type/:id', asyncHandler(handleMeta));

/**
 * GET /catalogs.json  (propio, no estándar)
 *
 * Manifiesto de catálogos en crudo. La app Android lo usa al arrancar para
 * construir las filas del Home dinámicamente, sin tener que descargar el
 * manifest completo (que está pensado para Stremio, no para nuestra UI).
 */
catalogRouter.get('/catalogs.json', asyncHandler(async (req, res) => {
  await ensureCatalogs();
  res.setHeader('Cache-Control', 'public, max-age=300');
  res.json({
    catalogs: getCatalogs().map((catalog) => ({
      type: catalog.type,
      id: catalog.id,
      name: catalog.name,
      posterShape: catalog.posterShape,
      order: catalog.order,
      genres: catalog.genres,
      /* La app puede precalcular la URL exacta del catálogo. */
      feed: `/catalog/${catalog.type}/${catalog.id}.json`,
    })),
  });
}));

export default catalogRouter;

/* `catalogCacheKey` se reexporta para los tests de claves (evita que un cambio
 * de formato pase desapercibido). */
export { catalogCacheKey };

/** @param {string} value */
function decode(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}
