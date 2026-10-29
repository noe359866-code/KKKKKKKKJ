/**
 * test/routes.test.js
 * Integration tests: boots the real Express app on an ephemeral port and
 * exercises /manifest.json, /stream/:type/:id.json, /health and /proxy.
 *
 * The Python demo scrapers are executed for real (DEMO_DELAY_MS is lowered so
 * the suite stays fast); this validates the whole Node -> Python -> cache flow.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

process.env.DEMO_DELAY_MS = '60';
process.env.RATE_LIMIT_MAX = '0';
/* El proveedor real `tvenvivo` está habilitado en config/providers.json.
 * En los tests se apunta a un puerto cerrado para que falle al instante y la
 * suite no dependa de la red externa. */
process.env.TVENVIVO_BASE = 'http://127.0.0.1:1';
process.env.TVENVIVO_INDEX_TTL_SECONDS = '0';
/* Los proveedores reales están habilitados en config/providers.json:
 * en los tests apuntan a un puerto cerrado para no depender de la red. */
process.env.ANIMEFLV_BASE = 'http://127.0.0.1:1';
process.env.JKANIME_BASE = 'http://127.0.0.1:1';
process.env.MONOSCHINOS_BASE = 'http://127.0.0.1:1';
process.env.GNULA_BASE = 'http://127.0.0.1:1';
process.env.CUEVANA_BASE = 'http://127.0.0.1:1';
process.env.PELISPLUSHD_BASE = 'http://127.0.0.1:1';
process.env.REPELIS_BASE = 'http://127.0.0.1:1';
process.env.PELICULASFLIX_API = 'http://127.0.0.1:1/api/gql';
process.env.PELISJUANITA_BASE = 'http://127.0.0.1:1';
process.env.COMODIN_BASE = 'http://127.0.0.1:1';
process.env.FUTBOLIBRE_BASE = 'http://127.0.0.1:1';
process.env.FUTBOLIBRE_AGENDA_URL = 'http://127.0.0.1:1/agenda.json';
process.env.FUTBOLIBRE_INDEX_TTL_SECONDS = '0';
process.env.FUTBOLIBRE_AGENDA_TTL_SECONDS = '0';
process.env.DEPORTELIBRE_BASE = 'http://127.0.0.1:1';
process.env.DEPORTELIBRE_INDEX_TTL_SECONDS = '0';

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

test('GET /manifest.json returns the Stremio manifest', async () => {
  const res = await fetch(`${baseUrl}/manifest.json`);
  assert.equal(res.status, 200);

  const manifest = await res.json();
  assert.deepEqual([...manifest.types].sort(), ['anime', 'movie', 'series', 'tv']);
  assert.equal(manifest.resources[0].name, 'stream');
  assert.ok(manifest.id);
});

test('GET /manifest.json accepts a Stremio config path prefix', async () => {
  const res = await fetch(`${baseUrl}/someconfig/manifest.json`);
  assert.equal(res.status, 200);
  assert.ok((await res.json()).id);
});

