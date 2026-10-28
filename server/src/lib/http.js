/**
 * lib/http.js
 * ---------------------------------------------------------------------------
 * Small HTTP utilities:
 *   - `fetchWithTimeout` for our own outbound calls
 *   - `extractMediaUrl` to recover a real .m3u8/.mp4 URL from provider pages
 *   - `looksLikeMediaUrl` used by validators
 *
 * Why proxy at all? Some providers block cross-origin HLS requests (no CORS
 * headers). Stremio Web / a browser then refuses to play the direct URL, while
 * the Android app (ExoPlayer) would work. `/proxy?url=...` re-serves the
 * playlist and rewrites segment URLs so both clients work.
 */

import config from '../config.js';

const MEDIA_EXTENSIONS = ['.m3u8', '.mpd', '.mp4', '.mkv', '.webm', '.m4v', '.ts', '.flv'];

/** Returns true when the URL points at a media file/playlist. */
export function looksLikeMediaUrl(url) {
  if (typeof url !== 'string' || url.length < 8) return false;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return false;

  const pathname = parsed.pathname.toLowerCase();
  if (MEDIA_EXTENSIONS.some((ext) => pathname.endsWith(ext))) return true;

  /* Some providers hide the extension in a query param: ?file=/hls/stream.m3u8 */
  const haystack = (pathname + parsed.search).toLowerCase();
  return MEDIA_EXTENSIONS.some((ext) => haystack.includes(ext));
}

/** True for HLS playlists (needs proxying for cross-origin playback). */
export function isHls(url) {
  return /\.m3u8(\?|$)/i.test(url || '');
}

/**
 * fetch() with an AbortController timeout.
 * @param {string} url
 * @param {RequestInit & { timeoutMs?: number }} [options]
 */
export async function fetchWithTimeout(url, options = {}) {
  const { timeoutMs = config.streams.providerTimeoutMs, headers, ...rest } = options;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new Error(`timeout after ${timeoutMs}ms`)), timeoutMs);

  try {
    return await fetch(url, {
      ...rest,
      headers: {
        'User-Agent': config.streams.defaultUserAgent,
        ...(config.streams.defaultReferer ? { Referer: config.streams.defaultReferer } : {}),
        ...headers,
      },
      signal: controller.signal,
    });
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Some providers return an HTML page with an embedded `file: "..."` or
 * `<source src="...">`. This extracts the first plausible media URL.
 *
 * @param {string} html
 * @param {string} [baseUrl] - used to resolve relative URLs
 * @returns {string|null}
 */
export function extractMediaUrl(html, baseUrl) {
  if (typeof html !== 'string' || html.length === 0) return null;

  const patterns = [
    /["']?(?:file|src|source|url|link)["']?\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|mpd)(?:\?[^"']*)?)["']/i,
    /(https?:\/\/[^\s"'<>]+?\.(?:m3u8|mp4|mpd)(?:\?[^\s"'<>]*)?)/i,
    /(https?:\/\/[^\s"'<>]+\/playlist\.m3u8[^\s"'<>]*)/i,
  ];

  for (const pattern of patterns) {
    const match = html.match(pattern);
    if (match?.[1]) {
      const candidate = match[1].replace(/\\\//g, '/');
      try {
        return baseUrl ? new URL(candidate, baseUrl).toString() : candidate;
      } catch {
        continue;
      }
    }
  }
  return null;
}

/**
 * Rewrites the URIs inside an HLS playlist so segments also go through the
 * proxy. Absolute URLs are prefixed, relative ones resolved against the
 * original playlist origin.
 *
 * @param {string} playlist raw m3u8 body
 * @param {string} originalUrl the playlist URL being proxied
 * @param {string} proxyBase  e.g. "http://host/proxy?url="
 */
export function rewriteHlsPlaylist(playlist, originalUrl, proxyBase) {
  const absolute = (uri) => {
    try {
      return new URL(uri, originalUrl).toString();
    } catch {
      return uri;
    }
  };
  const proxied = (uri) => `${proxyBase}${encodeURIComponent(absolute(uri))}`;

  return playlist
    .split('\n')
    .map((line) => {
      const trimmed = line.trim();
      if (trimmed === '') return line;

      /* #EXT-X-KEY:METHOD=AES-128,URI="key.bin" */
      if (trimmed.startsWith('#EXT-X-KEY') || trimmed.startsWith('#EXT-X-MAP')) {
        return line.replace(/URI="([^"]+)"/, (_, uri) => `URI="${proxied(uri)}"`);
      }

      /* Comments/metadata stay untouched. */
      if (trimmed.startsWith('#')) return line;

      /* Everything else is a segment or a variant playlist. */
      return proxied(trimmed);
    })
    .join('\n');
}

export default { fetchWithTimeout, extractMediaUrl, looksLikeMediaUrl, isHls, rewriteHlsPlaylist };
