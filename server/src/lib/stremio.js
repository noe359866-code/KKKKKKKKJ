/**
 * lib/stremio.js
 * ---------------------------------------------------------------------------
 * Stremio Addon protocol helpers: manifest builder and stream object builder.
 *
 * Reference: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
 *
 * Contract implemented here:
 *   GET /manifest.json        -> AddonManifest
 *   GET /stream/:type/:id.json -> { streams: Stream[] }
 *
 * Stream object shape requested by the product spec:
 *   { name, title, url, behaviorHints: { requestHeaders: { Referer, User-Agent } } }
 */

import config from '../config.js';

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
  ];

  return {
    id: config.addon.id,
    version: config.addon.version,
    name: config.addon.name,
    description: config.addon.description,
    ...(config.addon.logo ? { logo: config.addon.logo } : {}),
    types,
    resources,
    catalogs: [],
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
