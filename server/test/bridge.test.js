/**
 * test/bridge.test.js
 * ---------------------------------------------------------------------------
 * Contract tests for `src/bridge/pythonBridge.js`: the layer that runs the
 * Python scrapers.
 *
 * Everything here is about the guarantees the addon depends on:
 *
 *   - the payload travels as JSON on **stdin** (no shell, no argv interpolation);
 *   - stdout is parsed as JSON and `{ streams, meta }` is returned;
 *   - a script that ignores the budget is SIGKILLed and reported as `timeout`
 *     instead of hanging the request;
 *   - invalid JSON, a crash or a missing script become a normal result with a
 *     `reason`, never an exception;
 *   - `SCRAPER_DIR` cannot be escaped with a path-traversal script name;
 *   - the concurrency semaphore releases its slot on every outcome.
 *
 * The fixtures live in `test/fixtures/bridge/` and are plain .py files, so the
 * Node test runner ignores them.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/* Configure the bridge before importing it: config.js reads the env at load. */
process.env.SCRAPER_DIR = path.join(__dirname, 'fixtures', 'bridge');
process.env.SCRAPER_MAX_CONCURRENCY = '4';
process.env.SCRAPER_TIMEOUT_MS = '15000';
process.env.LOG_LEVEL = 'error';

const { runScraper } = await import('../src/bridge/pythonBridge.js');

const TIMEOUT_BUDGET_MS = 600;

test('el payload viaja por stdin y la respuesta se parsea como JSON', async () => {
  const result = await runScraper('echo_provider.py', { type: 'movie', id: 'gnula:street-fighter' });

  assert.equal(result.ok, true);
  assert.equal(result.streams.length, 1);
  assert.equal(result.streams[0].name, 'Echo');
  /* El propio script ecoa lo que recibió: si el stdin no llegó, esto falla. */
  assert.equal(result.meta.id, 'gnula:street-fighter');
  assert.equal(result.meta.type, 'movie');
});

test('ids con caracteres raros no se interpretan por el shell', async () => {
  const nasty = `"; rm -rf / #'$(id)&|{a,b}<x>`;
  const result = await runScraper('echo_provider.py', { type: 'movie', id: nasty });

  assert.equal(result.ok, true);
  assert.equal(result.meta.id, nasty, 'el id llega intacto: no hay shell de por medio');
});

test('un script más lento que el presupuesto se mata y se reporta como timeout', async () => {
  const startedAt = Date.now();
  const result = await runScraper('slow_provider.py', { type: 'movie', id: 'x' }, {
    timeoutMs: TIMEOUT_BUDGET_MS,
  });
  const elapsed = Date.now() - startedAt;

  assert.equal(result.ok, false);
  assert.equal(result.meta.reason, 'timeout');
  assert.equal(result.meta.timeoutMs, TIMEOUT_BUDGET_MS);
  assert.equal(result.streams.length, 0);
  assert.ok(elapsed < 5_000, `debe cortar en el presupuesto, no esperar los 30 s (tardó ${elapsed} ms)`);
});

test('una salida que no es JSON no rompe el puente', async () => {
  const result = await runScraper('bad_json_provider.py', { type: 'movie', id: 'x' });

  assert.equal(result.ok, false);
  assert.equal(result.meta.reason, 'invalid_json');
  assert.deepEqual(result.streams, []);
});

test('un script que falla deja el motivo y el final del stderr', async () => {
  const result = await runScraper('crash_provider.py', { type: 'movie', id: 'x' });

  assert.equal(result.ok, false);
  assert.equal(result.meta.reason, 'exit_error');
  assert.equal(result.meta.code, 3);
  assert.match(result.meta.stderr, /Traceback/);
});

test('un script inexistente devuelve script_not_found', async () => {
  const result = await runScraper('no_existe.py', { type: 'movie', id: 'x' });

  assert.equal(result.ok, false);
  assert.equal(result.meta.reason, 'script_not_found');
});

test('no se puede salir de SCRAPER_DIR con el nombre del script', async () => {
  /* `test/fixtures/outside_provider.py` existe y sí imprime streams: si el
   * puente lo ejecutara, `ok` sería true. El nombre se reduce a su basename y
   * se busca dentro de SCRAPER_DIR, así que nunca se alcanza. */
  for (const attempt of [
    '../../../etc/passwd.py',
    '../outside_provider.py',
    '../../fixtures/outside_provider.py',
  ]) {
    const result = await runScraper(attempt, { type: 'movie', id: 'x' });
    assert.equal(result.ok, false, `${attempt} no debe ejecutarse`);
    assert.equal(result.meta.reason, 'script_not_found');
  }
});

test('el semáforo libera su plaza en todos los desenlaces', async () => {
  /* 8 ejecuciones en paralelo con un tope de 4: si alguna plaza se perdiera,
   * esta promesa no resolvería nunca. */
  const runs = await Promise.all([
    runScraper('echo_provider.py', { id: 'a' }),
    runScraper('echo_provider.py', { id: 'b' }),
    runScraper('crash_provider.py', { id: 'c' }),
    runScraper('bad_json_provider.py', { id: 'd' }),
    runScraper('echo_provider.py', { id: 'e' }),
    runScraper('no_existe.py', { id: 'f' }),
    runScraper('echo_provider.py', { id: 'g' }, { timeoutMs: 500 }),
    runScraper('echo_provider.py', { id: 'h' }),
  ]);

  assert.equal(runs.length, 8);
  assert.ok(runs.every((run) => typeof run.ok === 'boolean'), 'todas resueltas con un resultado');
  assert.ok(runs.filter((run) => run.ok).length >= 4, 'las ejecuciones válidas no se quedan en la cola');
  assert.ok(runs.some((run) => run.meta.reason === 'script_not_found'));
  assert.ok(runs.some((run) => run.meta.reason === 'exit_error'));
  assert.ok(runs.some((run) => run.meta.reason === 'invalid_json'));
});
