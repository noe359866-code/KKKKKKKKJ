/**
 * test/remoteCatalogs.test.js
 * ---------------------------------------------------------------------------
 * Catálogos de **terceros**: el addon *Streaming Catalogs* (Netflix, Disney+,
 * Prime Video…) se republica como filas propias.
 *
 * El test levanta un mock que hace de ese addon (manifest + `/catalog/...`) y de
 * Cinemeta (`/meta/...`), y comprueba:
 *
 *   - el manifest se expande a filas con id prefijado y sólo los servicios
 *     pedidos (`only`), los tipos permitidos (`types`) y sin los catálogos que
 *     no encajan;
 *   - las metas se normalizan, se recortan a `limit` y se etiquetan con
 *     `sourceName`;
 *   - los extras **sólo** se reenvían si el addon remoto los declara;
 *   - una fila rota degrada sola (no tumba el Home) y un addon caído al
 *     arrancar no impide publicar el resto;
 *   - el manifest se cachea en disco 6 h, así que un segundo arranque no vuelve
 *     a pedirlo.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const tmp = await mkdtemp(path.join(os.tmpdir(), 'streamflix-remote-'));

/* -------------------------------------------------------------------------- */
/* Mock: addon de terceros + Cinemeta                                         */
/* -------------------------------------------------------------------------- */

const upstreamRequests = [];

const NETFLIX_METAS = {
  metas: [
    {
      id: 'tt0111161',
      type: 'movie',
      name: 'The Shawshank Redemption',
      poster: 'https://images.metahub.space/poster/medium/tt0111161/img',
      background: 'https://images.metahub.space/background/medium/tt0111161/img',
      description: 'Dos hombres hacen migas en la cárcel.',
      releaseInfo: '1994',
      imdbRating: '9.3',
      genres: ['Drama'],
    },
    {
      id: 'tt0068646',
      type: 'movie',
      name: 'The Godfather',
      poster: 'https://images.metahub.space/poster/medium/tt0068646/img',
      releaseInfo: '1972',
      imdbRating: '9.2',
      genres: ['Crime'],
    },
    /* Duplicado y entrada sin id: deben desaparecer sin romper la fila. */
    { id: 'tt0111161', type: 'movie', name: 'Duplicado' },
    { type: 'movie', name: 'Sin id' },
    /* Tercera meta distinta: la recorta `limit: 2`. */
    { id: 'tt0108052', type: 'movie', name: 'Schindler\u2019s List', releaseInfo: '1993' },
  ],
};

const MANIFEST = {
  id: 'pw.ers.netflix-catalog',
  version: '1.1.1',
  name: 'Streaming Catalogs',
  description: 'Your favourite streaming services!',
  resources: ['catalog'],
  types: ['movie', 'series'],
  idPrefixes: ['tt'],
  catalogs: [
    { id: 'nfx', type: 'movie', name: 'Netflix' },
    { id: 'nfx', type: 'series', name: 'Netflix' },
    { id: 'srch', type: 'movie', name: 'Buscable', extra: [{ name: 'search' }, { name: 'skip' }] },
    { id: 'ch', type: 'channel', name: 'Canal (tipo no publicado)' },
    { id: 'other', type: 'movie', name: 'Servicio no pedido' },
    { id: 'broken', type: 'movie', name: 'Servicio roto' },
  ],
};

