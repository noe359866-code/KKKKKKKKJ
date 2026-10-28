/**
 * routes/proxy.js
 * ---------------------------------------------------------------------------
 * Optional media proxy.
 *
 * Why it exists: many HLS providers only work when the request carries the
 * provider's `Referer`/`User-Agent` AND the response is readable cross-origin.
 * ExoPlayer (Android TV) can be told to send those headers via
 * `behaviorHints.requestHeaders`, but Stremio Web / a browser cannot. Routing
 * the playlist through this endpoint solves both cases and rewrites every
 * segment/key URI so the whole playback goes through the same origin.
 *
 * Security:
 *  - only http/https URLs;
 *  - private / loopback / link-local hosts are rejected (basic SSRF guard);
 *  - response size for playlists is capped;
 *  - the endpoint is disabled when PROXY_ENABLED=false.
 *
 * It is NOT a general-purpose open proxy: it only streams media responses.
 */

import { Router } from 'express';
import { Readable } from 'node:stream';
import net from 'node:net';

import config from '../config.js';
import { createLogger } from '../lib/logger.js';
import { asyncHandler } from '../middleware/errors.js';
import { rewriteHlsPlaylist, isHls } from '../lib/http.js';

const log = createLogger('proxy');

export const proxyRouter = Router();

const MAX_PLAYLIST_BYTES = 5 * 1024 * 1024;

/** Blocks loopback/private/link-local targets. */
export function isPrivateHost(hostname) {
  if (!hostname) return true;
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (host === 'localhost' || host.endsWith('.localhost') || host.endsWith('.local')) return true;

  const ipVersion = net.isIP(host);
  if (ipVersion === 4) {
    const [a, b] = host.split('.').map(Number);
    if (a === 10 || a === 127 || a === 0) return true;
    if (a === 169 && b === 254) return true; // link-local / cloud metadata
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 100 && b >= 64 && b <= 127) return true; // CGNAT
    return false;
  }
  if (ipVersion === 6) {
    if (host === '::1' || host === '::') return true;
    if (host.startsWith('fc') || host.startsWith('fd') || host.startsWith('fe80')) return true;
    /* IPv4-mapped: ::ffff:192.168.1.1 */
    const mapped = host.match(/^::ffff:(\d+\.\d+\.\d+\.\d+)$/);
    if (mapped) return isPrivateHost(mapped[1]);
    return false;
  }
  return false;
}

/** Validates the `url` query param and returns a URL instance. */
export function parseProxyUrl(raw) {
  if (!raw || typeof raw !== 'string') return null;
  let url;
  try {
    url = new URL(raw);
  } catch {
    return null;
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') return null;
  if (isPrivateHost(url.hostname)) return null;
  return url;
}

/**
 * Base used by rewriteHlsPlaylist(): it appends the encoded target URL at the
 * end of the query string, so `url=` must stay last.
 */
export function proxyBaseFor(origin, { referer, userAgent } = {}) {
  const params = new URLSearchParams();
  if (referer) params.set('referer', referer);
  if (userAgent) params.set('ua', userAgent);
  const prefix = params.toString();
  return `${origin}/proxy?${prefix ? `${prefix}&` : ''}url=`;
}

/** Full proxy URL for a single media URL (used when PROXY_STREAMS is on). */
export function proxiedMediaUrl(origin, targetUrl, headers = {}) {
  return `${proxyBaseFor(origin, headers)}${encodeURIComponent(targetUrl)}`;
}

proxyRouter.get(
  '/proxy',
  asyncHandler(async (req, res) => {
    if (!config.http.proxyEnabled) {
      return res.status(404).json({ err: 'proxy disabled', code: 'NOT_FOUND' });
    }

    const target = parseProxyUrl(req.query.url);
    if (!target) {
      return res.status(400).json({ err: 'invalid or blocked url', code: 'BAD_REQUEST' });
    }

    const referer = typeof req.query.referer === 'string' ? req.query.referer : config.streams.defaultReferer;
    const userAgent = typeof req.query.ua === 'string' ? req.query.ua : config.streams.defaultUserAgent;

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), config.streams.providerTimeoutMs);
    /* res 'close' fires when the client disconnects mid-download; req 'close'
     * would fire as soon as the (empty) request body is done. */
    res.on('close', () => {
      if (!res.writableEnded) controller.abort();
    });

    let upstream;
    try {
      upstream = await fetch(target, {
        signal: controller.signal,
        redirect: 'follow',
        headers: {
          'User-Agent': userAgent,
          ...(referer ? { Referer: referer } : {}),
          ...(req.get('range') ? { Range: req.get('range') } : {}),
          Accept: '*/*',
        },
      });
    } catch (error) {
      clearTimeout(timer);
      log.warn('upstream fetch failed', { url: target.href, error: error.message });
      return res.status(502).json({ err: 'upstream unavailable', code: 'BAD_GATEWAY' });
    }
    clearTimeout(timer);

    /* Validate the final URL after redirects: never expose internal hosts. */
    try {
      const finalUrl = new URL(upstream.url);
      if (isPrivateHost(finalUrl.hostname)) {
        upstream.body?.cancel?.();
        return res.status(403).json({ err: 'blocked redirect', code: 'FORBIDDEN' });
      }
    } catch {
      /* keep going with the original url */
    }

    if (!upstream.ok && upstream.status !== 206) {
      upstream.body?.cancel?.();
      return res.status(upstream.status === 404 ? 404 : 502).json({
        err: `upstream responded ${upstream.status}`,
        code: 'UPSTREAM_ERROR',
      });
    }

    const contentType = upstream.headers.get('content-type') || '';
    const looksHls = isHls(target.href) || /mpegurl/i.test(contentType);

    /* CORS: the whole point of the proxy is browser playback. */
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', '*');
    res.setHeader('Cache-Control', 'public, max-age=5');

    if (looksHls) {
      const body = await upstream.text();
      if (body.length > MAX_PLAYLIST_BYTES) {
        return res.status(413).json({ err: 'playlist too large', code: 'PAYLOAD_TOO_LARGE' });
      }

      const origin = `${req.protocol}://${req.get('host')}`;
      const rewritten = rewriteHlsPlaylist(
        body,
        upstream.url || target.href,
        proxyBaseFor(origin, { referer, userAgent }),
      );

      res.setHeader('Content-Type', 'application/vnd.apple.mpegurl; charset=utf-8');
      return res.send(rewritten);
    }

    /* Binary passthrough (segments, mp4, keys). */
    res.status(upstream.status);
    if (contentType) res.setHeader('Content-Type', contentType);
    for (const header of ['content-length', 'content-range', 'accept-ranges', 'etag', 'last-modified']) {
      const value = upstream.headers.get(header);
      if (value) res.setHeader(header, value);
    }

    if (!upstream.body) return res.end();

    const stream = Readable.fromWeb(upstream.body);
    stream.on('error', (error) => {
      log.warn('proxy stream error', { error: error.message });
      res.destroy(error);
    });
    return stream.pipe(res);
  }),
);

export default proxyRouter;
