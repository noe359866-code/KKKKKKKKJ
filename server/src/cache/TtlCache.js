/**
 * src/cache/TtlCache.js
 * ---------------------------------------------------------------------------
 * In-memory TTL cache with:
 *   - per-entry TTL (live streams: 2h, VOD: 6h)
 *   - LRU eviction when maxEntries is exceeded
 *   - stale-while-revalidate (serve stale data while refreshing in background)
 *   - request coalescing ("single flight") so 50 concurrent viewers of the same
 *     live channel trigger exactly ONE Python scrape
 *   - metrics (hits, misses, stale hits, evictions) for /health
 *
 * Why not node-cache? This module needs single-flight, negative caching and
 * stale-while-revalidate; all of them are ~40 lines on top of a Map and it
 * removes a dependency from a server that is meant to run on cheap hardware.
 *
 * Complexity: get/set/delete are O(1). The periodic sweep is O(n) every
 * `sweepIntervalMs`, not on every request.
 */

import config from '../config.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('cache');

/** A single cached entry. */
class Entry {
  constructor(value, ttlMs, meta = {}, now = Date.now()) {
    this.value = value;
    this.ttlMs = ttlMs;
    this.createdAt = now;
    this.expiresAt = this.createdAt + ttlMs;
    this.meta = meta; // { kind: 'live'|'vod'|'miss', providerCount, ... }
    this.hits = 0;
  }

  /** True while the entry is still inside its TTL. */
  isFresh(now = Date.now()) {
    return now < this.expiresAt;
  }

  /**
   * True while the entry is expired but still usable for
   * stale-while-revalidate (kept for ttl * staleFactor).
   */
  isStaleUsable(now = Date.now(), staleFactor = 1) {
    return now < this.expiresAt + this.ttlMs * (staleFactor - 1);
  }

  age(now = Date.now()) {
    return now - this.createdAt;
  }

  ttlRemaining(now = Date.now()) {
    return Math.max(0, this.expiresAt - now);
  }
}

export class TtlCache {
  /**
   * @param {object} [options]
   * @param {number} [options.maxEntries]    - LRU bound.
   * @param {number} [options.sweepIntervalMs]
   * @param {number} [options.staleFactor]
   * @param {Function} [options.now]         - injectable clock (tests).
   */
  constructor({
    maxEntries = config.cache.maxEntries,
    sweepIntervalMs = config.cache.sweepIntervalMs,
    staleFactor = config.cache.staleFactor,
    now = Date.now,
  } = {}) {
    /** @type {Map<string, Entry>} Map preserves insertion order -> LRU queue. */
    this.store = new Map();
    this.maxEntries = maxEntries;
    this.staleFactor = staleFactor;
    this.now = now;

    /** @type {Map<string, Promise<any>>} in-flight scrapes per key. */
    this.inflight = new Map();

    this.metrics = {
      hits: 0,
      misses: 0,
      staleHits: 0,
      sets: 0,
      evictions: 0,
      expired: 0,
      coalesced: 0,
      errors: 0,
      startedAt: new Date().toISOString(),
    };

    /* Periodic sweep keeps memory bounded even without traffic. `unref()` so
     * the timer never keeps the process alive during shutdown / tests. */
    if (sweepIntervalMs > 0) {
      this.sweepTimer = setInterval(() => this.sweep(), sweepIntervalMs);
      this.sweepTimer.unref?.();
    }
  }

  /* ---------------------------------------------------------------------- */
  /* Core API                                                               */
  /* ---------------------------------------------------------------------- */

  /**
   * @param {string} key
   * @returns {{ value: any, meta: object, stale: boolean, ageMs: number, ttlRemainingMs: number }|null}
   */
  get(key) {
    const entry = this.store.get(key);
    const now = this.now();

    if (!entry) {
      this.metrics.misses += 1;
      return null;
    }

    if (entry.isFresh(now)) {
      entry.hits += 1;
      this.metrics.hits += 1;
      this.touch(key, entry); // LRU: move to the most-recently-used position
      return {
        value: entry.value,
        meta: entry.meta,
        stale: false,
        ageMs: now - entry.createdAt,
        ttlRemainingMs: entry.expiresAt - now,
      };
    }

    if (entry.isStaleUsable(now, this.staleFactor)) {
      this.metrics.staleHits += 1;
      this.touch(key, entry);
      return {
        value: entry.value,
        meta: entry.meta,
        stale: true,
        ageMs: now - entry.createdAt,
        ttlRemainingMs: 0,
      };
    }

    /* Fully expired: lazy delete so a cold key never survives its window. */
    this.store.delete(key);
    this.metrics.expired += 1;
    this.metrics.misses += 1;
    return null;
  }

  /**
   * Stores a value.
   * @param {string} key
   * @param {any} value
   * @param {number} ttlMs
   * @param {object} [meta]
   */
  set(key, value, ttlMs, meta = {}) {
    if (!Number.isFinite(ttlMs) || ttlMs <= 0) {
      throw new Error(`[cache] invalid TTL for key "${key}": ${ttlMs}`);
    }

    const entry = new Entry(value, ttlMs, meta, this.now());

    /* Re-inserting moves the key to the tail (most recently used). */
    this.store.delete(key);
    this.store.set(key, entry);
    this.metrics.sets += 1;

    if (this.store.size > this.maxEntries) this.evictLru();
    return entry;
  }

