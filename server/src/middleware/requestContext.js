/**
 * middleware/requestContext.js
 * ---------------------------------------------------------------------------
 * Attaches a request id, a per-request child logger and emits one access log
 * line per response with the latency (important to prove the "live stream hit
 * returns in milliseconds" requirement).
 */

import { randomUUID } from 'node:crypto';
import { createLogger } from '../lib/logger.js';

const log = createLogger('http');

export function requestContext(req, res, next) {
  req.id = req.headers['x-request-id'] || randomUUID();
  req.startedAt = process.hrtime.bigint();
  res.setHeader('X-Request-Id', req.id);

  res.on('finish', () => {
    const ms = Number(process.hrtime.bigint() - req.startedAt) / 1e6;
    const meta = {
      id: req.id,
      method: req.method,
      path: req.originalUrl,
      status: res.statusCode,
      ms: Number(ms.toFixed(2)),
      ...(res.locals.cacheOutcome ? { cache: res.locals.cacheOutcome } : {}),
    };
    /* 5xx via error handler is already logged there; avoid double noise. */
    if (res.statusCode >= 500) log.error('request failed', meta);
    else if (res.statusCode >= 400) log.warn('request rejected', meta);
    else log.info('request', meta);
  });

  next();
}

/**
 * Small helper: marks the cache outcome for the access log.
 * Values: hit | stale | miss | coalesced | bypass
 */
export function markCacheOutcome(res, outcome) {
  res.locals.cacheOutcome = outcome;
}

export default { requestContext, markCacheOutcome };
