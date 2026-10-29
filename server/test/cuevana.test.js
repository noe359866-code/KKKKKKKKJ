/**
 * test/cuevana.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for `scrapers/cuevana.py`.
 *
 * El fixture reproduce las dos formas que usa Cuevana para publicar el embed:
 *
 *   <iframe src="/embed-page?showEmbed=<base64>"   (actual, base64 en la URL)
 *   <iframe src="/player.php?t=<token>">           (legado, un salto más)
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import {
  b64,
  hasUrl,
  reasonOf,
  runCli,
  runScraper,
  startFixture,
  streamwishPage,
  urlsOf,
} from '../testsupport/pelis.js';

const SCRIPT = 'cuevana.py';
const CLEAN = { CUEVANA_BASE: '', CUEVANA_MIRRORS: '', TMDB_API_KEY: '' };

let fixture;
let env;

before(async () => {
  fixture = await startFixture((base) => ({
    '/': `<html><body><section>
      <a href="/peliculas/spider-man-de-regreso-a-casa"><h3>Spider-Man: De regreso a casa</h3></a>
      <a href="/peliculas/clayface"><h3>Clayface</h3></a>
    </section></body></html>`,

    /* Ficha con `showEmbed` (base64) — formato actual. */
    '/peliculas/spider-man-de-regreso-a-casa': `<html>
<head><title>Spider-Man: De regreso a casa (2017) — Cuevana</title></head><body>
<h1>Spider-Man: De regreso a casa</h1>
<p>Peter Parker vuelve a casa junto a su tía May después de la batalla de
Civil War y trata de retomar su rutina mientras aparece el Buitre.</p>
<div class="tab_language_movie">
  <iframe src="${base}/embed-page?showEmbed=${encodeURIComponent(b64(`${base}/ext/streamwish`))}"></iframe>
  <iframe src="${base}/embed-page?showEmbed=${encodeURIComponent(b64(`${base}/ext/filemoon`))}"></iframe>
  <iframe src="${base}/embed-page?showEmbed=${encodeURIComponent(b64('https://mega.nz/file/abc123'))}"></iframe>
</div>
</body></html>`,

    /* Ficha con token opaco — formato legado. */
    '/peliculas/clayface': `<html><head><title>Clayface — Cuevana</title></head><body>
<h1>Clayface</h1>
<p>Un actor deformado por un accidente busca venganza en las calles de Gotham
mientras su cuerpo literalmente se deshace.</p>
<iframe src="/player.php?t=tok3nDePruebaLargo123456"></iframe>
</body></html>`,

    '/player.php': `<html><body><iframe src="${base}/ext/streamwish"></iframe></body></html>`,

    '/series/the-last-of-us': `<html><head><title>The Last of Us</title></head><body>
<h1>The Last of Us</h1><p>Serie de zombis y padres.</p></body></html>`,
    '/series/the-last-of-us/1/2': `<html><head><title>The Last of Us 1x02</title></head><body>
<h1>The Last of Us — Episodio 2</h1>
<p>Joel, Tess y Ellie se adentran en una zona contaminada de Boston. Lo que
encuentran dentro cambiará su relación para siempre y pondrá a prueba la
promesa que Joel le hizo a Marlene.</p>
<iframe src="${base}/embed-page?showEmbed=${encodeURIComponent(b64(`${base}/ext/streamwish`))}"></iframe>
</body></html>`,

    '/': ({ url }) => {
      const query = url.searchParams.get('s') || '';
      const hits = [
        ['spider-man-de-regreso-a-casa', 'Spider-Man: De regreso a casa'],
        ['clayface', 'Clayface'],
      ].filter(([, title]) => title.toLowerCase().includes(query.toLowerCase().split(' ')[0]));
      return `<html><body><div class="results">${hits.map(([slug, title]) =>
        `<a href="/peliculas/${slug}"><h3>${title}</h3></a>`).join('')}</div></body></html>`;
    },

    '/ext/streamwish': streamwishPage('http://cdn.example/sw/index.m3u8?token=abc'),
    '/ext/filemoon': `<html><body><script>player = new Clappr.Player({source: "http://cdn.example/fm/index.m3u8", mimeType: "application/x-mpegURL"});</script></body></html>`,
  }));
  env = { ...CLEAN, CUEVANA_BASE: fixture.base };
});

after(async () => {
  await fixture.close();
});

const ask = (id) => ({ type: 'movie', id, provider: 'cuevana', timeoutMs: 8000 });

test('showEmbed en base64 → un stream por servidor', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, ask('cuevana:spider-man-de-regreso-a-casa'), env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
  assert.ok(hasUrl(body, 'cdn.example/fm/index.m3u8'));
});

test('las etiquetas llevan el sitio delante', async () => {
  const { body } = await runScraper(SCRIPT, ask('cuevana:spider-man-de-regreso-a-casa'), env);

  assert.equal(body.streams[0].name, 'Spider Man de Regreso a Casa');
  assert.match(body.streams[0].title, /^Cuevana · Spider Man de Regreso a Casa · /);
});

test('el base64 de Mega se descarta', async () => {
  const { body } = await runScraper(SCRIPT, ask('cuevana:spider-man-de-regreso-a-casa'), env);

  assert.ok(!urlsOf(body).some((url) => url.includes('mega.nz')));
  assert.ok((body.meta.download_only || []).some((name) => /mega/i.test(name)), JSON.stringify(body.meta));
});

test('el formato legado (player.php?t=) se sigue hasta el iframe', async () => {
  const { body } = await runScraper(SCRIPT, ask('cuevana:clayface'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
  assert.ok(fixture.requested.some((entry) => entry.includes('/player.php?t=tok3n')), fixture.requested.join(' | '));
});

test('serie: la página por capítulo trae su propio showEmbed', async () => {
  const { body } = await runScraper(SCRIPT, ask('cuevana:the-last-of-us:1:2'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.match(body.streams[0].name, /· T1 E2$/);
  assert.ok(fixture.requested.some((entry) => entry.includes('/series/the-last-of-us/1/2')));
});

test('un título inexistente devuelve [] con motivo', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('cuevana:pelicula-inexistente-1999'), env);

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_found');
});

test('rechaza payloads en directo', async () => {
  const { body } = await runScraper(SCRIPT, { type: 'tv', id: 'tv:cuevana:x', isLive: true }, env);
  assert.equal(reasonOf(body), 'not_vod');
});

test('--list y --list --query', async () => {
  const home = await runCli(SCRIPT, ['--list', '--json'], env);
  assert.ok(JSON.parse(home.stdout).items.some((item) => item.id === 'cuevana:spider-man-de-regreso-a-casa'));

  const searched = await runCli(SCRIPT, ['--list', '--query', 'clayface', '--json'], env);
  assert.equal(JSON.parse(searched.stdout).items[0].slug, 'clayface');
});
