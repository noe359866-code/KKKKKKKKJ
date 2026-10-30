/**
 * test/animeflv.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/animeflv.py` provider.
 *
 * A local HTTP fixture reproduces the site's real shape:
 *
 *   /ver/one-piece-1180        episode page with `var videos = [...]` (base64 embeds)
 *   /ext/streamtape            Streamtape: `ideoooolink` + token  → .mp4 url
 *   /ext/streamwish            Streamwish: `sources: [{file: …m3u8}]`
 *   /ext/filemoon              Filemoon:  .m3u8 inside a packed eval()
 *   /ext/offline               404, nothing to extract
 *   /browse?q=…                search results → /anime/<slug>
 *
 * so the base64 server list, the per-hoster extractors, the failover order and
 * the fallbacks are validated without any external network access.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SCRAPER = path.resolve(__dirname, '../scrapers/animeflv.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

const b64 = (value) => Buffer.from(value, 'utf8').toString('base64');

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                   */
/* -------------------------------------------------------------------------- */

const packer = (payload, table) =>
  `eval(function(p,a,c,k,e,d){e=function(c){return c};while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+c+'\\\\b','g'),k[c])}}return p}('${payload}',${table.length},${table.length},'${table.join('|')}'.split('|'),0,{}))`;

const PLAYER_TABLE = ['var', 'src', 'https', 'player', 'Clappr', 'hls'];

/* El reproductor de Filemoon esconde el .m3u8 en un eval() empaquetado. */
const packedPlayer = (url) =>
  `<html><body><div id="player"></div><script>
${packer(`0 1="2://cdn.example/fm/index.m3u8?s=tok";3=new 4.Player({source:1});`, PLAYER_TABLE)}
</script></body></html>`;

const streamwishPage = (url) =>
  `<html><body><script>window.player = {sources: [{file: "${url}", label: "HD"}]};</script></body></html>`;

const streamtapePage = (fragment) =>
  `<html><body><div id="robotlink" style="display:none">//streamtape.com/get_video?id=abc123&expires=1&ip=1.2.3.4</div>
<span id='ideoooolink' style='display:none'>${fragment}</span>
<script>var token = 'tok3n';</script></body></html>`;

/* La página del episodio: `var videos` con el embed en base64. Mezcla a
 * propósito claves sin comillas y comillas simples (así lo publica el sitio). */
const episodePage = (base, servers) => `<html>
<head><title>One Piece 1180 Sub Español - AnimeFLV</title></head><body>
<h1>One Piece</h1>
<div id="player"></div>
<script>
var videos = [${servers.map((server, index) =>
  `{server: '${server.name}', title: 'One Piece 1180', code: '${b64(server.embed ?? `${base}${server.path}`)}', ads: ${index}}`,
).join(', ')}];
var episodes = [[1179, "one-piece-1179"], [1180, "one-piece-1180"]];
</script>
</body></html>`;

const searchPage = (base, hits) => `<html><body><ul class="ListAnimes">
${hits.map(([slug, title]) => `<li><article>
  <h3 class="Title">${title}</h3>
  <a href="/anime/${slug}">${title}</a>
</article></li>`).join('')}
</ul></body></html>`;

const homePage = (base) => `<html><body><ul class="ListEpisodios">
  <li><a href="/ver/one-piece-1180"><span class="Capi">Episodio 1180</span><strong class="Title">One Piece</strong></a></li>
  <li><a href="/ver/jujutsu-kaisen-47"><span class="Capi">Episodio 47</span><strong class="Title">Jujutsu Kaisen</strong></a></li>
</ul></body></html>`;

let server;
let base;
const requested = [];

let routes;

function routesFor(baseUrl) {
  return {
    '/': homePage(baseUrl),
    '/ver/one-piece-1180': episodePage(baseUrl, [
      { name: 'stape', path: '/ext/streamtape' },
      { name: 'sw', path: '/ext/streamwish' },
      { name: 'filemoon', path: '/ext/filemoon' },
      { name: 'mega', embed: 'https://mega.nz/file/abc123' },
      { name: 'hqq', path: '/ext/offline' },
    ]),
    /* Un episodio "que existe" pero sin servidores: la ficha tiene el enlace. */
    '/ver/one-piece-1179': '<html><body><h1>One Piece</h1><p>Sin servidores</p></body></html>',
    '/ver/one-piece-1179-latino':
      '<html><body><h1>One Piece</h1><div class="player"><iframe src="/ext/streamwish"></iframe></div></body></html>',
    /* Ficha del anime: lista el episodio 1179 en otra ruta, nada del 1176. */
    '/anime/one-piece': `<html><body><h1>One Piece</h1><ul class="ListEpisodios">
  <li><a href="/ver/one-piece-1179-latino"><span class="Capi">Episodio 1179</span></a></li>
  <li><a href="/ver/one-piece-1180"><span class="Capi">Episodio 1180</span></a></li>
</ul></body></html>`,
    /* Episodio sin servidores y sin enlace en la ficha. */
    '/ver/one-piece-1176': '<html><body><h1>One Piece</h1><p>Sin servidores</p></body></html>',
    /* Segundo episodio, usado por el respaldo por búsqueda. */
    '/ver/one-piece-1': episodePage(baseUrl, [{ name: 'sw', path: '/ext/streamwish' }]),
    '/browse': searchPage(baseUrl, [['one-piece', 'One Piece']]),

    '/ext/streamtape': streamtapePage('//streamtape.com/get_video?id=abc123&expires=1&ip=1.2.3.4'),
    '/ext/streamwish': streamwishPage('http://cdn.example/sw/index.m3u8?token=abc'),
    '/ext/filemoon': packedPlayer('http://cdn.example/fm/index.m3u8'),
  };
}

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname + (url.search || '');
    requested.push(key);
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
      env: { ...process.env, ANIMEFLV_BASE: base, ...extraEnv },
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

