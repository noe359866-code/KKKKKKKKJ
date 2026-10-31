/**
 * routes/health.js
 * ---------------------------------------------------------------------------
 * GET /health  (alias /healthz)
 *
 * Liveness/readiness probe + live observability for the cache. Exposes no
 * secrets and no stream URLs, only counters and configuration that is safe to
 * show publicly.
 */

import { Router } from 'express';
import config from '../config.js';
import { cache } from '../cache/TtlCache.js';
import { getProvidersMeta, getProviders } from '../providers/index.js';
import { ttlPolicy } from '../services/streamService.js';
import { getCatalogs } from '../catalogs/registry.js';
import { liveShelfStatus } from '../services/catalogService.js';

export const healthRouter = Router();

function healthPayload() {
  const memory = process.memoryUsage();
  return {
    status: 'ok',
    service: 'streamflix-stremio-addon',
    version: config.addon.version,
    env: config.env,
    uptimeSeconds: Math.round(process.uptime()),
    now: new Date().toISOString(),
    addon: {
      id: config.addon.id,
      types: config.addon.types,
      resources: config.addon.resources,
      liveTtlHours: Number((ttlPolicy().liveMs / 3_600_000).toFixed(2)),
      vodTtlHours: Number((ttlPolicy().vodMs / 3_600_000).toFixed(2)),
    },
    catalogs: {
      published: getCatalogs().map((catalog) => ({
        type: catalog.type,
        id: catalog.id,
        name: catalog.name,
        posterShape: catalog.posterShape,
        source: catalog.source.kind,
      })),
      cinemetaBase: config.catalogs.cinemetaBase,
    },
    /* Canales y agenda que publican los proveedores de directo: si `loaded` es
     * false, `tv/agenda` sale vacío a propósito y no hay nada que arreglar en el
     * cliente. */
    liveShelf: liveShelfStatus(),
    cache: cache.stats(),
    providers: {
      ...getProvidersMeta(),
      enabled: getProviders().map((provider) => ({
        id: provider.id,
        label: provider.label,
        priority: provider.priority,
        kind: provider.kind,
        script: provider.script,
      })),
    },
    memory: {
      rssMb: Number((memory.rss / 1024 / 1024).toFixed(1)),
      heapUsedMb: Number((memory.heapUsed / 1024 / 1024).toFixed(1)),
    },
    node: process.version,
  };
}

healthRouter.get('/health', (req, res) => res.json(healthPayload()));
healthRouter.get('/healthz', (req, res) => res.json(healthPayload()));

export default healthRouter;
