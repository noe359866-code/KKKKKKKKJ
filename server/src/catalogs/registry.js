/**
 * src/catalogs/registry.js
 * ---------------------------------------------------------------------------
 * Registro de catálogos del addon.
 *
 * Un catálogo se declara en `config/catalogs.json` (mismo espíritu que
 * `config/providers.json`: añadir una fila no debería requerir tocar código) y
 * se publica en `/manifest.json` para que **cualquier** cliente Stremio lo vea,
 * no sólo nuestra app Android.
 *
 * Forma de una definición:
 *
 *   {
 *     "type": "movie",              // tipo Stremio publicado
 *     "id": "top",                  // id del catálogo en la URL
 *     "name": "Películas populares",
 *     "posterShape": "poster",      // poster (2:3) | landscape (16:9)
 *     "order": 2,                   // orden en el manifest y en el Home
 *     "genres": ["Action"],         // opcional: alimenta el extra genre=
 *     "source": {
 *       "kind": "cinemeta",         // cinemeta | local
 *       "type": "movie",            // tipo real en Cinemeta (anime -> series)
 *       "id": "top",
 *       "genre": "Animation"        // opcional: filtro fijo del origen
 *     }
 *   }
 *
 * Reglas de validación (un catálogo inválido se descarta con warning, nunca
 * rompe el addon):
 *   - `type` debe ser uno de los tipos que declara el addon (config.addon.types);
 *   - `id` sólo admite letras, números, guion y guion bajo;
 *   - `source.kind` debe ser `cinemeta` o `local`;
 *   - `source.type`, si existe, debe estar en CINEMETA_TYPES.
 */

import { readFile } from 'node:fs/promises';

import config from '../config.js';
import { CINEMETA_TYPES } from '../lib/cinemeta.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('catalogs');

const KINDS = new Set(['cinemeta', 'local']);
const SHAPES = new Set(['poster', 'landscape']);
const ID_RE = /^[a-zA-Z0-9_-]{1,48}$/;

/**
 * Copia de seguridad: si `config/catalogs.json` falta o está corrupto, el addon
 * sigue publicando la cartelera mínima en lugar de quedarse sin catálogos
 * (un manifest sin `catalogs` deja el Home del cliente vacío).
 */
export const DEFAULT_CATALOGS = Object.freeze([
  {
    type: 'tv',
    id: 'live',
    name: 'TV en Vivo y Deportes',
    posterShape: 'landscape',
    order: 1,
    source: { kind: 'local' },
  },
  {
    type: 'movie',
    id: 'top',
    name: 'Películas populares',
    posterShape: 'poster',
    order: 2,
    source: { kind: 'cinemeta', type: 'movie', id: 'top' },
  },
  {
    type: 'series',
    id: 'top',
    name: 'Series populares',
    posterShape: 'poster',
    order: 3,
    source: { kind: 'cinemeta', type: 'series', id: 'top' },
  },
]);

/** @type {Array<object>} */
let catalogs = DEFAULT_CATALOGS.map(normalizeCatalog).filter(Boolean);
let loadedAt = null;

/**
 * Valida y normaliza una definición.
 * @returns {object|null} definición lista para usar, o null si es inválida
 */
export function normalizeCatalog(raw, index = 0) {
  if (!raw || typeof raw !== 'object') return null;

  const type = String(raw.type || '').toLowerCase().trim();
  const id = String(raw.id || '').trim();
  const sourceKind = String(raw.source?.kind || '').toLowerCase().trim();

  if (!config.addon.types.includes(type)) {
    log.warn('catalog skipped: unsupported type', { type, id, supported: config.addon.types });
    return null;
  }
  if (!ID_RE.test(id)) {
    log.warn('catalog skipped: invalid id', { type, id });
    return null;
  }
  if (!KINDS.has(sourceKind)) {
    log.warn('catalog skipped: unknown source kind', { type, id, kind: sourceKind });
    return null;
  }

  const source = { kind: sourceKind };

  if (sourceKind === 'cinemeta') {
    const sourceType = String(raw.source.type || type).toLowerCase().trim();
    if (!CINEMETA_TYPES.includes(sourceType)) {
      log.warn('catalog skipped: cinemeta does not serve this type', { type, id, sourceType });
      return null;
    }
    source.type = sourceType;
    source.id = String(raw.source.id || id).trim() || id;
    if (raw.source.genre) source.genre = String(raw.source.genre).trim();
  } else {
    /* Fuente local: el filtro opcional por categoría permite publicar
     * "Deportes" y "Canales" como dos filas del mismo JSON. */
    if (raw.source?.category) source.category = String(raw.source.category).trim().toLowerCase();
  }

  return {
    type,
    id,
    name: String(raw.name || id).trim(),
    posterShape: SHAPES.has(raw.posterShape) ? raw.posterShape : 'poster',
    order: Number.isFinite(raw.order) ? raw.order : 100 + index,
    genres: Array.isArray(raw.genres) ? raw.genres.map((genre) => String(genre).trim()).filter(Boolean) : [],
    source,
  };
}

