/**
 * routes/admin.js
 * ---------------------------------------------------------------------------
 * Protected maintenance endpoints. Disabled unless ADMIN_TOKEN is set.
 *
 *   GET    /admin/cache                -> cache stats + a sample of keys
 *   DELETE /admin/cache?type=&id=      -> invalidate one entry (or everything)
 *   POST   /admin/warm                 -> pre-warm the cache for a list of ids
 *   GET    /admin/providers            -> provider list ordered by priority
 *
 * Auth: `Authorization: Bearer <ADMIN_TOKEN>` or `?token=<ADMIN_TOKEN>`.
 */

import { Router } from 'express';
import { timingSafeEqual } from 'node:crypto';

import config from '../config.js';
import { cache } from '../cache/TtlCache.js';
import { getProviders } from '../providers/index.js';
import { getStreams, invalidate, cacheKey } from '../services/streamService.js';
import { asyncHandler } from '../middleware/errors.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('admin');

export const adminRouter = Router();

/** Constant-time token comparison (avoids trivial timing attacks). */
function safeEqual(a = '', b = '') {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

adminRouter.use('/admin', (req, res, next) => {
  /* No token configured -> feature off, answer as if the route didn't exist. */
  if (!config.http.adminToken) {
    return res.status(404).json({ err: 'not found', code: 'NOT_FOUND' });
  }

  const header = req.get('authorization') || '';
  const bearer = header.toLowerCase().startsWith('bearer ') ? header.slice(7).trim() : '';
  const token = bearer || String(req.query.token || '');

  if (!safeEqual(token, config.http.adminToken)) {
    return res.status(401).json({ err: 'unauthorized', code: 'UNAUTHORIZED' });
  }
  return next();
});

adminRouter.get('/admin/cache', (req, res) => {
  res.json({
    stats: cache.stats(),
    keys: cache.keys(Number(req.query.limit) || 50),
  });
});

adminRouter.delete('/admin/cache', (req, res) => {
  const { type, id } = req.query;

  if (id) {
    if (!type) return res.status(400).json({ err: 'type is required when id is provided', code: 'BAD_REQUEST' });
    const key = cacheKey(type, id);
    const removed = invalidate({ type, id });
    log.info('cache entry invalidated', { key, removed });
    return res.json({ ok: true, key, removed });
  }

  const before = cache.stats().size;
  invalidate({});
  const after = cache.stats().size;
  log.info('cache flushed', { removed: before - after });
  return res.json({ ok: true, flushed: before - after });
});

adminRouter.post(
  '/admin/warm',
  asyncHandler(async (req, res) => {
    const items = Array.isArray(req.body?.items) ? req.body.items : [];
    if (items.length === 0) {
      return res.status(400).json({ err: 'body.items[] is required', code: 'BAD_REQUEST' });
    }
    if (items.length > 25) {
      return res.status(400).json({ err: 'at most 25 items per call', code: 'BAD_REQUEST' });
    }

    const results = [];
    for (const item of items) {
      if (!item?.type || !item?.id) {
        results.push({ type: item?.type, id: item?.id, error: 'type and id are required' });
        continue;
      }
      const started = Date.now();
      // eslint-disable-next-line no-await-in-loop
      const result = await getStreams({ type: item.type, id: item.id, bypassCache: Boolean(item.refresh) });
      results.push({
        type: item.type,
        id: item.id,
        streams: result.streams.length,
        cached: result.cached,
        ms: Date.now() - started,
      });
    }

    return res.json({ ok: true, results });
  }),
);

adminRouter.get('/admin/providers', (req, res) => {
  res.json({
    providers: getProviders().map((provider) => ({
      id: provider.id,
      label: provider.label,
      priority: provider.priority,
      kind: provider.kind,
      script: provider.script,
      enabled: provider.enabled,
    })),
  });
});

export default adminRouter;
