/**
 * src/catalogs/remoteAddons.js
 * ---------------------------------------------------------------------------
 * Catálogos de **terceros**: republicamos las filas de otros addons de Stremio
 * sin copiar su código, hablando su mismo protocolo.
 *
 * El caso que motivó el módulo es *Streaming Catalogs*
 * (`pw.ers.netflix-catalog`, el addon que publica las filas de Netflix, Disney+,
 * Prime Video, HBO Max…). Su manifest trae 42 catálogos (`nfx`, `dnp`, `amp`,
 * `hbm`, `hlu`, `pmp`, `pcp`, `atp`, `cru`, …) y sólo el recurso `catalog`: no
 * sirve `/meta`, pero sus metas llevan ids de IMDb (`tt…`), así que la ficha y
 * los episodios los sigue resolviendo Cinemeta y las fuentes las sigue
 * resolviendo nuestro propio `/stream`.
 *
 * Cómo se publica
 * ---------------
 * El manifest de cada addon se lee **una vez por arranque** (con caché en
 * disco de 6 h) y de ahí salen las filas. Cada fila conserva:
 *   - `source.type` / `source.id`: lo que hay que pedir al addon remoto;
 *   - `source.baseUrl`: su origen, derivado del manifest URL;
 *   - `extraNames`: qué extras declara (así no anunciamos un buscador que el
 *     addon remoto no implementa).
 *
 * El id publicado se prefija (`streaming_nfx`) porque los ids remotos son
 * cortos y chocarían con los nuestros (`top`, `live`…).
 *
 * Reglas de robustez:
 *   - un addon caído no rompe el arranque: se usa la caché en disco; si no hay,
 *     sus filas simplemente no se publican;
 *   - nada de esto lanza: `resolveRemoteCatalogs()` devuelve `[]` como mucho.
 */

import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

import config from '../config.js';
import { cinemetaGet } from '../lib/cinemeta.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('catalogs:remote');

/** Tipos de Stremio que este addon puede publicar (config.addon.types). */
const TYPE_MAP = Object.freeze({
  movie: 'movie',
  series: 'series',
  anime: 'anime',
  tv: 'tv',
  channel: 'tv',
  events: 'tv',
  sport: 'tv',
});

const ADDON_ID_RE = /^[a-z0-9_-]{1,24}$/;

/* -------------------------------------------------------------------------- */
/* Configuración                                                              */
/* -------------------------------------------------------------------------- */

/**
 * Valida el bloque `addons` de `config/catalogs.json`.
 *
 * ```json
 * {
 *   "id": "streaming",
 *   "name": "Streaming Catalogs",
 *   "manifestUrl": "https://…/manifest.json",
 *   "only": ["nfx", "dnp", "amp"],
 *   "types": ["movie", "series"],
 *   "limit": 40,
 *   "order": 100
 * }
 * ```
 *
 * @returns {object|null} definición normalizada, o null si es inválida
 */
export function normalizeAddon(raw) {
  if (!raw || typeof raw !== 'object') return null;

  const id = String(raw.id || '').toLowerCase().trim();
  const manifestUrl = String(raw.manifestUrl || '').trim();

  if (!ADDON_ID_RE.test(id)) {
    log.warn('addon skipped: invalid id', { id });
    return null;
  }
  if (!/^https?:\/\//i.test(manifestUrl)) {
    log.warn('addon skipped: manifestUrl must be http(s)', { id, manifestUrl });
    return null;
  }

  const only = Array.isArray(raw.only) ? raw.only.map((value) => String(value).trim()).filter(Boolean) : [];
  const types = Array.isArray(raw.types)
    ? raw.types.map((value) => String(value).toLowerCase().trim()).filter(Boolean)
    : [];

  const limit = Number(raw.limit);
  const order = Number(raw.order);

  return {
    id,
    name: String(raw.name || id).trim(),
    manifestUrl,
    /* `only: []` (u omitido) = publica todos los catálogos del addon. */
    only,
    types: types.filter((type) => TYPE_MAP[type] && config.addon.types.includes(TYPE_MAP[type])),
    limit: Number.isInteger(limit) && limit > 0 ? Math.min(limit, config.catalogs.pageSize) : config.catalogs.pageSize,
    order: Number.isFinite(order) ? order : 100,
    posterShape: raw.posterShape === 'landscape' ? 'landscape' : 'poster',
    enabled: raw.enabled !== false,
  };
}

