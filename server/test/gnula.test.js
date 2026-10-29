/**
 * test/gnula.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for `scrapers/gnula.py`.
 *
 * El fixture reproduce la cadena de Gnula tal cual:
 *
 *   /ver/street-fighter/                       `window.GNRD={pid, tok, k}`
 *   /wp-json/gnrd/v1/player?id=…&t=…           `{"p": "<carga ofuscada>"}`
 *   /ext/streamwish, /ext/filemoon             servidores resolubles
 *
 * y `obfuscate()` aplica la misma transformación que el sitio, al revés que el
 * scraper (ROT13 ← base64 ← −3 ← invertir ← base64 ← JSON), así que el
 * desofuscado se prueba de verdad.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import {
  hasUrl,
  reasonOf,
  runCli,
  runScraper,
  startFixture,
  streamwishPage,
  urlsOf,
} from '../testsupport/pelis.js';

const SCRIPT = 'gnula.py';
const CLEAN = { GNULA_BASE: '', GNULA_MIRRORS: '', TMDB_API_KEY: '' };

/* -------------------------------------------------------------------------- */
/* Ofuscado inverso                                                          */
/* -------------------------------------------------------------------------- */

const rot13 = (text) =>
  text.replace(/[a-z]/gi, (char) => {
    const code = char.charCodeAt(0);
    const base = code <= 90 ? 65 : 97;
    return String.fromCharCode(((code - base + 13) % 26) + base);
  });

/** JSON → el `p` que publica el sitio. */
const obfuscate = (value) => {
  let text = Buffer.from(JSON.stringify(value), 'utf8').toString('base64');
  text = [...text].reverse().join('');
  text = [...text].map((char) => String.fromCharCode(char.charCodeAt(0) + 3)).join('');
  text = Buffer.from(text, 'utf8').toString('base64');
  return rot13(text);
};

/* -------------------------------------------------------------------------- */
/* Fixtures                                                                   */
/* -------------------------------------------------------------------------- */

const PLAYER_PAYLOAD = [
  {
    label: 'Latino',
    servers: [
      { name: 'Streamwish', src: '__BASE__/ext/streamwish' },
      { name: 'Filemoon', src: '__BASE__/ext/filemoon' },
    ],
  },
  {
    label: 'Subtitulado',
    servers: [{ name: 'Mega', src: 'https://mega.nz/file/abc123' }],
  },
];

const moviePage = (base, payload = PLAYER_PAYLOAD) => `<html>
<head><title>Street Fighter (2026) Online — Gnula</title></head><body>
<h1 class="gnrd-fi-title">Street Fighter</h1>
<p class="gnrd-fi-syn">Ryu y Ken, distanciados, son reclutados para un torneo
que esconde algo mucho peor que una pelea callejera.</p>
<div class="gnrd-player" data-id="1234"></div>
<script>window.GNRD={pid:1234, tok:"tok-abc", k:"a2V5", type:"movie", autorepro:1};</script>
<script>window.__payload = ${JSON.stringify(JSON.stringify(payload))};</script>
</body></html>`;

const seriesPage = (base) => `<html>
<head><title>VisionQuest — Gnula</title></head><body>
<h1 class="gnrd-fi-title">VisionQuest</h1>
<p class="gnrd-fi-syn">Vision busca un nuevo propósito mientras escapa de
quienes quieren convertirlo en un arma.</p>
<div class="gnrd-eplist">
  <a class="gnrd-epc" data-s="1" data-e="1" data-id="8001" data-t="tok-ep1" href="/ver/visionquest/1x1/">1x01</a>
  <a class="gnrd-epc" data-s="1" data-e="2" data-id="8002" data-t="tok-ep2" href="/ver/visionquest/1x2/">1x02</a>
  <a class="gnrd-epc" data-s="1" data-e="3" data-id="8003" data-t="tok-ep3" href="/ver/visionquest/1x3/">1x03</a>
</div>
<script>window.GNRD={pid:8000, tok:"tok-serie", k:"a2V5", type:"serie"};</script>
</body></html>`;

const plainBase64Page = (base) => `<html><head><title>Clayface</title></head><body>
<h1 class="gnrd-fi-title">Clayface</h1>
<p class="gnrd-fi-syn">Un actor deformado por un accidente busca venganza en las
calles de Gotham mientras su cuerpo se deshace.</p>
<script>window.GNRD={pid:777, tok:"tok-plano", k:"a2V5"};</script>
</body></html>`;

let fixture;
let env;
let plainFixture;

