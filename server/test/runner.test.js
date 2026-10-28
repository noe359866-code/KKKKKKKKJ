/**
 * test/runner.test.js
 * Integration tests for the provider pool: parallel execution, priority
 * ordering and the "never wait for the slowest site" deadline.
 */

import test, { before } from 'node:test';
import assert from 'node:assert/strict';

process.env.DEMO_DELAY_MS = '150';
/* Proveedor real deshabilitado por red en tests: falla al instante. */
process.env.TVENVIVO_BASE = 'http://127.0.0.1:1';
process.env.TVENVIVO_INDEX_TTL_SECONDS = '0';
/* Los proveedores reales están habilitados en config/providers.json:
 * en los tests apuntan a un puerto cerrado para no depender de la red. */
process.env.FUTBOLIBRE_BASE = 'http://127.0.0.1:1';
process.env.FUTBOLIBRE_AGENDA_URL = 'http://127.0.0.1:1/agenda.json';
process.env.FUTBOLIBRE_INDEX_TTL_SECONDS = '0';
process.env.FUTBOLIBRE_AGENDA_TTL_SECONDS = '0';
process.env.DEPORTELIBRE_BASE = 'http://127.0.0.1:1';
process.env.DEPORTELIBRE_INDEX_TTL_SECONDS = '0';

const { runProviderPool } = await import('../src/providers/runner.js');
const { loadProviders, getProvidersFor } = await import('../src/providers/index.js');
const { parseMediaId } = await import('../src/lib/ids.js');

const context = {
  type: 'tv',
  id: 'tv:runner-test',
  parsed: parseMediaId('tv:runner-test'),
  isLive: true,
};

before(async () => {
  await loadProviders();
});

test('pool returns results in priority order', async () => {
  const providers = getProvidersFor(true); // demo-live (10), demo-fallback (30)
  const started = Date.now();

  const { results, timedOut } = await runProviderPool(providers, context, {
    deadlineMs: 5000,
    minBeforeGrace: providers.length + 1, // disable the early return
    graceMs: 0,
  });
  const elapsed = Date.now() - started;

  assert.equal(timedOut, false);
  assert.deepEqual(
    results.map((result) => result.provider.id),
    providers.map((provider) => provider.id),
  );
  assert.ok(results.some((result) => result.streams.length > 0));

  /* Providers run in parallel: total time ~ one scrape, not the sum. */
  assert.ok(elapsed < 150 * providers.length, `providers should run concurrently, took ${elapsed}ms`);
});

test('pool gives up at the deadline instead of hanging on a slow provider', async () => {
  const providers = getProvidersFor(true); // each demo scraper sleeps 150ms

  const started = Date.now();
  const { results, timedOut } = await runProviderPool(providers, context, {
    deadlineMs: 30,
    minBeforeGrace: 99,
    graceMs: 0,
  });
  const elapsed = Date.now() - started;

  assert.equal(timedOut, true, 'pool must report the timeout');
  assert.ok(elapsed < 120, `should return at the deadline, took ${elapsed}ms`);
  assert.equal(results.length, providers.length);
  assert.ok(results.every((result) => Array.isArray(result.streams)));
});
