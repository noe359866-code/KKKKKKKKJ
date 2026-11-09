/** Protected catalog-cache maintenance endpoints, disabled unless ADMIN_TOKEN is set. */

import { Router } from 'express';
import { timingSafeEqual } from 'node:crypto';

import config from '../config.js';
import { cache } from '../cache/TtlCache.js';

export const adminRouter = Router();

/** Constant-time token comparison. */
function safeEqual(a = '', b = '') {
  const bufA = Buffer.from(String(a));
  const bufB = Buffer.from(String(b));
  if (bufA.length !== bufB.length) return false;
  return timingSafeEqual(bufA, bufB);
}

adminRouter.use('/admin', (req, res, next) => {
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
  const before = cache.stats().size;
  cache.clear();
  return res.json({ ok: true, flushed: before });
});

export default adminRouter;
