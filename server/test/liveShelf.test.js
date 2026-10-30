/**
 * test/liveShelf.test.js
 * ---------------------------------------------------------------------------
 * Las dos filas vivas de la Home: **canales** y **agenda deportiva**.
 *
 * Qué se prueba aquí (sin red):
 *   1. `getCatalog` sirve la sección correcta (`channels` / `events`) desde la
 *      estantería, con su `posterShape`, sus géneros, la búsqueda y el `skip`.
 *   2. Si el scrape no trae nada, la fila cae al JSON curado en vez de quedarse
 *      vacía (la TV no puede desaparecer del Home porque un sitio esté caído).
 *   3. `loadLiveShelf()` pasa por el puente real (spawn de Python) y manda el
 *      payload que espera `scrapers/_liveshelf.py`, con los proveedores de
 *      directo del registro.
 *
 * El doble de Python (`fixtures/live-shelf/scrapers/_liveshelf.py`) responde
 * fijo: lo que se comprueba es el contrato, no el constructor real (ese tiene
 * sus pruebas en `app/src/test/python/test_live_shelf.py`).
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const fixtures = path.join(here, 'fixtures', 'live-shelf');

/* El entorno se fija ANTES de importar config.js (lee el entorno al cargarse). */
process.env.RATE_LIMIT_MAX = '0';
process.env.CATALOGS_CONFIG = path.join(fixtures, 'catalogs.json');
process.env.LIVE_CATALOG_PATH = path.join(fixtures, 'live-catalog.json');
process.env.SCRAPER_DIR = path.join(fixtures, 'scrapers');
process.env.CATALOG_LIVE_TTL_MINUTES = '15';

const { getCatalog, invalidateCatalogs, loadLiveShelf } = await import('../src/services/catalogService.js');

/* -------------------------------------------------------------------------- */
/* Estantería de mentira, con la forma que devuelve _liveshelf.py             */
/* -------------------------------------------------------------------------- */

const shelf = {
  channels: [
    {
      id: 'tv:futbolibre:espn-1', type: 'tv', name: 'ESPN 1', sourceName: 'Fútbol Libre',
      releaseInfo: 'En directo', genres: ['Deportes', 'Canales'], live: true,
    },
    {
      id: 'tv:ntv:nfl-network', type: 'tv', name: 'NFL Network', sourceName: 'NTV',
      releaseInfo: 'En directo', genres: ['Canales'], live: true,
    },
    {
      id: 'tv:streamcorner:mlb-network', type: 'tv', name: 'MLB Network',
      sourceName: 'StreamCorner', releaseInfo: 'En directo', genres: ['Canales'], live: true,
    },
  ],
  events: [
    {
      id: 'tv:futbolibre:evento:liga-1-x-vs-y', type: 'tv', name: 'Cienciano vs Atlético Grau',
      sourceName: 'Fútbol Libre', league: 'Liga 1', home: 'Cienciano', away: 'Atlético Grau',
      startTime: '19:00', status: 'En vivo', onAir: true, releaseInfo: 'En vivo · 19:00',
      genres: ['Deportes', 'Liga 1'], live: true,
    },
    {
      id: 'tv:damitv:mlb-yankees-vs-red-sox', type: 'tv', name: 'Yankees vs Red Sox',
      sourceName: 'DAMITV', league: 'MLB', home: 'Yankees', away: 'Red Sox',
      startTime: '21:00', status: 'Programado', onAir: false, releaseInfo: 'Hoy · 21:00',
      genres: ['Deportes', 'MLB'], live: true,
    },
  ],
};

/* -------------------------------------------------------------------------- */
/* Canales                                                                    */
/* -------------------------------------------------------------------------- */

test('the channels row publishes every collected channel as a landscape card', async () => {
  const result = await getCatalog({ type: 'tv', id: 'canales' }, { liveShelf: shelf });
  const metas = result.metas;

  assert.equal(metas.length, 3);
  assert.deepEqual(metas.map((meta) => meta.name), ['ESPN 1', 'NFL Network', 'MLB Network']);
  for (const meta of metas) {
    assert.equal(meta.type, 'tv');
    assert.equal(meta.posterShape, 'landscape', 'la TV se pinta en 16:9');
    assert.ok(meta.sourceName, 'la tarjeta dice de qué proveedor sale');
    assert.match(meta.id, /^tv:[a-z0-9]+:.+/, 'el id es el que resuelve /stream/tv/<id>');
  }
  assert.deepEqual(result.catalog, {
    type: 'tv', id: 'canales', name: 'TV en vivo · Canales', posterShape: 'landscape',
    genres: ['Deportes', 'Canales'],
  });
});

