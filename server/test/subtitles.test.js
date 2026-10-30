/**
 * test/subtitles.test.js
 * ---------------------------------------------------------------------------
 * Subtítulos: normalización + recurso del addon.
 *
 * Dos niveles, igual que el resto de la suite:
 *   1. Funciones puras de `lib/subtitles.js` (sin red, sin app).
 *   2. Integración real: la app se levanta con los proveedores de demostración
 *      (`example_provider.py` publica `meta.subtitles`) y se comprueba que la
 *      lista viaja en `/stream/...` y en `/subtitles/...` con el mismo cacheo.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildSubtitleList,
  languageName,
  looksLikeSubtitle,
  normalizeLanguage,
  normalizeSubtitle,
  subtitleFormat,
} from '../src/lib/subtitles.js';
import { buildManifest } from '../src/lib/stremio.js';

/* -------------------------------------------------------------------------- */
/* 1. Funciones puras                                                         */
/* -------------------------------------------------------------------------- */

test('normalizeLanguage maps provider spellings to BCP-47', () => {
  assert.equal(normalizeLanguage('SPA'), 'es');
  assert.equal(normalizeLanguage('Spanish'), 'es');
  assert.equal(normalizeLanguage('Español'), 'es');
  assert.equal(normalizeLanguage('es_419'), 'es-419');
  assert.equal(normalizeLanguage('en-US'), 'en-us');
  assert.equal(normalizeLanguage('eng'), 'en');
  /* Basura: mejor sin idioma que con uno inventado. */
  assert.equal(normalizeLanguage('subtitulado por alguien'), '');
  assert.equal(normalizeLanguage(null), '');
});

test('languageName keeps the region when it matters', () => {
  assert.equal(languageName('es'), 'Español');
  assert.equal(languageName('es-419'), 'Español (Latino)');
  assert.equal(languageName('pt-BR'), 'Portugués (Brasil)');
  assert.equal(languageName('xx'), '');
});

test('subtitleFormat only trusts known media extensions', () => {
  assert.equal(subtitleFormat('https://cdn/a/b.es.srt?token=1'), 'srt');
  assert.equal(subtitleFormat('https://cdn/a/b.vtt#t=1'), 'vtt');
  assert.equal(subtitleFormat('https://cdn/a/b.ass'), 'ass');
  assert.equal(subtitleFormat('https://cdn/a/b.xml'), 'ttml');
  assert.equal(subtitleFormat('https://cdn/subs/12345'), null);
});

test('normalizeSubtitle drops what ExoPlayer cannot open', () => {
  const provider = { label: 'NovaHD' };
  assert.equal(normalizeSubtitle({ url: 'https://cdn/a.mkv' }), null);
  assert.equal(normalizeSubtitle({ url: 'https://cdn/page.html' }), null);
  assert.equal(normalizeSubtitle({ url: '/relative/sub.srt' }), null);
  assert.equal(normalizeSubtitle({ url: '' }), null);
  assert.equal(normalizeSubtitle(null), null);
  assert.equal(looksLikeSubtitle('https://cdn/a.es.srt'), true);
  assert.equal(looksLikeSubtitle('https://cdn/embed/1'), true);
});

test('normalizeSubtitle fills id, language, label and format', () => {
  const provider = { label: 'NovaHD' };
  assert.deepEqual(normalizeSubtitle({ url: 'https://cdn/a.es.srt', lang: 'SPA' }, provider), {
    id: 'https://cdn/a.es.srt',
    url: 'https://cdn/a.es.srt',
    lang: 'es',
    label: 'Español',
    provider: 'NovaHD',
    format: 'srt',
  });
  /* Sin idioma ni etiqueta: al menos el proveedor, nunca una fila vacía. */
  const unlabelled = normalizeSubtitle({ url: 'https://cdn/a.vtt' }, provider);
  assert.equal(unlabelled.label, 'NovaHD');
  assert.equal(unlabelled.lang, '');
});