const ask = (id, extra = {}) => ({ type: 'anime', id, provider: 'animeflv', timeoutMs: 8000, ...extra });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('episode id -> base64 server list -> one stream per resolvable server', async () => {
  const { code, body, stderr } = await runScraper(ask('animeflv:one-piece:1180'));

  assert.equal(code, 0, stderr);
  assert.equal(body.meta.title, 'One Piece');
  assert.equal(body.meta.episode, 1180);
  assert.deepEqual(body.meta.servers, ['Streamtape', 'Streamwish', 'Filemoon', 'Mega', 'HQQ']);

  const urls = body.streams.map((stream) => stream.url);
  assert.ok(urls.includes('http://cdn.example/sw/index.m3u8?token=abc'), `streamwish: ${urls}`);
  assert.ok(
    urls.some((url) => url.includes('cdn.example/fm/index.m3u8')),
    `the packed player must be unpacked: ${urls}`,
  );
  assert.ok(
    urls.some((url) => url.startsWith('https://streamtape.com/get_video?id=abc123') && url.includes('token=tok3n')),
    `streamtape url must be reassembled with its token: ${urls}`,
  );
});

test('streams keep the site order and carry the episode in their labels', async () => {
  const { body } = await runScraper(ask('animeflv:one-piece:1180'));

  assert.equal(body.streams[0].name, 'One Piece · Ep. 1180');
  assert.match(body.streams[0].title, /^AnimeFLV · One Piece · Ep\. 1180 · Streamtape$/);
  assert.equal(body.streams[0].isLive, false);
  assert.equal(body.streams[0].behaviorHints.notWebReady, false);
  assert.ok(body.streams[0].referer.startsWith(`${base}/ext/`), 'referer must be the embed page');
});

test('download-only servers are skipped and reported', async () => {
  const { body } = await runScraper(ask('animeflv:one-piece:1180'));

  assert.ok(!body.streams.some((stream) => stream.url.includes('mega.nz')));
  assert.ok(body.meta.unresolved.includes('Mega'), `unresolved: ${body.meta.unresolved}`);
  assert.ok(body.meta.unresolved.includes('HQQ'), 'the 404 embed is reported as unresolved');
});

test('accepts season:episode, url and free-text ids', async () => {
  const cases = [
    ['animeflv:one-piece:1:1180', 1180, 1],
    ['animeflv:one-piece', null, null],
    [`${base}/ver/one-piece-1180`, 1180, null],
  ];

  for (const [id, episode, season] of cases) {
    const { body } = await runScraper(ask(id));
    if (episode) assert.equal(body.meta.episode, episode, `id "${id}"`);
    if (season) assert.equal(body.meta.season, season, `id "${id}"`);
    assert.ok(body.streams.length >= 1, `id "${id}" should resolve a stream`);
  }
});

test('falls back to the site search when the slug is unknown', async () => {
  /* "one" no existe como slug: la búsqueda del sitio lo resuelve a one-piece. */
  const { body } = await runScraper(ask('animeflv:one:1'));

  assert.equal(body.meta.resolved_from_search, 'one-piece');
  assert.equal(body.meta.title, 'One Piece');
  assert.ok(body.streams.length >= 1);
});

test('an episode whose own page has no servers is rescued from the anime page', async () => {
  /* /ver/one-piece-1179 no trae botones; la ficha enlaza -latino, que sí. */
  const { code, body } = await runScraper(ask('animeflv:one-piece:1179'));

  assert.equal(code, 0);
  assert.equal(body.meta.servers_via, 'anime_page');
  assert.match(body.meta.servers_from, /one-piece-1179-latino$/);
  assert.ok(body.streams.length >= 1);
  assert.match(body.streams[0].url, /m3u8/);
});

test('an episode without servers and without a link degrades to an empty result', async () => {
  const { code, body } = await runScraper(ask('animeflv:one-piece:1176'));
  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'no_servers');
});

test('unreachable site returns [] with a reason instead of crashing', async () => {
  const { code, body } = await runScraper(ask('animeflv:one-piece:1180'), {
    ANIMEFLV_BASE: 'http://127.0.0.1:1',
  });
  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.ok(['episode_unavailable', 'no_servers'].includes(body.meta.reason));
});

test('refuses live payloads (this is a VOD provider)', async () => {
  const { body } = await runScraper({ type: 'tv', id: 'tv:animeflv:one-piece:1180', isLive: true });
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'not_vod');
});

test('--list prints ready-to-use ids for the latest episodes', async () => {
  const output = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--json'], {
      env: { ...process.env, ANIMEFLV_BASE: base },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  const payload = JSON.parse(output);
  const ids = payload.items.map((item) => item.id);
  assert.ok(ids.includes('animeflv:one-piece:1180'), `ids: ${ids}`);
  assert.ok(ids.includes('animeflv:jujutsu-kaisen:47'));
});

test('--list --query uses the site search', async () => {
  const output = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list', '--query', 'one piece', '--json'], {
      env: { ...process.env, ANIMEFLV_BASE: base },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  const payload = JSON.parse(output);
  assert.equal(payload.items[0].slug, 'one-piece');
  assert.equal(payload.items[0].id, 'animeflv:one-piece:1');
});