test('GET /stream/tv/:id.json returns ordered streams with request headers', async () => {
  const res = await fetch(`${baseUrl}/stream/tv/demo-live.json`);
  assert.equal(res.status, 200);

  const body = await res.json();
  assert.ok(Array.isArray(body.streams));
  assert.ok(body.streams.length >= 2, 'live demo should return several sources');
  assert.equal(typeof body.cacheMaxAge, 'number');

  const [first] = body.streams;
  assert.ok(first.name && first.title && first.url);
  assert.match(first.url, /^https?:\/\//);
  assert.equal(first.behaviorHints.requestHeaders.Referer, 'https://demo.local/');
  assert.ok(first.behaviorHints.requestHeaders['User-Agent']);

  /* Priority order: demo-live (priority 10) before demo-fallback (30). */
  const providers = body.streams.map((stream) => stream.provider);
  const firstFallback = providers.indexOf('demo-fallback');
  if (firstFallback !== -1) {
    assert.ok(
      providers.slice(firstFallback).every((provider) => provider === 'demo-fallback'),
      'lower priority providers must come last',
    );
  }
  assert.equal(providers[0], 'demo-live');
});

test('second request is served from the TTL cache in milliseconds', async () => {
  const id = 'cache-probe-' + Date.now();
  const url = `${baseUrl}/stream/tv/${id}.json`;

  const coldStart = Date.now();
  const cold = await fetch(url);
  const coldBody = await cold.json();
  const coldMs = Date.now() - coldStart;
  assert.equal(cold.headers.get('x-cache'), 'MISS');
  assert.ok(coldBody.streams.length > 0);

  const warmStart = Date.now();
  const warm = await fetch(url);
  const warmMs = Date.now() - warmStart;
  const warmBody = await warm.json();

  assert.equal(warm.headers.get('x-cache'), 'HIT');
  assert.deepEqual(warmBody.streams, coldBody.streams);
  assert.ok(warmMs < coldMs, `cache hit (${warmMs}ms) should beat the scrape (${coldMs}ms)`);
  assert.ok(warmMs < 150, `cache hit should be fast, got ${warmMs}ms`);
});

test('concurrent identical requests are coalesced into one scrape', async () => {
  const id = 'coalesce-' + Date.now();
  const url = `${baseUrl}/stream/tv/${id}.json`;

  const responses = await Promise.all(Array.from({ length: 6 }, () => fetch(url)));
  const statuses = responses.map((res) => res.status);
  assert.deepEqual(statuses, [200, 200, 200, 200, 200, 200]);

  const bodies = await Promise.all(responses.map((res) => res.json()));
  assert.ok(bodies.every((body) => JSON.stringify(body.streams) === JSON.stringify(bodies[0].streams)));

  const outcomes = responses.map((res) => res.headers.get('x-cache'));
  assert.equal(outcomes.filter((outcome) => outcome === 'MISS').length, 1, 'only one request should scrape');
});

test('VOD requests use the VOD providers', async () => {
  const res = await fetch(`${baseUrl}/stream/movie/tt0133093.json`);
  assert.equal(res.status, 200);
  const body = await res.json();
  assert.ok(body.streams.length > 0);
  assert.ok(body.streams.some((stream) => stream.provider === 'demo-vod'));
});

test('unsupported types are rejected with a Stremio-style error', async () => {
  const res = await fetch(`${baseUrl}/stream/channel/abc.json`);
  assert.equal(res.status, 404);
  const body = await res.json();
  assert.ok(body.err);
  assert.equal(body.code, 'UNSUPPORTED_TYPE');
});

test('HEAD /stream/... does not trigger a scrape', async () => {
  const res = await fetch(`${baseUrl}/stream/tv/head-probe.json`, { method: 'HEAD' });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get('x-cache'), 'BYPASS');

  /* If HEAD had scraped, the GET below would be a cache HIT. */
  const get = await fetch(`${baseUrl}/stream/tv/head-probe.json`);
  assert.ok(['MISS', 'COALESCED'].includes(get.headers.get('x-cache')));
});

test('GET /health exposes cache and provider status', async () => {
  const res = await fetch(`${baseUrl}/health`);
  assert.equal(res.status, 200);

  const body = await res.json();
  assert.equal(body.status, 'ok');
  assert.ok(body.cache.size >= 1);
  assert.ok(body.providers.enabled.length >= 1);
  assert.equal(body.addon.liveTtlHours, 2);
  assert.equal(body.addon.vodTtlHours, 6);
});

test('GET /proxy rejects private targets', async () => {
  const res = await fetch(`${baseUrl}/proxy?url=${encodeURIComponent('http://127.0.0.1:8080/x.m3u8')}`);
  assert.equal(res.status, 400);
  assert.equal((await res.json()).code, 'BAD_REQUEST');
});

test('unknown routes return a JSON 404', async () => {
  const res = await fetch(`${baseUrl}/nope`);
  assert.equal(res.status, 404);
  assert.equal((await res.json()).code, 'NOT_FOUND');
});
