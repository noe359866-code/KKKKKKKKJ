/**
 * routes/stream.js
 * ---------------------------------------------------------------------------
 * GET /stream/:type/:id.json
 *
 * Response (Stremio protocol):
 *   {
 *     "streams": [ { name, title, url, behaviorHints: { requestHeaders } } ],
 *     "cacheMaxAge": 21600,        // seconds, mirrors the TTL policy
 *     "staleRevalidate": 3600,
 *     "staleError": 600
 *   }
 *
 * The array is ordered by provider priority so ExoPlayer can fail over from
 * source 1 to source 2 to source 3 automatically.
 */

import { Router } from 'express';
import { getStreams, ttlPolicy } from '../services/streamService.js';
import { isSupportedType } from '../lib/stremio.js';
import { normalizeStreamId } from '../lib/ids.js';
import { asyncHandler } from '../middleware/errors.js';
import { markCacheOutcome } from '../middleware/requestContext.js';
import { createLogger } from '../lib/logger.js';
import config from '../config.js';

const log = createLogger('route:stream');

export const streamRouter = Router();

/**
 * @param {import('express').Request} req
 * @param {import('express').Response} res
 */
async function handleStream(req, res) {
  const type = String(req.params.type || '').toLowerCase();
  const id = normalizeStreamId(req.params.id);

  if (!type || !id) {
    return res.status(400).json({ err: 'type and id are required', code: 'BAD_REQUEST' });
  }

  if (!isSupportedType(type)) {
    return res.status(404).json({
      err: `unsupported type "${type}"`,
      code: 'UNSUPPORTED_TYPE',
      supported: config.addon.types,
    });
  }

  if (id.length > 512) {
    return res.status(400).json({ err: 'id too long', code: 'BAD_REQUEST' });
  }

  const result = await getStreams({ type, id });

  markCacheOutcome(
    res,
    result.negative ? 'miss:empty' : result.cached ? (result.stale ? 'stale' : 'hit') : result.coalesced ? 'coalesced' : 'miss',
  );

  /* Never tell the client to cache more than the entry will actually live:
   * negative entries expire after CACHE_TTL_MISS_SECONDS, not after the full
   * content TTL, and a warm hit only has the remaining TTL left. */
  const cacheMaxAge = result.negative
    ? Math.floor(config.cache.ttlMissMs / 1000)
    : Math.floor((result.ttlRemainingMs ?? result.ttlMs) / 1000);
  res.setHeader('Cache-Control', `public, max-age=${Math.min(cacheMaxAge, 3600)}`);
  res.setHeader('X-Cache', result.cached ? (result.stale ? 'STALE' : 'HIT') : result.coalesced ? 'COALESCED' : 'MISS');
  res.setHeader('X-Response-Time', `${result.durationMs.toFixed(1)}ms`);

  log.debug('stream response', {
    type,
    id,
    streams: result.streams.length,
    live: result.isLive,
    cached: result.cached,
    stale: result.stale,
    coalesced: result.coalesced,
    ms: result.durationMs,
    providers: result.providers,
  });

  return res.json({
    streams: result.streams,
    cacheMaxAge,
    /* Client-side hints from the Stremio SDK: how long stale data is still
     * acceptable while the addon refreshes in the background. */
    staleRevalidate: Math.floor(result.ttlMs / 1000),
    staleError: Math.floor(ttlPolicy().missMs / 1000),
  });
}

/**
 * HEAD must be registered BEFORE the GET routes.
 *
 * Express lets a GET handler answer HEAD requests, so a HEAD registered after
 * `router.get(...)` would never run and the probe would trigger a full scrape.
 * Clients/proxies use HEAD to test reachability; that must stay free.
 */
const headProbe = (req, res) => {
  res.setHeader('X-Cache', 'BYPASS');
  res.status(200).end();
};
streamRouter.head('/stream/:type/:id.json', headProbe);
streamRouter.head('/stream/:type/:id', headProbe);

streamRouter.get('/stream/:type/:id.json', asyncHandler(handleStream));

/**
 * Legacy/short form used by some Stremio builds: /stream/:type/:id (no .json)
 */
streamRouter.get('/stream/:type/:id', asyncHandler(handleStream));

export default streamRouter;
