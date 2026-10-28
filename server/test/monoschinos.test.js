/**
 * test/monoschinos.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/monoschinos.py` provider.
 *
 * A local HTTP fixture reproduces the site's real shape:
 *
 *   /ver/one-piece-episodio-1     episode page: one <button data-player="…"> per server
 *   /reproductor?url=<token>      returns `atob("<base64 embed>")`
 *   /ext/voe                      player with `sources: [{file: …m3u8}]`
 *   /ext/dood                     player whose .m3u8 only appears after unpacking
 *   /ext/offline                  404
 *   /buscar?q=…                   search results → /anime/<slug>
 *
 * so the player indirection, the base64 payloads, the download-server filtering
 * and the fallbacks are validated without external network access.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/monoschinos.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

const b64 = (value) => Buffer.from(value, 'utf8').toString('base64');

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                   */
/* -------------------------------------------------------------------------- */

const packer = (payload, table) =>
  `eval(function(p,a,c,k,e,d){e=function(c){return c};while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+c+'\\\\b','g'),k[c])}}return p}('${payload}',${table.length},${table.length},'${table.join('|')}'.split('|'),0,{}))`;

const PACKED_TABLE = ['var', 'src', 'https', 'hls', 'player', 'Dood'];

/* `data-player` real es un token opaco que el sitio canjea en /reproductor.
 * El último botón trae ya la URL para cubrir ese atajo. */
const episodePage = (base) => `<html>
<head><title>One piece Episodio 1 Sub Español - MonosChinos</title></head><body>
<h1>Ver One piece - 1</h1>
<div class="servers">
  <button class="btn btn-sm" data-player="tok-voe">voe</button>
  <button class="btn btn-sm" data-player="tok-dood">doodstream</button>
  <button class="btn btn-sm" data-player="tok-mega">mega</button>
  <button class="btn btn-sm" data-player="tok-offline">filemoon</button>
  <button class="btn btn-sm" data-player="${base}/ext/direct">mp4upload</button>
</div>
</body></html>`;

/* Tokens → embed final (lo que devuelve el reproductor del sitio). */
const playerTokens = (base) => ({
  'tok-voe': `${base}/ext/voe`,
  'tok-dood': `${base}/ext/dood`,
  'tok-mega': 'https://mega.nz/file/abc',
  'tok-offline': `${base}/ext/offline`,
});

const playerShell = (embed) =>
  `<html><body><script type="text/javascript">window.location = atob("${b64(embed)}");</script></body></html>`;

const sourcesPage = (url) =>
  `<html><body><script>player = new Playerjs({file: "${url}"});</script></body></html>`;

const packedPage = (url) =>
  `<html><body><div id="v"></div><script>${packer(
    `0 1="2://cdn.example/dood/index.m3u8?s=tok";3=new 4.Player({source:1});`,
    PACKED_TABLE,
  )}</script></body></html>`;

const searchPage = (base, hits) => `<html><body><section><ul>
${hits.map(([slug, title]) => `<li><article>
  <a href="${base}/anime/${slug}"><div class="tarjeta"><img src="${base}/img/${slug}.jpg"></div>
  <h3 class="fs-6">${title}</h3></a>
</article></li>`).join('')}
</ul></section></body></html>`;

const homePage = (base) => `<html><body><section><ul>
  <li><article><a href="${base}/ver/one-piece-episodio-1"><h3>One piece</h3>
  <span class="text-muted">Episodio 1</span></a></article></li>
  <li><article><a href="${base}/ver/solo-leveling-episodio-13"><h3>Solo Leveling</h3>
  <span class="text-muted">Episodio 13</span></a></article></li>
</ul></section></body></html>`;

let server;
let base;
let routes;
const requested = [];

function routesFor(baseUrl) {
  return {
    '/': homePage(baseUrl),
    '/ver/one-piece-episodio-1': episodePage(baseUrl),
    '/ver/one-piece-episodio-2': '<html><body><h1>Ver One piece - 2</h1><p>Sin servidores</p></body></html>',
    '/buscar': searchPage(baseUrl, [['one-piece-sub-espanol', 'One piece']]),

    '/ext/voe': sourcesPage('http://cdn.example/voe/index.m3u8?token=voe'),
    '/ext/dood': packedPage('http://cdn.example/dood/index.m3u8'),
    '/ext/direct': sourcesPage('http://cdn.example/direct/index.m3u8'),
  };
}

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname + (url.search || '');
    requested.push(key);

    /* El reproductor del sitio: /reproductor?url=<token> → atob(...) */
    if (url.pathname === '/reproductor') {
      const token = url.searchParams.get('url') || '';
      const embed = playerTokens(base)[token] || '';
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(playerShell(embed));
      return;
    }

    const body = routes[key] ?? routes[url.pathname];
    if (body === undefined) {
      res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' }).end('<h1>404</h1>');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' }).end(body);
  });
  server.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
  routes = routesFor(base);
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
      env: { ...process.env, MONOSCHINOS_BASE: base, ...extraEnv },
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