test('buildSubtitleList dedupes, puts Spanish first and caps the list', () => {
  const results = [
    {
      provider: { id: 'novahd', label: 'NovaHD' },
      meta: {
        subtitles: [
          { url: 'https://cdn/a.en.vtt', lang: 'eng' },
          { url: 'https://cdn/a.es.srt', lang: 'SPA' },
          { url: 'https://cdn/a.es.srt', lang: 'es' },
        ],
      },
    },
    {
      provider: { id: 'otro', label: 'Otro' },
      meta: { subtitles: [{ url: 'https://cdn/b.es-419.srt', lang: 'es-419' }] },
    },
    /* Proveedor sin subtítulos (la mayoría). */
    { provider: { id: 'gnula', label: 'Gnula' }, meta: { reason: 'timeout' } },
    { provider: { id: 'roto', label: 'Roto' } },
  ];

  const list = buildSubtitleList(results);
  assert.deepEqual(
    list.map((item) => `${item.lang}|${item.label}`),
    ['es|Español', 'es-419|Español (Latino)', 'en|Inglés'],
  );
  assert.equal(new Set(list.map((item) => item.url)).size, list.length);

  const many = buildSubtitleList([{
    provider: { id: 'x', label: 'X' },
    meta: { subtitles: Array.from({ length: 60 }, (_, index) => ({ url: `https://cdn/${index}.vtt` })) },
  }]);
  assert.equal(many.length, 40);
  assert.deepEqual(buildSubtitleList([]), []);
  assert.deepEqual(buildSubtitleList(undefined), []);
});

test('the manifest advertises the subtitles resource', () => {
  const manifest = buildManifest('https://addon.example');
  const names = manifest.resources.map((resource) => resource.name);
  assert.ok(names.includes('subtitles'));
  assert.deepEqual([...manifest.types].sort(), ['anime', 'movie', 'series', 'tv']);
});

/* -------------------------------------------------------------------------- */
/* 2. Integración HTTP                                                        */
/* -------------------------------------------------------------------------- */

process.env.DEMO_DELAY_MS = '40';
process.env.RATE_LIMIT_MAX = '0';
/* Ningún proveedor real: la suite no depende de la red externa. */
for (const name of [
  'TVENVIVO_BASE', 'ANIMEFLV_BASE', 'JKANIME_BASE', 'MONOSCHINOS_BASE', 'GNULA_BASE',
  'CUEVANA_BASE', 'PELISPLUSHD_BASE', 'REPELIS_BASE', 'PELISJUANITA_BASE',
  'DEPORTELIBRE_BASE',
]) process.env[name] = 'http://127.0.0.1:1';
process.env.PELICULASFLIX_API = 'http://127.0.0.1:1/api/gql';
process.env.FUTBOLIBRE_BASE = 'http://127.0.0.1:1';
process.env.FUTBOLIBRE_AGENDA_URL = 'http://127.0.0.1:1/agenda.json';

const { createApp } = await import('../src/app.js');
const { loadProviders } = await import('../src/providers/index.js');

let server;
let baseUrl;

before(async () => {
  await loadProviders();
  const app = createApp({ rateLimit: 0 });
  server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(() => new Promise((resolve) => server.close(resolve)));

test('GET /stream/... carries the subtitles resolved by the same scrape', async () => {
  const res = await fetch(`${baseUrl}/stream/movie/tt0133093.json`);
  assert.equal(res.status, 200);

  const body = await res.json();
  assert.ok(Array.isArray(body.subtitles), 'subtitles must always be an array');
  assert.ok(body.subtitles.length >= 2, 'demo providers publish subtitles');
  assert.equal(body.subtitles[0].lang, 'es', 'Spanish first');

  for (const subtitle of body.subtitles) {
    assert.match(subtitle.url, /^https:\/\//);
    assert.ok(subtitle.label.length > 0);
    assert.ok(subtitle.id.length > 0);
  }
  /* El `player.html` del proveedor de respaldo no es una pista. */
  assert.equal(body.subtitles.some((subtitle) => subtitle.url.endsWith('.html')), false);
});

test('GET /subtitles/... serves the same list as the stream response', async () => {
  const stream = await (await fetch(`${baseUrl}/stream/movie/tt0133093.json`)).json();
  const res = await fetch(`${baseUrl}/subtitles/movie/tt0133093.json`);
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-cache'), 'HIT', 'the subtitles route reuses the stream cache');

  const body = await res.json();
  assert.deepEqual(body.subtitles, stream.subtitles);
  assert.ok(body.cacheMaxAge > 0);
  assert.equal(body.staleError > 0, true);
});

test('live content never returns subtitles', async () => {
  const res = await fetch(`${baseUrl}/stream/tv/demo-live.json`);
  const body = await res.json();
  assert.deepEqual(body.subtitles, []);
});

test('unknown types are rejected by the subtitles route', async () => {
  const res = await fetch(`${baseUrl}/subtitles/zzz/abc.json`);
  assert.equal(res.status, 404);
  const body = await res.json();
  assert.equal(body.code, 'UNSUPPORTED_TYPE');
});

test('HEAD /subtitles/... does not trigger a scrape', async () => {
  const res = await fetch(`${baseUrl}/subtitles/movie/tt0133093.json`, { method: 'HEAD' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-cache'), 'BYPASS');
});
