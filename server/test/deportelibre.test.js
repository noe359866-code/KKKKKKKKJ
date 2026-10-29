/**
 * test/deportelibre.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/deportelibre.py` provider.
 *
 * A local HTTP fixture reproduces the site's real shape:
 *
 *   /stream/                         catalogue: /total/stream-NN.php + names
 *   /total/stream-01.php             channel page with the option links
 *   /en-vivo-online/espn/embed2.php  Clappr player, .m3u8 inside a packed eval()
 *   /en-vivo-online/espn/embed.php   "VPN AR" option, offline
 *   /mpd2.php?id=…                   same player, DASH variant
 *
 * so the parser, the parallel option walk and the token handling are validated
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
const SCRAPER = path.resolve(__dirname, '../scrapers/deportelibre.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';
const INDEX_CACHE = path.join(os.tmpdir(), `deportelibre-test-index-${process.pid}.json`);

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                   */
/* -------------------------------------------------------------------------- */

const CHANNELS = `<!doctype html><html><body>
  <nav><a href="/agenda-deportiva/">🏠 Agenda</a></nav>
  <section id="todos">
    <div class="canal">
      <img src="/stream/assets/flags/sudamerica.svg" alt="Sudamérica">ESPN
      <a href="/total/stream-01.php">▶ Ver canal</a>
    </div>
    <div class="canal">
      <img src="/stream/assets/flags/ar.svg" alt="Argentina">ESPN Premium
      <a href="/total/stream-387.php">▶ Ver canal</a>
    </div>
    <div class="canal">
      <img src="/stream/assets/flags/pe.svg" alt="Perú">Liga 1 Max
      <a href="/total/stream-012.php">▶ Ver canal</a>
    </div>
    <a href="https://otro.example/total/stream-99.php">Ajeno</a>
  </section>
</body></html>`;

const channelPage = (title, slug, options) => `<!doctype html><html>
<head><title>${title} en directo | DeporteLibre</title></head><body>
  <div class="opciones">${options
    .map((label, index) => `<a href="/en-vivo-online/${slug}/embed${index + 2}.php">${label}</a>`)
    .join('')}<a href="/en-vivo-online/${slug}/embed.php">(VPN AR)</a></div>
  <a href="/total/stream-01.php">🔄 Actualizar canal</a>
  <a href="/agenda-deportiva">← Volver a Agenda</a>
</body></html>`;

/* El reproductor real: Clappr con la URL del CDN dentro de un eval() empaquetado.
 * El empaquetador sustituye tokens por su índice en la tabla, así que en el
 * HTML el esquema aparece "empaquetado": 2://host/...  (2 -> "https"). */
const packer = (payload, table) => `eval(function(p,a,c,k,e,d){e=function(c){return c};while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+c+'\\\\b','g'),k[c])}}return p}('${payload}',${table.length},${table.length},'${table.join('|')}'.split('|'),0,{}))`;

const PLAYER_TABLE = ['var', 'src', 'https', 'hls', 'player', 'p2p', 'Clappr'];

const playerPage = (ref, token, expiry) => `<!doctype html><html><body>
<div id="player"></div>
<script src="/js/clappr.min.js"></script>
<script>
${packer(`0 1="2:\\/\\/ghhuraj.234276133.fit:8443\\/3\\/${ref}.m3u8?s=${token}&e=${expiry}";4=new 6.Player({source:1});`, PLAYER_TABLE)}
</script>
</body></html>`;

/* Mismo reproductor con la tabla del empaquetador irrecuperable: la URL queda
 * como "2://host" y el scraper debe normalizarla igualmente. */
const brokenPackerPage = (ref, token, expiry) => `<!doctype html><html><body>
<script>
eval(function(p,a,c,k,e,d){e=function(c){return c};while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+c+'\\\\b','g'),k[c])}}return p}('2:\\/\\/bk.9931.live:8443\\/hls\\/${ref}.m3u8?s=${token}&e=${expiry}';
</script></body></html>`;

/* Variante JSON del mismo backend (stream_url / stream_url_nop2p). */
const jsonPlayer = (ref, token, expiry) => `<!doctype html><html><body>
<script>
const cfg = {"stream_url":"https:\\/\\/92vhj8.39564828.net:8443\\/hls\\/${ref}.m3u8?s=${token}&amp;e=${expiry}",
             "stream_url_nop2p":"https://py3hsjj2.04334746.net:8443/hls/${ref}.m3u8?s=${token}&amp;e=${expiry}",
             "p2p": true};
</script></body></html>`;

const OFFLINE = `<!doctype html><html><body>
  <div class="tb stream-offline"><h2>STREAM IS OFFLINE</h2>
  <p>Retrying in <span class="counter"></span> seconds</p></div>
</body></html>`;

const FUTURE = Math.floor(Date.now() / 1000) + 7200;
const PAST = Math.floor(Date.now() / 1000) - 7200;