const ask = (id) => ({ type: 'anime', id, provider: 'monoschinos', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('data-player buttons go through /reproductor and become streams', async () => {
  const { code, body, stderr } = await runScraper(ask('monoschinos:one-piece:1'));

  assert.equal(code, 0, stderr);
  assert.equal(body.meta.title, 'One piece');
  assert.equal(body.meta.episode, 1);
  assert.deepEqual(body.meta.servers, ['Voe', 'Doodstream', 'Filemoon', 'Mp4upload']);

  const urls = body.streams.map((stream) => stream.url);
  assert.ok(urls.includes('http://cdn.example/voe/index.m3u8?token=voe'), `voe: ${urls}`);
  assert.ok(urls.includes('http://cdn.example/direct/index.m3u8'), `plain data-player: ${urls}`);
  assert.ok(
    urls.some((url) => url.includes('cdn.example/dood/index.m3u8')),
    `packed player must be unpacked: ${urls}`,
  );
  assert.ok(
    requested.some((key) => key.startsWith('/reproductor?url=')),
    'the site player endpoint must be used',
  );
});

test('stream labels include the site, the episode and the server', async () => {
  const { body } = await runScraper(ask('monoschinos:one-piece:1'));

  assert.equal(body.streams[0].name, 'One piece · Ep. 1');
  assert.match(body.streams[0].title, /^MonosChinos · One piece · Ep\. 1 · Voe$/);
  assert.equal(body.streams[0].isLive, false);
});

test('download servers are filtered before resolving', async () => {
  const { body } = await runScraper(ask('monoschinos:one-piece:1'));

  assert.ok(!body.streams.some((stream) => stream.url.includes('mega.nz')));
  assert.ok(!body.meta.servers.includes('Mega'));
  assert.ok(body.meta.unresolved.includes('Filemoon'), 'the 404 embed is reported');
});

test('accepts the full anime slug, season:episode and url ids', async () => {
  for (const id of [
    'monoschinos:one-piece:1:1',
    'anime:monoschinos:one-piece:1',
    `${base}/ver/one-piece-episodio-1`,
  ]) {
    const { body } = await runScraper(ask(id));
    assert.equal(body.meta.episode, 1, `id "${id}"`);
    assert.ok(body.streams.length >= 1, `id "${id}" should resolve streams`);
  }
});

test('uses the site search when the slug does not exist', async () => {
  const { body } = await runScraper(ask('monoschinos:one:1'));

  assert.equal(body.meta.resolved_from_search, 'one-piece-sub-espanol');
  assert.equal(body.meta.page, `${base}/ver/one-piece-sub-espanol-episodio-1`);
  assert.deepEqual(body.streams, [], 'the resolved slug has no fixture episode page');
  assert.equal(body.meta.reason, 'episode_unavailable');
});

test('an episode without server buttons degrades to an empty result', async () => {
  const { code, body } = await runScraper(ask('monoschinos:one-piece:2'));
  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'no_servers');
});

test('unreachable site returns [] with a reason instead of crashing', async () => {
  const { code, body } = await runScraper(ask('monoschinos:one-piece:1'), {
    MONOSCHINOS_BASE: 'http://127.0.0.1:1',
  });
  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.ok(['episode_unavailable', 'no_servers'].includes(body.meta.reason));
});

test('refuses live payloads (this is a VOD provider)', async () => {
  const { body } = await runScraper({ type: 'tv', id: 'tv:monoschinos:one-piece:1', isLive: true });
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'not_vod');
});

test('--list prints ids for the latest episodes', async () => {
  const output = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--json'], {
      env: { ...process.env, MONOSCHINOS_BASE: base },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  const payload = JSON.parse(output);
  const ids = payload.items.map((item) => item.id);
  assert.ok(ids.includes('monoschinos:one-piece:1'), `ids: ${ids}`);
  assert.ok(ids.includes('monoschinos:solo-leveling:13'));
});

test('--list --query uses the site search', async () => {
  const output = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--query', 'one piece', '--json'], {
      env: { ...process.env, MONOSCHINOS_BASE: base },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  const payload = JSON.parse(output);
  assert.equal(payload.items[0].slug, 'one-piece-sub-espanol');
  assert.equal(payload.items[0].id, 'monoschinos:one-piece-sub-espanol:1');
});
