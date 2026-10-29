/**
 * src/app.js
 * ---------------------------------------------------------------------------
 * Express application wiring.
 *
 * Kept separate from server.js so tests can import `createApp()` and use it
 * with supertest / fetch without binding a port.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

import express from 'express';
import cors from 'cors';

import config from './config.js';
import { requestContext } from './middleware/requestContext.js';
import { errorHandler, notFoundHandler } from './middleware/errors.js';
import { createRateLimiter } from './middleware/rateLimit.js';

import { manifestRouter } from './routes/manifest.js';
import { streamRouter } from './routes/stream.js';
import { healthRouter } from './routes/health.js';
import { adminRouter } from './routes/admin.js';
import { proxyRouter } from './routes/proxy.js';
import { catalogRouter } from './routes/catalog.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC_DIR = path.resolve(__dirname, '../public');

/**
 * @param {object} [options]
 * @param {boolean} [options.rateLimit] - override for tests
 * @returns {import('express').Express}
 */
export function createApp(options = {}) {
  const app = express();

  /* Reverse proxies (nginx, Caddy, Fly, Railway) set X-Forwarded-*.
   * Required for accurate req.protocol / req.ip in the manifest URL. */
  if (config.http.trustProxy) app.set('trust proxy', 1);

  app.disable('x-powered-by');
  app.set('etag', 'strong');

  /* ------------------------------------------------------------------ */
  /* Global middleware                                                  */
  /* ------------------------------------------------------------------ */
  const corsOrigin = config.http.corsOrigins.includes('*') ? '*' : config.http.corsOrigins;
  app.use(
    cors({
      origin: corsOrigin,
      methods: ['GET', 'HEAD', 'POST', 'DELETE', 'OPTIONS'],
      allowedHeaders: ['Content-Type', 'Authorization', 'Range'],
      exposedHeaders: ['X-Cache', 'X-Response-Time', 'X-Request-Id', 'Content-Range', 'Accept-Ranges'],
      maxAge: 86400,
    }),
  );

  app.use(requestContext);

  /* Only the admin API needs a body. 64kb is enough for a warm-up list. */
  app.use(express.json({ limit: '64kb' }));

  /* Rate limiting applies to the heavy endpoints, never to probes. */
  app.use(
    createRateLimiter({
      max: options.rateLimit === undefined ? config.http.rateLimitMax : options.rateLimit,
      pathPrefixes: options.rateLimit === 0 ? null : ['/stream', '/proxy', '/catalog', '/meta'],
    }),
  );

  /* ------------------------------------------------------------------ */
  /* Routes                                                             */
  /* ------------------------------------------------------------------ */
  app.use(manifestRouter);
  app.use(catalogRouter);
  app.use(streamRouter);
  app.use(healthRouter);
  app.use(adminRouter);
  app.use(proxyRouter);

  /* Human-friendly landing page: check the server and copy the install URL. */
  app.get('/', (req, res) => res.sendFile(path.join(PUBLIC_DIR, 'index.html')));
  app.get('/favicon.ico', (req, res) => res.status(204).end());

  /* ------------------------------------------------------------------ */
  /* Errors                                                             */
  /* ------------------------------------------------------------------ */
  app.use(notFoundHandler);
  app.use(errorHandler);

  return app;
}

export default createApp;
