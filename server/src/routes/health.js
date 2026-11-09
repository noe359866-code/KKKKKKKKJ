/** Liveness/readiness probe plus safe catalog/cache diagnostics. */

import { Router } from 'express';
import config from '../config.js';
import { cache } from '../cache/TtlCache.js';
import { getCatalogs } from '../catalogs/registry.js';

export const healthRouter = Router();

function healthPayload() {
  const memory = process.memoryUsage();
  const catalogs = getCatalogs();
  return {
    status: 'ok',
    service: 'streamflix-catalog-addon',
    version: config.addon.version,
    env: config.env,
    uptimeSeconds: Math.round(process.uptime()),
    now: new Date().toISOString(),
    addon: {
      id: config.addon.id,
      types: config.addon.types,
      resources: config.addon.resources,
    },
    catalogs: {
      count: catalogs.length,
      published: catalogs.map((catalog) => ({
        type: catalog.type,
        id: catalog.id,
        name: catalog.name,
        posterShape: catalog.posterShape,
        source: catalog.source.kind,
      })),
      cinemetaBase: config.catalogs.cinemetaBase,
    },
    cache: cache.stats(),
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