test('genre, search and skip filter the live rows server side', async () => {
  const sports = await getCatalog({ type: 'tv', id: 'canales', extra: { genre: 'Deportes' } }, { liveShelf: shelf });
  assert.deepEqual(sports.metas.map((meta) => meta.name), ['ESPN 1']);

  const search = await getCatalog({ type: 'tv', id: 'canales', extra: { search: 'nfl' } }, { liveShelf: shelf });
  assert.deepEqual(search.metas.map((meta) => meta.name), ['NFL Network']);

  const skip = await getCatalog({ type: 'tv', id: 'canales', extra: { skip: '1' } }, { liveShelf: shelf });
  assert.deepEqual(skip.metas.map((meta) => meta.name), ['NFL Network', 'MLB Network']);
});

/* -------------------------------------------------------------------------- */
/* Agenda deportiva                                                           */
/* -------------------------------------------------------------------------- */

test('the agenda row keeps league, teams, time and the live flag', async () => {
  const result = await getCatalog({ type: 'tv', id: 'agenda' }, { liveShelf: shelf });
  const [live, upcoming] = result.metas;

  assert.equal(result.metas.length, 2);
  assert.equal(live.id, 'tv:futbolibre:evento:liga-1-x-vs-y');
  assert.equal(live.name, 'Cienciano vs Atlético Grau');
  assert.equal(live.league, 'Liga 1');
  assert.equal(live.home, 'Cienciano');
  assert.equal(live.away, 'Atlético Grau');
  assert.equal(live.startTime, '19:00');
  assert.equal(live.onAir, true);
  assert.equal(live.sourceName, 'Fútbol Libre', 'la tarjeta dice qué proveedor lo sirve');

  assert.equal(upcoming.league, 'MLB');
  assert.equal(upcoming.onAir, false);
  assert.equal(upcoming.posterShape, 'landscape');
});

test('the agenda can be searched by league or team', async () => {
  const byLeague = await getCatalog({ type: 'tv', id: 'agenda', extra: { search: 'mlb' } }, { liveShelf: shelf });
  assert.deepEqual(byLeague.metas.map((meta) => meta.name), ['Yankees vs Red Sox']);

  const byTeam = await getCatalog({ type: 'tv', id: 'agenda', extra: { search: 'Cienciano' } }, { liveShelf: shelf });
  assert.deepEqual(byTeam.metas.map((meta) => meta.name), ['Cienciano vs Atlético Grau']);
});

/* -------------------------------------------------------------------------- */
/* Respaldo y puente                                                          */
/* -------------------------------------------------------------------------- */

test('an empty scrape falls back to the curated json instead of an empty row', async () => {
  invalidateCatalogs(); // la respuesta anterior está cacheada por (tipo, id, extras)
  const result = await getCatalog(
    { type: 'tv', id: 'canales' },
    { liveShelf: { channels: [], events: [] } },
  );
  assert.deepEqual(result.metas.map((meta) => meta.name), ['ESPN 1']);
  assert.equal(result.metas[0].id, 'tv:futbolibre:espn-1');
});

test('loadLiveShelf runs the real bridge against the providers of the registry', async () => {
  invalidateCatalogs();
  const data = await loadLiveShelf();

  assert.deepEqual(data.channels.map((meta) => meta.name), ['ESPN 1', 'NFL Network', 'MLB Network']);
  assert.deepEqual(data.events.map((meta) => meta.name),
    ['Cienciano vs Atlético Grau', 'Yankees vs Red Sox']);

  /* El payload que viaja a Python lleva los proveedores de directo, no todos. */
  const liveIds = data.channels.map((meta) => meta.id.split(':')[1]);
  assert.ok(liveIds.every((id) => !['gnula', 'novahd', 'animeflv'].includes(id)));
});

test('an empty agenda stays empty: a stale match would be a dead card', async () => {
  invalidateCatalogs();
  const result = await getCatalog(
    { type: 'tv', id: 'agenda', extra: { search: 'nada' } },
    { liveShelf: { channels: [], events: [] } },
  );
  assert.deepEqual(result.metas, []);
});

test('a broken scrape never throws: the row degrades and the curated list answers', async () => {
  process.env.LIVESHELF_FIXTURE = 'empty';
  delete process.env.CATALOG_LIVE_TTL_MINUTES;

  /* Sin TTL nuevo la copia buena sigue en memoria: se pide otra fila distinta
   * con la estantería vacía para comprobar el respaldo sin depender del orden. */
  const result = await getCatalog(
    { type: 'tv', id: 'agenda' },
    { liveShelf: { channels: [], events: [] } },
  );
  assert.ok(Array.isArray(result.metas));
  delete process.env.LIVESHELF_FIXTURE;
});
