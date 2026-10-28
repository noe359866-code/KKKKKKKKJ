/**
 * middleware/errors.js
 * ---------------------------------------------------------------------------
 * Stremio expects JSON error bodies shaped as `{ err: "message" }`.
 * We keep that contract for protocol routes and a generic JSON for the rest.
 */

import config from '../config.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('errors');

/** 404 fallback. */
export function notFoundHandler(req, res) {
  res.status(404).json({
    err: `not found: ${req.method} ${req.path}`,
    code: 'NOT_FOUND',
  });
}

/** Central error handler (must keep the 4-arg signature for Express). */
// eslint-disable-next-line no-unused-vars
export function errorHandler(error, req, res, next) {
  const status = Number.isInteger(error.status) ? error.status : 500;

  if (status >= 500) {
    log.error('unhandled error', {
      id: req.id,
      path: req.originalUrl,
      error: error.message,
      stack: config.isProduction ? undefined : error.stack,
    });
  }

  if (res.headersSent) return res.end();

  res.status(status).json({
    err: error.expose === false ? 'internal error' : error.message,
    code: error.code || (status >= 500 ? 'INTERNAL_ERROR' : 'BAD_REQUEST'),
    ...(config.isProduction ? {} : { stack: error.stack?.split('\n').slice(0, 5) }),
  });
}

/** Wraps async Express handlers so rejected promises reach errorHandler. */
export function asyncHandler(fn) {
  return (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
}

export default { notFoundHandler, errorHandler, asyncHandler };