before(async () => {
  fixture = await startFixture((base) => ({
    '/': `<html><body><section><a href="/ver/street-fighter/"><h3>Street Fighter</h3></a>
      <a href="/ver/clayface/"><h3>Clayface</h3></a></section></body></html>`,

    '/ver/street-fighter/': moviePage(base),
    '/ver/visionquest/': seriesPage(base),
    '/ver/clayface/': plainBase64Page(base),

    /* API del buscador: Gnula responde JSON, no HTML. */
    '/wp-json/gnrd/v1/search': ({ url }) => ({
      type: 'application/json',
      body: JSON.stringify({
        items: [
          { title: 'Street Fighter', url: `${base}/ver/street-fighter/`, type: 'Pelicula' },
          { title: 'Clayface', url: `${base}/ver/clayface/`, type: 'Pelicula' },
        ].filter((item) => url.searchParams.get('q') && item.title.toLowerCase()
          .includes(String(url.searchParams.get('q')).toLowerCase().split(' ')[0])),
      }),
    }),

    /* API del reproductor: `p` según el token pedido. */
    '/wp-json/gnrd/v1/player': ({ url }) => {
      const token = url.searchParams.get('t');
      if (token === 'tok-plano') {
        return {
          type: 'application/json',
          body: JSON.stringify({
            p: Buffer.from(JSON.stringify([
              { label: 'Latino', servers: [{ name: 'Streamwish', src: `${base}/ext/streamwish` }] },
            ])).toString('base64'),
          }),
        };
      }
      if (token === 'tok-ep2') {
        return {
          type: 'application/json',
          body: JSON.stringify({
            p: obfuscate([{ label: 'Latino', servers: [{ name: 'Streamwish', src: `${base}/ext/streamwish` }] }]),
          }),
        };
      }
      if (token !== 'tok-abc') {
        return { status: 404, type: 'application/json', body: '{"error":"not found"}' };
      }
      const payload = JSON.parse(JSON.stringify(PLAYER_PAYLOAD).replaceAll('__BASE__', base));
      return { type: 'application/json', body: JSON.stringify({ p: obfuscate(payload) }) };
    },

    '/ext/streamwish': streamwishPage('http://cdn.example/sw/index.m3u8?token=abc'),
    '/ext/filemoon': `<html><body><script>player = new Clappr.Player({source: "http://cdn.example/fm/index.m3u8", mimeType: "application/x-mpegURL"});</script></body></html>`,
  }));
  env = { ...CLEAN, GNULA_BASE: fixture.base };
});

after(async () => {
  await fixture.close();
  if (plainFixture) await plainFixture.close();
});

const ask = (id) => ({ type: 'movie', id, provider: 'gnula', timeoutMs: 8000 });

/* -------------------------------------------------------------------------- */
/* Tests                                                                     */
/* -------------------------------------------------------------------------- */

test('GNRD → API del reproductor → desofuscado → servidores', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, ask('gnula:street-fighter'), env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
  assert.ok(hasUrl(body, 'cdn.example/fm/index.m3u8'));
  assert.ok(
    fixture.requested.includes('GET /wp-json/gnrd/v1/player?id=1234&t=tok-abc'),
    fixture.requested.join(' | '),
  );
});

test('las etiquetas usan el idioma que publica el sitio', async () => {
  const { body } = await runScraper(SCRIPT, ask('gnula:street-fighter'), env);

  assert.equal(body.streams[0].name, 'Street Fighter');
  assert.match(body.streams[0].title, /^Gnula · Street Fighter · Latino - Streamwish$/);
  assert.equal(body.meta.server_names[0], 'Latino - Streamwish');
  assert.ok(body.meta.server_names.some((name) => name.startsWith('Subtitulado')));
});

test('el aviso de relleno y los hostings de descarga no son streams', async () => {
  const { body } = await runScraper(SCRIPT, ask('gnula:street-fighter'), env);

  assert.ok(!urlsOf(body).some((url) => url.includes('mega.nz')));
  assert.ok(!urlsOf(body).some((url) => url.includes('aviso.mp4')));
});

test('serie: usa el id/t del capítulo pedido', async () => {
  const { body } = await runScraper(SCRIPT, ask('gnula:visionquest:1:2'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.match(body.streams[0].name, /· T1 E2$/);
  assert.ok(
    fixture.requested.includes('GET /wp-json/gnrd/v1/player?id=8002&t=tok-ep2'),
    fixture.requested.join(' | '),
  );
});

test('si `p` viene en base64 plano también se resuelve', async () => {
  const { body } = await runScraper(SCRIPT, ask('gnula:clayface'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
});

test('el buscador JSON del sitio resuelve un slug incompleto', async () => {
  const { body } = await runScraper(SCRIPT, ask('gnula:street'), env);

  assert.equal(body.meta.resolved_by, 'search');
  assert.equal(body.meta.page, `${fixture.base}/ver/street-fighter/`);
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
});

test('sin GNRD, el respaldo genérico recoge los iframes', async () => {
  plainFixture = await startFixture((base) => ({
    '/ver/solo-iframe/': `<html><head><title>Solo iframe</title></head><body>
      <h1 class="gnrd-fi-title">Solo iframe</h1>
      <p class="gnrd-fi-syn">Ficha sin objeto GNRD: sólo el iframe del servidor,
      como en las páginas que van por el reproductor clásico. Aquí no hay API a
      la que preguntar, así que todo depende del respaldo genérico del scraper:
      iframes, atributos data-* y literales JavaScript con la lista de
      servidores. Es el camino que usan los clones del sitio.</p>
      <div class="player"><iframe src="${base}/ext/streamwish"></iframe></div>
      </body></html>`,
    '/ext/streamwish': streamwishPage('http://cdn.example/sw/index.m3u8?token=abc'),
  }));
  try {
    const { body } = await runScraper(SCRIPT, ask('gnula:solo-iframe'), {
      ...CLEAN,
      GNULA_BASE: plainFixture.base,
    });

    assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
    assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
  } finally {
    await plainFixture.close();
    plainFixture = null;
  }
});

test('una ficha inexistente devuelve [] con motivo', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('gnula:no-existe-1999'), env);

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_found');
});

test('rechaza payloads en directo', async () => {
  const { body } = await runScraper(SCRIPT, { type: 'tv', id: 'tv:gnula:x', isLive: true }, env);
  assert.equal(reasonOf(body), 'not_vod');
});

test('--list usa la API de búsqueda del sitio', async () => {
  const home = await runCli(SCRIPT, ['--list', '--json'], env);
  const items = JSON.parse(home.stdout).items;
  assert.ok(items.some((item) => item.id === 'gnula:street-fighter'), home.stdout);

  const searched = await runCli(SCRIPT, ['--list', '--query', 'clayface', '--json'], env);
  assert.deepEqual(
    JSON.parse(searched.stdout).items.map((item) => item.slug),
    ['clayface'],
  );
});