const mock = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://mock');
  upstreamRequests.push(url.pathname);

  const send = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  if (url.pathname === '/manifest.json') return send(200, MANIFEST);

  if (url.pathname === '/catalog/movie/nfx.json') return send(200, NETFLIX_METAS);
  if (url.pathname === '/catalog/series/nfx.json') {
    return send(200, {
      metas: [
        {
          id: 'tt0944947',
          type: 'series',
          name: 'Game of Thrones',
          releaseInfo: '2011\u20132019',
          imdbRating: '9.2',
          genres: ['Drama'],
        },
      ],
    });
  }
  /* `srch` sí declara extras: cualquier variante con search/skip cae aquí. */
  if (url.pathname.startsWith('/catalog/movie/srch')) {
    return send(200, { metas: [{ id: 'tt0133093', type: 'movie', name: 'The Matrix', releaseInfo: '1999' }] });
  }
  if (url.pathname.startsWith('/catalog/movie/broken')) return send(500, { err: 'boom' });

  /* Cinemeta (ficha de los ids IMDb de las filas remotas). */
  if (url.pathname === '/meta/movie/tt0133093.json') {
    return send(200, {
      meta: {
        id: 'tt0133093',
        type: 'movie',
        name: 'The Matrix',
        releaseInfo: '1999',
        videos: [],
      },
    });
  }

  return send(404, { err: 'not found' });
});

await new Promise((resolve) => mock.listen(0, '127.0.0.1', resolve));
const mockBase = `http://127.0.0.1:${mock.address().port}`;

/* -------------------------------------------------------------------------- */
/* Configuración del test                                                     */
/* -------------------------------------------------------------------------- */

const configPath = path.join(tmp, 'catalogs.json');
const cachePath = path.join(tmp, 'remote-cache.json');

await writeFile(
  configPath,
  JSON.stringify(
    {
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
          posterShape: 'poster',
          order: 2,
          source: { kind: 'cinemeta', type: 'movie', id: 'top' },
        },
      ],
      addons: [
        {
          id: 'streaming',
          name: 'Streaming Catalogs',
          manifestUrl: `${mockBase}/manifest.json`,
          only: ['nfx', 'srch', 'broken'],
          types: ['movie', 'series'],
          limit: 2,
          order: 100,
        },
      ],
    },
    null,
    2,
  ),
  'utf8',
);

process.env.RATE_LIMIT_MAX = '0';
process.env.CINEMETA_BASE = mockBase;
process.env.CATALOGS_CONFIG = configPath;
process.env.CATALOG_REMOTE_CACHE = cachePath;
process.env.LIVE_CATALOG_PATH = path.join(path.dirname(fileURLToPath(import.meta.url)), 'fixtures', 'live-catalog.json');

const { createApp } = await import('../src/app.js');
const { getCatalogs, loadCatalogs, manifestCatalogs, findCatalog } = await import('../src/catalogs/registry.js');
const { invalidateCatalogs } = await import('../src/services/catalogService.js');
const { addonBaseUrl, publishedCatalogId, normalizeAddon, expandManifest } = await import(
  '../src/catalogs/remoteAddons.js'
);

let server;
let baseUrl;

before(async () => {
  await loadCatalogs();
  const app = createApp({ rateLimit: 0 });
  server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise((resolve) => server.close(resolve));
  await new Promise((resolve) => mock.close(resolve));
});

/* -------------------------------------------------------------------------- */
/* Helpers puros                                                              */
/* -------------------------------------------------------------------------- */

test('addonBaseUrl quita /manifest.json y barras finales', () => {
  assert.equal(addonBaseUrl('https://host/abc/manifest.json'), 'https://host/abc');
  assert.equal(addonBaseUrl('https://host/abc/manifest.json/'), 'https://host/abc');
  assert.equal(addonBaseUrl('https://host/'), 'https://host');
});

test('publishedCatalogId prefija y sanea para no chocar con ids propios', () => {
  assert.equal(publishedCatalogId('streaming', 'nfx'), 'streaming_nfx');
  assert.equal(publishedCatalogId('streaming', 'Top Movies!'), 'streaming_top-movies');
  assert.equal(publishedCatalogId('a', ''), 'a_catalog');
  assert.equal(publishedCatalogId('a', 'x'.repeat(60)), `a_${'x'.repeat(46)}`);
});

