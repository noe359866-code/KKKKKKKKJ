/**
 * src/bridge/pythonBridge.js
 * ---------------------------------------------------------------------------
 * Node <-> Python bridge.
 *
 * Each scraper is a standalone .py script that reads a JSON payload on stdin
 * and prints a JSON result on stdout:
 *
 *   $ echo '{"id":"tt1254207","type":"movie"}' | python3 scrapers/example_provider.py
 *   {"streams":[{"name":"...","title":"...","url":"https://...m3u8", ...}]}
 *
 * Why spawn() and not exec()?
 *  - exec() runs through a shell -> injection risk and arg-escaping pain.
 *  - spawn() gives us a real process handle: we can SIGKILL a hung scraper,
 *    cap stdout/stderr size, and stream logs line by line.
 *
 * Why JSON over stdin and not CLI args? Ids like "tt0944947:2:5" are safe, but
 * payloads will grow (locale, season packs). Stdin keeps the contract stable.
 */

import { spawn } from 'node:child_process';
import path from 'node:path';
import { access } from 'node:fs/promises';

import config from '../config.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('bridge');

const MAX_OUTPUT_BYTES = 1024 * 1024; // 1 MB: a stream list is never bigger.

/** Simple semaphore so we never spawn more than `maxConcurrency` pythons. */
class Semaphore {
  constructor(limit) {
    this.limit = limit;
    this.active = 0;
    this.queue = [];
  }

  async acquire() {
    if (this.active < this.limit) {
      this.active += 1;
      return;
    }
    await new Promise((resolve) => this.queue.push(resolve));
    this.active += 1;
  }

  release() {
    this.active -= 1;
    const next = this.queue.shift();
    if (next) next();
  }
}

const semaphore = new Semaphore(config.scrapers.maxConcurrency);

/** Cache of "does this script exist" checks, avoids a stat per request. */
const scriptExistsCache = new Map();

async function resolveScript(scriptName) {
  if (scriptExistsCache.has(scriptName)) return scriptExistsCache.get(scriptName);

  /* Only allow plain file names inside SCRAPER_DIR: prevents path traversal
   * like "../../etc/passwd" if a provider name ever comes from user input. */
  const safeName = path.basename(scriptName);
  if (!safeName.endsWith('.py')) return null;

  const fullPath = path.join(config.scrapers.dir, safeName);
  let exists = true;
  try {
    await access(fullPath);
  } catch {
    exists = false;
  }

  const resolved = exists ? fullPath : null;
  scriptExistsCache.set(scriptName, resolved);
  return resolved;
}

/**
 * Runs a Python script of the scraper directory and resolves with its raw JSON.
 *
 * `runScraper` is the stream-shaped adapter on top of this: some scripts answer
 * with `{streams, meta}`, others (like the live shelf builder) with their own
 * document. Both share the spawn/timeout/kill behaviour.
 *
 * @param {string} scriptName  file name inside SCRAPER_DIR, e.g. "demo_provider.py"
 * @param {object} payload     JSON-serialisable input for the script
 * @param {object} [options]
 * @param {number} [options.timeoutMs]
 * @returns {Promise<{ ok: boolean, data: any, meta: object }>}
 */
export async function runScript(scriptName, payload = {}, options = {}) {
  const timeoutMs = options.timeoutMs ?? config.scrapers.timeoutMs;
  const scriptPath = await resolveScript(scriptName);

  if (!scriptPath) {
    log.warn('scraper script not found', { scriptName, dir: config.scrapers.dir });
    return { ok: false, data: null, meta: { reason: 'script_not_found' } };
  }

  await semaphore.acquire();
  const startedAt = process.hrtime.bigint();

  try {
    return await new Promise((resolve) => {
      const child = spawn(config.scrapers.python, [scriptPath], {
        cwd: config.scrapers.dir,
        env: {
          ...process.env,
          ...config.scrapers.extraEnv,
          ...(options.env || {}),
        },
        /* Detached so the child gets its own process group; SIGKILL on
         * timeout kills grandchildren too (python may spawn yt-dlp etc.). */
        detached: process.platform !== 'win32',
        stdio: ['pipe', 'pipe', 'pipe'],
      });

      let stdout = '';
      let stderr = '';
      let settled = false;
      let killedByTimeout = false;

      const finish = (result) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        const durationMs = Number(process.hrtime.bigint() - startedAt) / 1e6;
        log.debug('scraper finished', {
          scriptName,
          durationMs: Number(durationMs.toFixed(1)),
          ok: result.ok,
          streams: result.data?.streams?.length ?? result.data?.length ?? 0,
          ...(result.meta || {}),
        });
        resolve(result);
      };

      const killTree = () => {
        killedByTimeout = true;
        try {
          if (process.platform === 'win32') child.kill('SIGKILL');
          else process.kill(-child.pid, 'SIGKILL'); // negative pid => group
        } catch {
          child.kill('SIGKILL');
        }
      };

      const timer = setTimeout(() => {
        log.warn('scraper timed out, killing', { scriptName, timeoutMs });
        killTree();
      }, timeoutMs);

      child.stdout.on('data', (chunk) => {
        if (stdout.length < MAX_OUTPUT_BYTES) stdout += chunk.toString('utf8');
      });

      child.stderr.on('data', (chunk) => {
        /* Python tracebacks are valuable in dev; keep a bounded tail. */
        stderr = (stderr + chunk.toString('utf8')).slice(-4_000);
      });

      child.on('error', (error) => {
        finish({ ok: false, data: null, meta: { reason: 'spawn_error', error: error.message } });
      });

      child.on('close', (code, signal) => {
        if (killedByTimeout) {
          return finish({ ok: false, data: null, meta: { reason: 'timeout', timeoutMs } });
        }
        if (code !== 0) {
          log.warn('scraper exited with error', { scriptName, code, signal, stderr: stderr.slice(0, 500) });
          return finish({ ok: false, data: null, meta: { reason: 'exit_error', code, stderr: stderr.slice(-500) } });
        }

        try {
          const parsed = JSON.parse(stdout);
          finish({ ok: true, data: parsed, meta: parsed?.meta || {} });
        } catch (error) {
          log.warn('invalid JSON from scraper', { scriptName, error: error.message, sample: stdout.slice(0, 300) });
          finish({ ok: false, data: null, meta: { reason: 'invalid_json', error: error.message } });
        }
      });

      /* Send the payload; ignore EPIPE if the child dies before reading. */
      child.stdin.on('error', () => {});
      child.stdin.end(JSON.stringify(payload));
    });
  } finally {
    semaphore.release();
  }
}

/**
 * Stream-shaped adapter: `{ streams, meta }` or a bare array.
 *
 * @returns {Promise<{ ok: boolean, streams: object[], meta: object }>}
 */
export async function runScraper(scriptName, payload = {}, options = {}) {
  const result = await runScript(scriptName, payload, options);
  if (!result.ok) return { ok: false, streams: [], meta: result.meta };
  const parsed = result.data;
  try {
    /* The script may return either { streams: [...] } or [...] */
    const streams = Array.isArray(parsed) ? parsed : parsed.streams || [];
    if (!Array.isArray(streams)) throw new Error('"streams" is not an array');
    return { ok: true, streams, meta: result.meta };
  } catch (error) {
    log.warn('invalid scraper payload', { scriptName, error: error.message });
    return { ok: false, streams: [], meta: { reason: 'invalid_json', error: error.message } };
  }
}

export default { runScraper, runScript };
