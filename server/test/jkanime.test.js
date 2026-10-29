/**
 * test/jkanime.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/jkanime.py` provider.
 *
 * A local HTTP fixture reproduces the site's real shape:
 *
 *   /one-piece/1180/        episode page: `var servers = [{server, remote(b64)}]`
 *                          plus a standalone `video[0] = '<iframe src=…>'`
 *   /ext/voe                VOE-like page: `window.location.href` → player
 *   /ext/player             the player itself with `sources: [{file: …m3u8}]`
 *   /ext/mp4upload          `<script>… src: "….mp4" …</script>`
 *   /buscar/<q>/            search results → /<slug>/
 *
 * so the base64 servers, the language variants, the download links that must be
 * ignored and the redirect chain are validated without external network access.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/jkanime.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

const b64 = (value) => Buffer.from(value, 'utf8').toString('base64');

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                   */
/* -------------------------------------------------------------------------- */

const episodePage = (base) => `<html>
<head><title>One Piece 1180 Sub Español Online gratis — JkAnime</title></head><body>
<h1>Episodio 1180 Japones - One Piece</h1>
<div id="player"></div>
<script>
var remote = "${base}/d";
var servers = [
  {server: 'Desu', remote: '${b64(`${base}/ext/voe`)}', slug: 'abc', lang: 'sub'},
  {server: 'Mp4upload', remote: '${b64(`${base}/ext/mp4upload`)}', slug: 'def', lang: 'sub'},
  {server: 'Mega', remote: '${b64('https://mega.nz/file/xyz')}', slug: 'ghi', lang: 'sub'},
  {server: 'Streamwish', remote: '${b64(`${base}/ext/offline`)}', slug: 'jkl', lang: 'lat'}
];
video[0] = '<iframe src="${base}/ext/jkplayer" frameborder="0"></iframe>';
</script>
<ul>
  <li><a href="${base}/d/abc">Descargar HD</a></li>
  <li><a href="${base}/one-piece/1179/">Episodio 1179</a></li>
  <li><a href="${base}/one-piece/1180/">Episodio 1180</a></li>
</ul>
</body></html>`;

const redirectPage = (target) =>
  `<html><body><script>window.location.href = "${target}";</script></body></html>`;

const sourcesPage = (url) =>
  `<html><body><script>var player = {sources: [{file: "${url}"}]};</script></body></html>`;

const mp4Page = (url) =>
  `<html><body><script>$(document).ready(function(){ jwplayer("v").setup({ file: "${url}" }); });</script></body></html>`;

const searchPage = (base, hits) => `<html><body><div class="page_directorio">
${hits.map(([slug, title]) => `<div class="custom_item2"><div class="custom_thumb2">
  <h5 class="card-title"><a href="${base}/${slug}/">${title}</a></h5>
  <img src="${base}/img/${slug}.jpg">
</div></div>`).join('')}
<a href="${base}/directorio/2">Siguiente</a>
</div></body></html>`;

const homePage = (base) => `<html><body><section class="last_episodes">
  <a href="${base}/one-piece/1180/">Episodio 1180 One Piece</a>
  <a href="${base}/solo-leveling/13/">Episodio 13 Solo Leveling</a>
</section></body></html>`;

let server;
let base;
let routes;

function routesFor(baseUrl) {
  return {
    '/': homePage(baseUrl),
    '/one-piece/1180/': episodePage(baseUrl),
    '/one-piece/1/': `<html><body><h1>Episodio 1 Japones - One Piece</h1>
<script>var servers = [{server: 'Desu', remote: '${b64(`${baseUrl}/ext/voe`)}', lang: 'sub'}];</script>
</body></html>`,
    '/one-piece/1179/': '<html><body><h1>Episodio 1179 - One Piece</h1><p>Sin servidores</p></body></html>',
    '/buscar/one/': searchPage(baseUrl, [['one-piece', 'One Piece']]),

    /* VOE: primero un redirect por JavaScript, después el player real. */
    '/ext/voe': redirectPage(`${baseUrl}/ext/player`),
    '/ext/player': sourcesPage('http://cdn.example/voe/index.m3u8?token=voe'),
    '/ext/mp4upload': mp4Page('http://cdn.example/mp4upload/video.mp4'),
    '/ext/jkplayer': sourcesPage('http://cdn.example/jk/index.m3u8'),
  };
}

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname + (url.search || '');
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
      env: { ...process.env, JKANIME_BASE: base, ...extraEnv },
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

