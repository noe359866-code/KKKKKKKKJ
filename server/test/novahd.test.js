/**
 * test/novahd.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/novahd.py` provider.
 *
 * NOVA publica su propia API (`/api/sources?tmdbId=…&type=movie|show`), así que
 * el fixture replica **exactamente** esa respuesta: varias señales por título,
 * con `language`/`quality`/`provider`/`type`, más `subtitles[]`. Cinemeta se
 * reproduce también en local porque es lo que traduce IMDb/título → `tmdbId`.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/novahd.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

/* -------------------------------------------------------------------------- */
/* Fixtures: misma forma que https://novahd.cc/api/sources                    */
/* -------------------------------------------------------------------------- */

const MOVIE = {
  sources: [
    {
      url: 'https://nova-edge-a.example/mpd/abc/master.m3u8',
      quality: '720p', type: 'hls', provider: 'Orion', language: 'English',
      name: 'Orion · Alpha', codecs: 'hevc', origAudio: true,
    },
    {
      /* Sin extensión: el `type` de la API es la única pista para ExoPlayer. */
      url: 'https://nova-edge-b.example/?e=xyz&ip=1.2.3&ipsig=deadbeef',
      quality: 'FHD', type: 'hls', provider: 'Swift', language: 'Spanish',
      name: 'Swift · OrionC', codecs: 'h264', origAudio: true,
    },
    {
      url: 'https://nova-edge-c.example/dash/manifest.mpd?x=1',
      quality: 'FHD', type: 'dash', provider: 'Atlas', language: '',
      codecs: 'h264', origAudio: false,
    },
    {
      url: 'https://nova-edge-d.example/live/nova/master.m3u8',
      quality: '720p', type: 'hls', provider: 'Nova', language: 'Spanish',
      name: 'Nova · Vega', codecs: 'h264', origAudio: true,
    },
  ],
  subtitles: [
    { url: 'https://nova-edge-e.example/vtt?e=1', label: 'Spanish', lang: 'es' },
    { url: 'https://nova-edge-e.example/vtt?e=2', label: 'English', lang: 'English' },
  ],
};

const SHOW = {
  sources: [
    {
      url: 'https://nova-edge-f.example/mpd/ep/master.m3u8',
      quality: '1080p', type: 'hls', provider: 'Orion', language: 'English',
      codecs: 'h264', origAudio: true,
    },
    {
      url: 'https://nova-edge-g.example/?e=ep', quality: '720p', type: 'hls',
      provider: 'Swift', language: 'Spanish', codecs: 'hevc', origAudio: true,
    },
  ],
  subtitles: [],
};

const EMPTY = { sources: [], subtitles: [] };
const API_ERROR = { error: 'querystring/type must be equal to one of the allowed values' };

/* Cinemeta: lo que traduce IMDb → tmdbId (sin clave) y título → IMDb. */
const CINEMETA = {
  '/cinemeta/meta/movie/tt0468569.json': { meta: { id: 'tt0468569', name: 'The Dark Knight', moviedb_id: 155 } },
  '/cinemeta/meta/series/tt0903747.json': { meta: { id: 'tt0903747', name: 'Breaking Bad', moviedb_id: 1396 } },
  '/cinemeta/catalog/movie/top/search=The Dark Knight.json': {
    metas: [
      { id: 'tt1345836', name: 'The Dark Knight Rises' },
      { id: 'tt0468569', name: 'The Dark Knight' },
    ],
  },
  '/cinemeta/catalog/series/top/search=Breaking Bad.json': {
    metas: [{ id: 'tt0903747', name: 'Breaking Bad' }],
  },
};

let server;
let base;
const requested = [];

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    /* Cinemeta recibe el título con espacios (%20): se compara ya decodificado. */
    const key = (url.pathname + url.search).replace(/%20/g, ' ');
    requested.push(key);

    const json = (body) => res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(body));

    if (CINEMETA[key]) return json(CINEMETA[key]);

    if (url.pathname === '/api/sources') {
      const tmdbId = url.searchParams.get('tmdbId');
      const type = url.searchParams.get('type');
      if (tmdbId === '155' && type === 'movie') return json(MOVIE);
      if (tmdbId === '1396' && type === 'show') {
        assert.equal(url.searchParams.get('season'), '1');
        assert.equal(url.searchParams.get('episode'), '1');
        return json(SHOW);
      }
      if (tmdbId === '777') return json(EMPTY);
      if (tmdbId === '888') return json(API_ERROR);
      return json({ error: `tmdbId ${tmdbId} no existe` });
    }

    res.writeHead(404, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: 'not found' }));
  });
  server.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise((resolve) => server.close(resolve));
});

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

function runScraper(payload, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER], {
      env: {
        ...process.env,
        NOVAHD_BASE: base,
        NOVAHD_MIRRORS: '',
        CINEMETA_BASE: `${base}/cinemeta`,
        ...extraEnv,
      },
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', (code) => {
      try {
        resolve({ code, stderr, body: JSON.parse(stdout) });
      } catch {
        reject(new Error(`scraper did not print JSON (exit ${code}):\n${stdout}\n${stderr}`));
      }
    });
    child.stdin.end(JSON.stringify(payload));
  });
}

