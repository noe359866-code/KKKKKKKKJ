/**
 * test/pelisjuanita.test.js
 * ---------------------------------------------------------------------------
 * Offline tests for `scrapers/pelisjuanita.py`.
 *
 * PelisJuanita (TMDB de fondo) separa las fichas en `/pelicula/<slug>` y
 * `/serie/<slug>`; el segundo nivel de las series no es fiable, así que el
 * proveedor sigue el enlace del capítulo cuando la ficha no trae reproductores.
 */

import test, { after, before } from 'node:test';
import assert from 'node:assert/strict';

import {
  classicSiteRoutes,
  hasUrl,
  reasonOf,
  runCli,
  runScraper,
  startFixture,
  streamwishPage,
  urlsOf,
} from '../testsupport/pelis.js';

const SCRIPT = 'pelisjuanita.py';
const CLEAN = { PELISJUANITA_BASE: '', PELISJUANITA_MIRRORS: '', TMDB_API_KEY: '' };

let fixture;
let env;

before(async () => {
  fixture = await startFixture((base) => ({
    ...classicSiteRoutes(base, {
      moviePath: '/pelicula/spider-man-un-nuevo-dia-2026',
      movieSlug: 'spider-man-un-nuevo-dia-2026',
      searchPath: '/buscar',
      searchSlug: 'spider-man-un-nuevo-dia-2026',
      withSeries: false,
    }),
    '/serie/reacher-2022': `<html><head><title>Reacher (2022)</title></head><body>
      <h1>Reacher</h1>
      <p>Jack Reacher, un exmilitar, recorre el país y se topa con un pueblo
      donde todos mienten. Lo que empieza como un caso de asesinato acaba
      destapando una conspiración con la Guardia Nacional dentro.</p>
      <div class="episodios">
        <a href="/serie/reacher-2022/1x03">T1 E3</a>
        <a href="/serie/reacher-2022/1x04">T1 E4</a>
        <a href="/serie/reacher-2022/1x05">T1 E5</a>
      </div>
      <section class="recomendadas"><a href="/serie/mentes-criminales-2005">Mentes Criminales</a></section>
      </body></html>`,
    '/serie/reacher-2022/1x03': `<html><head><title>Reacher 1x03</title></head><body>
      <h1>Reacher — Episodio 3</h1>
      <p>Sinopsis del capítulo.</p>
      <div class="player"><iframe src="${base}/ext/streamwish"></iframe></div>
      <p>Reacher tira de la cuerda y descubre quién manda en Margrave, mientras el
      pueblo entero mira hacia otro lado y la Guardia Nacional empieza a moverse.
      Un episodio de esos que se recuerdan.</p>
      </body></html>`,
    /* El capítulo 1x05 no tiene ruta directa: sólo se llega por el enlace de la ficha. */
    '/serie/reacher-2022/1x05': `<html><head><title>Reacher 1x05</title></head><body>
      <h1>Reacher — Episodio 5</h1>
      <div class="player"><iframe src="${base}/ext/streamwish"></iframe></div>
      </body></html>`,
    /* Sección de anime: la ficha NO está en /pelicula/. */
    '/anime/naruto-shippuden': `<html><head><title>Naruto Shippuden</title></head><body>
      <h1>Naruto Shippuden</h1>
      <p>Naruto vuelve a la aldea después de dos años de entrenamiento y todo ha
      cambiado: Akatsuki se mueve, Sasuke sigue fuera y el examen de ascenso ya
      no es lo importante. La aldea está en paz, pero nadie se fía del silencio
      y el equipo 7 ya no existe como tal. Una etapa nueva que empieza con una
      noticia que nadie quiere dar.</p>
      <div class="player"><iframe src="${base}/ext/streamwish"></iframe></div>
      </body></html>`,
    /* Reproductor propio: la ficha sólo apunta a la página del sitio, que es la
       que lista el embed de verdad. */
    '/pelicula/doble-salto': `<html><head><title>Doble Salto</title></head><body>
      <h1>Doble Salto</h1>
      <p>Un doble de riesgo acepta el trabajo de su vida y descubre que la escena
      que tiene que rodar es la misma en la que desapareció su hermano hace diez
      años. Nada de lo que ocurre en el plató es casualidad.</p>
      <div id="player"><iframe src="${base}/player/999"></iframe></div>
      </body></html>`,
    '/player/999': `<html><head><title>Reproductor</title></head><body>
      <h1>Reproductor</h1>
      <div class="servidores">
        <iframe src="${base}/ext/streamwish"></iframe>
      </div>
      <p>Este reproductor sólo devuelve embeds, ningún manifiesto directo.</p>
      </body></html>`,
  }));
  env = { ...CLEAN, PELISJUANITA_BASE: fixture.base };
});

