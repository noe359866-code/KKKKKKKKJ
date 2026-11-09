import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
process.env.CATALOGS_CONFIG = path.join(__dirname, 'fixtures/catalogs.json');
process.env.CATALOG_REMOTE_TIMEOUT_MS = '500';
process.env.RATE_LIMIT_MAX = '0';

const { createApp } = await import('../src/app.js');
let server;
let baseUrl;

before(async () => {
  const app = createApp({ rateLimit: 0 });
  server = app.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));
  baseUrl = `http://127.0.0.1:${server.address().port}`;
});

after(() => new Promise((resolve) => {
  server.close(resolve);
  server.closeIdleConnections?.();
  server.closeAllConnections?.();
}));

test('manifest publishes only catalogs and metadata resources', async () => {
  const response = await fetch(`${baseUrl}/manifest.json`);
  assert.equal(response.status, 200);
  const manifest = await response.json();
  assert.deepEqual([...manifest.types].sort(), ['anime', 'movie', 'series']);
  assert.deepEqual(manifest.resources.map(({ name }) => name), ['catalog', 'meta']);
  assert.ok(manifest.catalogs.length > 0);
});

test('playback and subtitles endpoints are not exposed', async () => {
  for (const pathName of ['/stream/movie/tt0133093.json', '/subtitles/movie/tt0133093.json']) {
    const response = await fetch(`${baseUrl}${pathName}`);
    assert.equal(response.status, 404);
  }
});

test('health describes the catalog-only service without provider/scraper data', async () => {
  const response = await fetch(`${baseUrl}/health`);
  assert.equal(response.status, 200);
  const body = await response.json();
  assert.equal(body.status, 'ok');
  assert.equal(body.service, 'streamflix-catalog-addon');
  assert.deepEqual(body.addon.resources, ['catalog', 'meta']);
  assert.ok(body.catalogs.count > 0);
  assert.equal('providers' in body, false);
});

test('proxy rejects private targets', async () => {
  const response = await fetch(`${baseUrl}/proxy?url=${encodeURIComponent('http://127.0.0.1:8080/x.m3u8')}`);
  assert.equal(response.status, 400);
  assert.equal((await response.json()).code, 'BAD_REQUEST');
});

test('unknown routes return a JSON 404', async () => {
  const response = await fetch(`${baseUrl}/nope`);
  assert.equal(response.status, 404);
  assert.equal((await response.json()).code, 'NOT_FOUND');
});
