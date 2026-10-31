/**
 * lib/signedUrls.js
 * ---------------------------------------------------------------------------
 * Caducidad declarada dentro de una URL firmada.
 *
 * Muchos CDN no sirven un `.m3u8` para siempre: la URL lleva su propio
 * vencimiento (`?e=1712345678`, `?expires=…`, `?token=…~exp=…`). El TTL del
 * addon es único por clase de contenido (6 h en VOD, 2 h en directo), así que un
 * enlace firmado a 30 minutos se quedaba cacheado horas y el usuario recibía un
 * 403 al reintentar: la app lo tomaba por "no reproduce nada".
 *
 * Aquí se extrae el vencimiento **más cercano** de la lista de fuentes y se
 * recorta el TTL a lo que de verdad dura el enlace (con 15 s de margen para no
 * servir algo que caduca mientras ExoPlayer abre el manifiesto).
 *
 * El motor embebido hace lo mismo en Python (`declared_expiry`); las dos
 * implementaciones tienen que aceptar los mismos formatos.
 */

/** Parámetros que declaran el vencimiento en segundos o milisegundos. */
const EXPIRY_KEYS = new Set(['e', 'exp', 'expires', 'expire', 'expiry']);

/** Margen: un enlace que caduca dentro de 15 s ya no sirve para una sesión. */
export const EXPIRY_MARGIN_MS = 15_000;

/** Un `segundo` de 9-13 dígitos es unix; si es enorme, venía en milisegundos. */
function toMs(value) {
  const digits = String(value).trim();
  if (!/^\d{9,13}$/.test(digits)) return null;
  const epoch = Number(digits);
  return epoch > 1e11 ? epoch : epoch * 1000;
}

/**
 * Vencimiento declarado en una URL, en milisegundos unix, o `null`.
 *
 * Acepta los formatos que se ven en la calle:
 *  - `?e=1712345678` y `?expires=1712345678000`
 *  - `?token=abc~exp=1712345678` (algunos hosters meten la firma en un token)
 *
 * @param {string} url
 * @returns {number|null}
 */
export function urlExpiryMs(url) {
  const text = String(url || '');
  if (!text) return null;
  const queryStart = text.indexOf('?');
  if (queryStart < 0) return null;

  const found = [];
  for (const pair of text.slice(queryStart + 1).split('&')) {
    /* `split('=')` a secas cortaría `token=abc~exp=123` en tres trozos: el
     * valor puede llevar `=` dentro (firmas, base64). */
    const separator = pair.indexOf('=');
    const key = decodeURIComponent(separator < 0 ? pair : pair.slice(0, separator)).toLowerCase();
    const value = separator < 0 ? '' : pair.slice(separator + 1);

    if (EXPIRY_KEYS.has(key)) {
      const ms = toMs(value);
      if (ms !== null) found.push(ms);
    }
    /* `token=…~exp=1712345678~…`: la firma viaja dentro de otro valor. */
    for (const match of value.matchAll(/(?:^|[~&])exp=(\d{9,13})(?:$|[~&])/g)) {
      const ms = toMs(match[1]);
      if (ms !== null) found.push(ms);
    }
  }

  return found.length > 0 ? Math.min(...found) : null;
}

/**
 * Vencimiento más cercano de un conjunto de URLs (o `null` si ninguna caduca).
 *
 * @param {Array<string|{url?: string}>} sources
 * @returns {number|null}
 */
export function earliestExpiryMs(sources = []) {
  const expiries = sources
    .map((source) => urlExpiryMs(typeof source === 'string' ? source : source?.url))
    .filter((value) => value !== null);
  return expiries.length > 0 ? Math.min(...expiries) : null;
}

/**
 * Recorta el TTL a lo que dura el enlace firmado más corto.
 *
 * Nunca devuelve menos de 0 (un enlace ya caducado no se cachea) ni más que
 * `ttlMs`: la política por clase de contenido sigue siendo el techo.
 *
 * @param {number} ttlMs
 * @param {Array<string|{url?: string}>} sources
 * @param {number} [nowMs]
 * @returns {number}
 */
export function capTtlByExpiry(ttlMs, sources = [], nowMs = Date.now()) {
  const expiry = earliestExpiryMs(sources);
  if (expiry === null) return ttlMs;
  const remaining = expiry - nowMs - EXPIRY_MARGIN_MS;
  return Math.max(0, Math.min(ttlMs, remaining));
}

export default { urlExpiryMs, earliestExpiryMs, capTtlByExpiry, EXPIRY_MARGIN_MS };
