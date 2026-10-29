/**
 * test/repelis.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for `scrapers/repelis.py`.
 *
 * Repelis cambia de dominio cada pocos meses, así que el proveedor es el único
 * que prueba `REPELIS_BASE` y luego `REPELIS_MIRRORS`. Aquí se valida eso y el
 * flujo normal contra un fixture local.
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

const SCRIPT = 'repelis.py';
const CLEAN = { REPELIS_BASE: '', REPELIS_MIRRORS: '', TMDB_API_KEY: '' };

let fixture;
let env;

before(async () => {
  fixture = await startFixture((base) => classicSiteRoutes(base, {
    moviePath: '/pelicula/spider-man-de-regreso-a-casa',
    seriesPath: '/serie/the-last-of-us',
    searchPath: '/search',
  }));
  env = { ...CLEAN, REPELIS_BASE: fixture.base };
});

after(async () => {
  await fixture.close();
});

const ask = (id) => ({ type: 'movie', id, provider: 'repelis', timeoutMs: 8000 });

test('resuelve la ficha y devuelve un stream por servidor', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, ask('repelis:spider-man-de-regreso-a-casa'), env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined);
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
  assert.ok(hasUrl(body, 'cdn.example/voe/master.m3u8'));
  assert.ok(hasUrl(body, 'cdn.example/fm/index.m3u8'));
});

test('las etiquetas llevan el sitio, el título y el servidor', async () => {
  const { body } = await runScraper(SCRIPT, ask('repelis:spider-man-de-regreso-a-casa'), env);
  const first = body.streams[0];

  assert.equal(first.name, 'Spider Man de Regreso a Casa');
  assert.match(first.title, /^Repelis · Spider Man de Regreso a Casa · /);
});

test('descarta Megas y servidores caídos', async () => {
  const { body } = await runScraper(SCRIPT, ask('repelis:spider-man-de-regreso-a-casa'), env);

  assert.ok(!urlsOf(body).some((url) => url.includes('mega.nz')));
  assert.ok((body.meta.download_only || []).length >= 1, JSON.stringify(body.meta));
});

test('serie con página por capítulo', async () => {
  const { body } = await runScraper(SCRIPT, ask('repelis:the-last-of-us:1:2'), env);

  assert.equal(reasonOf(body), undefined);
  assert.match(body.streams[0].name, /· T1 E2$/);
  assert.match(body.streams[0].title, /^Repelis · The Last of Us · T1 E2 · /);
});

test('sin slug válido, resuelve por el buscador del sitio', async () => {
  const { body } = await runScraper(SCRIPT, ask('repelis:spider man'), env);

  assert.equal(body.meta.resolved_by, 'search');
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
});

test('cae al espejo de REPELIS_MIRRORS cuando la base no responde', async () => {
  const { body } = await runScraper(SCRIPT, ask('repelis:spider-man-de-regreso-a-casa'), {
    ...CLEAN,
    REPELIS_BASE: 'http://127.0.0.1:1',
    REPELIS_MIRRORS: `http://127.0.0.1:1,${fixture.base}`,
  });

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.equal(body.meta.base, fixture.base);
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
});

test('todos los dominios caídos → [] con motivo, exit 0', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('repelis:spider-man-de-regreso-a-casa'), {
    ...CLEAN,
    REPELIS_BASE: 'http://127.0.0.1:1',
  });

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_found');
});

test('rechaza payloads en directo', async () => {
  const { body } = await runScraper(SCRIPT, { type: 'tv', id: 'tv:repelis:x', isLive: true }, env);

  assert.equal(reasonOf(body), 'not_vod');
});

test('--list y --list --query', async () => {
  const plain = await runCli(SCRIPT, ['--list', '--json'], env);
  const payload = JSON.parse(plain.stdout);
  assert.ok(payload.items.some((item) => item.id === 'repelis:spider-man-de-regreso-a-casa'));

  const searched = await runCli(SCRIPT, ['--list', '--query', 'spider', '--json'], env);
  assert.equal(JSON.parse(searched.stdout).items[0].slug, 'spider-man-de-regreso-a-casa');
});
