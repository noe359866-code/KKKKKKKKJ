/**
 * test/cache.test.js
 * Unit tests for the TTL cache: expiry, stale-while-revalidate, LRU bound and
 * single-flight (request coalescing).
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import { TtlCache } from '../src/cache/TtlCache.js';

/** Builds a cache with a fake clock we can advance manually. */
function makeCache(options = {}) {
  const clock = { now: 1_000_000 };
  const cache = new TtlCache({
    maxEntries: 100,
    sweepIntervalMs: 0,
    staleFactor: 1.2,
    now: () => clock.now,
    ...options,
  });
  return { cache, clock };
}

test('set/get returns the value while the TTL is valid', () => {
  const { cache, clock } = makeCache();
  cache.set('k', { hello: 'world' }, 1000);

  const hit = cache.get('k');
  assert.equal(hit.value.hello, 'world');
  assert.equal(hit.stale, false);
  assert.equal(cache.stats().hits, 1);

  clock.now += 900;
  assert.equal(cache.get('k').stale, false, 'still fresh at 900ms');
});

test('entry becomes unusable after TTL + stale window', () => {
  const { cache, clock } = makeCache();
  cache.set('k', 'v', 1000);

  /* TTL = 1000ms, staleFactor = 1.2 -> usable until 1200ms. */
  clock.now += 1100;
  const stale = cache.get('k');
  assert.ok(stale, 'stale entries are still served');
  assert.equal(stale.stale, true);

  clock.now += 200; // 1300ms: beyond the stale window
  assert.equal(cache.get('k'), null);
  assert.equal(cache.stats().expired, 1);
});

test('negative cache entries are stored as empty arrays', () => {
  const { cache } = makeCache();
  cache.setMiss('missing', 500);
  const hit = cache.get('missing');
  assert.deepEqual(hit.value, []);
  assert.equal(hit.meta.negative, true);
});

test('LRU eviction keeps the cache bounded', () => {
  const { cache } = makeCache({ maxEntries: 2 });
  cache.set('a', 1, 10_000);
  cache.set('b', 2, 10_000);
  cache.get('a'); // "a" is now the most recently used
  cache.set('c', 3, 10_000);

  assert.equal(cache.size, 2);
  assert.equal(cache.get('b'), null, 'least recently used entry was evicted');
  assert.equal(cache.get('a').value, 1);
  assert.equal(cache.get('c').value, 3);
  assert.equal(cache.stats().evictions, 1);
});

test('singleFlight coalesces concurrent identical work', async () => {
  const { cache } = makeCache();
  let calls = 0;

  const factory = async () => {
    calls += 1;
    await new Promise((resolve) => setTimeout(resolve, 20));
    return [{ url: 'https://cdn.example/live.m3u8' }];
  };

  const results = await Promise.all(
    Array.from({ length: 5 }, () => cache.singleFlight('live:canal-13', factory)),
  );

  assert.equal(calls, 1, 'factory must run exactly once');
  assert.equal(results.length, 5);
  assert.equal(results.filter((r) => r.coalesced).length, 4);
  assert.equal(cache.stats().coalesced, 4);
});

test('singleFlight releases the lock after an error', async () => {
  const { cache } = makeCache();
  await assert.rejects(cache.singleFlight('x', async () => Promise.reject(new Error('boom'))));

  const retry = await cache.singleFlight('x', async () => 'recovered');
  assert.equal(retry.value, 'recovered');
});

test('fetch computes once then serves from cache', async () => {
  const { cache } = makeCache();
  let calls = 0;
  const factory = async () => {
    calls += 1;
    return ['stream'];
  };

  const first = await cache.fetch('key', factory, 5000);
  const second = await cache.fetch('key', factory, 5000);

  assert.equal(first.source, 'computed');
  assert.equal(second.source, 'hit');
  assert.equal(calls, 1);
  assert.deepEqual(second.value, ['stream']);
});

test('invalid TTL is rejected instead of silently cached forever', () => {
  const { cache } = makeCache();
  assert.throws(() => cache.set('bad', 1, 0), /invalid TTL/);
  assert.throws(() => cache.set('bad', 1, Number.NaN), /invalid TTL/);
});

test('sweep removes fully expired entries', () => {
  const { cache, clock } = makeCache();
  cache.set('a', 1, 100);
  cache.set('b', 2, 10_000);
  clock.now += 1000;

  const removed = cache.sweep();
  assert.equal(removed, 1);
  assert.equal(cache.size, 1);
});