test('normalizeAddon exige id corto y manifest http(s)', () => {
  assert.equal(normalizeAddon({ id: 'streaming', manifestUrl: 'ftp://x/manifest.json' }), null);
  assert.equal(normalizeAddon({ id: 'Streaming Catalog!', manifestUrl: 'https://x/manifest.json' }), null);

  const addon = normalizeAddon({
    id: 'streaming',
    manifestUrl: 'https://x/manifest.json',
    types: ['movie', 'podcast'],
    limit: 5000,
  });
  assert.deepEqual(addon.types, ['movie'], 'sólo quedan los tipos publicables');
  assert.equal(addon.limit, 100, 'limit se recorta a CATALOG_PAGE_SIZE');
  assert.equal(addon.order, 100);
});

test('expandManifest filtra por only/types y conserva los extras declarados', () => {
  const addon = normalizeAddon({
    id: 'streaming',
    manifestUrl: `${mockBase}/manifest.json`,
    only: ['nfx', 'srch'],
  });
  const rows = expandManifest(addon, {
    name: 'Streaming Catalogs',
    baseUrl: mockBase,
    catalogs: MANIFEST.catalogs,
  });

  assert.deepEqual(rows.map((row) => `${row.type}/${row.id}`), [
    'movie/streaming_nfx',
    'series/streaming_nfx',
    'movie/streaming_srch',
  ]);
  assert.deepEqual(rows[2].extraNames, ['search', 'skip']);
  assert.deepEqual(rows[0].extraNames, [], 'sin extra en el manifest remoto');
  assert.equal(rows[0].source.baseUrl, mockBase);
  assert.equal(rows[0].source.id, 'nfx', 'el id remoto se conserva para la petición');
});

/* -------------------------------------------------------------------------- */
/* Registro                                                                   */
/* -------------------------------------------------------------------------- */

test('las filas del addon remoto se publican con su origen y su capacidad', () => {
  const ids = getCatalogs().map((catalog) => `${catalog.type}/${catalog.id}`);
  assert.deepEqual(ids, [
    'tv/live',
    'movie/top',
    'movie/streaming_nfx',
    'series/streaming_nfx',
    'movie/streaming_srch',
    'movie/streaming_broken',
  ]);

  const netflix = findCatalog('movie', 'streaming_nfx');
  assert.equal(netflix.name, 'Netflix');
  assert.equal(netflix.source.kind, 'remote');
  assert.equal(netflix.source.type, 'movie');
  assert.equal(netflix.source.id, 'nfx');
  assert.equal(netflix.provider, 'Streaming Catalogs');
  assert.equal(netflix.capabilities.search, false, 'el manifest remoto no declara search');

  const searchable = findCatalog('movie', 'streaming_srch');
  assert.equal(searchable.capabilities.search, true);
  assert.equal(searchable.capabilities.skip, true);
});

test('el manifest sólo anuncia los extras que el addon remoto soporta', () => {
  const rows = manifestCatalogs();
  const netflix = rows.find((row) => row.type === 'movie' && row.id === 'streaming_nfx');
  const searchable = rows.find((row) => row.id === 'streaming_srch');

  assert.deepEqual(netflix.extra, [], 'sin buscador para quien no busca');
  assert.ok(searchable.extra.some((extra) => extra.name === 'search'));
  assert.ok(searchable.extra.some((extra) => extra.name === 'skip'));
});

test('el manifest de un addon se cachea en disco para el siguiente arranque', async () => {
  const cache = JSON.parse(await readFile(cachePath, 'utf8'));
  assert.ok(cache.streaming?.fetchedAt > 0);
  assert.equal(cache.streaming.manifest.id, 'pw.ers.netflix-catalog');
  assert.equal(cache.streaming.manifest.catalogs.length, MANIFEST.catalogs.length);
});

test('un segundo loadCatalogs reutiliza la caché y no vuelve a pedir el manifest', async () => {
  const beforeCount = upstreamRequests.filter((url) => url === '/manifest.json').length;
  await loadCatalogs();
  const afterCount = upstreamRequests.filter((url) => url === '/manifest.json').length;
  assert.equal(afterCount, beforeCount, 'el manifest está fresco: no se repite la petición');
  assert.ok(getCatalogs().length >= 6);
});

