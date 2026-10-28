/**
 * test/tvenvivo.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/tvenvivo.py` provider.
 *
 * The site is reproduced with a local HTTP fixture that copies its real shape
 * (channel page -> `SOURCES` map -> `/live/core.php` wrapper -> iframe ->
 * escaped `.m3u8`), so the parser and the wrapper/iframe walking are validated
 * without any external network access.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/tvenvivo.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';
const INDEX_CACHE = path.join(os.tmpdir(), `tvenvivo-test-index-${process.pid}.json`);

/* -------------------------------------------------------------------------- */
/* Fixtures: misma estructura que www.tvenvivo.org                            */
/* -------------------------------------------------------------------------- */

const HOME = `<!doctype html><html><body>
  <a href="/espn-2-en-vivo-online.php"><img src="/imge/espn2.webp" alt="ESPN 2"></a>
  <a href="/liga-1-en-vivo-online.php">Liga 1</a>
  <a href="/redes/">Mundo Latam</a>
  <a href="https://otro.example/espn-9-en-vivo-online.php">Ajeno</a>
</body></html>`;

const CHANNEL = `<!doctype html><html><head>
  <title>▷ ESPN 2 EN VIVO HD | TV EN VIVO</title></head><body>
  <h1>ESPN 2 EN VIVO</h1>
  <a href="/liga-1-en-vivo-online.php">Ver más canales</a>
  <script>
    const SOURCES = {
      1: "/live/core.php?canal=espn-2",
      2: "/live2/core.php?canal=espn-2",
      3: "https://ads.doubleclick.net/player.php?spot=1"
    };
    videoFrame.src = SOURCES[1];
  </script>
</body></html>`;

/* El wrapper devuelve un iframe; el player esconde la URL con barras escapadas. */
const wrapper = (option) =>
  `<html><body><iframe src="/embed/player.php?opt=${option}"></iframe></body></html>`;

const player = (option) =>
  `<html><body><script>
     var setup = { file : "https:\\/\\/cdn.example.com\\/hls\\/espn2_${option}_720p\\/index.m3u8?token=x" };
   </script></body></html>`;

/* Algunos canales publican el .m3u8 directamente, sin wrapper intermedio. */
const LIGA = `<html><head><title>Liga 1 EN VIVO HD</title></head><body>
  <h1>Liga 1 EN VIVO</h1>
  <video controls><source src="https://cdn.example.com/hls/liga1/index.m3u8" type="application/x-mpegURL"></video>
</body></html>`;

const routes = {
  '/': HOME,
  '/espn-2-en-vivo-online.php': CHANNEL,
  '/liga-1-en-vivo-online.php': LIGA,
  '/live/core.php': wrapper(1),
  '/live2/core.php': wrapper(2),
  '/embed/player.php?opt=1': player(1),
  '/embed/player.php?opt=2': player(2),
};

let server;
let base;
const requested = [];

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    requested.push(url.pathname + url.search);
    const body = routes[url.pathname + url.search] ?? routes[url.pathname];
    if (body === undefined) {
      res.writeHead(404, { 'Content-Type': 'text/html' }).end('<h1>404</h1>');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(body);
  });
  server.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  await new Promise((resolve) => server.close(resolve));
  await rm(INDEX_CACHE, { force: true });
});

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

