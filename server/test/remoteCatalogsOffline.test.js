/**
 * test/remoteCatalogsOffline.test.js
 * ---------------------------------------------------------------------------
 * Arranque con el addon de terceros **caído**.
 *
 * Dos escenarios, en orden:
 *  1. sin caché en disco → el addon remoto se descarta y sólo se publican las
 *     filas propias (el servidor arranca igual, sin lanzar ni bloquear);
 *  2. con un manifest cacheado de un arranque anterior → las filas remotas
 *     siguen publicadas aunque la red no esté.
 *
 * El manifest apunta a `127.0.0.1:1` (conexión rechazada al instante), así que
 * el test no depende de la red externa ni tarda.
 */

import test, { before } from 'node:test';
import assert from 'node:assert/strict';
import os from 'node:os';
import path from 'node:path';
import { mkdtemp, writeFile } from 'node:fs/promises';

const tmp = await mkdtemp(path.join(os.tmpdir(), 'streamflix-remote-offline-'));
const configPath = path.join(tmp, 'catalogs.json');
const cachePath = path.join(tmp, 'remote-cache.json');

await writeFile(
  configPath,
  JSON.stringify({
    catalogs: [
      {
        type: 'tv',
        id: 'live',
        name: 'TV en Vivo',
        posterShape: 'landscape',
        order: 1,
        source: { kind: 'local' },
      },
      {
        type: 'movie',
        id: 'top',
        name: 'Películas populares',
        order: 2,
        source: { kind: 'cinemeta', type: 'movie', id: 'top' },
      },
    ],
    addons: [
      {
        id: 'streaming',
        name: 'Streaming Catalogs',
        manifestUrl: 'http://127.0.0.1:1/manifest.json',
        only: ['nfx'],
        types: ['movie', 'series'],
      },
    ],
  }),
  'utf8',
);

process.env.RATE_LIMIT_MAX = '0';
process.env.CATALOGS_CONFIG = configPath;
process.env.CATALOG_REMOTE_CACHE = cachePath;
process.env.CINEMETA_BASE = 'http://127.0.0.1:1';

const { loadCatalogs, getCatalogs, findCatalog } = await import('../src/catalogs/registry.js');

before(async () => {
  /* Sin caché: el addon no responde y no debe romper el arranque. */
  const startedAt = Date.now();
  await loadCatalogs();
  const elapsed = Date.now() - startedAt;

  assert.ok(elapsed < 8_000, `el arranque no debe esperar al addon caído (tardó ${elapsed} ms)`);
  assert.deepEqual(
    getCatalogs().map((catalog) => `${catalog.type}/${catalog.id}`),
    ['tv/live', 'movie/top'],
    'sin manifest no se publican filas remotas',
  );
  assert.equal(findCatalog('movie', 'streaming_nfx'), null);
});

test('un addon caído sin caché no deja el registro vacío', () => {
  assert.ok(getCatalogs().length >= 2);
});

test('con un manifest cacheado, las filas remotas sobreviven a la caída del addon', async () => {
  await writeFile(
    cachePath,
    JSON.stringify({
      streaming: {
        fetchedAt: Date.now(),
        manifest: {
          id: 'pw.ers.netflix-catalog',
          name: 'Streaming Catalogs',
          version: '1.1.1',
          baseUrl: 'http://127.0.0.1:1',
          catalogs: [
            { id: 'nfx', type: 'movie', name: 'Netflix' },
            { id: 'nfx', type: 'series', name: 'Netflix' },
          ],
        },
      },
    }),
    'utf8',
  );

  await loadCatalogs();

  assert.deepEqual(
    getCatalogs().map((catalog) => `${catalog.type}/${catalog.id}`),
    ['tv/live', 'movie/top', 'movie/streaming_nfx', 'series/streaming_nfx'],
  );

  const netflix = findCatalog('movie', 'streaming_nfx');
  assert.equal(netflix.name, 'Netflix');
  assert.equal(netflix.source.baseUrl, 'http://127.0.0.1:1', 'se conserva el origen del manifest cacheado');
});

test('el registro sigue sirviendo los catálogos propios', () => {
  const live = findCatalog('tv', 'live');
  assert.equal(live.source.kind, 'local');
  assert.equal(live.posterShape, 'landscape');
});
