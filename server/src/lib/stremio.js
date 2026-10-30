/**
 * lib/stremio.js
 * ---------------------------------------------------------------------------
 * Stremio Addon protocol helpers: manifest builder and stream object builder.
 *
 * Reference: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
 *
 * Contract implemented here:
 *   GET /manifest.json          -> AddonManifest
 *   GET /stream/:type/:id.json   -> { streams: Stream[] }
 *   GET /catalog/:type/:id.json  -> { metas: Meta[] }
 *   GET /meta/:type/:id.json     -> { meta: Meta }
 *
 * Stream object shape requested by the product spec:
 *   { name, title, url, behaviorHints: { requestHeaders: { Referer, User-Agent } } }
 */

import config from '../config.js';
import { manifestCatalogs } from '../catalogs/registry.js';

/**
 * Builds the addon manifest.
 * @param {string} baseUrl - absolute origin of this server (for logo/links).
 */
export function buildManifest(baseUrl = '') {
  const types = config.addon.types;
  /* Only advertise resources/types the server actually implements. */
  const resources = [
    {
      name: 'stream',
      types,
      /* idPrefixes intentionally omitted: it would reject custom live ids.
       * The server validates ids per route instead. */
    },
    {
      /* Catalog resource: this is what makes the addon show a browsable Home
       * in Stremio (and what our Android client reads). No idPrefixes either,
       * because live channels use ids like "tv:futbolibre:espn-1" that do not
       * start with "tt"; declaring a prefix would hide those catalogs. */
      name: 'catalog',
      types,
    },
    {
      /* Meta resource: enriches the detail screen with Cinemeta data
       * (synopsis, year, poster, episodes) instead of Stremio's fallback. */
      name: 'meta',
      types,
    },
    {
      /* Subtitles resource: only providers that publish them fill it (novaHD
       * today). Declaring it is what makes a Stremio client ask; our own app
       * reads the same list from the stream response body. */
      name: 'subtitles',
      types,
    },
  ];

  return {
    id: config.addon.id,
    version: config.addon.version,
    name: config.addon.name,
    description: config.addon.description,
    ...(config.addon.logo ? { logo: config.addon.logo } : {}),
    types,
    resources,
    /* Catalogs declared in config/catalogs.json. Each one maps to
     * GET /catalog/:type/:id.json; the client renders posterShape to decide
     * between 2:3 posters and 16:9 live cards. */
    catalogs: manifestCatalogs(),
    /* Needed by ExoPlayer/Android clients to play direct .m3u8 URLs. */
    behaviorHints: {
      adult: false,
      p2p: false,
      configurable: false,
    },
    /* Non-standard but useful for debugging clients / preview URL. */
    ...(baseUrl ? { url: baseUrl } : {}),
  };
}

/** Keys whose names are case-insensitive in the headers map. */
const HEADER_CASE = {
  referer: 'Referer',
  'user-agent': 'User-Agent',
  origin: 'Origin',
  cookie: 'Cookie',
  authorization: 'Authorization',
};

/**
 * Normalises a header bag into the canonical casing ExoPlayer expects.
 * @param {Record<string,string>} headers
 * @returns {Record<string,string>}
 */
export function normalizeHeaders(headers = {}) {
  const out = {};
  for (const [key, value] of Object.entries(headers)) {
    if (value === undefined || value === null || value === '') continue;
    const canonical = HEADER_CASE[key.toLowerCase()] || key;
    out[canonical] = String(value);
  }
  return out;
}

/**
 * Builds a single Stremio stream object.
 *
 * @param {object} input
 * @param {string} input.name    - short label, e.g. "Provider 1 · 1080p"
 * @param {string} input.title   - rich label shown under the name
 * @param {string} input.url     - direct video URL (.m3u8 / .mp4)
 * @param {object} [input.behaviorHints]
 * @param {string} [input.referer]
 * @param {string} [input.userAgent]
 * @param {boolean} [input.notWebReady]
 * @returns {object|null} stream object, or null when it has no playable URL.
 */
export function buildStream({
  name,
  title,
  url,
  behaviorHints = {},
  referer,
  userAgent,
  notWebReady = false,
  quality,
  provider,
  isLive = false,
}) {
  if (!url || typeof url !== 'string') return null;

  const requestHeaders = normalizeHeaders({
    Referer: referer || config.streams.defaultReferer,
    'User-Agent': userAgent || config.streams.defaultUserAgent,
    ...(behaviorHints.requestHeaders || {}),
  });

  const hints = {
    ...behaviorHints,
    /* requestHeaders is what makes ExoPlayer's OkHttp data source send the
     * Referer/UA required by most HLS providers (anti-hotlink protection). */
    requestHeaders,
  };

  if (notWebReady) hints.notWebReady = true;
  if (isLive) hints.isLive = true;

  const stream = {
    name: name || config.addon.name,
    title: title || name || url,
    url,
    behaviorHints: hints,
  };

  /* Optional, non-breaking metadata used by this addon's own UI layer. */
  if (quality) stream.quality = quality;
  if (provider) stream.provider = provider;

  return stream;
}

/**
 * Validates the "type" path param.
 * @param {string} type
 * @returns {boolean}
 */
export function isSupportedType(type) {
  return config.addon.types.includes(String(type).toLowerCase());
}

export default { buildManifest, buildStream, normalizeHeaders, isSupportedType };
