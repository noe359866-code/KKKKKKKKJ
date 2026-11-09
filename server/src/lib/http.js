/** Small HLS playlist helpers used by the optional media proxy. */

/** True for HLS playlists that need their child URIs rewritten through proxy. */
export function isHls(url) {
  return /\.m3u8(\?|$)/i.test(url || '');
}

/**
 * Rewrites the URIs inside an HLS playlist so segments also go through the
 * proxy. Absolute URLs are prefixed; relative ones resolve against the playlist.
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
      if (trimmed.startsWith('#EXT-X-KEY') || trimmed.startsWith('#EXT-X-MAP')) {
        return line.replace(/URI="([^"]+)"/, (_, uri) => `URI="${proxied(uri)}"`);
      }
      if (trimmed.startsWith('#')) return line;
      return proxied(trimmed);
    })
    .join('\n');
}

export default { isHls, rewriteHlsPlaylist };