after(async () => {
  await fixture.close();
});

const ask = (id) => ({ type: 'movie', id, provider: 'pelisjuanita', timeoutMs: 8000 });

test('película: ficha, servidores y señales', async () => {
  const { code, body, stderr } = await runScraper(SCRIPT, ask('pelisjuanita:spider-man-un-nuevo-dia-2026'), env);

  assert.equal(code, 0, stderr);
  assert.equal(reasonOf(body), undefined);
  assert.equal(body.meta.page, `${fixture.base}/pelicula/spider-man-un-nuevo-dia-2026`);
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
});

test('etiquetas con el sitio delante', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisjuanita:spider-man-un-nuevo-dia-2026'), env);

  assert.equal(body.streams[0].name, 'Spider Man un Nuevo Dia 2026');
  assert.match(body.streams[0].title, /^PelisJuanita · Spider Man un Nuevo Dia 2026 · /);
});

test('serie: abre la ruta del capítulo con dos dígitos', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisjuanita:reacher-2022:1:3'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.equal(body.meta.kind, 'series');
  assert.equal(body.meta.resolved_by, 'direct');
  assert.equal(body.meta.page, `${fixture.base}/serie/reacher-2022/1x03`);
  assert.match(body.streams[0].name, /· T1 E3$/);
});

test('serie: si la ruta directa no existe, sigue el enlace de la ficha', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisjuanita:reacher-2022:1:5'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.equal(body.meta.episode_page, `${fixture.base}/serie/reacher-2022/1x05`);
  assert.match(body.streams[0].name, /· T1 E5$/);
});

test('anime: la ficha vive en /anime/, no en /pelicula/', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisjuanita:naruto-shippuden'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.equal(body.meta.page, `${fixture.base}/anime/naruto-shippuden`);
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
});

test('abre la página de reproductor del propio sitio y lee sus embeds', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisjuanita:doble-salto'), env);

  assert.equal(reasonOf(body), undefined, JSON.stringify(body.meta));
  assert.deepEqual(body.meta.opened_players, [`${fixture.base}/player/999`]);
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'), urlsOf(body).join(' '));
  /* La página intermedia no se publica como stream: no es reproducible. */
  assert.ok(!urlsOf(body).some((url) => url.includes('/player/999')));
});

test('el buscador del sitio resuelve un título escrito a mano', async () => {
  const { body } = await runScraper(SCRIPT, ask('pelisjuanita:spider man'), env);

  assert.equal(body.meta.resolved_by, 'search');
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
});

test('un título inexistente devuelve [] sin romperse', async () => {
  const { code, body } = await runScraper(SCRIPT, ask('pelisjuanita:pelicula-que-no-existe-1999'), env);

  assert.equal(code, 0);
  assert.deepEqual(body.streams, []);
  assert.equal(reasonOf(body), 'not_found');
});

test('rechaza payloads en directo', async () => {
  const { body } = await runScraper(SCRIPT, { type: 'tv', id: 'tv:pelisjuanita:x', isLive: true }, env);
  assert.equal(reasonOf(body), 'not_vod');
});

test('--list sale de la portada y --list --query del buscador', async () => {
  const plain = await runCli(SCRIPT, ['--list', '--json'], env);
  const items = JSON.parse(plain.stdout).items;
  assert.ok(items.some((item) => item.id === 'pelisjuanita:spider-man-un-nuevo-dia-2026'), plain.stdout);

  const searched = await runCli(SCRIPT, ['--list', '--query', 'spider', '--json'], env);
  assert.equal(JSON.parse(searched.stdout).items[0].slug, 'spider-man-un-nuevo-dia-2026');
});

test('acepta la URL de la ficha como id', async () => {
  const { body } = await runScraper(
    SCRIPT,
    ask(`${fixture.base}/pelicula/spider-man-un-nuevo-dia-2026`),
    env,
  );

  assert.equal(body.meta.resolved_by, 'id_url');
  assert.ok(hasUrl(body, 'cdn.example/sw/index.m3u8'));
});

test('el fixture sirve una página de servidor reutilizable', async () => {
  /* Guardia: si alguien toca el fixture, este test avisa antes que los demás. */
  assert.match(streamwishPage('http://cdn.example/x.m3u8'), /sources/);
});
