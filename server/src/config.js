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

/*
 * TTL contract (enforced at boot and again when the cache is written):
 *  - LIVE (live TV, sports, IPTV): 2h – 4h. The .m3u8 URLs of tvenvivo,
 *    deportelibre and futbolibre must be answered from a cache hit, but they
 *    rotate, so the window is capped at 4 hours.
 *  - VOD  (movies / series / anime): 2h – 6h. Links are stable for longer.
 */
const TTL_MIN_MS = 2 * HOUR;
const TTL_MAX_MS = 6 * HOUR;
const TTL_LIVE_MIN_MS = 2 * HOUR;
const TTL_LIVE_MAX_MS = 4 * HOUR;

/* Out of range => throw at boot instead of silently caching stale links. */
const ttlLiveMs = envInt('CACHE_TTL_LIVE_HOURS', 2, { min: 2, max: 4 }) * HOUR;
const ttlVodMs = envInt('CACHE_TTL_VOD_HOURS', 6, { min: 2, max: 6 }) * HOUR;

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
      'Live TV, sports, movies, series and anime aggregated from multiple providers with automatic failover.',
    logo: process.env.ADDON_LOGO || '',
    /* Base URL used to build the public manifest "id"/links preview.
     * Left empty in dev: it is derived from the incoming request headers. */
    publicUrl: (process.env.PUBLIC_URL || '').replace(/\/+$/, ''),
    /* Types declared by the manifest. "anime" is a custom type on this addon;
     * Stremio/ExoPlayer clients treat it as an extra row in the catalog. */
    types: envList('ADDON_TYPES', ['movie', 'series', 'anime', 'tv']),
    resources: ['stream'],
  },

  cache: {
    /* Hard contract: 2h <= TTL <= 6h (live: 2h <= TTL <= 4h). */
    ttlMinMs: TTL_MIN_MS,
    ttlMaxMs: TTL_MAX_MS,
    ttlLiveMinMs: TTL_LIVE_MIN_MS,
    ttlLiveMaxMs: TTL_LIVE_MAX_MS,
    /* VOD / movies / series / anime. */
    ttlVodMs,
    /* Live TV + sports: streams expire fast, so cache them conservatively. */
    ttlLiveMs,
    /* Ephemeral cache for negative results (no working source found).
     * Kept short so a provider outage does not poison the cache for hours. */
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
    /* Metadata for one title (title + year + episodes) is stable for a day,
     * and it is what turns an IMDb id into a provider search. */
    metaTtlMs: envInt('CATALOG_META_TTL_HOURS', 24, { min: 1, max: 720 }) * HOUR,
    /* Empty/failed upstream responses are cached for a minute, not for 30. */
    missTtlMs: envInt('CATALOG_MISS_SECONDS', 60, { min: 5, max: 3600 }) * 1000,
    /* Metas returned per catalog page. Cinemeta itself caps at 100. */
    pageSize: envInt('CATALOG_PAGE_SIZE', 100, { min: 10, max: 200 }),
    /* Where the published catalogs are declared (type, id, name, source). */
    configPath: process.env.CATALOGS_CONFIG || path.join(ROOT, 'config', 'catalogs.json'),
    /* Curated live TV / sports channels: the "local" half of the home screen. */
    liveCatalogPath: process.env.LIVE_CATALOG_PATH || path.join(ROOT, 'config', 'live-catalog.json'),
  },

  scrapers: {
    /* Python interpreter: prefers a virtualenv if SCRAPER_PYTHON is set. */
    python: process.env.SCRAPER_PYTHON || 'python3',
    /* Directory that contains the .py scrapers. */
    dir: process.env.SCRAPER_DIR || path.join(ROOT, 'scrapers'),
    /* Default hard timeout per scraper process. A hung child process is
     * SIGKILLed so it can never block the event loop or leak sockets. */
    timeoutMs: envInt('SCRAPER_TIMEOUT_MS', 15_000, { min: 500, max: 120_000 }),
    /* Concurrent child processes cap (protects small Android TV boxes
     * acting as a server, or a cheap VPS). */
    maxConcurrency: envInt('SCRAPER_MAX_CONCURRENCY', 4, { min: 1, max: 32 }),
    /* Extra env vars passed to the child (PATH is inherited automatically). */
    extraEnv: {
      PYTHONUNBUFFERED: '1',
      PYTHONIOENCODING: 'utf-8',
    },
  },

  streams: {
    /* Per-provider budget (8 s). A slower script is killed by the bridge and
     * its slot is filled by the secondary sources that did answer, so one dead
     * site never delays the playable response. */
    providerTimeoutMs: envInt('PROVIDER_TIMEOUT_MS', 8_000, { min: 500, max: 60_000 }),
    /* Give up when this many providers returned at least one stream, so the
     * response is fast even if the last provider is slow. */
    minProvidersForFastReturn: envInt('MIN_PROVIDERS_FOR_FAST_RETURN', 2, { min: 1, max: 10 }),
    /* Once enough providers answered, wait this extra window for the others
     * so higher-priority sources are not dropped from the failover list. */
    fastReturnGraceMs: envInt('FAST_RETURN_GRACE_MS', 1_500, { min: 0, max: 10_000 }),
    /* Placeholder UA/Referer for providers that do not declare their own. */
    defaultUserAgent:
      process.env.STREAM_USER_AGENT ||
      'Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    defaultReferer: process.env.STREAM_REFERER || '',
    /* If true, streams without a resolvable URL are never returned. */
    requireUrl: envBool('STREAM_REQUIRE_URL', true),
  },

  logging: {
    level: process.env.LOG_LEVEL || (process.env.NODE_ENV === 'production' ? 'info' : 'debug'),
    /* Struct logs (one JSON object per line) are easier to ingest in prod. */
    json: envBool('LOG_JSON', process.env.NODE_ENV === 'production'),
  },
};

export default config;
