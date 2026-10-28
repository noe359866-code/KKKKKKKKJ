/**
 * src/services/streamService.js
 * ---------------------------------------------------------------------------
 * The brain of the addon: cache lookup -> provider pool -> Stremio streams.
 *
 * Flow for GET /stream/:type/:id.json
 * -----------------------------------
 *  1. Normalise the id and decide whether it is live (tv / sports) or VOD.
 *  2. Build a deterministic cache key:  streams:<type>:<id>
 *  3. Cache HIT  -> return the stored .m3u8 in milliseconds, no Python at all.
 *     Cache STALE-> return the stored array immediately and refresh in the
 *                  background (stale-while-revalidate).
 *  4. Cache MISS -> run every provider scraper in parallel (Python bridge),
 *     normalise + dedupe + sort by provider priority, store with the right TTL
 *     and return the ordered failover list.
 *  5. Empty result -> short negative cache so a provider outage isn't re-scraped
 *     on every client retry.
 *
 * TTL policy (configurable, bounded to 2h–6h by config.js):
 *   live / sports -> CACHE_TTL_LIVE_HOURS (default 2h)
 *   movie / series / anime -> CACHE_TTL_VOD_HOURS (default 6h)
 */

import config from '../config.js';
import { cache } from '../cache/TtlCache.js';
import { getProvidersFor } from '../providers/index.js';
import { runProviderPool } from '../providers/runner.js';
import { buildStream } from '../lib/stremio.js';
import { parseMediaId, normalizeStreamId } from '../lib/ids.js';
import { normalizeQuality, qualityRank } from '../lib/quality.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('streams');

/* -------------------------------------------------------------------------- */
/* Cache keys & TTL                                                           */
/* -------------------------------------------------------------------------- */

/** Deterministic key: content id + type (the id alone can be ambiguous). */
export function cacheKey(type, id) {
  return `streams:${String(type).toLowerCase()}:${normalizeStreamId(id)}`;
}

/**
 * Live content is identified by:
 *  - Stremio type "tv"
 *  - custom live ids: "tv:canal-13", "sports:..." (see lib/ids.js)
 */
export function isLiveContent(type, parsed) {
  return String(type).toLowerCase() === 'tv' || parsed.isLive || parsed.isSports;
}

/** TTL for a content class, clamped to the 2h–6h contract. */
export function ttlFor(isLive) {
  const ttl = isLive ? config.cache.ttlLiveMs : config.cache.ttlVodMs;
  return Math.min(Math.max(ttl, config.cache.ttlMinMs), config.cache.ttlMaxMs);
}

/* -------------------------------------------------------------------------- */
/* Stream normalisation                                                       */
/* -------------------------------------------------------------------------- */

/**
 * Converts one raw provider stream into a Stremio stream object.
 *
 * Accepted (flexible) Python output:
 *   { name?, title?, url, quality?, referer?, userAgent?,
 *     behaviorHints?: { notWebReady?, requestHeaders? } }
 *
 * @param {object} raw
 * @param {object} provider
 * @returns {object|null}
 */
export function normalizeProviderStream(raw, provider, context = {}) {
  if (!raw || typeof raw !== 'object') return null;

  const url = typeof raw.url === 'string' ? raw.url.trim() : '';
  if (!url) return null;

  /* Reject relative/garbage urls: ExoPlayer needs an absolute media URL. */
  if (!/^https?:\/\//i.test(url)) {
    log.debug('stream rejected: non-absolute url', { provider: provider.id, url });
    return null;
  }
  if (config.streams.requireUrl && !url.includes('.')) return null;

  const hints = raw.behaviorHints && typeof raw.behaviorHints === 'object' ? raw.behaviorHints : {};
  const requestHeaders = hints.requestHeaders || raw.requestHeaders || {};

  const referer =
    requestHeaders.Referer || requestHeaders.referer || raw.referer || provider.referer || config.streams.defaultReferer;
  const userAgent =
    requestHeaders['User-Agent'] ||
    requestHeaders['user-agent'] ||
    raw.userAgent ||
    provider.userAgent ||
    config.streams.defaultUserAgent;

  const quality = normalizeQuality(raw.quality || detectQualityFromUrl(url) || 'Unknown');

  /* Propagate extra client hints (notWebReady, proxyHeaders...) untouched. */
  const extraHints = { ...hints };
  delete extraHints.requestHeaders;

  const name = raw.name ? String(raw.name) : `${provider.label}${quality !== 'Unknown' ? ` · ${quality}` : ''}`;
  const title =
    raw.title ||
    [provider.label, quality !== 'Unknown' ? quality : null, provider.isFallback ? 'backup' : null]
      .filter(Boolean)
      .join(' · ');

  const stream = buildStream({
    name,
    title,
    url,
    behaviorHints: extraHints,
    referer,
    userAgent,
    quality: quality !== 'Unknown' ? quality : undefined,
    provider: provider.id,
    isLive: Boolean(raw.isLive ?? context.isLive),
  });

  return stream ? { ...stream, _priority: provider.priority, _qualityRank: qualityRank(quality) } : null;
}