/* -------------------------------------------------------------------------- */
/* Integración HTTP                                                           */
/* -------------------------------------------------------------------------- */

test('GET /catalog/movie/streaming_nfx.json normaliza, recorta a limit y etiqueta el origen', async () => {
  invalidateCatalogs();
  const res = await fetch(`${baseUrl}/catalog/movie/streaming_nfx.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(body.metas.map((meta) => meta.id), ['tt0111161', 'tt0068646'], 'dedup + limit 2');
  assert.equal(body.metas[0].year, 1994);
  assert.equal(body.metas[0].rating, 9.3);
  assert.equal(body.metas[0].sourceName, 'Streaming Catalogs');
  assert.equal(body.metas[0].type, 'movie');

  const warm = await fetch(`${baseUrl}/catalog/movie/streaming_nfx.json`);
  assert.equal(warm.headers.get('x-cache'), 'HIT');
});

test('la fila de series del mismo servicio se publica por su propio tipo', async () => {
  const res = await fetch(`${baseUrl}/catalog/series/streaming_nfx.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(body.metas.map((meta) => meta.id), ['tt0944947']);
  assert.equal(body.metas[0].year, 2011);
});

test('los extras sólo se reenvían si el addon remoto los declara', async () => {
  /* `nfx` no declara extras: el `?q=` se queda en casa. */
  await fetch(`${baseUrl}/catalog/movie/streaming_nfx.json?q=godfather`);
  assert.ok(
    upstreamRequests.includes('/catalog/movie/nfx.json'),
    `se pidió: ${upstreamRequests.filter((url) => url.includes('nfx')).join(', ')}`,
  );
  assert.ok(!upstreamRequests.some((url) => url.includes('nfx') && url.includes('search=')));

  /* `srch` sí: la búsqueda viaja como segmento de path. */
  const res = await fetch(`${baseUrl}/catalog/movie/streaming_srch/search=matrix.json`);
  assert.equal(res.status, 200);
  assert.ok(
    upstreamRequests.includes('/catalog/movie/srch/search=matrix.json'),
    `se pidió: ${upstreamRequests.filter((url) => url.includes('srch')).join(', ')}`,
  );
  assert.deepEqual((await res.json()).metas.map((meta) => meta.id), ['tt0133093']);
});

test('una fila remota rota degrada sola y no tumba el Home', async () => {
  const res = await fetch(`${baseUrl}/catalog/movie/streaming_broken.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(body.metas, []);
  assert.equal(body.degraded, true);
  assert.equal(res.headers.get('x-catalog-degraded'), '1');

  /* Las demás filas siguen respondiendo. */
  const netflix = await fetch(`${baseUrl}/catalog/movie/streaming_nfx.json`);
  assert.equal(netflix.status, 200);
  assert.ok((await netflix.json()).metas.length > 0);
});

test('la ficha de un id de IMDb de una fila remota la resuelve Cinemeta', async () => {
  const res = await fetch(`${baseUrl}/meta/movie/tt0133093.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.equal(body.meta.name, 'The Matrix');
});

test('GET /catalogs.json expone las filas remotas con su proveedor', async () => {
  const res = await fetch(`${baseUrl}/catalogs.json`);
  const body = await res.json();
  const remote = body.catalogs.filter((catalog) => catalog.source === 'remote');

  assert.equal(remote.length, 4);
  assert.ok(remote.every((catalog) => catalog.provider === 'Streaming Catalogs'));
  assert.deepEqual(remote.map((catalog) => catalog.feed), [
    '/catalog/movie/streaming_nfx.json',
    '/catalog/series/streaming_nfx.json',
    '/catalog/movie/streaming_srch.json',
    '/catalog/movie/streaming_broken.json',
  ]);
});
