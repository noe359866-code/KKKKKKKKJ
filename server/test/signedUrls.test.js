/**
 * test/signedUrls.test.js
 * ---------------------------------------------------------------------------
 * URLs firmadas: el TTL de caché no puede sobrevivir al enlace.
 *
 * El addon cachea VOD 6 h y directo 2 h. Un `.m3u8` firmado a 30 minutos se
 * quedaba cacheado hasta 6 h: al reintentar, el CDN devolvía 403 y el usuario
 * veía "no reproduce nada" sin haber ningún proveedor caído.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import { capTtlByExpiry, earliestExpiryMs, EXPIRY_MARGIN_MS, urlExpiryMs } from '../src/lib/signedUrls.js';

const NOW = 1_700_000_000_000; // unix ms fijo, para no depender del reloj
const VOD_TTL = 6 * 3_600_000;

/* -------------------------------------------------------------------------- */
/* 1. Funciones puras                                                        */
/* -------------------------------------------------------------------------- */

test('urlExpiryMs reads the usual signed URL shapes', () => {
  assert.equal(urlExpiryMs('https://cdn/a.m3u8?e=1700000060'), 1_700_000_060_000);
  assert.equal(urlExpiryMs('https://cdn/a.m3u8?expires=1700000060000'), 1_700_000_060_000);
  assert.equal(urlExpiryMs('https://cdn/a.m3u8?EXPIRE=1700000060'), 1_700_000_060_000);
  /* La firma dentro de un token: el valor lleva `=` y `~`. */
  assert.equal(urlExpiryMs('https://cdn/a.m3u8?token=abc~exp=1700000060~x'), 1_700_000_060_000);
  /* Un `=` dentro de un valor no parte el parámetro. */
  assert.equal(urlExpiryMs('https://cdn/a.m3u8?a=b=c&e=1700000060'), 1_700_000_060_000);
});

test('urlExpiryMs ignores what is not an expiry', () => {
  assert.equal(urlExpiryMs('https://cdn/a.m3u8'), null);
  assert.equal(urlExpiryMs('https://cdn/a.m3u8?e=abc'), null);
  assert.equal(urlExpiryMs('https://cdn/a.m3u8?quality=1080'), null);
  assert.equal(urlExpiryMs(''), null);
  assert.equal(urlExpiryMs(null), null);
});

test('capTtlByExpiry keeps the earliest expiry and a safety margin', () => {
  const sources = [
    { url: 'https://cdn/a.m3u8?e=1700000120' },
    { url: 'https://cdn/b.m3u8?e=1700000060' },
  ];

  assert.equal(earliestExpiryMs(sources), 1_700_000_060_000);
  assert.equal(
    capTtlByExpiry(VOD_TTL, sources, NOW),
    1_700_000_060_000 - NOW - EXPIRY_MARGIN_MS,
  );

  /* Sin caducidad declarada manda la política. */
  assert.equal(capTtlByExpiry(VOD_TTL, [{ url: 'https://cdn/a.m3u8' }], NOW), VOD_TTL);
  assert.equal(capTtlByExpiry(VOD_TTL, [], NOW), VOD_TTL);

  /* Un enlace ya caducado no se cachea (nunca un TTL negativo). */
  assert.equal(capTtlByExpiry(VOD_TTL, [{ url: 'https://cdn/a.m3u8?e=1600000000' }], NOW), 0);

  /* Nunca por encima de la política. */
  assert.equal(capTtlByExpiry(1_000, [{ url: 'https://cdn/a.m3u8?e=1900000000' }], NOW), 1_000);
});

/* -------------------------------------------------------------------------- */
/* 2. Integración HTTP                                                       */
/* -------------------------------------------------------------------------- */

process.env.DEMO_DELAY_MS = '40';
process.env.RATE_LIMIT_MAX = '0';
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

test('a signed source shortens the cache instead of the 6h policy', async () => {
  const res = await fetch(`${baseUrl}/stream/movie/signed-demo.json`);
  assert.equal(res.status, 200);

  const body = await res.json();
  assert.ok(body.streams.length > 0, 'the demo provider still resolves streams');
  assert.match(body.streams[0].url, /[?&]e=\d{10}/);

  /* ~20 s de vida, menos el margen de 15 s. */
  assert.ok(body.cacheMaxAge <= 20, `cacheMaxAge was ${body.cacheMaxAge}`);
  assert.ok(body.cacheMaxAge >= 1);
  assert.ok(body.staleRevalidate <= 20, 'staleRevalidate no puede prometer más que el enlace');
  assert.equal(body.subtitles !== undefined, true);

  const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get('cache-control') || '')?.[1]);
  assert.ok(maxAge <= 20, `Cache-Control max-age was ${maxAge}`);
});

test('a plain source keeps the full VOD policy', async () => {
  const res = await fetch(`${baseUrl}/stream/movie/tt0133093.json`);
  const body = await res.json();

  assert.ok(body.streams.length > 0);
  assert.ok(body.cacheMaxAge > 3_600, `la política de VOD sigue siendo horas (${body.cacheMaxAge})`);
});

test('the cached signed entry expires with the link', async () => {
  /* Segunda petición: viene de caché (dentro de los ~5 s del enlace). */
  const again = await fetch(`${baseUrl}/stream/movie/signed-demo.json`);
  assert.equal(again.headers.get('x-cache'), 'HIT');
  const body = await again.json();
  assert.ok(body.cacheMaxAge <= 20);
});
