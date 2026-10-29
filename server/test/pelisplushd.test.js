/**
 * test/pelisplushd.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for `scrapers/pelisplushd.py` (PelisPlus / PelisPlusHD).
 *
 * `testsupport/pelis.js` levanta un sitio de mentira con la forma real:
 * `/pelicula/<slug>` con los embeds en `data-player`, `/serie/<slug>` listando
 * capítulos y la página de cada capítulo, más Streamwish / VOE / Filemoon
 * resueltos y un hosting de descarga que debe quedar fuera.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import {
  b64,
  classicSiteRoutes,
  hasUrl,
  reasonOf,
  runCli,
  runScraper,
  startFixture,
  streamwishPage,
  urlsOf,
} from '../testsupport/pelis.js';

const SCRIPT = 'pelisplushd.py';
const SITE = {
  PELISPLUSHD_BASE: '',
  PELISPLUSHD_MIRRORS: '',
  TMDB_API_KEY: '',
};

let fixture;
let env;

before(async () => {
  fixture = await startFixture((base) => classicSiteRoutes(base, {
    moviePath: '/pelicula/spider-man-de-regreso-a-casa',
    seriesPath: '/serie/the-last-of-us',
    searchPath: '/search',
  }));
  env = { ...SITE, PELISPLUSHD_BASE: fixture.base };
});

after(async () => {
  await fixture.close();
});

const ask = (id) => ({ type: 'movie', id, provider: 'pelisplushd', timeoutMs: 8000 });

test('ficha de película → un stream por servidor resuelto', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, ask('pelisplushd:spider-man-de-regreso-a-casa'), env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined);
  assert.equal(body.meta.page, `${fixture.base}/pelicula/spider-man-de-regreso-a-casa`);
  assert.equal(body.meta.resolved_by, 'direct');
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), `streamwish: ${urlsOf(body)}`);
  assert.ok(hasUrl(body, 'cdn.example/voe/master.m3u8'), `voe: ${urlsOf(body)}`);
  assert.ok(hasUrl(body, 'cdn.example/fm/index.m3u8'), `filemoon empaquetado: ${urlsOf(body)}`);
});

test('el orden de los servidores es el de la ficha (failover)', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisplushd:spider-man-de-regreso-a-casa'), env);
  const urls = urlsOf(body);

  assert.ok(urls[0].includes('/sw/'), `primero streamwish: ${urls}`);
  assert.ok(urls.indexOf(urls.find((url) => url.includes('/voe/'))) <
    urls.indexOf(urls.find((url) => url.includes('/fm/'))), `orden: ${urls}`);
});

test('etiquetas: sitio, título y servidor', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisplushd:spider-man-de-regreso-a-casa'), env);
  const first = body.streams[0];

  assert.equal(first.name, 'Spider Man de Regreso a Casa');
  assert.match(first.title, /^PelisPlus · Spider Man de Regreso a Casa · \S/);
  assert.equal(first.isLive, false);
});

test('los hostings de descarga y los servidores caídos no son streams', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisplushd:spider-man-de-regreso-a-casa'), env);

  assert.ok(!urlsOf(body).some((url) => url.includes('mega.nz')));
  assert.ok(!body.streams.some((stream) => (stream.title || '').includes('Offline')));
});

test('serie: pide la página del capítulo y resuelve sus servidores', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisplushd:the-last-of-us:1:2'), env);

  assert.equal(reasonOf(body), undefined);
  assert.equal(body.meta.kind, 'series');
  assert.equal(body.meta.resolved_by, 'direct');
  assert.equal(body.meta.page, `${fixture.base}/serie/the-last-of-us/temporada/1/capitulo/2`);
  assert.match(body.streams[0].name, /· T1 E2$/);
});

test('serie sin páginas por capítulo: sigue el enlace del episodio', async () => {
  /* Sólo existe la ficha de la serie: los reproductores están en el capítulo. */
  const fichaOnly = await startFixture((base) => ({
    '/serie/the-last-of-us': `<html><head><title>The Last of Us</title></head><body>
      <h1>The Last of Us</h1>
      <p>Veinte años después de la destrucción de la civilización, Joel y Ellie
      atraviesan Estados Unidos en una serie de aventuras y desgracias.</p>
      <div class="episodios">
        <a href="/serie/the-last-of-us/1x02">1x02</a>
        <a href="/serie/the-last-of-us/1x03">1x03</a>
      </div></body></html>`,
    '/serie/the-last-of-us/1x02': `<html><head><title>Episodio 2</title></head><body>
      <h1>The Last of Us — Episodio 2</h1>
      <div class="player"><iframe src="${base}/ext/streamwish"></iframe></div>
      <p>Joel, Tess y Ellie se adentran en una zona contaminada.</p></body></html>`,
    '/ext/streamwish': streamwishPage('http://cdn.example/sw/index.m3u8?token=abc'),
  }));
  try {
    const { body } = await runScraper(SCRIPT, ask('pelisplushd:the-last-of-us:1:2'), {
      ...SITE,
      PELISPLUSHD_BASE: fichaOnly.base,
    });

    assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
    assert.equal(body.meta.episode_page, `${fichaOnly.base}/serie/the-last-of-us/1x02`);
    assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
  } finally {
    await fichaOnly.close();
  }
});

test('si el slug no existe se resuelve por el buscador del sitio', async () => {
  const fresh = await startFixture((base) => classicSiteRoutes(base, {
    moviePath: '/pelicula/spider-man-de-regreso-a-casa',
    searchPath: '/search',
    withSeries: false,
  }));
  try {
    const { body } = await runScraper(SCRIPT, ask('pelisplushd:spider man'), {
      ...SITE,
      PELISPLUSHD_BASE: fresh.base,
    });

    assert.equal(body.meta.resolved_by, 'search');
    assert.equal(body.meta.page, `${fresh.base}/pelicula/spider-man-de-regreso-a-casa`);
    assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
    // Ningún intento con `data-player` (no hay sitio raro en la URL pedida).
    assert.ok(!fresh.requested.includes('GET /pelicula/spider'), 'méta el slug directo primero');
  } finally {
    await fresh.close();
  }
});

test('un sitio inalcanzable devuelve [] con motivo, sin romperse', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('pelisplushd:spider-man-de-regreso-a-casa'), {
    ...SITE,
    PELISPLUSHD_BASE: 'http://127.0.0.1:1',
  });

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_found');
});

test('rechaza payloads en directo (es VOD)', async () => {
  const { body } = await runScraper(SCRIPT, { type: 'tv', id: 'tv:pelisplushd:x', isLive: true }, env);

  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_vod');
});

test('--list imprime ids de la portada', async () => {
  const { code, stdout } = await runCli(SCRIPT, ['--list', '--json'], env);
  const payload = JSON.parse(stdout);

  assert.equal(code, 0);
  assert.ok(payload.items.some((item) => item.id === 'pelisplushd:spider-man-de-regreso-a-casa'), stdout);
  assert.ok(payload.items.every((item) => item.id.startsWith('pelisplushd:')));
});

test('--list --query usa el buscador', async () => {
  const { stdout } = await runCli(SCRIPT, ['--list', '--query', 'spider', '--json'], env);
  const payload = JSON.parse(stdout);

  assert.equal(payload.items[0].slug, 'spider-man-de-regreso-a-casa');
});

test('un id IMDb sin clave TMDB se degrada sin romperse', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('pelisplushd:tt0145487'), { ...env, TMDB_API_KEY: '' });

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'no_id');
  assert.equal(body.meta.imdb, 'tt0145487');
});
