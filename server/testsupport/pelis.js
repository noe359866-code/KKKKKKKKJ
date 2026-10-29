/**
 * testsupport/pelis.js
 * ---------------------------------------------------------------------------
 * Utilidades compartidas por los tests offline de los proveedores de películas
 * y series (Gnula, Cuevana, PelisPlus, Repelis, PeliculasFlix, PelisJuanita).
 *
 * Cada test levanta un `http.createServer` local que imita al sitio real y
 * apunta el scraper a él con su variable de entorno (`GNULA_BASE`, …). Así se
 * valida la cadena completa —ficha → servidor → `.m3u8`— sin red.
 *
 * (Vive fuera de `test/` a propósito: `node --test` ejecuta todo lo que hay en
 * ese directorio y este archivo no es un test.)
 */

import http from 'node:http';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
export const SCRAPERS_DIR = path.resolve(__dirname, '../scrapers');
const PYTHON = process.env.SCRAPER_PYTHON || 'python3';

/** base64, como lo publican estos sitios. */
export const b64 = (value) => Buffer.from(String(value), 'utf8').toString('base64');

/* -------------------------------------------------------------------------- */
/* Fixture HTTP                                                              */
/* -------------------------------------------------------------------------- */

/**
 * Levanta el fixture.
 *
 * @param {(base: string) => Record<string, {body: string, type?: string}>} build
 * @returns {Promise<{base: string, requested: string[], close: () => Promise<void>}>}
 */
export async function startFixture(build) {
  const requested = [];
  let routes = {};

  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const key = url.pathname + (url.search || '');
    requested.push(`${req.method} ${key}`);

    /* APIs GraphQL: la respuesta depende del cuerpo, así que la decide el test. */
    const handler = routes[key] ?? routes[url.pathname];
    if (typeof handler === 'function') {
      let raw = '';
      req.on('data', (chunk) => { raw += chunk; });
      req.on('end', () => {
        const result = handler({ url, raw });
        const body = typeof result === 'string' ? result : result?.body ?? '';
        const type = typeof result === 'string' ? 'text/html; charset=utf-8' : result?.type ?? 'text/html; charset=utf-8';
        const status = typeof result === 'string' ? 200 : result?.status ?? 200;
        res.writeHead(status, { 'Content-Type': type }).end(body);
      });
      return;
    }

    if (handler === undefined) {
      res.writeHead(404, { 'Content-Type': 'text/html; charset=utf-8' }).end('<h1>404</h1>');
      return;
    }
    const body = typeof handler === 'string' ? handler : handler.body;
    const type = typeof handler === 'string' ? 'text/html; charset=utf-8' : handler.type ?? 'text/html; charset=utf-8';
    res.writeHead(200, { 'Content-Type': type }).end(body);
  });

  server.listen(0, '127.0.0.1');
  await new Promise((resolve) => server.once('listening', resolve));

  const base = `http://127.0.0.1:${server.address().port}`;
  routes = build(base) || {};

  return {
    base,
    requested,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}

/* -------------------------------------------------------------------------- */
/* Ejecución del scraper                                                      */
/* -------------------------------------------------------------------------- */

/**
 * Ejecuta un scraper con un payload por stdin.
 *
 * @param {string} script  nombre del archivo en `scrapers/`
 * @param {object} payload JSON de entrada
 * @param {Record<string, string>} [env]
 * @returns {Promise<{code: number, body: any, stdout: string, stderr: string}>}
 */
export function runScraper(script, payload, env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [path.join(SCRAPERS_DIR, script)], {
      env: { ...process.env, ...env },
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', (code) => {
      try {
        resolve({ code, stdout, stderr, body: JSON.parse(stdout) });
      } catch {
        reject(new Error(`scraper sin JSON (exit ${code}):\n${stdout}\n${stderr}`));
      }
    });
    child.stdin.end(JSON.stringify(payload));
  });
}

/**
 * Ejecuta la CLI del scraper (`--list …`).
 *
 * @param {string} script
 * @param {string[]} args
 * @param {Record<string, string>} [env]
 * @returns {Promise<{code: number, stdout: string, stderr: string}>}
 */
export function runCli(script, args, env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(PYTHON, [path.join(SCRAPERS_DIR, script), ...args], {
      env: { ...process.env, ...env },
    });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => { stdout += chunk; });
    child.stderr.on('data', (chunk) => { stderr += chunk; });
    child.on('error', reject);
    child.on('close', (code) => resolve({ code, stdout, stderr }));
  });
}

/* -------------------------------------------------------------------------- */
/* Páginas de servidores (las que resuelve `_hosters.py`)                     */
/* -------------------------------------------------------------------------- */

/** Streamwish: `sources: [{file: …m3u8}]`. */
export const streamwishPage = (url) =>
  `<html><body><script>window.player = {sources: [{file: "${url}", label: "HD"}]};</script></body></html>`;

/** VOE: redirección por JavaScript a un reproductor con `hls`. */
export const voePage = (url) =>
  `<html><body><script>window.location.href = "/ext/voe-player";</script></body></html>`;

export const voePlayerPage = (url) =>
  `<html><body><script>sources = [{file: "${url}", type: "hls"}];</script></body></html>`;

/** Streamtape: `ideoooolink` + token. */
export const streamtapePage = (fragment) =>
  `<html><body><span id="ideoooolink" style="display:none">${fragment}</span>\n` +
  `<script>var token = 'tok3n';</script></body></html>`;

