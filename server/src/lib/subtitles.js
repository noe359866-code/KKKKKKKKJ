/**
 * lib/subtitles.js
 * ---------------------------------------------------------------------------
 * Subtítulos que publican los scrapers.
 *
 * Hoy sólo novaHD los entrega (su API de origen trae `subs[]` y el scraper lo
 * deja en `meta.subtitles`), pero el contrato es el del protocolo Stremio:
 *
 *   { id, url, lang, label }
 *
 * El reproductor los carga como pistas de texto externas (`MediaItem
 * .SubtitleConfiguration`), así que aquí sólo se valida lo que ExoPlayer puede
 * abrir de verdad: HTTP(S), formato reconocible (VTT/SRT/ASS/TTML) y nada que
 * apunte a una página web. Una URL mala no es un subtítulo que falla: es un
 * `PlayerException` en mitad de la película.
 *
 * Los subtítulos viajan **dentro de la misma respuesta de streams** (campo
 * extra ignorado por los clientes Stremio) para no obligar a la app a una
 * segunda petición por título: la lista se resuelve con el mismo scrape y se
 * cachea con el mismo TTL que las fuentes.
 */

/** Tope defensivo: ninguna película necesita 40 pistas y no se cachea basura. */
export const MAX_SUBTITLES = 40;

/** Extensiones/formatos que Media3 sabe leer de forma nativa. */
const FORMAT_BY_EXTENSION = {
  vtt: 'vtt',
  webvtt: 'vtt',
  srt: 'srt',
  sub: 'srt',
  ass: 'ass',
  ssa: 'ass',
  ttml: 'ttml',
  dfxp: 'ttml',
  xml: 'ttml',
};

/** Códigos de tres letras y nombres de idioma que mandan los proveedores. */
const LANGUAGE_ALIASES = {
  spa: 'es',
  esp: 'es',
  spanish: 'es',
  castellano: 'es',
  espanol: 'es',
  español: 'es',
  latino: 'es',
  eng: 'en',
  english: 'en',
  ing: 'en',
  ingles: 'en',
  inglés: 'en',
  por: 'pt',
  portuguese: 'pt',
  portugues: 'pt',
  fre: 'fr',
  fra: 'fr',
  french: 'fr',
  ger: 'de',
  deu: 'de',
  german: 'de',
  ita: 'it',
  italian: 'it',
  jpn: 'ja',
  japanese: 'ja',
  kor: 'ko',
  korean: 'ko',
  zho: 'zh',
  chi: 'zh',
  chinese: 'zh',
  rus: 'ru',
  russian: 'ru',
};

/** Nombres legibles cuando el proveedor sólo manda el código. */
const LANGUAGE_NAMES = {
  es: 'Español',
  en: 'Inglés',
  pt: 'Portugués',
  fr: 'Francés',
  de: 'Alemán',
  it: 'Italiano',
  ja: 'Japonés',
  ko: 'Coreano',
  zh: 'Chino',
  ru: 'Ruso',
};

/** Lo que nunca es un subtítulo, aunque esté servido por el mismo host. */
const REJECTED_EXTENSIONS = new Set([
  'html', 'htm', 'php', 'asp', 'aspx', 'jsp', 'json', 'txt', 'css', 'js',
  'jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'mp4', 'm3u8', 'mpd',
]);

/** `.../foo.es.srt?token=...` → `srt`. */
export function subtitleFormat(url) {
  const clean = String(url || '').split('#')[0].split('?')[0].toLowerCase();
  const extension = clean.includes('.') ? clean.slice(clean.lastIndexOf('.') + 1) : '';
  return FORMAT_BY_EXTENSION[extension] || null;
}

/** `SPA`, `es-419`, `Spanish`… → `es` / `es-419` (lo que espera ExoPlayer). */
export function normalizeLanguage(value) {
  const text = String(value || '').trim().toLowerCase().replace('_', '-');
  if (!text) return '';
  const [base, ...rest] = text.split('-');
  const normalizedBase = LANGUAGE_ALIASES[base] || base;
  if (!/^[a-z]{2,3}$/.test(normalizedBase)) return '';
  /* `es-419` se conserva (latino ≠ castellano); un sufijo raro se descarta. */
  const region = rest.join('-');
  return /^([a-z]{2}|\d{3})$/.test(region) ? `${normalizedBase}-${region}` : normalizedBase;
}