/** Origen (sin `/manifest.json`) a partir del manifest URL. */
export function addonBaseUrl(manifestUrl) {
  return String(manifestUrl)
    .trim()
    .replace(/\/+$/, '')
    .replace(/\/manifest\.json$/i, '')
    .replace(/\/+$/, '');
}

/** Id publicado: prefijado y saneado para cumplir `[a-zA-Z0-9_-]`. */
export function publishedCatalogId(addonId, remoteId) {
  const safeRemote = String(remoteId).toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'catalog';
  return `${addonId}_${safeRemote}`.slice(0, 48);
}

/* -------------------------------------------------------------------------- */
/* Manifest                                                                   */
/* -------------------------------------------------------------------------- */

/**
 * Descarga y valida el manifest de un addon remoto.
 *
 * @returns {Promise<object|null>} manifest limpio, o null si no se pudo leer
 */
export async function fetchManifest(addon, options = {}) {
  const base = addonBaseUrl(addon.manifestUrl);
  const payload = await cinemetaGet('/manifest.json', {
    base,
    timeoutMs: config.catalogs.remoteTimeoutMs,
    fetchImpl: options.fetchImpl,
  });

  if (!payload || typeof payload !== 'object' || !Array.isArray(payload.catalogs)) {
    return null;
  }

  return {
    id: String(payload.id || addon.id),
    name: String(payload.name || addon.name),
    version: String(payload.version || ''),
    baseUrl: base,
    catalogs: payload.catalogs.filter((catalog) => catalog && catalog.type && catalog.id),
  };
}

/**
 * Expande un manifest en definiciones de fila.
 *
 * @param {object} addon - configuración normalizada
 * @param {object} manifest - resultado de fetchManifest
 * @returns {Array<object>} definiciones parciales (sin `capabilities`)
 */
export function expandManifest(addon, manifest) {
  const selected = new Set(addon.only);
  const rows = [];

  manifest.catalogs.forEach((catalog, index) => {
    const remoteType = String(catalog.type).toLowerCase();
    const publishedType = TYPE_MAP[remoteType];

    /* Tipos que no publicamos (p. ej. `channel` de un addon de TV) se saltan. */
    if (!publishedType || !config.addon.types.includes(publishedType)) return;
    if (addon.types.length > 0 && !addon.types.includes(remoteType)) return;
    if (selected.size > 0 && !selected.has(String(catalog.id))) return;

    const extraNames = Array.isArray(catalog.extra)
      ? catalog.extra.map((extra) => String(extra?.name || '').toLowerCase()).filter(Boolean)
      : [];
    const genres = Array.isArray(catalog.genres)
      ? catalog.genres.map((genre) => String(genre).trim()).filter(Boolean)
      : (catalog.extra || []).find((extra) => extra?.name === 'genre')?.options || [];

    rows.push({
      type: publishedType,
      id: publishedCatalogId(addon.id, catalog.id),
      name: String(catalog.name || catalog.id),
      posterShape: addon.posterShape,
      order: addon.order + index / 100,
      genres: genres.map(String),
      source: {
        kind: 'remote',
        addonId: addon.id,
        addonName: manifest.name || addon.name,
        baseUrl: manifest.baseUrl,
        type: remoteType,
        id: String(catalog.id),
        limit: addon.limit,
      },
      extraNames,
      /* Orden de publicación: el que el usuario escribió en `only` (Netflix
       * primero si así lo puso), y si no, el del manifest remoto. */
      __rank: selected.size > 0 ? addon.only.indexOf(String(catalog.id)) : index,
    });
  });

  return rows
    .sort((a, b) => a.__rank - b.__rank)
    .map((row, position) => {
      const { __rank, ...rest } = row;
      return { ...rest, order: addon.order + position / 100 };
    });
}