/** Packer de Dean Edwards (`eval(function(p,a,c,k,e,d)…`). */
export const packer = (payload, table) =>
  `eval(function(p,a,c,k,e,d){e=function(c){return c};while(c--){if(k[c]){p=p.replace(new RegExp('\\\\b'+c+'\\\\b','g'),k[c])}}return p}('${payload}',${table.length},${table.length},'${table.join('|')}'.split('|'),0,{}))`;

/** Filemoon: el `.m3u8` escondido en un `eval()` empaquetado. */
export const packedPlayerPage = (url, table = ['var', 'src', 'https', 'player', 'Clappr', 'hls']) =>
  `<html><body><div id="player"></div><script>\n` +
  `${packer(`0 1="2://cdn.example/fm/index.m3u8?s=tok";3=new 4.Player({source:1});`, table)}\n` +
  `</script></body></html>` + (url ? `<!-- ${url} -->` : '');

/* -------------------------------------------------------------------------- */
/* Fixture de un sitio "clásico" de películas y series                       */
/* -------------------------------------------------------------------------- */

/**
 * Rutas de un sitio con el patrón habitual (ficha de película, ficha de serie
 * con capítulos, buscador y cuatro servidores: dos vivos, uno empaquetado, uno
 * caído y un hosting de descarga).
 *
 * @param {string} base
 * @param {object} [options]
 * @returns {Record<string, string | {body: string, type: string}>}
 */
export function classicSiteRoutes(base, options = {}) {
  const {
    movieSlug = 'spider-man-de-regreso-a-casa',
    moviePath = `/pelicula/${movieSlug}`,
    seriesSlug = 'the-last-of-us',
    seriesPath = `/serie/${seriesSlug}`,
    episodePath = `/serie/${seriesSlug}/temporada/1/capitulo/2`,
    searchPath = '/buscar',
    searchSlug = movieSlug,
    withSeries = true,
  } = options;

  const routes = {
    '/': `<html><body><section class="latest">
      <a href="/pelicula/${movieSlug}"><h3>Spider-Man: De regreso a casa</h3></a>
      <a href="/pelicula/otra-pelicula-2026"><h3>Otra película</h3></a>
    </section></body></html>`,

    [moviePath]: `<html>
<head><title>Spider-Man: De regreso a casa (2017) Online</title></head><body>
<h1>Spider-Man: De regreso a casa</h1>
<div class="player">
  <iframe src="${base}/ext/streamwish"></iframe>
  <iframe data-src="${base}/ext/voe"></iframe>
  <button class="player-option" data-player="${b64(`${base}/ext/filemoon`)}">filemoon</button>
  <a data-player="https://mega.nz/file/abc123">Mega</a>
  <iframe src="${base}/ext/offline"></iframe>
</div>
<div class="synopsis">
  <p>Peter Parker vuelve a casa junto a su tía May después de la batalla de
  Civil War, y trata de retomar su rutina mientras aparece un nuevo villano:
  el Buitre.</p>
</div>
<section class="recomendadas">
  <a href="/pelicula/spider-man-lejos-de-casa">Spider-Man: Lejos de casa</a>
  <a href="/pelicula/spider-man-no-way-home">Spider-Man: No way home</a>
  <a href="/pelicula/venom">Venom</a>
</section>
</body></html>`,

    [searchPath]: `<html><body><ul class="results">
      <li><a href="/pelicula/${searchSlug}"><h3>Spider-Man: De regreso a casa</h3></a></li>
      <li><a href="/pelicula/otra-pelicula-2026"><h3>Otra película</h3></a></li>
    </ul></body></html>`,

    '/ext/streamwish': streamwishPage('http://cdn.example/sw/index.m3u8?token=abc'),
    '/ext/voe': voePlayerPage('http://cdn.example/voe/master.m3u8'),
    '/ext/filemoon': packedPlayerPage(),
  };

  if (withSeries) {
    routes[seriesPath] = `<html>
<head><title>The Last of Us</title></head><body>
<h1>The Last of Us</h1>
<p>Veinte años después de la destrucción de la civilización, Joel y Ellie
atraviesan Estados Unidos en una serie de aventuras y desgracias.</p>
<div class="episodios">
  <a href="${base}/serie/${seriesSlug}/temporada/1/capitulo/1">1x01</a>
  <a href="${base}/serie/${seriesSlug}/temporada/1/capitulo/2">1x02</a>
  <a href="${base}/serie/${seriesSlug}/temporada/1/capitulo/3">1x03</a>
</div>
</body></html>`;
    routes[episodePath] = `<html><head><title>The Last of Us 1x02</title></head><body>
<h1>The Last of Us — Episodio 2</h1>
<p>Joel, Tess y Ellie se adentran en una zona contaminada.</p>
<div class="player">
  <iframe src="${base}/ext/streamwish"></iframe>
  <iframe data-src="${base}/ext/voe"></iframe>
</div>
<div class="recomendadas"><a href="/serie/the-last-of-us/temporada/1/capitulo/1">1x01</a></div>
</body></html>`;
    routes[`/serie/${seriesSlug}/temporada/1/capitulo/1`] = `<html><body><h1>Episodio 1</h1>
<div class="player"><iframe src="${base}/ext/streamwish"></iframe></div></body></html>`;
  }

  return routes;
}

/* -------------------------------------------------------------------------- */
/* Aserciones útiles                                                          */
/* -------------------------------------------------------------------------- */

/** URLs de los streams, en orden. */
export const urlsOf = (body) => (body.streams || []).map((item) => item.url);

/** ¿Alguna URL contiene el fragmento? */
export const hasUrl = (body, fragment) => urlsOf(body).some((url) => url.includes(fragment));

/** Motivo de degradación. */
export const reasonOf = (body) => body?.meta?.reason;
