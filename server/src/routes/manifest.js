/**
 * routes/manifest.js
 * ---------------------------------------------------------------------------
 * GET /manifest.json
 *
 * Returns the Stremio Addon manifest declaring the supported types
 * (movie, series, anime, tv) and the stream resource.
 *
 * Also served at /:config?/manifest.json so clients configured with a
 * Stremio-style configuration path keep working unchanged.
 */

import { Router } from 'express';
import config from '../config.js';
import { buildManifest } from '../lib/stremio.js';
import { ensureCatalogs } from '../catalogs/registry.js';
import { asyncHandler } from '../middleware/errors.js';

export const manifestRouter = Router();

/** Resolves the public origin, honouring PUBLIC_URL and reverse proxies. */
export function getBaseUrl(req) {
  if (config.addon.publicUrl) return config.addon.publicUrl;
  const proto = req.protocol || 'http';
  const host = req.get('host') || `${config.http.host}:${config.http.port}`;
  return `${proto}://${host}`;
}

async function sendManifest(req, res) {
  /* El manifest publica los catálogos: hay que tenerlos leídos de disco antes
   * de responder, o el primer cliente vería una lista vacía. */
  await ensureCatalogs();
  const manifest = buildManifest(getBaseUrl(req));

  /* Manifests are static: let Stremio cache them for an hour. */
  res.setHeader('Cache-Control', 'public, max-age=3600');
  res.json(manifest);
}

manifestRouter.get('/manifest.json', asyncHandler(sendManifest));

/* Stremio sometimes appends a config segment: /<config>/manifest.json.
 * We don't use config yet, but accepting it avoids "addon invalid" errors. */
manifestRouter.get('/:config/manifest.json', asyncHandler(sendManifest));

export default manifestRouter;
