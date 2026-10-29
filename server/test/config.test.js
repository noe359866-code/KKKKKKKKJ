/**
 * test/config.test.js
 * ---------------------------------------------------------------------------
 * Guards the two numbers the whole addon is tuned around:
 *
 *   - per-provider budget: 8 s (a slower scraper is killed and the secondary
 *     sources answer instead);
 *   - cache TTL: live/sports 2 h – 4 h, VOD 2 h – 6 h, and an out-of-range
 *     override must fail at boot instead of silently caching stale .m3u8 links.
 *
 * The boot checks run in a child Node process because `config.js` validates the
 * environment when it is first imported.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HOUR = 60 * 60 * 1000;
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SERVER_DIR = path.resolve(__dirname, '..');

process.env.LOG_LEVEL = 'error';

const { default: config } = await import('../src/config.js');
const { ttlFor, ttlPolicy } = await import('../src/services/streamService.js');

/** Boots `config.js` in a child process with a given environment. */
function bootWith(env) {
  return spawnSync(
    process.execPath,
    ['--input-type=module', '-e', "await import('./src/config.js');"],
    {
      cwd: SERVER_DIR,
      env: { ...process.env, ...env },
      encoding: 'utf8',
      timeout: 20_000,
    },
  );
}

test('el presupuesto por proveedor es de 8 segundos', () => {
  assert.equal(config.streams.providerTimeoutMs, 8_000);
  assert.ok(config.streams.providerTimeoutMs < config.scrapers.timeoutMs,
    'el presupuesto del proveedor corta antes que el kill de seguridad del puente');
});

test('el TTL de TV en vivo y deportes vive entre 2 y 4 horas', () => {
  const live = ttlFor(true);

  assert.ok(live >= 2 * HOUR, `live >= 2h (es ${live})`);
  assert.ok(live <= 4 * HOUR, `live <= 4h (es ${live})`);
  assert.equal(ttlPolicy().liveMinMs, 2 * HOUR);
  assert.equal(ttlPolicy().liveMaxMs, 4 * HOUR);
});

test('el TTL de películas, series y anime se queda en la ventana de 2 a 6 horas', () => {
  const vod = ttlFor(false);

  assert.ok(vod >= 2 * HOUR && vod <= 6 * HOUR, `vod entre 2h y 6h (es ${vod})`);
});

test('un TTL de vivo fuera de rango no arranca el servidor', () => {
  const tooLong = bootWith({ CACHE_TTL_LIVE_HOURS: '5' });

  assert.notEqual(tooLong.status, 0, 'debe fallar al arrancar');
  assert.match(`${tooLong.stderr}${tooLong.stdout}`, /CACHE_TTL_LIVE_HOURS/);
});

test('un TTL de vivo dentro de rango sí arranca, y se respeta', () => {
  const ok = bootWith({ CACHE_TTL_LIVE_HOURS: '4' });
  assert.equal(ok.status, 0, ok.stderr);

  const tooShort = bootWith({ CACHE_TTL_LIVE_HOURS: '1' });
  assert.notEqual(tooShort.status, 0, 'menos de 2 horas tampoco es válido');
});

test('los tipos de contenido y los recursos del manifest son los esperados', () => {
  assert.deepEqual([...config.addon.types].sort(), ['anime', 'movie', 'series', 'tv']);
  assert.deepEqual(config.addon.resources, ['stream']);
});
