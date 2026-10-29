/**
 * test/catalogs.test.js
 * ---------------------------------------------------------------------------
 * Catálogos Stremio: funciones puras + integración HTTP.
 *
 * Estrategia (sin red):
 *   - Se levanta un **mock de Cinemeta** en un puerto efímero que sirve los
 *     fixtures de `test/fixtures/cinemeta/` y anota las rutas pedidas.
 *   - `CINEMETA_BASE`, `CATALOGS_CONFIG` y `LIVE_CATALOG_PATH` se apuntan a ese
 *     mock / a los fixtures **antes** de importar la app (config.js lee el
 *     entorno al importarse, igual que en routes.test.js).
 *   - Se ejercita la app real sobre un puerto efímero con `fetch`.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.join(here, 'fixtures');
const cinemetaFixtures = path.join(fixtures, 'cinemeta');

/* -------------------------------------------------------------------------- */
/* Mock de Cinemeta                                                           */
/* -------------------------------------------------------------------------- */

/** Rutas que el addon pidió al upstream (para comprobar la traducción). */
const upstreamRequests = [];

const mock = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://mock');
  upstreamRequests.push(url.pathname);

  const send = (status, body) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  };

  try {
    if (url.pathname === '/catalog/movie/top.json') {
      return send(200, JSON.parse(await readFile(path.join(cinemetaFixtures, 'movie-top.json'), 'utf8')));
    }
    /* /catalog/movie/top/search=… y /genre=… y /skip=… caen aquí. */
    if (url.pathname.startsWith('/catalog/movie/top/')) {
      return send(200, JSON.parse(await readFile(path.join(cinemetaFixtures, 'movie-top.json'), 'utf8')));
    }
    if (url.pathname.startsWith('/catalog/series/top')) {
      return send(200, JSON.parse(await readFile(path.join(cinemetaFixtures, 'series-top.json'), 'utf8')));
    }
    /* Simula un catálogo upstream roto (para el modo degradado). */
    if (url.pathname.startsWith('/catalog/movie/boom')) {
      return send(500, { err: 'boom' });
    }
    if (url.pathname === '/meta/movie/tt0133093.json') {
      return send(200, JSON.parse(await readFile(path.join(cinemetaFixtures, 'meta-movie.json'), 'utf8')));
    }
    if (url.pathname === '/meta/series/tt0944947.json') {
      return send(200, JSON.parse(await readFile(path.join(cinemetaFixtures, 'meta-series.json'), 'utf8')));
    }
    return send(404, { err: 'not found' });
  } catch (error) {
    return send(500, { err: error.message });
  }
});

await new Promise((resolve) => mock.listen(0, '127.0.0.1', resolve));

/* El entorno de los tests se fija ANTES de importar config.js. */
process.env.RATE_LIMIT_MAX = '0';
process.env.CINEMETA_BASE = `http://127.0.0.1:${mock.address().port}/`;
process.env.CATALOGS_CONFIG = path.join(fixtures, 'catalogs.json');
process.env.LIVE_CATALOG_PATH = path.join(fixtures, 'live-catalog.json');
process.env.CATALOG_TTL_MINUTES = '30';
process.env.CATALOG_SEARCH_TTL_MINUTES = '5';

const { createApp } = await import('../src/app.js');
const { loadCatalogs, getCatalogs, manifestCatalogs } = await import('../src/catalogs/registry.js');
const { invalidateCatalogs, liveItemToMeta, loadLiveCatalog, catalogCacheKey } = await import(
  '../src/services/catalogService.js'
);
const { normalizeMeta, normalizeMetas, normalizeVideos, buildCatalogPath, buildMetaPath, parseYear, parseRating } =
  await import('../src/lib/cinemeta.js');
const { parseCatalogPath } = await import('../src/routes/catalog.js');

/* -------------------------------------------------------------------------- */
/* Servidor bajo prueba                                                       */
/* -------------------------------------------------------------------------- */

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
/* Funciones puras: normalización                                             */
/* -------------------------------------------------------------------------- */

