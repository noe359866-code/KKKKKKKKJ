/**
 * config.js
 * ---------------------------------------------------------------------------
 * Single source of truth for runtime configuration.
 *
 * Every value can be overridden with an environment variable, so the same
 * image/binary can run in dev, staging and production without code changes.
 * Values are validated at boot: an invalid TTL or port must fail fast instead
 * of producing a silently broken cache.
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

/* Load server/.env when present (Node >= 20.12, no dependency needed).
 * Variables already present in the real environment always win. */
try {
  process.loadEnvFile?.(path.join(ROOT, '.env'));
} catch {
  /* No .env file: perfectly normal in containers / CI. */
}

/* -------------------------------------------------------------------------- */
/* Helpers                                                                    */
/* -------------------------------------------------------------------------- */

/** Read an integer from env, clamped to [min, max] to keep the cache sane. */
function envInt(name, fallback, { min = -Infinity, max = Infinity } = {}) {
  const raw = process.env[name];
  if (raw === undefined || raw.trim() === '') return fallback;

  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value)) {
    throw new Error(`[config] ${name} must be an integer, received "${raw}"`);
  }
  if (value < min || value > max) {
    throw new Error(`[config] ${name} must be between ${min} and ${max}, received ${value}`);
  }
  return value;
}

function envBool(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined) return fallback;
  return ['1', 'true', 'yes', 'on'].includes(raw.trim().toLowerCase());
}

function envList(name, fallback) {
  const raw = process.env[name];
  if (!raw) return fallback;
  return raw.split(',').map((item) => item.trim()).filter(Boolean);
}

/* -------------------------------------------------------------------------- */
/* Cache TTLs (milliseconds)                                                  */
/* -------------------------------------------------------------------------- */

const HOUR = 60 * 60 * 1000;

/* -------------------------------------------------------------------------- */
/* Exported config                                                            */
/* -------------------------------------------------------------------------- */

