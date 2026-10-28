/**
 * src/providers/runner.js
 * ---------------------------------------------------------------------------
 * Runs every provider scraper concurrently and aggregates the results.
 *
 * Failover strategy:
 *   - All providers start at the same time (parallel, not sequential), so the
 *     response time is that of the slowest provider within the deadline, not
 *     the sum of all of them.
 *   - Each provider has its own hard timeout; a hung site never blocks the
 *     response.
 *   - Results are sorted by provider priority afterwards, which is what makes
 *     the returned `streams` array usable as an ordered failover list by
 *     ExoPlayer (Sitio 1, Sitio 2, Sitio 3...).
 *   - Optional "fast return": once N providers answered, the pool waits a short
 *     grace period and returns whatever is ready.
 */

import config from '../config.js';
import { createLogger } from '../lib/logger.js';
import { runScraper } from '../bridge/pythonBridge.js';

const log = createLogger('runner');

/**
 * Single provider execution.
 * @returns {Promise<ProviderResult>}
 */
export async function runProvider(provider, context) {
  const startedAt = Date.now();

  const payload = {
    provider: provider.id,
    type: context.type,
    id: context.id,
    baseId: context.parsed.base,
    imdbId: context.parsed.imdbId,
    season: context.parsed.season,
    episode: context.parsed.episode,
    isLive: context.isLive,
    isSports: context.parsed.isSports,
    /* Passed so the Python side can build absolute URLs / set the right
     * Referer for the final request if it resolves playlists itself. */
    referer: provider.referer,
    userAgent: provider.userAgent,
    timeoutMs: config.streams.providerTimeoutMs,
    ...provider.options,
  };

  try {
    const { ok, streams, meta } = await runScraper(provider.script, payload, {
      timeoutMs: config.streams.providerTimeoutMs,
    });

    return {
      provider,
      status: ok ? (streams.length > 0 ? 'ok' : 'empty') : meta?.reason || 'error',
      streams,
      meta,
      durationMs: Date.now() - startedAt,
    };
  } catch (error) {
    log.warn('provider crashed', { provider: provider.id, error: error.message });
    return {
      provider,
      status: 'error',
      streams: [],
      meta: { error: error.message },
      durationMs: Date.now() - startedAt,
    };
  }
}

/**
 * Runs a pool of providers.
 *
 * @param {Array} providers - already sorted by priority.
 * @param {object} context   - { type, id, parsed, isLive }
 * @param {object} [options]
 * @param {number} [options.deadlineMs]      - maximum total wait.
 * @param {number} [options.minBeforeGrace]  - providers needed to start grace.
 * @param {number} [options.graceMs]         - extra wait after that point.
 * @returns {Promise<{ results: ProviderResult[], timedOut: boolean, durationMs: number }>}
 */
export async function runProviderPool(providers, context, options = {}) {
  const {
    deadlineMs = config.streams.providerTimeoutMs + config.streams.fastReturnGraceMs,
    minBeforeGrace = config.streams.minProvidersForFastReturn,
    graceMs = config.streams.fastReturnGraceMs,
  } = options;

  if (providers.length === 0) {
    return { results: [], timedOut: false, durationMs: 0 };
  }

  const startedAt = Date.now();
  const results = new Array(providers.length).fill(null);
  const inFlight = new Set();

  let answered = 0;
  let settled = 0;
  let graceTimer = null;

  /** Resolver injected below; called when the pool should stop waiting. */
  let releasePool;
  const poolDone = new Promise((resolve) => {
    releasePool = resolve;
  });

  /* Declared with `let` so stopWaiting() (defined first) can clear it. */
  let globalTimer = null;

  const stopWaiting = () => {
    if (graceTimer) {
      clearTimeout(graceTimer);
      graceTimer = null;
    }
    if (globalTimer) {
      clearTimeout(globalTimer);
      globalTimer = null;
    }
    releasePool();
  };

  globalTimer = setTimeout(() => {
    log.warn('provider pool deadline reached', {
      providers: providers.length,
      settled,
      pending: providers.length - settled,
      deadlineMs,
    });
    stopWaiting();
  }, deadlineMs);
  globalTimer.unref?.();

  providers.forEach((provider, index) => {
    const task = runProvider(provider, context)
      .then((result) => {
        results[index] = result;
        if (result.streams.length > 0) answered += 1;
      })
      .catch(() => {
        results[index] = { provider, status: 'error', streams: [], durationMs: 0 };
      })
      .finally(() => {
        settled += 1;
        inFlight.delete(task);
        if (settled === providers.length) return stopWaiting();

        /* Enough sources to start playing: don't wait for the stragglers. */
        if (answered >= minBeforeGrace && !graceTimer) {
          graceTimer = setTimeout(stopWaiting, graceMs);
          graceTimer.unref?.();
        }
      });

    inFlight.add(task);
  });

  await poolDone;

  const durationMs = Date.now() - startedAt;
  const timedOut = settled < providers.length;

  /* Late providers keep running in the background (their results would arrive
   * after the HTTP response). We log them for observability only. */
  if (timedOut && inFlight.size > 0) {
    Promise.allSettled([...inFlight]).then(() => {
      log.debug('late providers settled (discarded)', { providers: inFlight.size });
    });
  }

  /* Keep the priority order: index follows the sorted providers array. */
  const ordered = results
    .map((result, index) => result || { provider: providers[index], status: 'timeout', streams: [], durationMs: null });

  return { results: ordered, timedOut, durationMs };
}

export default { runProvider, runProviderPool };