test('normalizeMeta convierte strings de Cinemeta en números útiles', () => {
  const meta = normalizeMeta({
    id: 'tt1375666',
    type: 'movie',
    name: 'Inception',
    poster: 'p.jpg',
    releaseInfo: '2010–2011',
    imdbRating: '8.8',
    genres: ['Action', '  '],
  });

  assert.equal(meta.id, 'tt1375666');
  assert.equal(meta.type, 'movie');
  assert.equal(meta.year, 2010, 'el año se extrae del rango de la serie/film');
  assert.equal(meta.rating, 8.8, 'imdbRating string -> number');
  assert.deepEqual(meta.genres, ['Action'], 'los géneros vacíos se descartan');
  assert.equal(meta.imdb_id, 'tt1375666', 'un id tt es también imdb_id');
  assert.equal(meta.releaseInfo, '2010–2011', 'releaseInfo original se conserva');
});

test('normalizeMeta tolera fichas incompletas y rechaza las que no tienen id', () => {
  assert.equal(normalizeMeta(null), null);
  assert.equal(normalizeMeta({ name: 'sin id' }), null);

  const minimal = normalizeMeta({ id: 'tt1' });
  assert.equal(minimal.name, 'tt1', 'el nombre cae al id');
  assert.equal(minimal.year, undefined);
  assert.equal(minimal.rating, undefined);

  const na = normalizeMeta({ id: 'tt2', imdbRating: 'N/A' });
  assert.equal(na.rating, undefined, '"N/A" no debe convertirse en NaN');
});

test('normalizeMetas deduplica por id y descarta entradas rotas', () => {
  const metas = normalizeMetas([
    { id: 'tt1', name: 'A' },
    { id: 'tt1', name: 'A duplicada' },
    { id: 'tt2', name: 'B' },
    { name: 'sin id' },
    null,
  ]);
  assert.deepEqual(metas.map((meta) => meta.id), ['tt1', 'tt2']);
  assert.equal(normalizeMetas(undefined).length, 0);
});

test('normalizeVideos ordena por temporada/episodio y conserva el id de Stremio', () => {
  const videos = normalizeVideos([
    { id: 'tt0944947:1:2', season: 1, episode: 2, name: 'The Kingsroad' },
    { id: 'tt0944947:1:1', season: 1, episode: 1 },
    { episode: 3, season: 1 },
    null,
  ]);

  assert.deepEqual(videos.map((video) => video.id), ['tt0944947:1:1', 'tt0944947:1:2', '1:3']);
  assert.equal(videos[2].name, undefined, 'los campos opcionales ausentes no se inventan');
});

test('parseYear / parseRating aceptan los formatos reales de Cinemeta', () => {
  assert.equal(parseYear('2019–2023'), 2019);
  assert.equal(parseYear('2019-'), 2019);
  assert.equal(parseYear(''), undefined);
  assert.equal(parseRating('9.2'), 9.2);
  assert.equal(parseRating('0'), undefined);
  assert.equal(parseRating(undefined), undefined);
});

/* -------------------------------------------------------------------------- */
/* Funciones puras: URLs y paths                                              */
/* -------------------------------------------------------------------------- */

test('buildCatalogPath pone los extras en el orden del protocolo', () => {
  assert.equal(buildCatalogPath({ type: 'movie', id: 'top' }), '/catalog/movie/top.json');
  assert.equal(
    buildCatalogPath({ type: 'movie', id: 'top', extra: { skip: 100, genre: 'Sci-Fi', search: 'the matrix' } }),
    '/catalog/movie/top/genre=Sci-Fi/skip=100/search=the%20matrix.json',
  );
  assert.equal(
    buildCatalogPath({ type: 'series', id: 'top', extra: { skip: 0 } }),
    '/catalog/series/top.json',
    'skip=0 no se envía al upstream',
  );
  assert.equal(buildMetaPath({ type: 'movie', id: 'tt0133093' }), '/meta/movie/tt0133093.json');
});