/** Carga (o recarga) el registro desde disco. */
export async function loadCatalogs() {
  try {
    const raw = JSON.parse(await readFile(config.catalogs.configPath, 'utf8'));
    const list = Array.isArray(raw) ? raw : raw.catalogs || [];
    const parsed = list.map(normalizeCatalog).filter(Boolean);

    if (parsed.length === 0) {
      log.warn('catalog config produced no valid catalogs, using defaults', {
        path: config.catalogs.configPath,
      });
      catalogs = DEFAULT_CATALOGS.map(normalizeCatalog).filter(Boolean);
    } else {
      catalogs = parsed.sort((a, b) => a.order - b.order);
    }
    loadedAt = new Date().toISOString();
    log.info('catalogs loaded', {
      count: catalogs.length,
      ids: catalogs.map((catalog) => `${catalog.type}/${catalog.id}`),
      path: config.catalogs.configPath,
    });
  } catch (error) {
    log.warn('could not load catalog config, using defaults', {
      path: config.catalogs.configPath,
      error: error.message,
    });
    catalogs = DEFAULT_CATALOGS.map(normalizeCatalog).filter(Boolean);
    loadedAt = new Date().toISOString();
  }
  return catalogs;
}

/**
 * Garantiza que el registro está leído de disco, una sola vez por proceso.
 *
 * Se usa como `await ensureCatalogs()` al arrancar el servidor y en las rutas
 * que dependen del registro (manifest y catálogos). Si el archivo se edita en
 * caliente, `loadCatalogs()` lo vuelve a leer sin reiniciar.
 *
 * @returns {Promise<Array<object>>}
 */
let loadPromise = null;
export function ensureCatalogs() {
  if (!loadPromise) loadPromise = loadCatalogs();
  return loadPromise;
}

/** Todos los catálogos publicados, ordenados. */
export function getCatalogs() {
  return [...catalogs];
}

/** Busca la definición exacta (`type` + `id`), que es la clave de la URL. */
export function findCatalog(type, id) {
  const wantedType = String(type || '').toLowerCase();
  const wantedId = String(id || '');
  return catalogs.find((catalog) => catalog.type === wantedType && catalog.id === wantedId) || null;
}

/** Catálogos de un tipo, útil para `/catalog/:type/:id` con id inválido. */
export function catalogsForType(type) {
  const wanted = String(type || '').toLowerCase();
  return catalogs.filter((catalog) => catalog.type === wanted);
}

/**
 * Bloque `catalogs` del manifest.
 *
 * `extra` declara qué *extras* acepta el catálogo. Stremio sólo enseña la barra
 * de búsqueda si el catálogo declara `search`, y pagina si declara `skip`.
 */
export function manifestCatalogs() {
  return catalogs.map((catalog) => {
    const extra = [
      { name: 'search', isRequired: false },
      { name: 'skip', isRequired: false },
    ];
    if ((catalog.genres || []).length > 0) {
      extra.push({ name: 'genre', options: catalog.genres, isRequired: false });
    }

    return {
      type: catalog.type,
      id: catalog.id,
      name: catalog.name,
      posterShape: catalog.posterShape,
      extra,
    };
  });
}

/** Metadatos de diagnóstico (usado por /health). */
export function getCatalogsMeta() {
  return { loadedAt, count: catalogs.length };
}

/** Sólo para tests: fija el registro sin tocar disco. */
export function setCatalogsForTests(list = []) {
  catalogs = list.map(normalizeCatalog).filter(Boolean).sort((a, b) => a.order - b.order);
  return catalogs;
}

export default {
  DEFAULT_CATALOGS,
  normalizeCatalog,
  loadCatalogs,
  getCatalogs,
  catalogsForType,
  findCatalog,
  manifestCatalogs,
  getCatalogsMeta,
  setCatalogsForTests,
};
