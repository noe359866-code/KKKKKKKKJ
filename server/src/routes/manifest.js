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

export const manifestRouter = Router();

/** Resolves the public origin, honouring PUBLIC_URL and reverse proxies. */
export function getBaseUrl(req) {
  if (config.addon.publicUrl) return config.addon.publicUrl;
  const proto = req.protocol || 'http';
  const host = req.get('host') || `${config.http.host}:${config.http.port}`;
  return `${proto}://${host}`;
}

function sendManifest(req, res) {
  const manifest = buildManifest(getBaseUrl(req));

  /* Manifests are static: let Stremio cache them for an hour. */
  res.setHeader('Cache-Control', 'public, max-age=3600');
  res.json(manifest);
}

manifestRouter.get('/manifest.json', sendManifest);

/* Stremio sometimes appends a config segment: /<config>/manifest.json.
 * We don't use config yet, but accepting it avoids "addon invalid" errors. */
manifestRouter.get('/:config/manifest.json', sendManifest);

export default manifestRouter;