  /** @returns {boolean} whether the key existed. */
  delete(key) {
    return this.store.delete(key);
  }

  has(key) {
    const entry = this.store.get(key);
    return Boolean(entry && entry.isFresh(this.now()));
  }

  clear() {
    this.store.clear();
  }

  get size() {
    return this.store.size;
  }

  /**
   * Registers/reads a promise for a given key.
   *
   * This is the "single flight" primitive: the first caller performs the work
   * (a Python scrape); concurrent callers with the same key receive the same
   * promise instead of spawning a second process.
   *
   * @template T
   * @param {string} key
   * @param {() => Promise<T>} factory
   * @returns {Promise<{ value: T, coalesced: boolean }>}
   */
  async singleFlight(key, factory) {
    const existing = this.inflight.get(key);
    if (existing) {
      this.metrics.coalesced += 1;
      return { value: await existing, coalesced: true };
    }

    const promise = (async () => factory())();
    this.inflight.set(key, promise);

    try {
      const value = await promise;
      return { value, coalesced: false };
    } catch (error) {
      this.metrics.errors += 1;
      throw error;
    } finally {
      /* Always release the slot, even on error, otherwise a failed scrape
       * would poison the key forever. */
      this.inflight.delete(key);
    }
  }

  /* ---------------------------------------------------------------------- */
  /* Convenience helpers                                                    */
  /* ---------------------------------------------------------------------- */

  /**
   * Returns the cached value or computes it, storing the result with `ttlMs`.
   *
   * On a stale hit the value is returned immediately (milliseconds) and the
   * factory runs in the background to refresh the entry.
   *
   * @template T
   * @param {string} key
   * @param {() => Promise<T>} factory
   * @param {number} ttlMs
   * @param {object} [meta]
   * @returns {Promise<{ value: T, source: 'hit'|'stale'|'computed', coalesced: boolean }>}
   */
  async fetch(key, factory, ttlMs, meta = {}) {
    const cached = this.get(key);

    if (cached && !cached.stale) {
      return { value: cached.value, source: 'hit', coalesced: false };
    }

    if (cached && cached.stale) {
      /* Fire-and-forget refresh; failures keep the stale value alive. */
      this.singleFlight(key, async () => {
        const fresh = await factory();
        this.set(key, fresh, ttlMs, meta);
        return fresh;
      }).catch((error) => {
        log.warn('background refresh failed', { key, error: error.message });
      });

      return { value: cached.value, source: 'stale', coalesced: false };
    }

    const { value, coalesced } = await this.singleFlight(key, async () => {
      const computed = await factory();
      this.set(key, computed, ttlMs, meta);
      return computed;
    });

    return { value, source: 'computed', coalesced };
  }

  /**
   * Caches a known-bad result for a short period (negative cache) so an
   * outage of every provider is not re-scraped on every client retry.
   */
  setMiss(key, ttlMs = config.cache.ttlMissMs) {
    this.set(key, [], ttlMs, { kind: 'miss', negative: true });
  }

  /* ---------------------------------------------------------------------- */
  /* Maintenance                                                            */
  /* ---------------------------------------------------------------------- */

  /** Moves a key to the tail of the Map (O(1) with V8 Maps). */
  touch(key, entry) {
    if (this.store.get(key) === entry) {
      this.store.delete(key);
      this.store.set(key, entry);
    }
  }

  /** Drops the least-recently-used entry. */
  evictLru() {
    const oldestKey = this.store.keys().next().value;
    if (oldestKey !== undefined) {
      this.store.delete(oldestKey);
      this.metrics.evictions += 1;
      log.debug('LRU eviction', { key: oldestKey });
    }
  }

  /** Removes entries that are expired beyond the stale window. */
  sweep() {
    const now = this.now();
    let removed = 0;
    for (const [key, entry] of this.store) {
      if (!entry.isStaleUsable(now, this.staleFactor)) {
        this.store.delete(key);
        removed += 1;
      }
    }
    if (removed > 0) {
      this.metrics.expired += removed;
      log.debug('sweep removed expired entries', { removed, size: this.store.size });
    }
    return removed;
  }

  /** Snapshot used by /health and scripts/cache-stats.js. */
  stats() {
    const now = this.now();
    let fresh = 0;
    let stale = 0;
    for (const entry of this.store.values()) {
      if (entry.isFresh(now)) fresh += 1;
      else stale += 1;
    }

    const lookups = this.metrics.hits + this.metrics.staleHits + this.metrics.misses;
    return {
      ...this.metrics,
      size: this.store.size,
      fresh,
      stale,
      inflight: this.inflight.size,
      maxEntries: this.maxEntries,
      hitRate: lookups === 0 ? 0 : Number(((this.metrics.hits + this.metrics.staleHits) / lookups).toFixed(4)),
      uptimeSeconds: Math.round((Date.now() - new Date(this.metrics.startedAt).getTime()) / 1000),
    };
  }

  /** Lists keys for debugging (max 100 by default to avoid huge payloads). */
  keys(limit = 100) {
    return [...this.store.keys()].slice(0, limit);
  }

  /** Releases the sweep timer (tests, graceful shutdown). */
  dispose() {
    if (this.sweepTimer) clearInterval(this.sweepTimer);
    this.store.clear();
    this.inflight.clear();
  }
}

/* A single shared instance for the whole process. */
export const cache = new TtlCache();
export default cache;