export const config = {
  env: process.env.NODE_ENV || 'development',
  isProduction: process.env.NODE_ENV === 'production',

  http: {
    /* 0.0.0.0 is mandatory inside containers / E2B previews: binding to
     * 127.0.0.1 makes the port unreachable from the browser. */
    host: process.env.HOST || '0.0.0.0',
    port: envInt('PORT', 7000, { min: 1, max: 65535 }),
    /* Stremio apps live in another origin (web) or native app: CORS is open. */
    corsOrigins: envList('CORS_ORIGINS', ['*']),
    trustProxy: envBool('TRUST_PROXY', true),
    shutdownTimeoutMs: envInt('SHUTDOWN_TIMEOUT_MS', 10_000, { min: 1_000, max: 60_000 }),
    /* Fixed-window limit per IP. 0 disables it. */
    rateLimitMax: envInt('RATE_LIMIT_MAX', 120, { min: 0, max: 100_000 }),
    /* Optional bearer token for /admin/*. Empty => admin routes are disabled. */
    adminToken: process.env.ADMIN_TOKEN || '',
    /* Enable the /proxy endpoint (HLS re-serving with Referer/UA injection). */
    proxyEnabled: envBool('PROXY_ENABLED', true),
  },

  addon: {
    id: process.env.ADDON_ID || 'org.streamflix.reborn',
    version: process.env.ADDON_VERSION || '0.1.0',
    name: process.env.ADDON_NAME || 'Streamflix Reborn',
    description:
      process.env.ADDON_DESCRIPTION ||
      'Catalogs and metadata for movies, series and anime from Cinemeta and selected Stremio addons.',
    logo: process.env.ADDON_LOGO || '',
    /* Base URL used to build the public manifest "id"/links preview.
     * Left empty in dev: it is derived from the incoming request headers. */
    publicUrl: (process.env.PUBLIC_URL || '').replace(/\/+$/, ''),
    /* Types declared by the manifest. "anime" is a custom type on this addon;
     * Stremio/ExoPlayer clients treat it as an extra row in the catalog. */
    types: envList('ADDON_TYPES', ['movie', 'series', 'anime']),
    resources: ['catalog', 'meta'],
  },

  cache: {
    /* Short fallback for generic negative cache entries. */
    ttlMissMs: envInt('CACHE_TTL_MISS_SECONDS', 120, { min: 5, max: 3600 }) * 1000,
    /* Maximum number of keys before LRU eviction kicks in. */
    maxEntries: envInt('CACHE_MAX_ENTRIES', 5_000, { min: 10, max: 1_000_000 }),
    /* Background sweep of expired keys. */
    sweepIntervalMs: envInt('CACHE_SWEEP_SECONDS', 60, { min: 5, max: 3600 }) * 1000,
    /* Serve stale data while refreshing in the background (stale-while-revalidate)
     * as long as the entry is not older than ttl * staleFactor. */
    staleFactor: Number.parseFloat(process.env.CACHE_STALE_FACTOR || '1.25'),
  },

  catalogs: {
    /* Cinemeta is the official Stremio metadata addon: it publishes the
     * "top", "year" and "imdbRating" rows for movies/series plus every
     * /meta/:type/:id.json lookup. We proxy it so the client only ever talks
     * to this server (one origin, one cache, one failure mode). */
    cinemetaBase: (process.env.CINEMETA_BASE || 'https://v3-cinemeta.strem.io').replace(/\/+$/, ''),
    /* A slow Cinemeta must never hold the Home screen hostage. */
    timeoutMs: envInt('CINEMETA_TIMEOUT_MS', 6_000, { min: 500, max: 30_000 }),
    /* Catalog rows (top/imdbRating) move slowly: 30 min is plenty. */
    ttlMs: envInt('CATALOG_TTL_MINUTES', 30, { min: 1, max: 1440 }) * 60_000,
    /* Searches are user driven and change constantly: cache them briefly. */
    searchTtlMs: envInt('CATALOG_SEARCH_TTL_MINUTES', 5, { min: 1, max: 1440 }) * 60_000,
    /* Metadata for one title (title + year + episodes) is stable for a day. */
    metaTtlMs: envInt('CATALOG_META_TTL_HOURS', 24, { min: 1, max: 720 }) * HOUR,
    /* Empty/failed upstream responses are cached for a minute, not for 30. */
    missTtlMs: envInt('CATALOG_MISS_SECONDS', 60, { min: 5, max: 3600 }) * 1000,
    /* Metas returned per catalog page. Cinemeta itself caps at 100. */
    pageSize: envInt('CATALOG_PAGE_SIZE', 100, { min: 10, max: 200 }),
    /* Where the published catalogs are declared (type, id, name, source). */
    configPath: process.env.CATALOGS_CONFIG || path.join(ROOT, 'config', 'catalogs.json'),
    /* Third-party Stremio addons whose *catalogs* we republish (see the
     * `addons` block in config/catalogs.json). Their manifest is fetched once
     * per boot and cached on disk, so a cold start behind a broken WAN still
     * knows which rows to publish. */
    remoteTimeoutMs: envInt('CATALOG_REMOTE_TIMEOUT_MS', 8_000, { min: 500, max: 60_000 }),
    remoteTtlMs: envInt('CATALOG_REMOTE_TTL_HOURS', 6, { min: 1, max: 720 }) * HOUR,
    remoteCachePath: process.env.CATALOG_REMOTE_CACHE || path.join(ROOT, '.cache', 'remote-catalogs.json'),
  },

  /* Optional generic proxy for media URLs supplied by compatible Stremio addons.
   * It does not discover or scrape sources itself. */
  proxy: {
    timeoutMs: envInt('PROXY_TIMEOUT_MS', 15_000, { min: 500, max: 120_000 }),
    defaultUserAgent:
      process.env.PROXY_USER_AGENT ||
      'Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    defaultReferer: process.env.PROXY_REFERER || '',
  },

  logging: {
    level: process.env.LOG_LEVEL || (process.env.NODE_ENV === 'production' ? 'info' : 'debug'),
    /* Struct logs (one JSON object per line) are easier to ingest in prod. */
    json: envBool('LOG_JSON', process.env.NODE_ENV === 'production'),
  },
};

export default config;
