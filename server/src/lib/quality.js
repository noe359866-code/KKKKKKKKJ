/**
 * lib/quality.js
 * ---------------------------------------------------------------------------
 * Normalises the many ways providers describe video quality so streams can be
 * sorted/displayed consistently ("1080p", "4K", "HD", "SD"...).
 */

const RANK = {
  '4k': 6,
  '2160p': 6,
  uhd: 6,
  '1440p': 5,
  '1080p': 4,
  fhd: 4,
  '720p': 3,
  hd: 3,
  '576p': 2,
  '480p': 2,
  sd: 1,
  '360p': 1,
  cam: 0,
  unknown: -1,
};

const ALIASES = [
  [/\b(2160p?|4k|uhd)\b/i, '4K'],
  [/\b(1440p|2k)\b/i, '1440p'],
  [/\b(1080p?|fhd|full[\s-]?hd)\b/i, '1080p'],
  [/\b(720p?|hd)\b/i, '720p'],
  [/\b(576p)\b/i, '576p'],
  [/\b(480p)\b/i, '480p'],
  [/\b(360p|ld)\b/i, '360p'],
  [/\bsd\b/i, 'SD'],
  [/\bcam\b|\bts\b/i, 'CAM'],
];

/**
 * @param {string} value - e.g. "FullHD", "1080", "HD", "4K"
 * @returns {string} canonical label ("1080p") or "Unknown"
 */
export function normalizeQuality(value) {
  if (!value && value !== 0) return 'Unknown';
  const text = String(value).trim();

  for (const [pattern, label] of ALIASES) {
    if (pattern.test(text)) return label;
  }
  /* Bare numbers: "1080" -> "1080p" */
  const bare = text.match(/^\s*(\d{3,4})\s*$/);
  if (bare) return `${bare[1]}p`;

  return 'Unknown';
}

/** Sort weight for a quality label (higher = better). */
export function qualityRank(value) {
  const label = normalizeQuality(value).toLowerCase();
  return RANK[label] ?? -1;
}

export default { normalizeQuality, qualityRank };