/** Last resort quality detection from the URL/filename. */
function detectQualityFromUrl(url) {
  const match = url.match(/(\d{3,4})p/i);
  return match ? `${match[1]}p` : null;
}

/** Removes internal sorting fields before sending to the client. */
function toPublicStream(stream) {
  const { _priority, _qualityRank, ...publicStream } = stream;
  return publicStream;
}

/**
 * Builds the ordered, deduplicated failover list.
 *
 * Order: provider priority asc (Sitio 1 -> Sitio 3), then quality desc inside
 * the same provider. Duplicate URLs are dropped, keeping the first occurrence
 * (the one from the highest-priority provider).
 *
 * @param {Array<{ provider: object, streams: object[] }>} results
 * @param {{ isLive?: boolean }} [context]
 * @returns {object[]}
 */
export function buildStreamList(results, context = {}) {
  const candidates = [];

  for (const { provider, streams } of results) {
    for (const raw of streams) {
      const normalized = normalizeProviderStream(raw, provider, context);
      if (normalized) candidates.push(normalized);
    }
  }

  candidates.sort((a, b) => {
    if (a._priority !== b._priority) return a._priority - b._priority;
    return b._qualityRank - a._qualityRank;
  });

  const seen = new Set();
  const out = [];
  for (const stream of candidates) {
    if (seen.has(stream.url)) continue;
    seen.add(stream.url);
    out.push(toPublicStream(stream));
  }
  return out;
}

/* -------------------------------------------------------------------------- */
/* Main entry point                                                           */
/* -------------------------------------------------------------------------- */

/**
 * @param {object} params
 * @param {string} params.type - movie | series | anime | tv
 * @param {string} params.id   - Stremio video id
 * @param {boolean} [params.bypassCache] - force a fresh scrape (admin use)
 * @returns {Promise<{
 *   streams: object[], isLive: boolean, ttlMs: number, cacheKey: string,
 *   cached: boolean, stale: boolean, coalesced: boolean, durationMs: number,
 *   providers: Array<{ id: string, status: string, streams: number, ms: number|null }>
 * }>}
 */