/* -------------------------------------------------------------------------- */
/* Caché en disco                                                             */
/* -------------------------------------------------------------------------- */

async function readCache() {
  try {
    const raw = JSON.parse(await readFile(config.catalogs.remoteCachePath, 'utf8'));
    return raw && typeof raw === 'object' ? raw : {};
  } catch {
    return {};
  }
}

async function writeCache(cache) {
  try {
    await mkdir(path.dirname(config.catalogs.remoteCachePath), { recursive: true });
    await writeFile(config.catalogs.remoteCachePath, `${JSON.stringify(cache, null, 2)}\n`, 'utf8');
  } catch (error) {
    /* Un disco de solo lectura no debe impedir publicar catálogos. */
    log.warn('could not persist the remote manifest cache', { error: error.message });
  }
}

/* -------------------------------------------------------------------------- */
/* Resolución                                                                 */
/* -------------------------------------------------------------------------- */

/**
 * Manifest de un addon con caché en disco.
 *
 * - fresco (< `remoteTtlMs`) → se usa sin red;
 * - viejo pero el addon no responde → se sigue usando (las filas importan más
 *   que el `version` del manifest);
 * - si nunca se pudo leer → null.
 */
async function manifestFor(addon, cache, options = {}) {
  const cached = cache[addon.id];
  const isFresh = cached?.fetchedAt && Date.now() - cached.fetchedAt < config.catalogs.remoteTtlMs;

  if (cached?.manifest && isFresh) return { manifest: cached.manifest, fromCache: true };

  const manifest = await fetchManifest(addon, options);

  if (manifest) {
    cache[addon.id] = { fetchedAt: Date.now(), manifest };
    return { manifest, fromCache: false };
  }

  if (cached?.manifest) {
    log.warn('remote addon unreachable, using the cached manifest', {
      addon: addon.id,
      ageHours: Number(((Date.now() - (cached.fetchedAt || 0)) / 3_600_000).toFixed(1)),
    });
    return { manifest: cached.manifest, fromCache: true };
  }

  return { manifest: null, fromCache: false };
}

/**
 * Resuelve todas las filas remotas configuradas.
 *
 * @param {Array<object>} addons - bloque `addons` ya normalizado
 * @param {object} [options]
 * @param {typeof fetch} [options.fetchImpl] - inyectable en tests
 * @returns {Promise<Array<object>>} definiciones listas para el registro
 */
export async function resolveRemoteCatalogs(addons = [], options = {}) {
  const enabled = addons.filter((addon) => addon.enabled);
  if (enabled.length === 0) return [];

  const cache = options.cache || (await readCache());
  const rows = [];

  /* En serie y no en paralelo: son 1-2 addons, cada uno con su timeout, y así
   * el log queda legible cuando uno falla. */
  for (const addon of enabled) {
    const { manifest, fromCache } = await manifestFor(addon, cache, options);

    if (!manifest) {
      log.warn('remote addon skipped: no manifest', { addon: addon.id, url: addon.manifestUrl });
      continue;
    }

    const expanded = expandManifest(addon, manifest);
    if (expanded.length === 0) {
      log.warn('remote addon published no usable catalogs', {
        addon: addon.id,
        available: manifest.catalogs.length,
        only: addon.only,
      });
      continue;
    }

    log.info('remote addon resolved', {
      addon: addon.id,
      name: manifest.name,
      rows: expanded.length,
      fromCache,
    });
    rows.push(...expanded);
  }

  if (!options.cache) await writeCache(cache);
  return rows;
}

export default {
  normalizeAddon,
  addonBaseUrl,
  publishedCatalogId,
  fetchManifest,
  expandManifest,
  resolveRemoteCatalogs,
};