/** Runs the scraper through its stdin/stdout contract and parses the JSON. */
function runScraper(payload, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER], {
      env: {
        ...process.env,
        TVENVIVO_BASE: base,
        TVENVIVO_INDEX_CACHE: INDEX_CACHE,
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

const live = (id) => ({ type: 'tv', id, isLive: true, provider: 'tvenvivo', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('resolves a channel id through SOURCES, wrapper and iframe', async () => {
  const { code, body } = await runScraper(live('tv:tvenvivo:espn-2'));

  assert.equal(code, 0);
  assert.equal(body.streams.length, 2, 'both player options should yield a stream');

  const [first, second] = body.streams;
  assert.match(first.url, /^https:\/\/cdn\.example\.com\/hls\/espn2_1_720p\/index\.m3u8\?token=x$/);
  assert.match(second.url, /_2_720p/);

  /* The escaped `https:\/\/` in the player config must be unescaped. */
  assert.ok(!first.url.includes('\\'), 'escaped slashes must be resolved');

  /* Metadata the Android app needs (ExoPlayer applies these headers). */
  assert.equal(first.isLive, true);
  assert.equal(first.quality, '720p');
  assert.equal(first.name, 'ESPN 2 · Opción 1');
  assert.match(first.title, /^TV en Vivo · ESPN 2 · Opción 1$/);
  assert.ok(/^http:\/\/127\.0\.0\.1:\d+\//.test(first.referer), 'referer must be the player page');
  assert.equal(body.meta.channel, 'ESPN 2');
  assert.equal(body.meta.options, 2, 'advertising options must be filtered out');
  /* 2 wrappers + 2 players; the advertising option is never fetched. */
  assert.equal(body.meta.fetched, 4, 'one document per option and per player');
});

test('does not follow navigation links to other channel pages', async () => {
  requested.length = 0;
  const { body } = await runScraper(live('tv:tvenvivo:espn-2'));

  assert.equal(body.meta.fetched, 4, 'navigation links must not add fetches');
  assert.ok(
    !requested.includes('/liga-1-en-vivo-online.php'),
    `channel pages are not players: ${requested.join(',')}`,
  );
});

test('ignores advertising sources', async () => {
  const { body } = await runScraper(live('tv:tvenvivo:espn-2'));
  assert.ok(body.streams.every((stream) => !stream.url.includes('doubleclick')));
});

test('accepts plain slugs, suffixed pages and full urls', async () => {
  for (const id of [
    'espn-2',
    'tvenvivo:espn-2',
    'espn-2-en-vivo-online',
    'espn-2-en-vivo-online.php',
    `${base}/espn-2-en-vivo-online.php`,
  ]) {
    const { body } = await runScraper(live(id));
    assert.equal(body.streams.length, 2, `id ${id} should resolve`);
  }
});

test('resolves free text through the site index', async () => {
  const { body } = await runScraper(live('ESPN 2'));
  assert.equal(body.meta.channel, 'ESPN 2');
  assert.equal(body.streams.length, 2);

  /* Case/accent insensitive, and pages that expose the .m3u8 directly. */
  const liga = await runScraper(live('liga 1'));
  assert.equal(liga.body.meta.channel, 'Liga 1');
  assert.equal(liga.body.streams.length, 1);
  assert.equal(liga.body.streams[0].url, 'https://cdn.example.com/hls/liga1/index.m3u8');
  assert.equal(liga.body.meta.fetched, 0, 'no extra documents needed');
});

test('falls back to the index when the slug spelling differs', async () => {
  /* "espn2" no existe como página; el índice lo asocia con "espn-2". */
  const { body } = await runScraper(live('tv:tvenvivo:espn2'));
  assert.equal(body.streams.length, 2);
  assert.equal(body.meta.channel, 'ESPN 2');
  assert.match(body.meta.page, /\/espn-2-en-vivo-online\.php$/);
});

test('unknown channels return an empty result instead of an exception', async () => {
  const { code, body } = await runScraper(live('tv:tvenvivo:canal-inexistente'));
  assert.equal(code, 0, 'the contract is: exit 0 with {"streams": []}');
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'channel_unavailable');
});

test('refuses non-live payloads', async () => {
  const { body } = await runScraper({ type: 'movie', id: 'tt0133093', isLive: false });
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'not_live');
});

test('--list prints channel ids usable by Stremio', async () => {
  const output = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list'], {
      env: { ...process.env, TVENVIVO_BASE: base, TVENVIVO_INDEX_CACHE: INDEX_CACHE },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  assert.match(output, /tv:tvenvivo:espn-2/);
  assert.match(output, /tv:tvenvivo:liga-1/);
  assert.ok(!output.includes('otro.example'), 'only channels from this site are indexed');
});

test('uses the on-disk index cache instead of re-downloading the home page', async () => {
  await runScraper(live('ESPN 2'), { TVENVIVO_INDEX_TTL_SECONDS: '600' });
  requested.length = 0;
  await runScraper(live('Liga 1'), { TVENVIVO_INDEX_TTL_SECONDS: '600' });
  assert.ok(!requested.includes('/'), `home page should not be re-fetched: ${requested.join(',')}`);
});
