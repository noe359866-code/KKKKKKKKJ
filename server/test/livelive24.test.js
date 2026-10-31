/**
 * test/livelive24.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/livelive24.py` provider.
 *
 * The site is reproduced with a local HTTP fixture that copies its real shape
 * (`Live Sport` / `Live TV` index with the source label inside the card →
 * `/watch/{slug}` page → player iframe → escaped `.m3u8`), so both the index
 * lookup and the player walking are validated without network access.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/livelive24.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

/* -------------------------------------------------------------------------- */
/* Fixtures: misma forma que https://livelive24.com/                          */
/* -------------------------------------------------------------------------- */

const HOME = `<!doctype html><html><head><title>Live24 · TV &amp; Sport</title></head><body>
  <nav>
    <a href="/live">Live Sport <b>654</b></a>
    <a href="/tv">Live TV <b>434</b></a>
    <a href="/country/es">Spain</a>
  </nav>
  <section id="sport">
    <a href="/watch/espn"><span class="src">Football24</span> ESPN</a>
    <a href="/watch/dazn-f1"><span class="src">HotHD🔥</span> DAZN F1</a>
  </section>
  <section id="tv">
    <a href="/channel/la-1"><span class="src">PeleZidaneTV</span> La 1</a>
  </section>
</body></html>`;

/* Ficha del canal: el reproductor real se monta en un iframe. */
const WATCH = (name) => `<!doctype html><html><head>
  <title>${name} en vivo | Live24 · TV &amp; Sport</title></head><body>
  <h1>${name} en vivo</h1>
  <div class="sources"><span>Football24</span><span>Tennis24</span></div>
  <iframe src="/player.php?id=77" allowfullscreen></iframe>
</body></html>`;

/* El reproductor esconde la URL con barras escapadas. */
const PLAYER = `<html><body><script>
  var cfg = { file: "https:\\/\\/cdn.livelive24.example\\/live\\/espn\\/index.m3u8", type: "hls" };
</script></body></html>`;

/* Un canal sin señal: la ficha responde pero no hay manifiesto. */
const DEAD = `<html><head><title>DAZN F1 en vivo | Live24</title></head><body><p>Offline</p></body></html>`;

const routes = {
  '/': HOME,
  '/live': HOME,
  '/tv': HOME,
  '/watch/espn': WATCH('ESPN'),
  '/channel/la-1': WATCH('La 1'),
  '/watch/dazn-f1': DEAD,
  '/player.php?id=77': PLAYER,
};

let server;
let base;

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname + url.search;
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
      env: { ...process.env, LIVELIVE24_BASE: base, LIVELIVE24_MIRRORS: '', ...extraEnv },
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

const live = (id) => runScraper({ type: 'tv', id, isLive: true, provider: 'livelive24', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('resolves a channel through its card, page and player iframe', async () => {
  const { code, body } = await live('tv:livelive24:espn');

  assert.equal(code, 0);
  assert.equal(body.streams.length, 1);

  const [stream] = body.streams;
  assert.equal(stream.url, 'https://cdn.livelive24.example/live/espn/index.m3u8');
  assert.ok(!stream.url.includes('\\'), 'escaped slashes must be unescaped');
  assert.equal(stream.isLive, true);
  assert.equal(stream.behaviorHints.mediaType, 'hls');
  assert.equal(stream.name, 'ESPN', 'the card name wins over the page title');
  assert.match(stream.title, /^Live24 · ESPN/);
  /* El CDN ve el referer del documento donde aparece el manifiesto (el
   * reproductor); `meta.page` queda como la ficha del canal. */
  assert.equal(stream.referer, `${base}/player.php?id=77`);
  assert.equal(body.meta.page, `${base}/watch/espn`);
});

test('accepts explicit paths, full urls and the site prefix', async () => {
  for (const id of ['watch/espn', `${base}/watch/espn`, 'tv:livelive24:espn', 'livelive24:espn']) {
    const { body } = await live(id);
    assert.equal(body.streams.length, 1, `id ${id} should resolve`);
  }
});

test('resolves channels from the Live TV block too', async () => {
  const { body } = await live('La 1');
  assert.equal(body.streams.length, 1);
  assert.equal(body.meta.page, `${base}/channel/la-1`, 'the card url is used as-is');
});

test('reports no streams for a dead channel instead of opening the site', async () => {
  const { body } = await live('livelive24:dazn-f1');

  assert.equal(body.streams.length, 0);
  assert.equal(body.meta.reason, 'no_streams');
  assert.ok(body.streams.every((stream) => !stream.url.startsWith(base)));
});

test('--list prints catalog-ready ids for both blocks', async () => {
  const items = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--json'], {
      env: { ...process.env, LIVELIVE24_BASE: base, LIVELIVE24_MIRRORS: '' },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(JSON.parse(stdout).items));
  });

  const ids = items.map((item) => item.id);
  assert.ok(ids.includes('tv:livelive24:watch/espn'), `ids: ${ids.join(', ')}`);
  assert.ok(ids.includes('tv:livelive24:channel/la-1'), 'Live TV channels must be indexed');
  const names = items.map((item) => item.name);
  assert.ok(names.includes('ESPN'));
  assert.ok(names.includes('La 1'));
  assert.equal(new Set(ids).size, ids.length, 'no duplicate ids');
});