export async function getStreams({ type, id, bypassCache = false }) {
  const startedAt = process.hrtime.bigint();
  const normalizedId = normalizeStreamId(id);
  const parsed = parseMediaId(normalizedId);
  const isLive = isLiveContent(type, parsed);
  const ttlMs = ttlFor(isLive);
  const key = cacheKey(type, normalizedId);

  const respond = (payload) => ({
    ...payload,
    isLive,
    ttlMs,
    cacheKey: key,
    durationMs: Number((Number(process.hrtime.bigint() - startedAt) / 1e6).toFixed(2)),
  });

  /* ------------------------------------------------------------------ */
  /* 1. Hot path: cache hit. Live .m3u8 returns without spawning Python. */
  /* ------------------------------------------------------------------ */
  if (!bypassCache) {
    const hit = cache.get(key);
    if (hit && !hit.stale) {
      log.debug('cache hit', {
        key,
        streams: hit.value.length,
        ageMs: hit.ageMs,
        ttlRemainingMs: hit.ttlRemainingMs,
      });
      return respond({
        streams: hit.value,
        cached: true,
        stale: false,
        coalesced: false,
        providers: hit.meta.providers || [],
        negative: Boolean(hit.meta.negative),
        ttlRemainingMs: hit.ttlRemainingMs,
      });
    }

    /* 2. Stale entry: answer immediately and refresh in the background. */
    if (hit && hit.stale) {
      log.info('stale cache hit, serving and revalidating', { key, ageMs: hit.ageMs });
      refreshInBackground(key, { type, id: normalizedId, parsed, isLive, ttlMs });
      return respond({
        streams: hit.value,
        cached: true,
        stale: true,
        coalesced: false,
        providers: hit.meta.providers || [],
        negative: Boolean(hit.meta.negative),
        ttlRemainingMs: 0,
      });
    }
  }

  /* 3. Cache miss: coalesce concurrent identical requests into one scrape. */
  const { value, coalesced } = await cache.singleFlight(key, async () => {
    const providers = getProvidersFor(isLive);

    const { results, durationMs, timedOut } = await runProviderPool(providers, {
      type,
      id: normalizedId,
      parsed,
      isLive,
    });

    const streams = buildStreamList(results, { isLive });

    const providerStatus = results.map((result) => ({
      id: result.provider.id,
      label: result.provider.label,
      status: result.status,
      streams: result.streams.length,
      ms: result.durationMs,
    }));

    if (streams.length === 0) {
      /* Negative cache: avoid hammering dead providers on every retry. */
      cache.setMiss(key);
      log.warn('no streams found, negative cache set', {
        key,
        providers: providerStatus,
        durationMs,
        timedOut,
      });
      return { streams: [], providers: providerStatus, negative: true };
    }

    cache.set(key, streams, ttlMs, { providers: providerStatus, isLive, ttlMs, negative: false });
    log.info('scrape complete, cached', {
      key,
      streams: streams.length,
      providers: providerStatus.filter((provider) => provider.status === 'ok').length,
      durationMs,
      ttlHours: (ttlMs / 3_600_000).toFixed(1),
      timedOut,
    });

    return { streams, providers: providerStatus, negative: false };
  });

  return respond({
    streams: value.streams,
    cached: false,
    stale: false,
    coalesced,
    providers: value.providers,
    negative: value.negative,
    ttlRemainingMs: ttlMs,
  });
}

/**
 * Refreshes a stale entry without blocking the caller.
 * A failure leaves the stale data in place (better than an empty screen).
 */
function refreshInBackground(key, context) {
  if (cache.inflight.has(key)) return;

  cache
    .singleFlight(key, async () => {
      const providers = getProvidersFor(context.isLive);
      const { results } = await runProviderPool(providers, {
        type: context.type,
        id: context.id,
        parsed: context.parsed,
        isLive: context.isLive,
      });
      const streams = buildStreamList(results, { isLive: context.isLive });

      if (streams.length === 0) {
        log.warn('background refresh returned no streams, keeping stale entry', { key });
        return streams;
      }
      cache.set(key, streams, context.ttlMs, {
        providers: results.map((result) => ({
          id: result.provider.id,
          status: result.status,
          streams: result.streams.length,
          ms: result.durationMs,
        })),
        isLive: context.isLive,
        ttlMs: context.ttlMs,
        negative: false,
      });
      log.info('background refresh stored fresh streams', { key, streams: streams.length });
      return streams;
    })
    .catch((error) => log.warn('background refresh failed', { key, error: error.message }));
}

/* -------------------------------------------------------------------------- */
/* Cache management helpers (used by /admin endpoints)                        */
/* -------------------------------------------------------------------------- */

/** Invalidates one content id, optionally only for one type. */
export function invalidate({ type, id }) {
  if (id) return cache.delete(cacheKey(type, id));
  /* No id: drop every stream cache entry. */
  let removed = 0;
  for (const key of cache.keys(Infinity)) {
    if (key.startsWith('streams:')) {
      cache.delete(key);
      removed += 1;
    }
  }
  return removed > 0;
}

/** Returns TTL policy for observability. */
export function ttlPolicy() {
  return {
    liveMs: ttlFor(true),
    vodMs: ttlFor(false),
    minMs: config.cache.ttlMinMs,
    maxMs: config.cache.ttlMaxMs,
    missMs: config.cache.ttlMissMs,
  };
}

export default { getStreams, invalidate, cacheKey, isLiveContent, ttlFor, buildStreamList, ttlPolicy };
