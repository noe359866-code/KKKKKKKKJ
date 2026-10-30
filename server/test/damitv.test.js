/**
 * test/damitv.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/damitv.py` provider.
 *
 * The site is reproduced with a local HTTP fixture that copies its real shape
 * (`/papi/api/streams` JSON → `sources[]` whose `embed` carries the manifest in
 * a `url=` query → `/c/346.m3u8`, plus `/papi/matches/all` for the 24/7 row),
 * so the feed parsing and the `url=` decoding are validated without network.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/damitv.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

/* -------------------------------------------------------------------------- */
/* Fixtures: misma forma que https://damitv.st/papi/api/streams               */
/* -------------------------------------------------------------------------- */

const FEED = {
  success: true,
  timestamp: 1793374267,
  READ_ME: 'Free public API by DAMITV.',
  streams: [
    {
      category: 'cricket',
      id: 1,
      streams: [
        {
          id: 'cr-new-zealand-india',
          name: 'new zealand vs india',
          starts_at: 1793343600,
          category_name: 'cricket',
          status: 'live',
          league: '4th t20',
          teams: { home: { name: 'new zealand' }, away: { name: 'india' } },
          uri_name: 'cr-new-zealand-india',
          always_live: 0,
          sources: [
            { source: 'hls', id: 'c1', name: 'Willow Cricket', sd: 0, hd: 1, embed: '/player/jw/?url=%2Fc%2F346.m3u8&name=Willow%20Cricket' },
            { source: 'hls', id: 'c2', name: 'Server 1', sd: 1, hd: 0, embed: '/player/jw/?url=%2Fc%2F1202.m3u8&name=Server%201' },
          ],
          iframe: '/embed/?id=cr-new-zealand-india',
          embed: '/embed/?id=cr-new-zealand-india',
        },
      ],
    },
    {
      category: 'motor-sports',
      id: 2,
      streams: [
        {
          id: 'mm-mot-dl-287282d214',
          name: 'Fia Extreme H World Cup Ay 2 - Qiddiya City',
          category_name: 'motor-sports',
          status: 'live',
          league: 'Motorsport',
          teams: {},
          always_live: 0,
          /* Sin `url=`: la señal sólo está en la página del reproductor. */
          sources: [
            { source: 'manual', id: 'dm1', name: 'ITV 4 UK', sd: 0, hd: 1, embed: '/player/jw/?v=540&name=ITV%204%20UK' },
          ],
          embed: '/embed/?id=mm-mot-dl-287282d214',
        },
        {
          id: 'ev-f1-2026-mexico-fp1',
          name: 'Mexico City Grand Prix - Practice 1',
          category_name: 'motor-sports',
          status: 'upcoming',
          league: 'Formula 1',
          teams: {},
          always_live: 0,
          sources: [{ source: 'hls', id: 'c1', name: 'Sky Sports F1 UK', sd: 0, hd: 1, embed: '/player/jw/?url=%2Fc%2F60.m3u8&name=Sky%20Sports%20F1%20UK' }],
          embed: '/embed/?id=ev-f1-2026-mexico-fp1',
        },
      ],
    },
  ],
};

/* `/papi/matches/all`: canales 24/7 con otra forma (`title`, fecha en ms). */
const MATCHES = {
  success: true,
  events: [
    {
      id: 'ppv-nfl-network',
      title: 'NFL Network',
      name: 'NFL Network',
      league: '24/7',
      teams: [],
      always_live: 1,
      sources: [
        { source: 'hls', id: 'c1', name: 'NFL Network', sd: 0, hd: 1, embed: '/player/jw/?url=%2Fc%2F247.m3u8&name=NFL%20Network' },
      ],
      embed: '/embed/?id=ppv-nfl-network',
    },
  ],
};

/* La ficha `/embed/?id=…` monta un iframe; el reproductor trae el literal JS. */
const EMBED_PAGE = `<html><body>
  <script>window.location.hash = "#play";</script>
  <iframe src="/player/jw/?v=540&e=1&name=ITV%204%20UK" allowfullscreen></iframe>
</body></html>`;

const PLAYER_PAGE = `<html><body><script>
  var player = new jwplayer("v").setup({ sources: [{ file: "https:\\/\\/cdn.example.com\\/dl\\/353.m3u8" }] });
</script></body></html>`;

