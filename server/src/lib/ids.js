/**
 * lib/ids.js
 * ---------------------------------------------------------------------------
 * Stremio ID helpers.
 *
 * Stremio identifies content with a VideoId made of the type, the id and an
 * optional video part separated by colons:
 *
 *   tt1254207                    -> a movie
 *   tt0944947:1:2                -> series tt0944947, season 1, episode 2
 *   sports:espn-1:2026-10-28     -> live event (custom id, this addon)
 *   tv:canal-13                  -> live channel (custom id, this addon)
 *   sf:movie:tt0111161           -> internal alias / search hint
 */

/** Splits "tt0944947:1:2" into its parts, safely handling custom prefixes. */
export function parseMediaId(rawId) {
  if (typeof rawId !== 'string' || rawId.length === 0) {
    return { raw: '', base: '', season: null, episode: null, isLive: false, isSports: false };
  }

  const segments = rawId.split(':');
  const [base, second, third] = segments;

  /* Series/episode numbering: only the tail numbers are relevant, because
   * custom ids may themselves contain colons (e.g. "sports:espn:1:2026-10-28"). */
  let season = null;
  let episode = null;

  if (segments.length >= 3) {
    const candidateSeason = Number(segments[segments.length - 2]);
    const candidateEpisode = Number(segments[segments.length - 1]);
    if (Number.isInteger(candidateSeason) && Number.isInteger(candidateEpisode)) {
      season = candidateSeason;
      episode = candidateEpisode;
    }
  }

  const lower = base.toLowerCase();
  const isSports = lower === 'sports' || lower === 'sport' || lower === 'deportes' || /:sport:/.test(rawId);
  /* "tv:" is a channel id; a plain type "tv" without colon is not live per se,
   * but Stremio sends live content as "tv:channel-slug". */
  const isLive = isSports || lower === 'tv' || /^tv:/i.test(rawId);

  return {
    raw: rawId,
    base,
    second: second ?? null,
    third: third ?? null,
    season,
    episode,
    isLive,
    isSports,
    isSeries: season !== null && episode !== null,
    /* IMDb id when available, used by most scrapers. */
    imdbId: base.startsWith('tt') ? base : null,
  };
}

/** Normalizes the ":type/:id.json" request into a canonical id string. */
export function normalizeStreamId(id) {
  return String(id || '').replace(/\.json$/i, '').trim();
}
