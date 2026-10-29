/**
 * test/futbolibre.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for the real `scrapers/futbolibre.py` provider.
 *
 * A local HTTP fixture reproduces the site's real shape:
 *
 *   /                                  channel cards -> /en-vivo/<slug>
 *   /en-vivo/espn-1/                   page embedding the backend player
 *   /vivo/canal.php?stream=espnmx      player with the CDN url in its JS
 *   /sw3.html?get=<player>             intermediate redirect (tarjetarojita)
 *   /eventos/json/agenda123.json       match agenda: several signals per match
 *
 * so channel resolution, the agenda grouping, the iframe/redirect chain and
 * the token handling are validated without any external network access.
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
const SCRAPER = path.resolve(__dirname, '../scrapers/futbolibre.py');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';
const CACHE_PREFIX = `futbolibre-test-${process.pid}`;
const INDEX_CACHE = path.join(os.tmpdir(), `${CACHE_PREFIX}-index.json`);
const AGENDA_CACHE = path.join(os.tmpdir(), `${CACHE_PREFIX}-agenda.json`);

const FUTURE = Math.floor(Date.now() / 1000) + 7200;
const PAST = Math.floor(Date.now() / 1000) - 7200;

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                   */
/* -------------------------------------------------------------------------- */

const card = (slug, name) => `
  <a class="card" href="/en-vivo/${slug}">
    <img src="/img/channels/${slug}.webp" alt="Logo de ${name}">
    <strong>${name}</strong>
    <span>Deportes en vivo</span>
    <span>Ver Canal</span>
  </a>`;

const HOME = `<!doctype html><html><head><title>Futbol Libre - Pelota Libre TV</title></head><body>
  <h1>Futbol Libre en Vivo y en HD</h1>
  <section id="canales">
    <h2>Canales Deportivos México</h2>
    ${card('espn-1', 'ESPN 1')}
    ${card('tudn-mx', 'TUDN')}
    ${card('fox-sports-3', 'FOX Sports 3')}
    <a href="https://otro.example/en-vivo/ajeno">Ajeno</a>
    <a href="https://futbolibre.live/deportes/">Deportes</a>
  </section>
  <a href="https://discord.gg/NzKp39ggew">Únete a nuestro Discord</a>
</body></html>`;

/* Página del canal: el reproductor del backend vive en un enlace "Disabled"
 * (así lo publica el sitio) y, en algunos canales, además en un iframe. */
const channelPage = (heading, players) => `<!doctype html><html>
<head><title>${heading} en vivo por internet | Fútbol Libre TV</title></head><body>
  <img src="/img/channels/x.webp" alt="Logo de ${heading}">
  <h1>${heading} en vivo por internet</h1>
  <span>live</span>
  <ul>${players.map((src) => `<li><a href="${src}">Disabled</a></li>`).join('')}</ul>
  <a href="#">↻ Recargar canal</a>
  <p>${heading} es uno de los principales canales de televisión deportiva.</p>
</body></html>`;

const framePage = (src) => `<!doctype html><html><body>
  <div class="player"><iframe src="${src}" allowfullscreen></iframe></div>
</body></html>`;

/* Reproductor real: la URL del CDN vive en el JS, con el token del día y el
 * parámetro `ip` que el televisor no debe reenviar. */
const playerPage = (url) => `<!doctype html><html><body>
<div id="player"></div>
<script>
  var src = "${url.replace(/\//g, '\\/').replace(/&/g, '\\u0026')}";
  var player = new Clappr.Player({ source: src, parentId: "#player", autoPlay: true });
</script>
</body></html>`;

const OFFLINE = `<!doctype html><html><body>
  <div class="alert">Señal no disponible</div>
  <noscript>JavaScript disabled</noscript>
</body></html>`;

/* Agenda del backend: un partido, varias señales (mismo título repetido). */
const agenda = (base) => JSON.stringify([
  {
    category: 'futbol',
    link: `${base}/vivo/canales.php?stream=liga1max`,
    title: 'Liga 1: Cienciano vs Atlético Grau',
    time: '15:00',
    status: 'En vivo',
    language: 'Español',
    date: '2026-10-28',
  },
  {
    category: 'futbol',
    link: `${base}/sw3.html?get=${encodeURIComponent(`${base}/repro/ligaperu.html`)}`,
    title: 'Liga 1: Cienciano vs Atlético Grau',
    time: '15:00',
    status: 'En vivo',
    language: 'Español',
    date: '2026-10-28',
  },
  {
    category: 'futbol',
    link: `${base}/vivo/canales.php?stream=liga1max`,
    title: 'Liga 1: Cienciano vs Atlético Grau',
    time: '15:00',
    status: 'En vivo',
    language: 'Español',
    date: '2026-10-28',
  },
  {
    category: 'futbol',
    link: `${base}/vivo/canales.php?stream=tntchile_exp`,
    title: 'Primera División de Chile: Universidad Católica vs Audax Italiano',
    time: '18:30',
    status: 'En vivo',
    language: 'Español',
    date: '2026-10-28',
  },
  {
    category: 'futbol',
    link: `${base}/vivo/canales.php?stream=tntchile`,
    title: 'Primera División de Chile: Universidad Católica vs Audax Italiano',
    time: '18:30',
    status: 'En vivo',
    language: 'Español',
    date: '2026-10-28',
  },
]);