const ask = (id) => ({ type: 'anime', id, provider: 'jkanime', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('episode page -> base64 servers -> resolved streams', async () => {
  const { code, body, stderr } = await runScraper(ask('jkanime:one-piece:1180'));

  assert.equal(code, 0, stderr);
  assert.equal(body.meta.title, 'One Piece');
  assert.equal(body.meta.episode, 1180);
  assert.deepEqual(body.meta.servers, ['Desu', 'Mp4upload', 'Mega', 'Streamwish', 'JKPlayer']);

  const urls = body.streams.map((stream) => stream.url);
  assert.ok(urls.includes('http://cdn.example/voe/index.m3u8?token=voe'), `voe: ${urls}`);
  assert.ok(urls.includes('http://cdn.example/mp4upload/video.mp4'), `mp4upload: ${urls}`);
  assert.ok(urls.includes('http://cdn.example/jk/index.m3u8'), `jkplayer iframe: ${urls}`);
});

test('follows the JavaScript redirect used by VOE', async () => {
  const { body } = await runScraper(ask('jkanime:one-piece:1180'));
  const voe = body.streams.find((stream) => stream.url.includes('cdn.example/voe'));
  assert.ok(voe, 'the redirect target must be resolved');
  assert.equal(voe.isLive, false);
});

test('download links (remote + /d/<slug>) are never turned into streams', async () => {
  const { body } = await runScraper(ask('jkanime:one-piece:1180'));

  assert.ok(!body.streams.some((stream) => stream.url.includes('/d/abc')));
  assert.ok(!body.streams.some((stream) => stream.url.includes('mega.nz')));
  assert.ok(body.meta.unresolved.includes('Mega'));
  assert.ok(body.meta.unresolved.includes('Streamwish'), 'the 404 embed is reported');
});

test('labels carry the site, the episode and the server', async () => {
  const { body } = await runScraper(ask('jkanime:one-piece:1180'));

  assert.equal(body.streams[0].name, 'One Piece · Ep. 1180');
  assert.match(body.streams[0].title, /^JkAnime · One Piece · Ep\. 1180 · Desu$/);
  assert.ok(body.streams[0].referer.startsWith(base), 'referer must point at the site');
});

test('accepts season:episode, url and provider prefixes', async () => {
  for (const id of [
    'jkanime:one-piece:1:1180',
    'anime:jkanime:one-piece:1180',
    `${base}/one-piece/1180/`,
  ]) {
    const { body } = await runScraper(ask(id));
    assert.equal(body.meta.episode, 1180, `id "${id}"`);
    assert.ok(body.streams.length >= 1, `id "${id}" should resolve streams`);
  }
});

test('uses the site search when the slug does not exist', async () => {
  const { body } = await runScraper(ask('jkanime:one:1'));

  assert.equal(body.meta.resolved_from_search, 'one-piece');
  assert.equal(body.meta.page, `${base}/one-piece/1/`);
  assert.ok(body.streams.length >= 1);
});

test('an episode without servers degrades to an empty result', async () => {
  const { code, body } = await runScraper(ask('jkanime:one-piece:1179'));
  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'no_servers');
});

test('unreachable site returns [] with a reason instead of crashing', async () => {
  const { code, body } = await runScraper(ask('jkanime:one-piece:1180'), {
    JKANIME_BASE: 'http://127.0.0.1:1',
  });
  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.ok(['episode_unavailable', 'no_servers'].includes(body.meta.reason));
});

test('refuses live payloads (this is a VOD provider)', async () => {
  const { body } = await runScraper({ type: 'tv', id: 'tv:jkanime:one-piece:1180', isLive: true });
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'not_vod');
});

test('--list prints ids for the latest episodes and for searches', async () => {
  const run = (args) => new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, ...args], {
      env: { ...process.env, JKANIME_BASE: base },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  const recent = JSON.parse(await run(['--list', '--json']));
  const ids = recent.items.map((item) => item.id);
  assert.ok(ids.includes('jkanime:one-piece:1180'), `ids: ${ids}`);
  assert.ok(ids.includes('jkanime:solo-leveling:13'));

  const search = JSON.parse(await run(['--list', '--query', 'one', '--json']));
  assert.equal(search.items[0].id, 'jkanime:one-piece:1');
});
