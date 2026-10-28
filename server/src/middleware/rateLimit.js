/**
 * middleware/rateLimit.js
 * ---------------------------------------------------------------------------
 * Minimal fixed-window rate limiter (no dependency).
 *
 * Android TV boxes and ExoPlayer retry aggressively when a stream fails, so an
 * unlimited /stream endpoint can start a Python stampede. The cache already
 * coalesces identical keys; this limiter protects the whole process.
 *
 * Default: 120 requests per minute per IP. Disable with RATE_LIMIT_MAX=0.
 */

import config from '../config.js';

const WINDOW_MS = 60_000;

export function createRateLimiter({
  max = config.http.rateLimitMax,
  windowMs = WINDOW_MS,
  pathPrefixes = null,
} = {}) {
  const buckets = new Map(); // ip -> { count, resetAt }

  /* Purge stale buckets so memory stays bounded. */
  const timer = setInterval(() => {
    const now = Date.now();
    for (const [ip, bucket] of buckets) {
      if (bucket.resetAt <= now) buckets.delete(ip);
    }
  }, windowMs);
  timer.unref?.();

  return function rateLimit(req, res, next) {
    if (!max || max <= 0) return next();
    if (pathPrefixes && !pathPrefixes.some((prefix) => req.path.startsWith(prefix))) return next();

    const ip = req.ip || req.socket.remoteAddress || 'unknown';
    const now = Date.now();
    let bucket = buckets.get(ip);

    if (!bucket || bucket.resetAt <= now) {
      bucket = { count: 0, resetAt: now + windowMs };
      buckets.set(ip, bucket);
    }

    bucket.count += 1;

    res.setHeader('X-RateLimit-Limit', max);
    res.setHeader('X-RateLimit-Remaining', Math.max(0, max - bucket.count));
    res.setHeader('X-RateLimit-Reset', Math.ceil(bucket.resetAt / 1000));

    if (bucket.count > max) {
      res.setHeader('Retry-After', Math.ceil((bucket.resetAt - now) / 1000));
      return res.status(429).json({ err: 'too many requests', code: 'RATE_LIMITED' });
    }

    return next();
  };
}

export default createRateLimiter;
