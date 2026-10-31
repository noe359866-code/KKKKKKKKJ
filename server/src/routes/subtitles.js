/**
 * routes/subtitles.js
 * ---------------------------------------------------------------------------
 * GET /subtitles/:type/:id.json
 *
 * Recurso `subtitles` del protocolo Stremio:
 *
 *   { "subtitles": [ { "id", "url", "lang", "label" } ], "cacheMaxAge": 21600 }
 *
 * Sale del **mismo scrape** que las fuentes: `getStreams()` ya devuelve la
 * lista en `result.subtitles`, así que esta ruta no añade ni una petición a los
 * proveedores ni una entrada de caché nueva. Para el cliente propio no hace
 * falta (la app los lee del cuerpo de `/stream/...`), pero cualquier cliente
 * Stremio puede pedirla y el manifiesto la declara.
 */

import { Router } from 'express';
import { getStreams, ttlPolicy } from '../services/streamService.js';
import { isSupportedType } from '../lib/stremio.js';
import { normalizeStreamId } from '../lib/ids.js';
import { asyncHandler } from '../middleware/errors.js';
import { markCacheOutcome } from '../middleware/requestContext.js';
import { createLogger } from '../lib/logger.js';
import config from '../config.js';

const log = createLogger('route:subtitles');

export const subtitlesRouter = Router();

/**
 * @param {import('express').Request} req
 * @param {import('express').Response} res
 */
async function handleSubtitles(req, res) {
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

  const cacheMaxAge = result.negative
    ? Math.floor(config.cache.ttlMissMs / 1000)
    : Math.floor((result.ttlRemainingMs ?? result.ttlMs) / 1000);
  res.setHeader('Cache-Control', `public, max-age=${Math.min(cacheMaxAge, 3600)}`);
  res.setHeader('X-Cache', result.cached ? (result.stale ? 'STALE' : 'HIT') : result.coalesced ? 'COALESCED' : 'MISS');

  const subtitles = Array.isArray(result.subtitles) ? result.subtitles : [];
  log.debug('subtitles response', { type, id, subtitles: subtitles.length, cached: result.cached });

  return res.json({
    subtitles,
    cacheMaxAge,
    staleRevalidate: Math.floor(result.ttlMs / 1000),
    staleError: Math.floor(ttlPolicy().missMs / 1000),
  });
}

/* HEAD antes que GET, igual que en `/stream`: un sondeo de salud no debe
 * disparar un scrape completo. */
const headProbe = (req, res) => {
  res.setHeader('X-Cache', 'BYPASS');
  res.status(200).end();
};
subtitlesRouter.head('/subtitles/:type/:id.json', headProbe);
subtitlesRouter.head('/subtitles/:type/:id', headProbe);

subtitlesRouter.get('/subtitles/:type/:id.json', asyncHandler(handleSubtitles));
subtitlesRouter.get('/subtitles/:type/:id', asyncHandler(handleSubtitles));

export default subtitlesRouter;