test('parseCatalogPath entiende extras en segmentos de path', () => {
  assert.deepEqual(parseCatalogPath('/catalog/movie/top.json'), { type: 'movie', id: 'top', extras: {} });

  assert.deepEqual(parseCatalogPath('/catalog/tv/live/genre=Deportes/skip=20.json'), {
    type: 'tv',
    id: 'live',
    extras: { genre: 'Deportes', skip: '20' },
  });

  /* Un `search` con `:` y espacios codificados debe sobrevivir intacto. */
  assert.deepEqual(parseCatalogPath('/catalog/movie/top/search=star%20wars.json').extras, {
    search: 'star wars',
  });

  /* Extras desconocidos se ignoran en lugar de romper la petición. */
  assert.deepEqual(parseCatalogPath('/catalog/movie/top/streamingService=netflix.json').extras, {});

  assert.equal(parseCatalogPath('/stream/movie/tt1.json'), null);
  assert.equal(parseCatalogPath('/catalog/movie'), null);
});

/* -------------------------------------------------------------------------- */
/* Fuente local                                                               */
/* -------------------------------------------------------------------------- */

test('liveItemToMeta produce tarjetas 16:9 listas para el cliente', () => {
  const meta = liveItemToMeta({
    id: 'tv:demo:espn-1',
    name: 'ESPN 1',
    category: 'deportes',
    genres: ['Deportes'],
    description: 'Señal',
    logo: 'https://x/logo.png',
  });

  assert.equal(meta.type, 'tv');
  assert.equal(meta.posterShape, 'landscape', 'la TV en vivo se pinta 16:9');
  assert.equal(meta.releaseInfo, 'En directo');
  assert.equal(meta.poster, 'https://x/logo.png');
  assert.equal(liveItemToMeta({ name: 'sin id' }), null);
});

test('loadLiveCatalog lee el JSON y descarta canales sin id', async () => {
  const items = await loadLiveCatalog({ force: true });
  assert.deepEqual(items.map((meta) => meta.id), ['tv:demo:espn-1', 'tv:demo:canal-13', 'tv:demo:evento-final']);
  assert.ok(items.every((meta) => meta.posterShape === 'landscape'));
});

/* -------------------------------------------------------------------------- */
/* Registro                                                                   */
/* -------------------------------------------------------------------------- */

test('el registro descarta catálogos inválidos y respeta el orden', () => {
  const ids = getCatalogs().map((catalog) => `${catalog.type}/${catalog.id}`);
  assert.deepEqual(ids, ['tv/live', 'movie/top', 'anime/top', 'movie/boom']);

  const manifest = manifestCatalogs();
  const live = manifest.find((catalog) => catalog.type === 'tv');
  assert.equal(live.posterShape, 'landscape');
  assert.ok(live.extra.some((extra) => extra.name === 'genre' && extra.options.includes('Deportes')));
  assert.ok(live.extra.some((extra) => extra.name === 'search'), 'sin `search` Stremio no muestra el buscador');
});

/* -------------------------------------------------------------------------- */
/* Integración HTTP                                                           */
/* -------------------------------------------------------------------------- */

test('GET /manifest.json publica catálogos y los recursos catalog/meta', async () => {
  const res = await fetch(`${baseUrl}/manifest.json`);
  const manifest = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(
    manifest.resources.map((resource) => resource.name),
    ['stream', 'catalog', 'meta'],
  );
  assert.ok(manifest.catalogs.length >= 4);
  assert.equal(manifest.catalogs[0].type, 'tv');
  assert.equal(manifest.catalogs[0].posterShape, 'landscape');
  assert.ok(!('idPrefixes' in manifest.catalogs[0]), 'los ids de TV no empiezan por tt');
});

