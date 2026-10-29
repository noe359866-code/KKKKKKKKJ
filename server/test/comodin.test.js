/**
 * test/comodin.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for `scrapers/comodin.py` (Comodín Libre).
 *
 * El dominio de este sitio no está verificado, así que el proveedor no trae
 * base por defecto: sin `COMODIN_BASE` responde `not_configured` sin hacer una
 * sola petición. Con base configurada se comporta como el resto.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import {
  classicSiteRoutes,
  hasUrl,
  reasonOf,
  runCli,
  runScraper,
  startFixture,
  urlsOf,
} from '../testsupport/pelis.js';

const SCRIPT = 'comodin.py';
const CLEAN = { COMODIN_BASE: '', COMODIN_MIRRORS: '', TMDB_API_KEY: '' };

let fixture;
let env;

before(async () => {
  fixture = await startFixture((base) => classicSiteRoutes(base, {
    moviePath: '/pelicula/spider-man-de-regreso-a-casa',
    seriesPath: '/serie/the-last-of-us',
    searchPath: '/buscar',
  }));
  env = { ...CLEAN, COMODIN_BASE: fixture.base };
});

after(async () => {
  await fixture.close();
});

const ask = (id) => ({ type: 'movie', id, provider: 'comodin', timeoutMs: 8000 });

test('sin COMODIN_BASE devuelve [] con not_configured y no toca la red', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('comodin:spider-man-de-regreso-a-casa'), CLEAN);

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_configured');
  assert.ok(body.meta.hint.includes('COMODIN_BASE'));
});

test('la CLI tampoco falla sin dominio configurado', async () => {
  const { code, stdout } = await runCli(SCRIPT, ['--list'], CLEAN);

  assert.equal(code, 0);
  assert.match(stdout, /Sin resultados|Uso:/);
});

test('con base configurada resuelve la ficha completa', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, ask('comodin:spider-man-de-regreso-a-casa'), env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined);
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
  assert.ok(hasUrl(body, 'cdn.example/voe/master.m3u8'));
});

test('etiquetas con el nombre del sitio', async () => {
  const { body } = await runScraper(SCRIPT, ask('comodin:spider-man-de-regreso-a-casa'), env);

  assert.match(body.streams[0].title, /^Comodín Libre · Spider Man de Regreso a Casa · /);
});

test('serie vía página de capítulo', async () => {
  const { body } = await runScraper(SCRIPT, ask('comodin:the-last-of-us:1:2'), env);

  assert.equal(reasonOf(body), undefined);
  assert.match(body.streams[0].name, /· T1 E2$/);
});

test('COMODIN_MIRRORS da alternativas al dominio principal', async () => {
  const { body } = await runScraper(SCRIPT, ask('comodin:spider-man-de-regreso-a-casa'), {
    ...CLEAN,
    COMODIN_BASE: 'http://127.0.0.1:1',
    COMODIN_MIRRORS: fixture.base,
  });

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.equal(body.meta.base, fixture.base);
});

test('rechaza payloads en directo incluso con base', async () => {
  const { body } = await runScraper(SCRIPT, { type: 'tv', id: 'tv:comodin:x', isLive: true }, env);
  assert.equal(reasonOf(body), 'not_vod');
});

test('--list con base lista la portada', async () => {
  const { stdout } = await runCli(SCRIPT, ['--list', '--json'], env);
  const items = JSON.parse(stdout).items;

  assert.ok(items.some((item) => item.id === 'comodin:spider-man-de-regreso-a-casa'), stdout);
});
