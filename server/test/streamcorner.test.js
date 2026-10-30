/**
 * test/streamcorner.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/streamcorner.py` provider.
 *
 * The site is reproduced with a local HTTP fixture that copies its real shape
 * (`/` index → `/stream/{liga}/{slug}` SPA detail → "AVAILABLE SOURCES" buttons
 * with `data-source` → Bitmovin config with the `.m3u8`), so the multi-source
 * resolution is validated without network access.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/streamcorner.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

/* -------------------------------------------------------------------------- */
/* Fixtures: misma forma que https://streamcorner.fun                        */
/* -------------------------------------------------------------------------- */

const HOME = `<!doctype html><html><body>
  <nav><a href="/">ALLLIVETODAY</a><a href="/extra">Extra</a></nav>
  <section>
    <a href="/stream/nfl/nfl-network">NFL NETWORK</a>
    <a href="/stream/nba/lakers-vs-celtics">Lakers vs Celtics</a>
    <a href="/stream/admin/49a4d2df66c13cddcf60bae785e4bec9">Family Guy</a>
  </section>
</body></html>`;

const EXTRA = `<!doctype html><html><body>
  <a href="/stream/admin/espn">ESPN</a>
</body></html>`;

/* Ficha SPA: botones de fuente + config Bitmovin con dos calidades. */
const DETAIL = `<!doctype html><html><head>
  <title>NFL NETWORK - StreamCorner</title></head><body>
  <div id="player"></div>
  <div class="sources">
    <button class="stream-source-btn" data-source="1">AVAILABLE SOURCES · 1</button>
    <button class="stream-source-btn" data-source="2">AVAILABLE SOURCES · 2</button>
  </div>
  <script>
    var conf = {
      key: "bitmovin-key",
      source: { hls: "https:\\/\\/edge.streamcorner-cdn.example\\/nfl\\/nfl-network\\/index.m3u8" },
      sources: [{ file: "https:\\/\\/backup.streamcorner-cdn.example\\/nfl\\/nfl-network\\/playlist.m3u8" }]
    };
  </script>
</body></html>`;

/* Un canal de /extra sólo publica el manifiesto en un data-*. */
const ESPN = `<html><body>
  <div class="player" data-hls="/hls/espn/master.m3u8"></div>
</body></html>`;

/* Un evento sin señal: la SPA responde, pero no hay manifiesto por ningún lado. */
const DEAD = `<html><body><h1>No stream available</h1></body></html>`;

const routes = {
  '/': HOME,
  '/extra': EXTRA,
  '/stream/nfl/nfl-network': DETAIL,
  '/stream/admin/espn': ESPN,
  '/stream/nba/lakers-vs-celtics': DEAD,
};

let server;
let base;

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const body = routes[url.pathname] ?? routes[url.pathname.replace(/\/$/, '')];
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
});

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

function runScraper(payload, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER], {
      env: { ...process.env, STREAMCORNER_BASE: base, STREAMCORNER_MIRRORS: '', ...extraEnv },
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

const live = (id) => runScraper({ type: 'tv', id, isLive: true, provider: 'streamcorner', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('collects every source of an event, in site order', async () => {
  const { code, body } = await live('tv:streamcorner:nfl/nfl-network');

  assert.equal(code, 0);
  assert.equal(body.streams.length, 2, 'AVAILABLE SOURCES lists two signals');

  const [first, second] = body.streams;
  assert.equal(first.url, 'https://edge.streamcorner-cdn.example/nfl/nfl-network/index.m3u8');
  assert.equal(second.url, 'https://backup.streamcorner-cdn.example/nfl/nfl-network/playlist.m3u8');
  assert.ok(!first.url.includes('\\'), 'escaped slashes must be unescaped');
  assert.equal(first.isLive, true);
  assert.equal(first.behaviorHints.mediaType, 'hls');
  assert.equal(first.name, 'NFL NETWORK', 'the league index gives the display name');
  assert.equal(first.referer, `${base}/stream/nfl/nfl-network`);
  assert.equal(body.meta.page, `${base}/stream/nfl/nfl-network`);
});

test('reads manifest urls hidden in data-* attributes', async () => {
  const { body } = await live('streamcorner:admin/espn');
  assert.equal(body.streams.length, 1);
  assert.equal(body.streams[0].url, `${base}/hls/espn/master.m3u8`);
});

test('accepts bare slugs, prefixed ids and full urls', async () => {
  for (const id of [
    'nfl-network',
    'streamcorner:nfl-network',
    `${base}/stream/nfl/nfl-network`,
    `tv:streamcorner:nfl/nfl-network`,
  ]) {
    const { body } = await live(id);
    assert.equal(body.streams.length, 2, `id ${id} should resolve`);
  }
});

test('never sends the user to the website when there is no signal', async () => {
  const { body } = await live('streamcorner:nba/lakers-vs-celtics');

  assert.equal(body.streams.length, 0, 'a dead event must not yield a fake stream');
  assert.equal(body.meta.reason, 'no_streams');
  assert.ok(body.streams.every((stream) => !stream.url.startsWith(base)));
});

test('--list prints catalog-ready ids from index and /extra', async () => {
  const items = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--json'], {
      env: { ...process.env, STREAMCORNER_BASE: base, STREAMCORNER_MIRRORS: '' },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(JSON.parse(stdout).items));
  });

  const ids = items.map((item) => item.id);
  assert.ok(ids.includes('tv:streamcorner:nfl/nfl-network'), `ids: ${ids.join(', ')}`);
  assert.ok(ids.includes('tv:streamcorner:admin/espn'), '/extra must be indexed too');
  assert.equal(new Set(ids).size, ids.length, 'no duplicate ids');
});
