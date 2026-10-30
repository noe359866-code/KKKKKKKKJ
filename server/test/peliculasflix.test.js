/**
 * test/peliculasflix.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for `scrapers/peliculasflix.py`.
 *
 * Este sitio no tiene reproductor en el HTML: todo pasa por su API GraphQL.
 * El fixture responde a las dos operaciones reales —
 *
 *   searchAll      → searchMovie / searchFilm   (búsqueda)
 *   getMovieLinks  → links_online               (servidores por idioma)
 *
 * — y los `link` llegan como texto con un `<iframe src="…">` dentro, que es
 * como los publica el sitio.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import {
  hasUrl,
  reasonOf,
  runCli,
  runScraper,
  startFixture,
  streamwishPage,
  urlsOf,
} from '../testsupport/pelis.js';

const SCRIPT = 'peliculasflix.py';
const CLEAN = { PELICULASFLIX_API: '', PELICULASFLIX_BASE: '', TMDB_API_KEY: '' };

let fixture;
let env;

before(async () => {
  fixture = await startFixture((base) => ({
    '/api/gql': ({ raw }) => {
      const request = JSON.parse(raw || '{}');
      const operation = request.operationName;

      if (operation === 'searchAll') {
        const input = String(request.variables?.input || '').toLowerCase();
        const hits = [
          { _id: '65f1', slug: 'spider-man-de-regreso-a-casa', title: '<span>Spider-Man: De regreso a casa</span>', name_es: 'Spider-Man: De regreso a casa' },
          { _id: '65f2', slug: 'clayface', title: 'Clayface', name_es: 'Clayface' },
          { _id: '65f3', slug: 'the-last-of-us', title: 'The Last of Us', name_es: 'The Last of Us' },
        ].filter((hit) => hit.slug.includes(input.split(' ')[0]));
        return {
          type: 'application/json',
          body: JSON.stringify({ data: { searchMovie: hits } }),
        };
      }

      if (operation === 'getMovieLinks') {
        const slug = String(request.variables?.slug || '');
        if (slug !== 'spider-man-de-regreso-a-casa') {
          return { type: 'application/json', body: JSON.stringify({ data: { getMovieLinks: null } }) };
        }
        return {
          type: 'application/json',
          body: JSON.stringify({
            data: {
              getMovieLinks: {
                _id: '65f1',
                slug,
                name_es: 'Spider-Man: De regreso a casa',
                links_online: [
                  { server: 'fw', lang: '37', link: `<iframe src="${base}/ext/streamwish" allowfullscreen></iframe>` },
                  { server: 'fm', lang: '192', link: `${base}/ext/filemoon` },
                  { server: 'mg', lang: '37', link: 'https://mega.nz/file/abc123' },
                ],
              },
            },
          }),
        };
      }

      /* Serie: la ficha de película no tiene enlaces, la de episodio sí. */
      if (operation === 'getSerieLinks') {
        return { type: 'application/json', body: JSON.stringify({ data: { getSerieLinks: null } }) };
      }

      if (operation === 'getEpisodeLinks') {
        const { slug, season, episode } = request.variables || {};
        if (slug !== 'the-last-of-us') {
          return { type: 'application/json', body: JSON.stringify({ data: { getEpisodeLinks: null } }) };
        }
        return {
          type: 'application/json',
          body: JSON.stringify({
            data: {
              getEpisodeLinks: {
                _id: '65f3',
                slug,
                name_es: 'The Last of Us',
                season,
                episode,
                links_online: [{ server: 'fw', lang: '37', link: `${base}/ext/streamwish` }],
              },
            },
          }),
        };
      }

      return { type: 'application/json', body: JSON.stringify({ data: {} }) };
    },

    '/ext/streamwish': streamwishPage('http://cdn.example/sw/index.m3u8?token=abc'),
    '/ext/filemoon': `<html><body><script>player = new Clappr.Player({source: "http://cdn.example/fm/index.m3u8", mimeType: "application/x-mpegURL"});</script></body></html>`,
  }));
  env = { ...CLEAN, PELICULASFLIX_API: `${fixture.base}/api/gql` };
});

after(async () => {
  await fixture.close();
});

const ask = (id) => ({ type: 'movie', id, provider: 'peliculasflix', timeoutMs: 8000 });

test('la API GraphQL resuelve la película y sus servidores', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, ask('peliculasflix:spider-man-de-regreso-a-casa'), env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.equal(body.meta.slug, 'spider-man-de-regreso-a-casa');
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
  assert.ok(hasUrl(body, 'cdn.example/fm/index.m3u8'));
});

test('el idioma del sitio se usa como etiqueta del servidor', async () => {
  const { body } = await runScraper(SCRIPT, ask('peliculasflix:spider-man-de-regreso-a-casa'), env);

  assert.equal(body.streams[0].name, 'Spider-Man: De regreso a casa');
  assert.match(body.streams[0].title, /· Español$/);
  assert.match(body.streams[1].title, /· Subtitulado$/);
});

test('el `<iframe>` que llega como texto se extrae y Mega se descarta', async () => {
  const { body } = await runScraper(SCRIPT, ask('peliculasflix:spider-man-de-regreso-a-casa'), env);

  assert.ok(!urlsOf(body).some((url) => url.includes('mega.nz')));
  assert.ok(!urlsOf(body).some((url) => url.includes('<iframe')));
});

test('una búsqueda sin coincidencias devuelve [] con not_found', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('peliculasflix:pelicula-inexistente-1999'), env);

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_found');
});

test('si la API no devuelve enlaces, el motivo es no_servers', async () => {
  const { body } = await runScraper(SCRIPT, ask('peliculasflix:clayface'), env);

  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'no_servers');
});

test('una serie se resuelve por la ficha de episodio, con temporada y capítulo', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, {
    ...ask('peliculasflix:the-last-of-us:2:3'),
    type: 'series',
  }, env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
  /* La página (y por tanto el Referer) es la del capítulo, no la de película. */
  assert.match(body.meta.page, /\/serie\/the-last-of-us\/2x3$/);
});

test('rechaza payloads en directo', async () => {
  const { body } = await runScraper(SCRIPT, { type: 'tv', id: 'tv:peliculasflix:x', isLive: true }, env);
  assert.equal(reasonOf(body), 'not_vod');
});

test('una API inalcanzable no rompe: [] con not_found', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('peliculasflix:spider-man-de-regreso-a-casa'), {
    ...CLEAN,
    PELICULASFLIX_API: 'http://127.0.0.1:1/api/gql',
  });

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_found');
});

test('--list --query consulta la API', async () => {
  const { stdout } = await runCli(SCRIPT, ['--list', '--query', 'clayface', '--json'], env);
  const items = JSON.parse(stdout).items;

  assert.deepEqual(items.map((item) => item.id), ['peliculasflix:clayface']);
});
