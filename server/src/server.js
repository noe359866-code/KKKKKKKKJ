/**
 * src/server.js
 * ---------------------------------------------------------------------------
 * Process entrypoint: loads providers, starts HTTP, handles graceful shutdown.
 */

import os from 'node:os';

import config from './config.js';
import { createApp } from './app.js';
import { loadProviders } from './providers/index.js';
import { cache } from './cache/TtlCache.js';
import { createLogger } from './lib/logger.js';

const log = createLogger('server');

/** First non-internal IPv4, useful to configure the Android TV client. */
function localIp() {
  for (const interfaces of Object.values(os.networkInterfaces())) {
    for (const iface of interfaces || []) {
      if (iface.family === 'IPv4' && !iface.internal) return iface.address;
    }
  }
  return null;
}

async function main() {
  await loadProviders();

  const app = createApp();
  const server = app.listen(config.http.port, config.http.host, () => {
    const ip = localIp();
    log.info('addon listening', {
      host: config.http.host,
      port: config.http.port,
      env: config.env,
      local: `http://localhost:${config.http.port}/manifest.json`,
      ...(ip ? { lan: `http://${ip}:${config.http.port}/manifest.json` } : {}),
      publicUrl: config.addon.publicUrl || '(derived from request host)',
    });
    log.info('install in Stremio with the manifest URL above; live TTL', {
      hours: config.cache.ttlLiveMs / 3_600_000,
      vodHours: config.cache.ttlVodMs / 3_600_000,
    });
  });

  /* Keep-alive slightly longer than typical load balancers (65s default). */
  server.keepAliveTimeout = 70_000;
  server.headersTimeout = 75_000;

  /* ---------------------------------------------------------------------- */
  /* Graceful shutdown                                                      */
  /* ---------------------------------------------------------------------- */
  let shuttingDown = false;

  const shutdown = (signal) => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info('shutting down', { signal });

    /* Hard deadline: never hang on a stuck socket. */
    const killer = setTimeout(() => {
      log.error('forced exit after shutdown timeout');
      process.exit(1);
    }, config.http.shutdownTimeoutMs);
    killer.unref();

    server.close(() => {
      cache.dispose();
      log.info('closed cleanly');
      clearTimeout(killer);
      process.exit(0);
    });

    /* Node 18+: drop idle keep-alive sockets so close() completes promptly. */
    server.closeIdleConnections?.();
  };

  process.on('SIGINT', () => shutdown('SIGINT'));
  process.on('SIGTERM', () => shutdown('SIGTERM'));

  /* Last-resort logging: never let a rejection silently kill the addon. */
  process.on('unhandledRejection', (reason) => {
    log.error('unhandled rejection', { error: reason instanceof Error ? reason.message : String(reason) });
  });

  process.on('uncaughtException', (error) => {
    log.error('uncaught exception', { error: error.message, stack: error.stack });
    shutdown('uncaughtException');
  });

  return server;
}

main().catch((error) => {
  log.error('failed to start', { error: error.message, stack: error.stack });
  process.exit(1);
});
