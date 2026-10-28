/**
 * src/providers/index.js
 * ---------------------------------------------------------------------------
 * Provider registry.
 *
 * A "provider" is a source (site) plus the Python script that knows how to
 * scrape it. Providers are declared in config/providers.json so adding a site
 * never requires touching the service code.
 *
 * Priority matters: the streams array is returned in ascending priority order
 * so ExoPlayer tries Site 1 -> Site 2 -> Site 3 (failover by ordering).
 */

import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import config from '../config.js';
import { createLogger } from '../lib/logger.js';

const log = createLogger('providers');
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = process.env.PROVIDERS_CONFIG || path.resolve(__dirname, '../../config/providers.json');

/** Validated provider shape. */
const KINDS = new Set(['vod', 'live', 'any']);

function normalizeProvider(raw, index) {
  if (!raw || typeof raw !== 'object') return null;
  if (!raw.id || !raw.script) {
    log.warn('provider skipped: missing id or script', { index });
    return null;
  }

  return {
    id: String(raw.id),
    label: String(raw.label || raw.id),
    script: String(raw.script),
    priority: Number.isFinite(raw.priority) ? raw.priority : 100 + index,
    kind: KINDS.has(raw.kind) ? raw.kind : 'any',
    enabled: raw.enabled !== false,
    referer: raw.referer || config.streams.defaultReferer,
    userAgent: raw.userAgent || config.streams.defaultUserAgent,
    /* Extra static args passed to the Python script (base URLs, cookies...). */
    options: raw.options && typeof raw.options === 'object' ? raw.options : {},
  };
}

/** @type {Array<ReturnType<typeof normalizeProvider>>} */
let providers = [];
let loadedAt = null;

/** Loads (or reloads) the provider list from disk. */
export async function loadProviders() {
  try {
    const raw = JSON.parse(await readFile(CONFIG_PATH, 'utf8'));
    const list = Array.isArray(raw) ? raw : raw.providers || [];
    providers = list
      .map(normalizeProvider)
      .filter(Boolean)
      .sort((a, b) => a.priority - b.priority);
    loadedAt = new Date().toISOString();
    log.info('providers loaded', { count: providers.length, config: CONFIG_PATH });
  } catch (error) {
    log.error('could not load providers config', { config: CONFIG_PATH, error: error.message });
    providers = [];
  }
  return providers;
}

/** All enabled providers, already sorted by priority. */
export function getProviders() {
  return providers.filter((provider) => provider.enabled);
}

/**
 * Providers relevant for the current content class.
 * Live/sports content only hits live-capable sites; VOD only hits VOD sites.
 *
 * @param {boolean} isLive
 */
export function getProvidersFor(isLive) {
  const wanted = isLive ? new Set(['live', 'any']) : new Set(['vod', 'any']);
  return getProviders().filter((provider) => wanted.has(provider.kind));
}

/** Lookup by id, used for manual cache invalidation and admin endpoints. */
export function getProviderById(id) {
  return providers.find((provider) => provider.id === id) || null;
}

export function getProvidersMeta() {
  return { loadedAt, count: providers.length };
}

export default { loadProviders, getProviders, getProvidersFor, getProviderById, getProvidersMeta };