const routes = {
  '/stream/': CHANNELS,
  '/total/stream-01.php': channelPage('ESPN', 'espn', ['Opcion 1', 'Opcion 2', 'Opcion 3']),
  '/total/stream-387.php': channelPage('ESPN Premium', 'espn-premium', ['Opcion 1']),
  '/total/stream-012.php': channelPage('Liga 1 Max', 'liga-1-max', ['Opcion 1']),

  /* ESPN: opción 1 viva (packed eval), opción 2 con JSON, opción 3 offline,
   * opción 4 (VPN) con token caducado. */
  '/en-vivo-online/espn/embed2.php': playerPage('2twhx', '3ULvk3kMvKeguoy1fjswUQ', FUTURE),
  '/en-vivo-online/espn/embed3.php': jsonPlayer('2twhx', 'Xk29fjQpsLmNvBtY', FUTURE),
  '/en-vivo-online/espn/embed4.php': OFFLINE,
  '/en-vivo-online/espn/embed.php': brokenPackerPage('oldref', 'ExpiredToken00000', PAST),

  '/en-vivo-online/espn-premium/embed2.php': playerPage('9kx2p', 'PremTokenAbCdEfGh', FUTURE),
  '/en-vivo-online/liga-1-max/embed2.php': OFFLINE,
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

function runScraper(payload, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER], {
      env: {
        ...process.env,
        DEPORTELIBRE_BASE: base,
        DEPORTELIBRE_INDEX_CACHE: INDEX_CACHE,
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

const live = (id) => ({ type: 'tv', id, isLive: true, provider: 'deportelibre', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('walks channel page -> options -> CDN url inside a packed player', async () => {
  const { code, body } = await runScraper(live('tv:deportelibre:stream-01'));

  assert.equal(code, 0);
  assert.equal(body.meta.channel, 'ESPN');
  assert.equal(body.meta.options.length, 4, 'three options plus the VPN one');

  const urls = body.streams.map((stream) => stream.url);
  assert.ok(urls.includes(
    'https://ghhuraj.234276133.fit:8443/hls/2twhx.m3u8?s=3ULvk3kMvKeguoy1fjswUQ&e=' + FUTURE,
  ), `escaped slashes/amp must be resolved, got ${urls.join(', ')}`);

  const [primary] = body.streams;
  assert.equal(primary.isLive, true);
  assert.match(primary.name, /^ESPN · Opcion 1$/);
  assert.match(primary.title, /^DeporteLibre · ESPN · Opcion 1$/);
  assert.ok(primary.referer.startsWith(base), 'referer must be the embed page');
  assert.ok(primary.url.startsWith('https://'));
});

test('reads the JSON variant (stream_url / stream_url_nop2p) with &amp; entities', async () => {
  const { body } = await runScraper(live('tv:deportelibre:stream-01'));
  const urls = body.streams.map((stream) => stream.url);

  assert.ok(urls.some((url) => url.includes('92vhj8.39564828.net:8443/hls/2twhx.m3u8')));
  assert.ok(urls.some((url) => url.includes('py3hsjj2.04334746.net:8443/hls/2twhx.m3u8')));
  assert.ok(urls.every((url) => !url.includes('&amp;')), 'HTML entities must be decoded');
});

test('orders fresh tokens before expired ones and keeps the option labels', async () => {
  const { body } = await runScraper(live('tv:deportelibre:stream-01'));
  const labels = body.streams.map((stream) => stream.name);

  assert.equal(labels[0], 'ESPN · Opcion 1');
  assert.ok(
    labels[labels.length - 1].includes('token caducado'),
    `expired token must come last, got ${labels.join(' | ')}`,
  );
});

test('skips options whose player is offline', async () => {
  const { body } = await runScraper(live('tv:deportelibre:stream-01'));
  assert.ok(body.streams.every((stream) => !stream.name.includes('Opcion 3')));
});

test('accepts stream ids, slugs, site urls and free text', async () => {
  const cases = [
    ['stream-387', 'ESPN Premium'],
    ['deportelibre:stream-387', 'ESPN Premium'],
    ['tv:deportelibre:espn-premium', 'ESPN Premium'],
    [`${base}/total/stream-387.php`, 'ESPN Premium'],
    ['ESPN Premium', 'ESPN Premium'],
    ['liga 1 max', 'Liga 1 Max'],
  ];

  for (const [id, expected] of cases) {
    const { body } = await runScraper(live(id));
    assert.equal(body.meta.channel, expected, `id "${id}" should resolve to ${expected}`);
  }
});

test('degrades to an empty result when no player exposes a stream', async () => {
  const { code, body } = await runScraper(live('tv:deportelibre:stream-012'));
  assert.equal(code, 0, 'contract: exit 0 with {"streams": []}');
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'no_streams');
});

test('unknown channels do not throw', async () => {
  const { code, body } = await runScraper(live('tv:deportelibre:canal-que-no-existe'));
  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.ok(['channel_unavailable', 'no_streams'].includes(body.meta.reason));
});

test('refuses non-live payloads', async () => {
  const { body } = await runScraper({ type: 'movie', id: 'tt0133093', isLive: false });
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'not_live');
});

test('--list prints channel ids usable by Stremio', async () => {
  const output = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list'], {
      env: { ...process.env, DEPORTELIBRE_BASE: base, DEPORTELIBRE_INDEX_CACHE: INDEX_CACHE },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  assert.match(output, /tv:deportelibre:stream-01/);
  assert.match(output, /tv:deportelibre:stream-387/);
  assert.match(output, /ESPN Premium/);
  assert.ok(!output.includes('otro.example'), 'only channels from this site are indexed');
});

test('caches the channel catalogue on disk', async () => {
  await runScraper(live('ESPN'), { DEPORTELIBRE_INDEX_TTL_SECONDS: '600' });
  requested.length = 0;
  await runScraper(live('ESPN Premium'), { DEPORTELIBRE_INDEX_TTL_SECONDS: '600' });
  assert.ok(!requested.includes('/stream/'), `catalogue should not be re-fetched: ${requested.join(',')}`);
});
