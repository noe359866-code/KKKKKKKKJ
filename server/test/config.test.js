import test from 'node:test';
import assert from 'node:assert/strict';

process.env.LOG_LEVEL = 'error';
const { default: config } = await import('../src/config.js');

test('backend declares catalog and metadata resources only', () => {
  assert.deepEqual(config.addon.resources, ['catalog', 'meta']);
  assert.equal('scrapers' in config, false);
  assert.equal('streams' in config, false);
});

test('catalog cache windows are configured independently', () => {
  assert.ok(config.catalogs.ttlMs > config.catalogs.searchTtlMs);
  assert.ok(config.catalogs.metaTtlMs > config.catalogs.ttlMs);
});

test('optional media proxy has its own timeout and defaults', () => {
  assert.equal(config.proxy.timeoutMs, 15_000);
  assert.ok(config.proxy.defaultUserAgent.includes('Android TV'));
});