const movie = (id, env = {}) => runScraper({ type: 'movie', id, timeoutMs: 8000 }, env);

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('publishes every language of a title, best signal first', async () => {
  const { code, body } = await movie('novahd:155');

  assert.equal(code, 0);
  assert.equal(body.streams.length, 4);

  /* 1 y 2: español (preferido por defecto), FHD antes que 720p. */
  assert.equal(body.streams[0].name, 'Español · Swift');
  assert.equal(body.streams[0].title, 'NOVA · Español · Swift · 1080p');
  assert.equal(body.streams[1].name, 'Español · Nova');

  /* 3: inglés con audio original; 4: sin idioma ni audio original. */
  assert.equal(body.streams[2].name, 'Inglés · Orion');
  assert.equal(body.streams[3].name, 'Atlas');

  assert.equal(body.meta.tmdb, '155');
  assert.deepEqual(body.meta.languages, ['Español', 'Inglés']);
  assert.deepEqual(body.meta.providers, ['Atlas', 'Nova', 'Orion', 'Swift']);
});

test('marks hls/dash even when the url has no extension', async () => {
  const { body } = await movie('155');
  const [spanish] = body.streams;
  const dash = body.streams.find((item) => item.behaviorHints.mediaType === 'dash');

  assert.match(spanish.url, /\/\?e=xyz&ip=/, 'las URLs firmadas se publican tal cual');
  assert.equal(spanish.behaviorHints.mediaType, 'hls');
  assert.equal(spanish.quality, '1080p', 'FHD se normaliza al vocabulario del cliente');

  assert.equal(dash.behaviorHints.mediaType, 'dash');
  assert.match(dash.url, /manifest\.mpd/);

  /* Todas necesitan el sitio como referer; ExoPlayer recibe también el UA. */
  for (const item of body.streams) {
    assert.equal(item.referer, `${base}/`);
    assert.ok(item.userAgent && item.userAgent.includes('Mozilla'));
    assert.equal(item.isLive, false);
  }
});

test('exposes the subtitle tracks the api advertises', async () => {
  const { body } = await movie('155');
  assert.equal(body.meta.subtitles.length, 2);
  assert.deepEqual(body.meta.subtitles.map((track) => track.lang), ['es', 'English']);
});

test('translates imdb ids through cinemeta (no api key needed)', async () => {
  requested.length = 0;
  const { body } = await movie('tt0468569');

  assert.equal(body.streams.length, 4);
  assert.equal(body.meta.imdb, 'tt0468569');
  assert.equal(body.meta.tmdb, '155');
  assert.ok(requested.includes('/cinemeta/meta/movie/tt0468569.json'), requested.join(', '));
  assert.ok(requested.some((url) => url.startsWith('/api/sources?tmdbId=155')));
});

test('resolves a title the way the device engine sends it', async () => {
  const { body } = await movie('The Dark Knight');

  assert.equal(body.streams.length, 4);
  assert.equal(body.meta.tmdb, '155');
  assert.equal(body.meta.cinemeta_hit, 'The Dark Knight');
  /* El título exacto gana al primer resultado de la búsqueda. */
  assert.equal(body.meta.imdb, 'tt0468569');
});

test('resolves series episodes with season and episode', async () => {
  const { body } = await runScraper({
    type: 'series', id: 'novahd:1396', season: 1, episode: 1, timeoutMs: 8000,
  });

  assert.equal(body.streams.length, 2);
  assert.equal(body.meta.type, 'show');
  assert.equal(body.streams[0].name, 'Español · Swift', 'el episodio también se ordena por idioma');
  assert.ok(requested.some((url) => url.includes('type=show') && url.includes('season=1')), requested.join(', '));
});

test('resolves a series from its imdb id', async () => {
  const { body } = await runScraper({
    type: 'series', id: 'tt0903747', season: 1, episode: 1, timeoutMs: 8000,
  });

  assert.equal(body.meta.tmdb, '1396');
  assert.equal(body.streams.length, 2);
});

test('refuses a series with no episode instead of guessing', async () => {
  const { body } = await runScraper({ type: 'series', id: 'novahd:1396', timeoutMs: 8000 });
  assert.equal(body.streams.length, 0);
  assert.equal(body.meta.reason, 'no_episode');
});

test('reports an empty title, an api rejection and an unknown id', async () => {
  const empty = await movie('novahd:777');
  assert.equal(empty.body.streams.length, 0);
  assert.equal(empty.body.meta.reason, 'no_streams');

  const rejected = await movie('novahd:888');
  assert.equal(rejected.body.streams.length, 0);
  assert.equal(rejected.body.meta.reason, 'api_error');

  const unknown = await movie('novahd:abc-def');
  assert.equal(unknown.body.streams.length, 0);
  assert.equal(unknown.body.meta.reason, 'not_found');
});

test('accepts site urls and an explicit media kind', async () => {
  for (const id of [`${base}/m/155`, 'movie:155', 'tv:novahd:155']) {
    const { body } = await movie(id);
    assert.equal(body.streams.length, 4, `id ${id} should resolve`);
  }
});

test('can restrict the published languages', async () => {
  const { body } = await movie('novahd:155', { NOVAHD_ONLY_LANGUAGES: 'Español' });
  assert.equal(body.streams.length, 2);
  assert.ok(body.streams.every((item) => item.name.startsWith('Español')));
});
