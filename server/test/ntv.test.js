/**
 * test/ntv.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/ntv.py` provider.
 *
 * The site is reproduced with a local HTTP fixture that copies its real shape
 * (`/matches/{servidor}` index with cards rendered as compressed text →
 * `/watch/{servidor}/ppv-{id}` page → player iframe → escaped `.m3u8`), plus
 * the four server mirrors so the failover can be exercised offline.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/ntv.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

/* -------------------------------------------------------------------------- */
/* Fixtures: misma forma que https://ntv.st/matches/raptor                    */
/* -------------------------------------------------------------------------- */

/* El índice pega canal + categoría + contador dentro del propio `<a>`. */
const index = (server) => `<!doctype html><html><body>
  <nav><a href="/matches/raptor">Raptor</a><a href="/matches/falcon">Falcon</a></nav>
  <h2>Trending</h2>
  <a href="/watch/${server}/ppv-18172"><span>Live</span><span>NFL Network</span><span>American Football</span><span>1</span></a>
  <a href="/watch/${server}/ppv-20431"><span>Live</span><span>Sky Sports 1</span><span>Football</span><span>3</span></a>
  <a href="/watch/${server}/ppv-19002"><span>Lakers vs Celtics</span><span>Basketball</span></a>
  <a href="/channels">24/7 Streams</a>
  <a href="https://ntvx.link/watch/${server}/ppv-1">otro espejo</a>
</body></html>`;

/* La ficha del evento monta un iframe "Loading stream"; el player trae el JS. */
const watch = (server, id) => `<!doctype html><html><head>
  <title>NFL Network - Watch Live Stream | NTVSTREAM</title></head><body>
  <h1>NFL Network</h1>
  <div class="sources"><button>Source 1 (247CHANNEL)</button></div>
  <iframe id="player" src="/embed/${server}/${id}?autoplay=1"></iframe>
</body></html>`;

const embed = (server, id) => `<html><body><script>
  const config = { sources: [{ file: "https:\\/\\/cdn${server === 'falcon' ? '2' : ''}.example.com\\/live\\/${id}\\/index.m3u8", type: "application/x-mpegURL" }] };
</script></body></html>`;

const routes = {
  '/matches/raptor': index('raptor'),
  '/matches/falcon': index('falcon'),
  '/channels': '<html><body><a href="/watch/raptor/ppv-24701">NFL Network</a></body></html>',
  /* raptor se cae para el 18172: la señal sólo está en falcon. */
  '/watch/raptor/ppv-18172': watch('raptor', 'ppv-18172'),
  '/watch/raptor/ppv-20431': watch('raptor', 'ppv-20431'),
  '/watch/raptor/ppv-19002': watch('raptor', 'ppv-19002'),
  '/watch/falcon/ppv-18172': watch('falcon', 'ppv-18172'),
  '/embed/raptor/ppv-18172?autoplay=1': '<html><body><p>Stream offline</p></body></html>',
  '/embed/falcon/ppv-18172?autoplay=1': embed('falcon', 'ppv-18172'),
  '/embed/raptor/ppv-20431?autoplay=1': embed('raptor', 'ppv-20431'),
  '/embed/raptor/ppv-19002?autoplay=1': embed('raptor', 'ppv-19002'),
};

let server;
let base;
const requested = [];

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname + url.search;
    requested.push(key);
    const body = routes[key] ?? routes[url.pathname];
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
      env: { ...process.env, NTV_BASE: base, NTV_MIRRORS: '', ...extraEnv },
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

const live = (id, env) => runScraper({ type: 'tv', id, isLive: true, provider: 'ntv', timeoutMs: 9000 }, env);

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('resolves ppv ids by walking the server pickers until a stream appears', async () => {
  const { code, body } = await live('tv:ntv:ppv-18172');

  assert.equal(code, 0);
  assert.equal(body.streams.length, 1);

  const [stream] = body.streams;
  assert.equal(stream.url, 'https://cdn2.example.com/live/ppv-18172/index.m3u8');
  assert.equal(stream.isLive, true);
  assert.equal(stream.behaviorHints.mediaType, 'hls');
  assert.ok(!stream.url.includes('\\'), 'escaped slashes must be unescaped');
  assert.equal(stream.name, 'NFL Network', 'the channel name comes from the page title');
  assert.match(stream.title, /^NTV · NFL Network/);
  assert.ok(stream.referer.startsWith(base), 'the CDN needs the watch page as referer');
});

test('fails over to the next server when one is dead', async () => {
  requested.length = 0;
  const { body } = await live('ntv:ppv-18172');

  assert.equal(body.streams.length, 1);
  assert.ok(requested.includes('/watch/raptor/ppv-18172'), 'raptor is tried first');
  assert.ok(requested.some((url) => url.endsWith('/watch/falcon/ppv-18172')), 'falcon is the fallback');
  assert.equal(body.meta.page, `${base}/watch/falcon/ppv-18172`);
});

test('honours an explicit server and still falls back', async () => {
  const { body } = await live('ntv:falcon/ppv-18172');
  assert.equal(body.meta.id, 'ppv-18172');
  assert.equal(body.streams[0].url, 'https://cdn2.example.com/live/ppv-18172/index.m3u8');
});

test('resolves free text through the mirrored indices without duplicates', async () => {
  const { body } = await live('ntv:Sky Sports 1');

  assert.equal(body.streams.length, 1);
  assert.equal(body.streams[0].url, 'https://cdn.example.com/live/ppv-20431/index.m3u8');
  /* El mismo evento aparece en raptor y falcon (y en channels): una entrada. */
  assert.ok(body.meta.index >= 3, `index size: ${body.meta.index}`);
  assert.ok(!body.meta.id.includes('/'), 'the catalog id is the event id, not the path');
});

test('accepts full watch urls and reports unknown events', async () => {
  const direct = await live(`${base}/watch/raptor/ppv-19002`);
  assert.equal(direct.body.streams.length, 1);
  assert.equal(direct.body.streams[0].url, 'https://cdn.example.com/live/ppv-19002/index.m3u8');

  const missing = await live('ntv:ppv-99999');
  assert.equal(missing.body.streams.length, 0);
  assert.equal(missing.body.meta.reason, 'no_streams');
});

test('--list prints catalog-ready ids', async () => {
  const items = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--json'], {
      env: { ...process.env, NTV_BASE: base, NTV_MIRRORS: '' },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(JSON.parse(stdout).items));
  });

  const names = items.map((item) => item.name);
  assert.ok(names.includes('NFL Network'), `names: ${names.join(', ')}`);
  assert.ok(names.includes('Sky Sports 1'), 'the category suffix must be stripped');
  assert.ok(items.every((item) => item.id.startsWith('tv:ntv:ppv-')));
  assert.equal(new Set(items.map((item) => item.id)).size, items.length, 'no duplicate ids');
});