let server;
let base;
const requested = [];

function routesFor(baseUrl) {
  const enc = encodeURIComponent;
  return {
    '/': HOME,
    '/deportes/': HOME,

    '/en-vivo/espn-1/': channelPage('ESPN 1 México', [`${baseUrl}/vivo/canal.php?stream=espnmx#`]),
    '/en-vivo/tudn-mx/': channelPage('TUDN México', [
      `${baseUrl}/vivo/canal.php?stream=tudn_mx`,
      `${baseUrl}/vivo/canales.php?stream=tudn_mx_op2`,
    ]),
    '/en-vivo/fox-sports-3/': framePage(
      `${baseUrl}/sw3.html?get=${enc(`${baseUrl}/repro/fox.html`)}`,
    ),

    /* Un canal con token vivo (y `ip`, que debe descartarse). */
    '/vivo/canal.php?stream=espnmx': playerPage(
      `https://la18hd.su/hls/espnmx/index.m3u8?s=EspnTokenAbCdEf&e=${FUTURE}&ip=10.0.0.7`,
    ),
    '/vivo/canal.php?stream=tudn_mx': playerPage(
      `https://la18hd.su/hls/tudn/index.m3u8?s=TudnTokenAbCdEf&e=${FUTURE}&ip=10.0.0.7`,
    ),
    /* Segunda señal del mismo canal, en otro backend. */
    '/vivo/canales.php?stream=tudn_mx_op2': playerPage(
      `https://cdn2.example.org/live/tudn/index.m3u8?s=TudnOp2TokenGhIj&e=${FUTURE}`,
    ),

    '/repro/fox.html': playerPage(
      `https://cdn3.example.net/live/fox3/index.m3u8?s=FoxTokenKlMnOp&e=${FUTURE}`,
    ),

    '/eventos/json/agenda123.json': null, // relleno abajo (necesita baseUrl)

    '/vivo/canales.php?stream=liga1max': playerPage(
      `https://cdn4.example.net/live/liga1max/index.m3u8?s=LigaTokenQrStUv&e=${FUTURE}`,
    ),
    '/repro/ligaperu.html': playerPage(
      `https://cdn5.example.org/live/ligaperu/index.m3u8?s=PeruTokenWxYz&e=${FUTURE}`,
    ),
    '/vivo/canales.php?stream=tntchile_exp': playerPage(
      `https://cdn6.example.net/live/tntchile/index.m3u8?s=OldToken000000&e=${PAST}`,
    ),
    '/vivo/canales.php?stream=tntchile': playerPage(
      `https://cdn6.example.net/live/tntchile/index.m3u8?s=NewToken111111&e=${FUTURE}`,
    ),
  };
}

let routes;

before(async () => {
  server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname === '/sw3.html' ? '/sw3.html' : url.pathname + url.search;
    requested.push(key);

    if (url.pathname === '/sw3.html') {
      /* Salto intermedio del backend: el reproductor real viene en `get`. */
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
        .end(framePage(url.searchParams.get('get')));
      return;
    }

    if (url.pathname === '/eventos/json/agenda123.json') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' }).end(agenda(base));
      return;
    }

    const body = routes[key] ?? routes[url.pathname.replace(/\/$/, '') + '/'];
    if (body === undefined) {
      res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' }).end('<h1>404: Not Found</h1>');
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
  await rm(INDEX_CACHE, { force: true });
  await rm(AGENDA_CACHE, { force: true });
});

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