const routes = {
  '/papi/api/streams': JSON.stringify(FEED),
  '/papi/matches/all': JSON.stringify(MATCHES),
  '/embed/?id=mm-mot-dl-287282d214': EMBED_PAGE,
  '/player/jw/?v=540&e=1&name=ITV%204%20UK': PLAYER_PAGE,
};

let server;
let base;

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname + url.search;
    const body = routes[key] ?? routes[url.pathname];
    if (body === undefined) {
      res.writeHead(404, { 'Content-Type': 'text/plain' }).end('nope');
      return;
    }
    const type = body.startsWith('{') ? 'application/json' : 'text/html; charset=utf-8';
    res.writeHead(200, { 'Content-Type': type }).end(body);
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
      env: { ...process.env, DAMITV_BASE: base, DAMITV_MIRRORS: '', ...extraEnv },
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

const live = (id) => ({ type: 'tv', id, isLive: true, provider: 'damitv', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('decodes the manifest route that the API hides in the embed query', async () => {
  const { code, body } = await runScraper(live('tv:damitv:cr-new-zealand-india'));

  assert.equal(code, 0);
  assert.equal(body.streams.length, 2);

  const [hd, sd] = body.streams;
  assert.equal(hd.url, `${base}/c/346.m3u8`, 'the `url=` query is the real HLS route');
  assert.equal(sd.url, `${base}/c/1202.m3u8`);
  assert.equal(hd.isLive, true);
  assert.equal(hd.behaviorHints.mediaType, 'hls');
  assert.equal(hd.quality, '720p', '`hd: 1` without `sd` is HD');
  assert.equal(sd.quality, 'SD', '`sd: 1` without `hd` is SD');
  assert.match(hd.title, /^DAMITV · new zealand vs india · Willow Cricket$/);
  assert.equal(hd.referer, `${base}/`, 'the CDN expects the site as referer');
  assert.ok(hd.userAgent, 'ExoPlayer needs the site UA');
  assert.equal(body.meta.title, 'new zealand vs india');
  assert.equal(body.meta.league, '4th t20');
  assert.equal(body.meta.streams, 2);
});

test('falls back to the embed page when a source carries no manifest route', async () => {
  const { body } = await runScraper(live('damitv:mm-mot-dl-287282d214'));

  assert.equal(body.streams.length, 1);
  assert.equal(body.streams[0].url, 'https://cdn.example.com/dl/353.m3u8');
  assert.ok(
    body.meta.opened.some((url) => url.endsWith('/embed/?id=mm-mot-dl-287282d214')),
    `the embed page must be the fallback: ${JSON.stringify(body.meta.opened)}`,
  );
});

test('serves the 24/7 row from /papi/matches/all', async () => {
  const { body } = await runScraper(live('damitv:ppv-nfl-network'));

  assert.equal(body.streams.length, 1);
  assert.equal(body.streams[0].url, `${base}/c/247.m3u8`);
  assert.equal(body.meta.always_live, true);
});

test('resolves free text, url ids and accented queries', async () => {
  for (const id of [
    'formula 1',
    'damitv:formula 1',
    `tv:damitv:ev-f1-2026-mexico-fp1`,
    `${base}/watch/ev-f1-2026-mexico-fp1`,
  ]) {
    const { body } = await runScraper(live(id));
    assert.equal(body.streams.length, 1, `id ${id} should resolve`);
    assert.equal(body.streams[0].url, `${base}/c/60.m3u8`);
  }
});

test('reports not_found and refuses non-live requests', async () => {
  const missing = await runScraper(live('damitv:no-existe'));
  assert.equal(missing.body.streams.length, 0);
  assert.equal(missing.body.meta.reason, 'not_found');

  const vod = await runScraper({ type: 'movie', id: 'damitv:x', isLive: false });
  assert.equal(vod.body.streams.length, 0);
  assert.equal(vod.body.meta.reason, 'not_live');
});

test('--list prints ids that the scraper can resolve', async () => {
  const items = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--json'], {
      env: { ...process.env, DAMITV_BASE: base, DAMITV_MIRRORS: '' },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(JSON.parse(stdout).items));
  });

  assert.ok(items.length >= 3);
  const id = items.find((item) => item.name.includes('india')).id;
  assert.equal(id, 'tv:damitv:cr-new-zealand-india', 'the id is the exact feed id');
});