test('GET /catalog/movie/top.json normaliza, deduplica y cachea', async () => {
  const cold = await fetch(`${baseUrl}/catalog/movie/top.json`);
  const body = await cold.json();

  assert.equal(cold.status, 200);
  assert.equal(cold.headers.get('x-cache'), 'MISS');
  assert.deepEqual(body.metas.map((meta) => meta.id), ['tt0133093', 'tt1375666'], 'dedup por id');
  assert.equal(body.metas[0].year, 1999);
  assert.equal(body.metas[0].rating, 8.7);
  assert.equal(body.metas[0].background, 'https://images.metahub.space/background/medium/tt0133093/img');
  assert.equal(body.catalog.posterShape, 'poster');
  assert.ok(body.cacheMaxAge > 0);
  assert.ok(body.staleError > 0);

  const warm = await fetch(`${baseUrl}/catalog/movie/top.json`);
  assert.equal(warm.headers.get('x-cache'), 'HIT', 'la segunda petición no toca Cinemeta');

  const requestsBefore = upstreamRequests.filter((url) => url === '/catalog/movie/top.json').length;
  assert.equal(requestsBefore, 1, 'single flight: una sola llamada al upstream');
});

test('el catálogo anime se sirve desde series + genre=Animation y conserva su tipo', async () => {
  const res = await fetch(`${baseUrl}/catalog/anime/top.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.ok(
    upstreamRequests.includes('/catalog/series/top/genre=Animation.json'),
    `el upstream debe recibir el género fijo; se pidió: ${upstreamRequests.join(', ')}`,
  );
  assert.deepEqual(body.metas.map((meta) => meta.id), ['tt0944947', 'tt0409591']);
  assert.ok(body.metas.every((meta) => meta.type === 'anime'), 'el tipo publicado manda');
  assert.ok(body.metas.every((meta) => meta.sourceType === 'series'), 'el cliente sabe a qué recurso pedir streams');
  assert.equal(body.metas.find((meta) => meta.id === 'tt0944947').rating, undefined, '"N/A" no es una nota');
});

test('la búsqueda se propaga al upstream como extra search=', async () => {
  const res = await fetch(`${baseUrl}/catalog/movie/top/search=matrix.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.ok(
    upstreamRequests.includes('/catalog/movie/top/search=matrix.json'),
    `se pidió: ${upstreamRequests.join(', ')}`,
  );
  assert.equal(body.metas.length, 2);
});

test('la búsqueda también se acepta por query string', async () => {
  const res = await fetch(`${baseUrl}/catalog/movie/top.json?q=inception`);
  assert.equal(res.status, 200);
  assert.ok(upstreamRequests.includes('/catalog/movie/top/search=inception.json'));

  /* Clave de caché distinta de la búsqueda por path y del catálogo sin extras. */
  assert.notEqual(
    catalogCacheKey({ type: 'movie', id: 'top', extra: { search: 'inception' } }),
    catalogCacheKey({ type: 'movie', id: 'top' }),
  );
});

test('GET /catalog/tv/live.json mezcla la fuente local en el Home', async () => {
  const res = await fetch(`${baseUrl}/catalog/tv/live.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(body.metas.map((meta) => meta.id), [
    'tv:demo:espn-1',
    'tv:demo:canal-13',
    'tv:demo:evento-final',
  ]);
  assert.ok(body.metas.every((meta) => meta.posterShape === 'landscape'));
  assert.equal(body.catalog.posterShape, 'landscape');
});

test('la fila local filtra por género y por texto', async () => {
  const byGenre = await (await fetch(`${baseUrl}/catalog/tv/live/genre=Deportes.json`)).json();
  assert.deepEqual(byGenre.metas.map((meta) => meta.id), ['tv:demo:espn-1', 'tv:demo:evento-final']);

  /* Ojo: "canal" también aparece en el género "Canales", así que se busca un
   * término que sólo tenga un canal. */
  const bySearch = await (await fetch(`${baseUrl}/catalog/tv/live/search=13.json`)).json();
  assert.deepEqual(bySearch.metas.map((meta) => meta.id), ['tv:demo:canal-13']);
});

test('un upstream caído devuelve 200 degradado, no un 502', async () => {
  const res = await fetch(`${baseUrl}/catalog/movie/boom.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(body.metas, []);
  assert.equal(body.degraded, true);
  assert.equal(res.headers.get('x-catalog-degraded'), '1');
});

test('un catálogo no publicado responde 404 con los ids válidos', async () => {
  const res = await fetch(`${baseUrl}/catalog/movie/nope.json`);
  const body = await res.json();

  assert.equal(res.status, 404);
  assert.equal(body.code, 'CATALOG_NOT_FOUND');
  assert.deepEqual(body.available.sort(), ['boom', 'top']);
});

test('un tipo no soportado responde 404 UNSUPPORTED_TYPE', async () => {
  const res = await fetch(`${baseUrl}/catalog/podcast/nope.json`);
  const body = await res.json();

  assert.equal(res.status, 404);
  assert.equal(body.code, 'UNSUPPORTED_TYPE');
});

test('HEAD /catalog/... no consulta al upstream', async () => {
  const before = upstreamRequests.length;
  const res = await fetch(`${baseUrl}/catalog/movie/top.json`, { method: 'HEAD' });

  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-cache'), 'BYPASS');
  assert.equal(upstreamRequests.length, before, 'un probe de disponibilidad no debe scrapear');
});

test('GET /meta/movie/tt0133093.json devuelve la ficha enriquecida', async () => {
  const res = await fetch(`${baseUrl}/meta/movie/tt0133093.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.equal(body.meta.name, 'The Matrix');
  assert.equal(body.meta.year, 1999);
  assert.equal(body.meta.rating, 8.7);
  assert.equal(body.meta.runtime, '136 min');
  assert.ok(!('trailerStreams' in body.meta), 'no se reenvían campos que el cliente no usa');
});

test('GET /meta/series/tt0944947:1:2.json selecciona el episodio', async () => {
  const res = await fetch(`${baseUrl}/meta/series/tt0944947:1:2.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(body.meta.videos.map((video) => video.id), ['tt0944947:1:1', 'tt0944947:1:2']);
  assert.equal(body.meta.selectedVideo.episode, 2);
  assert.equal(body.meta.selectedVideo.name, 'The Kingsroad');
});

test('GET /meta/tv/<id> devuelve la ficha del canal local', async () => {
  const res = await fetch(`${baseUrl}/meta/tv/${encodeURIComponent('tv:demo:espn-1')}.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.equal(body.meta.name, 'ESPN 1');
  assert.equal(body.meta.posterShape, 'landscape');
});

test('GET /meta de un id inexistente responde 404', async () => {
  const res = await fetch(`${baseUrl}/meta/movie/tt0000000.json`);
  assert.equal(res.status, 404);
  assert.equal((await res.json()).code, 'META_NOT_FOUND');
});

test('GET /catalogs.json da a Android las filas con su forma y su feed', async () => {
  const res = await fetch(`${baseUrl}/catalogs.json`);
  const body = await res.json();

  assert.equal(res.status, 200);
  assert.deepEqual(body.catalogs.map((catalog) => catalog.feed), [
    '/catalog/tv/live.json',
    '/catalog/movie/top.json',
    '/catalog/anime/top.json',
    '/catalog/movie/boom.json',
  ]);
  assert.equal(body.catalogs[0].posterShape, 'landscape');
  assert.equal(body.catalogs[1].posterShape, 'poster');
});

test('invalidateCatalogs limpia catálogos y fichas pero no los streams', async () => {
  await fetch(`${baseUrl}/catalog/movie/top.json`);
  const removed = invalidateCatalogs();

  assert.ok(removed > 0);
  const cold = await fetch(`${baseUrl}/catalog/movie/top.json`);
  assert.equal(cold.headers.get('x-cache'), 'MISS');
});