function runScraper(payload, extraEnv = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER], {
      env: {
        ...process.env,
        FUTBOLIBRE_BASE: base,
        FUTBOLIBRE_AGENDA_URL: `${base}/eventos/json/agenda123.json`,
        FUTBOLIBRE_INDEX_CACHE: INDEX_CACHE,
        FUTBOLIBRE_AGENDA_CACHE: AGENDA_CACHE,
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

const live = (id) => ({ type: 'tv', id, isLive: true, provider: 'futbolibre', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                      */
/* -------------------------------------------------------------------------- */

test('channel id -> site page -> backend player -> CDN url', async () => {
  const { code, body, stderr } = await runScraper(live('tv:futbolibre:espn-1'));

  assert.equal(code, 0, stderr);
  assert.equal(body.meta.channel, 'ESPN 1');
  assert.equal(body.meta.reason, undefined, `unexpected reason: ${JSON.stringify(body.meta)}`);

  const [primary] = body.streams;
  assert.ok(primary, 'must return one signal');
  assert.equal(
    primary.url,
    `https://la18hd.su/hls/espnmx/index.m3u8?s=EspnTokenAbCdEf&e=${FUTURE}`,
    'the `ip` parameter must be dropped and escaped slashes resolved',
  );
  assert.equal(primary.isLive, true);
  assert.equal(primary.behaviorHints.notWebReady, false);
  assert.equal(primary.name, 'ESPN 1 · Señal 1');
  assert.match(primary.title, /^Fútbol Libre · ESPN 1 · Señal 1$/);
  assert.ok(primary.referer.startsWith(`${base}/vivo/`), 'referer must be the player page');
});

test('a channel with two signals returns both, in order, for failover', async () => {
  const { body } = await runScraper(live('futbolibre:tudn-mx'));
  const urls = body.streams.map((stream) => stream.url);

  assert.equal(body.streams.length, 2);
  assert.match(urls[0], /la18hd\.su\/hls\/tudn\/index\.m3u8/);
  assert.match(urls[1], /cdn2\.example\.org\/live\/tudn\/index\.m3u8/);
  assert.deepEqual(body.streams.map((stream) => stream.name), [
    'TUDN · Señal 1',
    'TUDN · Señal 2 · cdn2.example.org',
  ]);
});

test('follows the sw3.html?get= redirect chain inside an iframe', async () => {
  const { body } = await runScraper(live('tv:futbolibre:fox-sports-3'));

  assert.equal(body.meta.channel, 'FOX Sports 3');
  assert.equal(body.streams.length, 1);
  assert.match(body.streams[0].url, /cdn3\.example\.net\/live\/fox3\/index\.m3u8/);
});

test('a match id resolves through the agenda and merges its signals', async () => {
  const { body } = await runScraper(live('tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau'));

  assert.equal(body.meta.source, 'evento');
  assert.equal(body.meta.event, 'Liga 1: Cienciano vs Atlético Grau');
  assert.equal(body.meta.category, 'futbol');
  assert.equal(body.meta.time, '15:00');

  const urls = body.streams.map((stream) => stream.url);
  assert.equal(urls.length, 2, 'the duplicated link must not duplicate the stream');
  assert.ok(urls.some((url) => url.includes('liga1max/index.m3u8')));
  assert.ok(urls.some((url) => url.includes('ligaperu/index.m3u8')));
});

test('free text finds the match (no prefix, accents ignored)', async () => {
  const { body } = await runScraper(live('Cienciano'));

  assert.equal(body.meta.source, 'evento');
  assert.match(body.meta.event, /Cienciano vs Atl/);
  assert.ok(body.streams.length >= 1);
});

test('expired tokens are returned last and flagged', async () => {
  const { body } = await runScraper(live('tv:futbolibre:evento:primera-division-de-chile-universidad-catolica-vs-audax-italiano'));

  const names = body.streams.map((stream) => stream.name);
  assert.equal(names.length, 2);
  assert.ok(!names[0].includes('caducado'), `fresh signal first, got ${names[0]}`);
  assert.match(names[names.length - 1], /token caducado/);
  assert.match(body.streams[0].url, /NewToken111111/, 'the live token wins');
});

test('unknown content degrades to an empty result without crashing', async () => {
  for (const id of ['tv:futbolibre:canal-que-no-existe', 'tv:futbolibre:evento:partido-inventado']) {
    const { code, body } = await runScraper(live(id));
    assert.equal(code, 0, 'contract: exit 0 with {"streams": []}');
    assert.deepEqual(body.streams, []);
    assert.equal(body.meta.reason, 'not_found');
  }
});

test('refuses non-live payloads', async () => {
  const { body } = await runScraper({ type: 'movie', id: 'tt0133093', isLive: false });
  assert.deepEqual(body.streams, []);
  assert.equal(body.meta.reason, 'not_live');
});

test('survives an unreachable agenda (channels still resolve)', async () => {
  const env = {
    FUTBOLIBRE_AGENDA_URL: 'http://127.0.0.1:1/agenda.json',
    FUTBOLIBRE_AGENDA_TTL_SECONDS: '0',
    FUTBOLIBRE_INDEX_TTL_SECONDS: '0',
  };

  const channel = await runScraper(live('tv:futbolibre:espn-1'), env);
  assert.equal(channel.code, 0);
  assert.equal(channel.body.streams.length, 1, 'the player chain does not depend on the agenda');

  const event = await runScraper(live('tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau'), env);
  assert.equal(event.code, 0);
  assert.deepEqual(event.body.streams, []);
});

test('--list prints ids usable by Stremio (channels and matches)', async () => {
  const output = await new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [SCRAPER, '--list'], {
      env: {
        ...process.env,
        FUTBOLIBRE_BASE: base,
        FUTBOLIBRE_AGENDA_URL: `${base}/eventos/json/agenda123.json`,
        FUTBOLIBRE_INDEX_CACHE: INDEX_CACHE,
        FUTBOLIBRE_AGENDA_CACHE: AGENDA_CACHE,
      },
    });
    let stdout = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.on('error', reject);
    child.on('close', () => resolve(stdout));
  });

  assert.match(output, /tv:futbolibre:espn-1/);
  assert.match(output, /tv:futbolibre:tudn-mx/);
  assert.match(output, /tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau/);
  assert.match(output, /Canales deportivos|canales/);
  assert.ok(!output.includes('ajeno'), 'only channels from this site are indexed');
  assert.ok(!output.includes('deportes'), 'the category hub is not a channel');
});