/**
 * `es` → `Español`, `es-419` → `Español (Latino)`, `pt-BR` → `Portugués (Brasil)`.
 *
 * La región importa: en una app en español, "Español" y "Español (Latino)" son
 * dos pistas distintas y el usuario elige entre ellas en el mismo panel.
 */
export function languageName(value) {
  const normalized = normalizeLanguage(value);
  const [code, ...region] = normalized.split('-');
  const base = LANGUAGE_NAMES[code];
  if (!base) return '';
  const suffix = region.join('-').toLowerCase();
  if (!suffix) return base;
  if (code === 'es' && suffix === '419') return `${base} (Latino)`;
  if (code === 'pt' && suffix === 'br') return `${base} (Brasil)`;
  return `${base} (${suffix.toUpperCase()})`;
}

function isHttp(url) {
  return /^https?:\/\//i.test(url);
}

/** Una URL de página o de imagen no es una pista: ExoPlayer la rechazaría. */
export function looksLikeSubtitle(u) {
  const clean = String(u || '').split('#')[0].split('?')[0].toLowerCase();
  const extension = clean.includes('.') ? clean.slice(clean.lastIndexOf('.') + 1) : '';
  return !REJECTED_EXTENSIONS.has(extension);
}

/**
 * Una entrada cruda (`{ url, lang, label }`) → subtítulo del protocolo.
 * Devuelve `null` si no es algo que el reproductor pueda abrir.
 *
 * @param {object} raw
 * @param {{ label?: string }} [provider]
 * @returns {{ id: string, url: string, lang: string, label: string, provider?: string, format?: string } | null}
 */
export function normalizeSubtitle(raw, provider = {}) {
  if (!raw || typeof raw !== 'object') return null;
  const url = String(raw.url || raw.src || '').trim();
  if (!url || url.length > 2048 || !isHttp(url) || !looksLikeSubtitle(url)) return null;

  const lang = normalizeLanguage(raw.lang || raw.language);
  const format = subtitleFormat(url) || (raw.format ? subtitleFormat(`.${raw.format}`) : null);
  /* Sin formato conocido se deja pasar: el cliente puede olfatearlo por el
   * `Content-Type`. Lo que no se deja pasar es una URL sin esquema. */
  const label = String(raw.label || raw.name || '').trim()
    || languageName(lang)
    || (lang ? lang.toUpperCase() : '')
    || String(provider.label || '').trim();

  if (!label) return null;

  return {
    id: String(raw.id || url),
    url,
    lang,
    label: label.slice(0, 64),
    ...(provider.label ? { provider: provider.label } : {}),
    ...(format ? { format } : {}),
  };
}

/**
 * Reúne los subtítulos de todos los proveedores de un scrape.
 *
 * - deduplica por URL (varios proveedores repiten el mismo CDN);
 * - pone primero los que están en español: es el idioma de la app y lo que el
 *   reproductor elige por defecto cuando hay pista propia;
 * - corta en [MAX_SUBTITLES].
 *
 * @param {Array<{ provider: object, meta?: object }>} results
 * @returns {object[]}
 */
export function buildSubtitleList(results = []) {
  const seen = new Set();
  const out = [];

  for (const result of results) {
    if (!result || !result.provider) continue;
    const raw = Array.isArray(result.meta?.subtitles) ? result.meta.subtitles : [];
    for (const entry of raw) {
      const subtitle = normalizeSubtitle(entry, result.provider);
      if (!subtitle || seen.has(subtitle.url)) continue;
      seen.add(subtitle.url);
      out.push(subtitle);
      if (out.length >= MAX_SUBTITLES) return sortByLanguage(out);
    }
  }

  return sortByLanguage(out);
}

/** Español primero, y dentro de cada idioma se respeta la prioridad del proveedor. */
function sortByLanguage(list) {
  const isSpanish = (item) => item.lang.toLowerCase().startsWith('es');
  return [...list.filter(isSpanish), ...list.filter((item) => !isSpanish(item))];
}

export default {
  buildSubtitleList,
  normalizeSubtitle,
  normalizeLanguage,
  languageName,
  subtitleFormat,
  looksLikeSubtitle,
};
