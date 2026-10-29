"""
scrapers/_peliskit.py
------------------------------------------------------------------------------
Capa común de los proveedores de **películas y series** (Gnula, Cuevana,
PelisPlus, Repelis, PeliculasFlix, PelisJuanita, Comodín Libre).

Los siete sitios son variantes del mismo patrón: una ficha por título, un
buscador y una lista de "servidores" (Streamwish, VOE, Filemoon, Streamtape,
Mixdrop, Mp4upload…) que esconden el `.m3u8`/`.mp4`. Así que aquí vive lo que
no cambia de un sitio a otro:

    parse_ref()        id de Stremio  → {slug, season, episode, imdb}
    page_for()         id            → URL de la ficha (directa o por búsqueda)
    harvest_servers()  HTML de ficha → [{name, url}] de servidores
    resolve_signals()  servidores    → señales reproducibles (vía `_hosters`)
    build_streams()    señales       → streams de Stremio, en orden de failover

Cada proveedor aporta su base, sus rutas y —si el sitio lo necesita— un
`extras()` que añade servidores desde una API propia (Gnula, Cuevana,
PeliculasFlix) o un `prepare()` que transforma el id antes de pedir la ficha.

Contrato (ver `_base.py`): sólo JSON por stdout, diagnóstico por stderr, y nunca
una excepción: si no hay nada, `streams: []` + `meta.reason`.
"""

from __future__ import annotations

import re
import urllib.parse
from typing import Any, Dict, Iterable, List, Optional, Tuple

from _animekit import (
    decode_maybe_base64,
    dedupe_servers,
    iframe_servers,
    objects,
    server_entry,
)
import _tmdb
from _base import log, stream
from _hosters import is_download_only, resolve_all
from _htmlkit import (
    Deadline,
    absolute,
    clean_title,
    host_of,
    http_get,
    is_media_url,
    player_links,
    slugify,
    unescape_markup,
    unique,
)

# ---------------------------------------------------------------------------
# Ids
# ---------------------------------------------------------------------------

#: Prefijos de tipo que puede añadir el cliente delante del id del sitio.
TYPE_TOKENS = {
    "movie", "movies", "film", "films", "pelicula", "peliculas", "película",
    "películas", "series", "serie", "show", "shows", "vod", "tv", "anime",
    "content",
}

#: Ids de proveedor que pueden venir como prefijo del id.
KNOWN_PROVIDERS = {
    "gnula", "cuevana", "pelisplushd", "pelisplus", "repelis", "peliculasflix",
    "pelisjuanita", "comodin", "animeflv", "jkanime", "monoschinos",
    "tvenvivo", "deportelibre", "futbolibre",
}

IMDB_RE = re.compile(r"^tt\d{6,9}$", re.IGNORECASE)
#: Numeración dentro del slug: "1x05".
SEASON_EPISODE_RE = re.compile(r"(?:^|[-_ ])(\d{1,2})x(\d{1,4})(?:$|[-_ ])", re.IGNORECASE)
#: Atributos donde estos sitios publican el embed del servidor.
DATA_ATTR_RE = re.compile(
    r"""data-(?:player|video|url|src|embed|option|link|server|iframe|code|source)"""
    r"""\s*=\s*(?:"([^"]*)"|'([^']*)')""",
    re.IGNORECASE,
)
IFRAME_SRC_RE = re.compile(r"""<iframe[^>]+?src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
OG_VIDEO_RE = re.compile(r"""<meta[^>]+property=["']og:video(?::url)?["'][^>]+content=["']([^"']+)""", re.IGNORECASE)
#: Nombres de literales JS donde los sitios listan sus servidores.
JS_SERVER_NAMES = ("videos", "servers", "options", "sources", "links", "players", "embed")
#: Claves donde los sitios guardan la URL del embed.
URL_KEYS = ("url", "code", "remote", "file", "link", "src", "embed", "iframe", "value")
NAME_KEYS = ("server", "title", "name", "label", "lang", "language", "idioma", "quality")


#: Palabras que van en minúscula al titular un slug.
SMALL_WORDS = {
    "de", "del", "la", "las", "el", "los", "y", "o", "a", "en", "al", "con",
    "por", "para", "un", "una", "the", "of", "and",
}


def title_from_slug(slug: str) -> str:
    """`spider-man-de-regreso-a-casa` → `Spider Man de Regreso a Casa`."""
    words = [word for word in str(slug or "").split("-") if word]
    out: List[str] = []
    for index, word in enumerate(words):
        if word.isdigit():
            out.append(word)
        elif index and word.lower() in SMALL_WORDS:
            out.append(word.lower())
        else:
            out.append(word[:1].upper() + word[1:])
    return " ".join(out)


def parse_ref(content_id: str, provider: str) -> Dict[str, Any]:
    """Convierte el id de Stremio en ``{slug, season, episode, imdb, url}``.

    Acepta las formas que puede mandar un cliente::

        gnula:spider-man-un-nuevo-dia-2026        (slug del sitio)
        gnula:spider-man-un-nuevo-dia-2026:1:2    (serie, temporada:episodio)
        movie:gnula:spider-man-un-nuevo-dia-2026  (prefijo de tipo del addon)
        gnula:tt0145487                           (IMDb: se resuelve con TMDB)
        gnula:spider man                          (texto libre: se busca)
        https://ww3.gnulahd.nu/ver/street-fighter/
    """
    raw = str(content_id or "").strip()
    raw = re.sub(r"\.json$", "", raw, flags=re.IGNORECASE).strip().rstrip("/")
    ref: Dict[str, Any] = {
        "slug": "", "season": None, "episode": None, "imdb": None,
        "url": "", "title_hint": "", "title": "", "year": "",
        "slug_candidates": [], "explicit_episode": False, "explicit_url": False,
    }
    if not raw:
        return ref

    if raw.lower().startswith(("http://", "https://")):
        path = urllib.parse.urlparse(raw).path.rstrip("/")
        slug = slugify(path.split("/")[-1]) if path else ""
        ref.update({"url": raw, "explicit_url": True, "slug": slug,
                    "title_hint": title_from_slug(slug)})
        return ref

    parts = [part for part in raw.split(":") if part.strip()]
    while parts and parts[0].strip().lower() in TYPE_TOKENS:
        parts.pop(0)
    if parts and parts[0].strip().lower() in ({str(provider or "").strip().lower()} | KNOWN_PROVIDERS):
        parts.pop(0)
    if not parts:
        return ref

    season = episode = None
    if len(parts) >= 3 and parts[-1].strip().isdigit() and parts[-2].strip().isdigit():
        season, episode = int(parts[-2]), int(parts[-1])
        parts = parts[:-2]
    elif len(parts) >= 2 and parts[-1].strip().isdigit():
        episode = int(parts[-1])
        parts = parts[:-1]

    tail = ":".join(parts).strip()
    if IMDB_RE.match(tail):
        ref["imdb"] = tail.lower()
        return ref

    slug = slugify(tail)
    if not slug:
        return ref
    match = SEASON_EPISODE_RE.search(slug)
    if match:
        season = season if season is not None else int(match.group(1))
        episode = episode if episode is not None else int(match.group(2))
        slug = SEASON_EPISODE_RE.sub("-", slug).strip("-")
    ref["slug"] = slug
    ref["title_hint"] = title_from_slug(slug)
    ref["season"], ref["episode"] = season, episode
    ref["explicit_episode"] = season is not None and episode is not None
    return ref


def apply_payload(ref: Dict[str, Any], payload: Dict[str, Any]) -> Dict[str, Any]:
    """Completa el id con temporada/episodio, IMDb y título/año del cliente.

    El modo autónomo de la APK manda `title`/`year` desde Cinemeta. Así los
    sitios pueden buscar por nombre aunque el id original sea sólo IMDb y no
    haya clave de TMDB configurada.
    """
    payload = payload or {}
    if not ref.get("explicit_episode"):
        season, episode = payload.get("season"), payload.get("episode")
        if isinstance(season, int) and isinstance(episode, int):
            ref["season"], ref["episode"] = season, episode
            ref["explicit_episode"] = True
    if not ref.get("imdb"):
        imdb = str(payload.get("imdbId") or "").strip()
        if IMDB_RE.match(imdb):
            ref["imdb"] = imdb.lower()
    title = str(payload.get("title") or "").strip()
    year = str(payload.get("year") or "").strip()
    if title:
        if not ref.get("title"):
            ref["title"] = title
        if not ref.get("title_hint"):
            ref["title_hint"] = title
        if year and not ref.get("year"):
            ref["year"] = year
        if not ref.get("slug"):
            candidates = slug_variants(title, year or ref.get("year"))
            if candidates:
                ref["slug_candidates"] = candidates
                ref["slug"] = candidates[0]
    return ref


def is_series(ref: Dict[str, Any]) -> bool:
    return ref.get("season") is not None and ref.get("episode") is not None


def slug_variants(title: str, year: Optional[str] = None) -> List[str]:
    """Slugs candidatos a partir de un título ("Spider-Man: Un nuevo día", 2026)."""
    base = slugify(title)
    if not base:
        return []
    year = str(year or "")[:4]
    candidates = [f"{base}-{year}" if year.isdigit() else "", base]
    stripped = re.sub(r"-?(19|20)\d{2}$", "", base)
    if stripped and stripped != base:
        candidates += [f"{stripped}-{year}" if year.isdigit() else "", stripped]
    for suffix in ("-pelicula", "-online", "-hd", "-latino", "-castellano"):
        if base.endswith(suffix):
            candidates.append(base[: -len(suffix)])
    return [candidate for candidate in dict.fromkeys(candidates) if candidate]


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


def fetch_html(
    url: str,
    headers: Dict[str, str],
    deadline: Deadline,
    accept: str = "text/html,application/xhtml+xml",
) -> Tuple[str, str]:
    """GET tolerante a fallos: ``(html, url_final)`` o ``("", "")``."""
    if not url or deadline.expired():
        return "", ""
    try:
        body, final_url, content_type = http_get(
            url, {**headers, "Accept": accept}, deadline.remaining(8.0)
        )
    except Exception as exc:  # noqa: BLE001 - un sitio caído no debe romper nada
        log("fetch failed:", url, exc)
        return "", ""
    if "json" in (content_type or ""):
        return body, final_url
    return body or "", final_url or url


def fill(template: str, **values: Any) -> str:
    """`\"/pelicula/{slug}\"` + slug → URL absoluta codificada."""
    try:
        return template.format(
            slug=values.get("slug", ""),
            season=values.get("season", ""),
            episode=values.get("episode", ""),
            query=urllib.parse.quote_plus(str(values.get("query", ""))),
        )
    except (KeyError, IndexError):
        return template


def env_bases(default: str, *names: str) -> List[str]:
    """Dominios del sitio, con espejos opcionales por entorno.

    `REPELIS_BASE` fija el dominio y `REPELIS_MIRRORS` añade alternativas
    separadas por comas. Estos sitios cambian de dominio a menudo, así que el
    proveedor prueba el principal y luego los espejos.
    """
    import os

    bases: List[str] = []
    for name in names:
        value = os.environ.get(name, "").strip()
        if not value:
            continue
        for piece in value.split(","):
            piece = piece.strip().rstrip("/")
            if piece and piece not in bases:
                bases.append(piece)
    if not bases and default:
        bases.append(default.rstrip("/"))
    return bases


def join(base: str, path: str) -> str:
    if not path:
        return base
    if path.lower().startswith(("http://", "https://")):
        return path
    return urllib.parse.urljoin(base.rstrip("/") + "/", path.lstrip("/"))


# ---------------------------------------------------------------------------
# Búsqueda y ficha
# ---------------------------------------------------------------------------


def json_hits(body: str, page_url: str, hints: Iterable[str]) -> List[Dict[str, str]]:
    """Resultados de un buscador que responde JSON: ``[{slug, title, url}]``.

    Se aceptan objetos con `url`/`link`/`href` (Gnula: `/wp-json/gnrd/v1/search`)
    o con `slug` + `title`, en cualquier profundidad del documento.
    """
    import json

    try:
        payload = json.loads(body)
    except (ValueError, TypeError):
        return []
    hints = tuple(hints)
    queue: List[Any] = [payload]
    found: List[Dict[str, str]] = []
    seen = set()
    while queue:
        node = queue.pop(0)
        if isinstance(node, list):
            queue.extend(node[:60])
            continue
        if not isinstance(node, dict):
            continue
        url = ""
        for key in ("url", "link", "href", "permalink"):
            value = node.get(key)
            if isinstance(value, str) and value.strip():
                url = value.strip()
                break
        title = ""
        for key in ("title", "name", "slug", "post_title"):
            value = node.get(key)
            if isinstance(value, str) and value.strip():
                title = value.strip()
                break
        if url:
            absolute_url = absolute(url, page_url)
            if hints and not any(hint in absolute_url for hint in hints):
                pass
            elif absolute_url not in seen:
                seen.add(absolute_url)
                slug = slugify(urllib.parse.urlparse(absolute_url).path.rstrip("/").split("/")[-1])
                found.append({"slug": slug, "title": title or slug.replace("-", " "), "url": absolute_url})
        for value in node.values():
            if isinstance(value, (dict, list)):
                queue.append(value)
    return found


def search_site(
    site: Dict[str, Any],
    query: str,
    headers: Dict[str, str],
    deadline: Deadline,
    hints: Iterable[str],
) -> List[Dict[str, str]]:
    """Resultados del buscador del sitio: ``[{slug, title, url}]``."""
    from _animekit import best_hit, search_hits

    if not (query or "").strip():
        return []
    hints = tuple(hints)
    hits: List[Dict[str, str]] = []
    for template in site.get("search_paths") or []:
        url = join(site["base"], fill(template, query=query))
        html, final_url = fetch_html(url, headers, deadline)
        if not html:
            continue
        if (html or "").lstrip().startswith(("{", "[")):
            candidates = json_hits(html, final_url or url, hints)
        else:
            candidates = [hit for hint in hints for hit in search_hits(html, final_url or url, hint)]
        for hit in candidates:
            if hit["url"] not in {item["url"] for item in hits}:
                hits.append(hit)
        if best_hit(query, hits):
            break
    return hits


def tmdb_prepare(
    ref: Dict[str, Any],
    payload: Dict[str, Any],
    media_type: str = "movie",
    deadline: Optional[Deadline] = None,
) -> Dict[str, Any]:
    """Completa el id con TMDB: IMDb → título/año, y título → slug candidatos.

    Sin clave (`TMDB_API_KEY`) no hace nada: el proveedor sigue con el slug del
    id o con la búsqueda del propio sitio.
    """
    key = _tmdb.api_key(payload)
    if not key:
        return ref
    found: Dict[str, Any] = {}
    if ref.get("imdb"):
        found = _tmdb.find_by_imdb(ref["imdb"], media_type, key, deadline)
    if not found and (ref.get("title_hint") or ref.get("slug")):
        guess = ref.get("title_hint") or " ".join(str(ref.get("slug", "")).split("-"))
        found = _tmdb.search(guess, None, media_type, key, deadline)
    if not found:
        return ref
    ref["title"] = found.get("title") or ref.get("title_hint") or ""
    ref["year"] = found.get("year") or ref.get("year") or ""
    ref["tmdb_id"] = found.get("tmdb_id")
    candidates: List[str] = [ref["slug"]] if ref.get("slug") else []
    for name in (found.get("title"), found.get("original_title")):
        for variant in slug_variants(name or "", found.get("year")):
            if variant not in candidates:
                candidates.append(variant)
    ref["slug_candidates"] = candidates
    if candidates and not ref.get("slug"):
        ref["slug"] = candidates[0]
    return ref


def page_for(
    ref: Dict[str, Any],
    site: Dict[str, Any],
    headers: Dict[str, str],
    deadline: Deadline,
) -> Tuple[str, str]:
    """URL de la ficha del título: ``(url, cómo_se_resolvió)``.

    Orden: URL explícita en el id → plantilla directa con el slug → buscador del
    sitio con el texto del slug. Nunca lanza: si nada existe devuelve ``("", …)``.
    """
    if ref.get("explicit_url") and ref.get("url"):
        return ref["url"], "id_url"

    slug = ref.get("slug") or ""
    season, episode = ref.get("season"), ref.get("episode")
    templates = site.get("series_paths") if is_series(ref) else site.get("movie_paths")
    templates = list(templates or site.get("movie_paths") or [])

    slugs = [slug] + [item for item in (ref.get("slug_candidates") or []) if item != slug]
    # Las plantillas de capítulo van primero, pero la ficha de la serie (la
    # última) siempre se prueba: hay sitios que sólo tienen ficha.
    ordered = list(dict.fromkeys(templates[:3] + templates[-1:]))
    attempts = 0
    for candidate_slug in [value for value in slugs if value][:3]:
        for template in ordered:
            attempts += 1
            if attempts > 5:
                break
            candidate = join(
                site["base"],
                fill(template, slug=candidate_slug, season=season, episode=episode),
            )
            html, final_url = fetch_html(candidate, headers, deadline)
            if html and looks_like_page(html, site):
                ref["slug"] = candidate_slug
                return final_url or candidate, "direct"

    query = ref.get("title_hint") or " ".join(slug.split("-"))
    hits = search_site(site, query, headers, deadline, site.get("search_hints") or ())
    from _animekit import best_hit

    hit = best_hit(query, hits)
    if hit:
        return hit["url"], "search"
    return "", "not_found"


def looks_like_page(html: str, site: Dict[str, Any]) -> bool:
    """¿La respuesta es una ficha y no un 404 blando?"""
    if not html or len(html) < 400:
        return False
    text = html.lower()
    if any(marker.lower() in text for marker in site.get("blocked_markers") or ()):
        return False
    for marker in site.get("not_found_markers") or ("404", "no encontrada", "not found"):
        if marker.lower() in text[:4000]:
            return False
    return True


# ---------------------------------------------------------------------------
# Servidores
# ---------------------------------------------------------------------------


def _clean_embed(value: str, page_url: str) -> str:
    """Valor de un `data-*` → URL del embed (acepta base64 y `<iframe>`)."""
    raw = unescape_markup(str(value or "")).strip()
    if not raw:
        return ""
    if "<iframe" in raw.lower():
        match = IFRAME_SRC_RE.search(raw)
        if not match:
            return ""
        raw = match.group(1)
    raw = decode_maybe_base64(raw)
    if raw.startswith("//"):
        raw = "https:" + raw
    if not raw.lower().startswith(("http://", "https://")):
        return ""
    return raw


def harvest_servers(
    html: str,
    page_url: str,
    site: Optional[Dict[str, Any]] = None,
    skip_hosts: Iterable[str] = (),
) -> List[Dict[str, Any]]:
    """Todos los servidores que se puedan leer de una ficha.

    Cubre lo que usan estos sitios: `<iframe src>`, `<iframe data-src>`,
    atributos `data-player`/`data-video`/`data-option`/… (URL, base64 o
    `<iframe>`), literales JS tipo `var servers = [{server, url}]`, `<meta
    property="og:video">` y URLs de reproductor por las que seguir.
    """
    site = site or {}
    if not html:
        return []
    text = unescape_markup(html)
    servers: List[Dict[str, Any]] = []

    # 1. iframes (incluye data-src / data-lazy-src de las cargas diferidas).
    for url in iframe_servers(text, page_url):
        servers.append(url)
    for match in re.finditer(
        r"""<iframe[^>]+?data-(?:src|lazy-src|url)\s*=\s*["']([^"']+)["']""", text, re.IGNORECASE
    ):
        url = _clean_embed(match.group(1), page_url)
        if url:
            servers.append(server_entry("", absolute(url, page_url)))

    # 2. Atributos data-* con el embed.
    for match in DATA_ATTR_RE.finditer(text):
        value = match.group(1) if match.group(1) is not None else match.group(2)
        url = _clean_embed(value, page_url)
        if not url:
            continue
        servers.append(server_entry("", absolute(url, page_url)))

    # 3. Literales JS con la lista de servidores.
    for name in JS_SERVER_NAMES:
        for entry in objects(text, name):
            url = ""
            label = ""
            for key in URL_KEYS:
                candidate = entry.get(key)
                if isinstance(candidate, str) and candidate.strip():
                    url = _clean_embed(candidate, page_url)
                    if url:
                        break
            if not url:
                continue
            for key in NAME_KEYS:
                value = entry.get(key)
                if isinstance(value, str) and value.strip():
                    label = value.strip()
                    break
            servers.append(server_entry(label, absolute(url, page_url)))

    # 4. `<meta property="og:video">` (mp4 directo o player).
    for match in OG_VIDEO_RE.finditer(text):
        url = absolute(unescape_markup(match.group(1)), page_url)
        if url:
            servers.append(server_entry("", url))

    # 5. URLs de reproductor plausibles (última red de seguridad).
    for url in player_links(text, page_url, site.get("player_hints") or ()):
        servers.append(server_entry("", url))

    skip = tuple(skip_hosts) or tuple(site.get("skip_hosts") or ())
    cleaned: List[Dict[str, Any]] = []
    for server in dedupe_servers(servers):
        url = server["url"]
        if skip and any(hint in url for hint in skip):
            continue
        if is_media_url(url):
            # Un .m3u8/.mp4 directo no hay que resolverlo: entra tal cual.
            cleaned.append({**server, "direct": True})
            continue
        if not url.lower().startswith(("http://", "https://")):
            continue
        cleaned.append(server)
    return cleaned


def resolve_signals(
    servers: Iterable[Dict[str, Any]],
    headers: Dict[str, str],
    deadline: Deadline,
    page_url: str,
    parallel: int = 4,
    max_servers: int = 6,
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Resuelve los servidores en paralelo, conservando el orden del sitio."""
    usable: List[Dict[str, Any]] = []
    skipped: List[str] = []
    for server in servers:
        url = server.get("url") or ""
        if not url:
            continue
        if is_download_only(url):
            skipped.append(server.get("name") or host_of(url))
            continue
        if server.get("direct") or is_media_url(url):
            usable.append(server)
            continue
        usable.append(server)
    usable = usable[:max_servers]

    signals: List[Dict[str, Any]] = []
    meta: Dict[str, Any] = {}
    remote = [server for server in usable if not is_media_url(server["url"])]
    inline = [server for server in usable if is_media_url(server["url"])]
    for server in inline:
        signals.append({"url": server["url"], "server": server.get("name") or host_of(server["url"]),
                        "referer": page_url})
    if remote:
        found, info = resolve_all(remote, headers, deadline, page_url, parallel=parallel)
        signals.extend(found)
        meta.update(info or {})
    if skipped:
        meta["download_only"] = unique(skipped)
    return signals, meta


EPISODE_LINK_RE = re.compile(r"""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""", re.IGNORECASE | re.DOTALL)
VISIBLE_RE = re.compile(r"<[^>]+>")


def candidate_episode_links(html: str, season: int, episode: int) -> List[str]:
    """Enlaces del capítulo pedido dentro de la ficha de una serie.

    Reconoce las formas habituales: `1x05`, `/1/5`, `temporada-1/capitulo-5`,
    `episodio-5`, `capitulo/5` y el texto "T1 E5".
    """
    padded = f"{episode:02d}"
    variants = [
        f"{season}x{episode}",
        f"{season}x{padded}",
        f"temporada/{season}/capitulo/{episode}",
        f"temporada-{season}/capitulo-{episode}",
        f"/{season}/{episode}",
        f"/{season}/{padded}",
        f"capitulo-{episode}",
        f"capitulo-{padded}",
        f"capitulo/{episode}",
        f"episodio-{episode}",
        f"episodio-{padded}",
        f"episodio/{episode}",
        f"episode-{episode}",
        f"temporada-{season}-capitulo-{episode}",
    ]
    text_variants = [
        f"t{season} e{episode}", f"t{season}e{episode}",
        f"{season}x{episode}", f"{season}x{padded}",
    ]
    links: List[str] = []
    for match in EPISODE_LINK_RE.finditer(html or ""):
        href = match.group(1).strip()
        label = " ".join(VISIBLE_RE.sub(" ", match.group(2)).split()).lower()
        haystack = f"{href.lower()} {label}"
        if any(variant in haystack.replace(" ", "") or variant in haystack for variant in variants) \
                or any(variant in haystack for variant in text_variants):
            if href not in links:
                links.append(href)
    return links


def episode_link_servers(
    html: str,
    page_url: str,
    season: int,
    episode: int,
    headers: Dict[str, str],
    deadline: Deadline,
    site: Optional[Dict[str, Any]] = None,
) -> Tuple[List[Dict[str, Any]], str]:
    """Ficha de serie → página del capítulo → servidores.

    Muchos sitios listan los capítulos en la ficha pero sólo ponen reproductores
    en la página de cada capítulo. Devuelve `(servidores, url_del_capitulo)`.
    """
    for href in candidate_episode_links(html, season, episode)[:3]:
        url = absolute(href, page_url)
        if not url.lower().startswith(("http://", "https://")):
            continue
        sub_html, final_url = fetch_html(url, headers, deadline)
        if not sub_html:
            continue
        servers = harvest_servers(sub_html, final_url or url, site)
        if servers:
            return servers, final_url or url
    return [], ""


# ---------------------------------------------------------------------------
# Streams
# ---------------------------------------------------------------------------


def display_name(
    title: str,
    year: Optional[str] = None,
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> str:
    """"Spider-Man: Un nuevo día (2026)" / "Reacher (2022) · T1 E2"."""
    name = clean_title(title) or title or "Sin título"
    year = str(year or "")[:4]
    label = f"{name} ({year})" if year.isdigit() else name
    if season is not None and episode is not None:
        label += f" · T{season} E{episode}"
    return label


def build_streams(
    provider_label: str,
    display: str,
    signals: List[Dict[str, Any]],
    user_agent: str,
) -> List[Dict[str, Any]]:
    """Un stream por servidor resuelto, en el orden del sitio (failover).

    ``name``  "Spider-Man: Un nuevo día (2026)"
    ``title`` "Gnula · Spider-Man: Un nuevo día (2026) · Streamwish"
    """
    streams: List[Dict[str, Any]] = []
    for signal in signals:
        server = signal.get("server") or signal.get("label") or "Servidor"
        streams.append(
            stream(
                url=signal["url"],
                name=display,
                title=f"{provider_label} · {display} · {server}",
                referer=signal.get("referer") or "",
                user_agent=user_agent,
                not_web_ready=False,
                is_live=False,
                quality=None,
            )
        )
    return streams


# ---------------------------------------------------------------------------
# Driver genérico
# ---------------------------------------------------------------------------


def scrape_site(payload: Dict[str, Any], site: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Flujo completo de un sitio de películas/series.

    ``site`` lo define cada proveedor::

        {
          "id": "gnula", "label": "Gnula", "base": "https://ww3.gnulahd.nu/",
          "movie_paths": ["/ver/{slug}/"],
          "series_paths": ["/ver/{slug}/"],
          "search_paths": ["/?s={query}"],
          "search_hints": ["/ver/"],
          "prepare": callable(ref, payload, headers, deadline) -> ref,   # opcional
          "extras": callable(html, page_url, headers, deadline) -> servers,  # opcional
          "max_servers": 6, "parallel": 4,
        }
    """
    if payload.get("isLive"):
        return [], {"reason": "not_vod", "site": site["id"]}

    prepared = site.get("ref")
    ref = dict(prepared) if isinstance(prepared, dict) else apply_payload(
        parse_ref(payload.get("id") or "", site["id"]), payload
    )
    meta: Dict[str, Any] = {"site": site["id"], "kind": "series" if is_series(ref) else "movie"}
    if ref.get("imdb"):
        meta["imdb"] = ref["imdb"]
    if ref.get("season") is not None:
        meta["season"], meta["episode"] = ref.get("season"), ref.get("episode")

    user_agent = str(payload.get("userAgent") or "")
    headers = {
        "User-Agent": user_agent or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Referer": site.get("base") or "",
    }
    deadline = Deadline(payload.get("timeoutMs") or 15000)

    if site.get("tmdb"):
        ref = tmdb_prepare(ref, payload, "tv" if is_series(ref) else "movie", deadline)
    prepare = site.get("prepare")
    if callable(prepare):
        ref = prepare(ref, payload, headers, deadline) or ref
    if not ref.get("slug") and not ref.get("url"):
        meta["reason"] = "no_id"
        return [], meta

    bases = [base for base in (site.get("bases") or [site["base"]]) if base]
    page_url, how = "", ""
    for base in bases:
        if base != site.get("base"):
            current = {**site, "base": base}
            headers["Referer"] = base
        else:
            current = site
        page_url, how = page_for(ref, current, headers, deadline)
        if page_url:
            site = current
            meta["base"] = base
            break
    if not page_url:
        meta["reason"] = "not_found"
        meta["query"] = ref.get("title_hint") or ref.get("slug")
        return [], meta
    meta["page"] = page_url
    meta["resolved_by"] = how

    html, final_url = fetch_html(page_url, headers, deadline)
    if not html:
        meta["reason"] = "page_unavailable"
        return [], meta
    page_url = final_url or page_url
    meta["page"] = page_url

    servers = harvest_servers(html, page_url, site)
    extras = site.get("extras")
    if callable(extras):
        try:
            for server in extras(html, page_url, headers, deadline) or []:
                if server and server.get("url"):
                    servers.append(server)
        except Exception as exc:  # noqa: BLE001 - el respaldo genérico sigue vivo
            log("extras failed:", site["id"], exc)
    servers = dedupe_servers(servers)
    if not servers and is_series(ref):
        # La ficha de la serie lista capítulos pero los reproductores están en
        # la página del capítulo: se sigue el enlace del episodio pedido.
        episode_url = ""
        try:
            servers, episode_url = episode_link_servers(
                html, page_url, int(ref["season"]), int(ref["episode"]), headers, deadline, site
            )
        except Exception as exc:  # noqa: BLE001
            log("episode link failed:", site["id"], exc)
        if episode_url:
            meta["episode_page"] = episode_url
    # `servers` es un contador en el meta de `_hosters`; aquí van los nombres.
    meta["server_names"] = [server.get("name") for server in servers if server.get("name")]
    if not servers:
        meta["reason"] = "no_servers"
        return [], meta

    signals, resolve_meta = resolve_signals(
        servers,
        headers,
        deadline,
        page_url,
        parallel=int(site.get("parallel") or 4),
        max_servers=int(site.get("max_servers") or 6),
    )
    meta.update(resolve_meta)
    if not signals:
        meta["reason"] = "no_streams"
        return [], meta

    title = ref.get("title") or ref.get("title_hint") or ref.get("slug") or ""
    year = ref.get("year")
    if not title:
        meta["reason"] = "no_title"
        return [], meta
    display = display_name(title, year, ref.get("season"), ref.get("episode"))
    meta["title"] = display
    return build_streams(site["label"], display, signals, user_agent), meta


def list_latest(
    site: Dict[str, Any],
    limit: int = 20,
    query: str = "",
) -> List[Dict[str, str]]:
    """`--list`: últimas fichas de la portada o resultados de una búsqueda."""
    from _animekit import search_hits

    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                      "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
        "Accept-Language": "es-MX,es;q=0.9",
        "Referer": site.get("base") or "",
    }
    deadline = Deadline(10000)
    hits: List[Dict[str, str]] = []
    if query.strip():
        hits = search_site(site, query.strip(), headers, deadline, site.get("search_hints") or ())
    else:
        html, final_url = fetch_html(join(site["base"], site.get("home_path") or "/"), headers, deadline)
        for hint in site.get("search_hints") or ():
            hits.extend(search_hits(html, final_url or site["base"], hint))
    items: List[Dict[str, str]] = []
    seen = set()
    for hit in hits:
        slug = hit.get("slug") or ""
        if not slug or slug in seen:
            continue
        seen.add(slug)
        items.append({
            "id": f"{site['id']}:{slug}",
            "slug": slug,
            "title": hit.get("title") or slug.replace("-", " ").title(),
            "url": hit.get("url") or "",
        })
        if len(items) >= limit:
            break
    return items


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def cli(site: Dict[str, Any], argv: Optional[List[str]] = None) -> bool:
    """`--list [--query X] [--json]`; devuelve True si atendió la CLI."""
    import json
    import sys

    args = list(sys.argv[1:] if argv is None else argv)
    if not args:
        return False
    if args[0] in ("-h", "--help"):
        print(f"Uso: {site['id']}.py [--list [--query TEXTO] [--json]]")
        return True
    if args[0] != "--list":
        return False
    query = ""
    if "--query" in args:
        index = args.index("--query")
        query = args[index + 1] if len(args) > index + 1 else ""
    elif len(args) > 1 and not args[1].startswith("-"):
        query = args[1]
    items = list_latest(site, query=query)
    if "--json" in args:
        print(json.dumps({"site": site["id"], "items": items}, ensure_ascii=False, indent=2))
        return True
    if not items:
        print("Sin resultados.")
        return True
    print(f"{site['label']} — {len(items)} título(s)\n")
    for item in items:
        print(f"  {item['id']:<48} {item['title']}")
    print("\nPídelos en /stream/movie/<id>.json (o /stream/series/<id>:T:E.json)")
    return True


__all__ = [
    "apply_payload",
    "build_streams",
    "cli",
    "candidate_episode_links",
    "display_name",
    "env_bases",
    "episode_link_servers",
    "fetch_html",
    "fill",
    "harvest_servers",
    "is_series",
    "join",
    "json_hits",
    "list_latest",
    "looks_like_page",
    "page_for",
    "parse_ref",
    "resolve_signals",
    "tmdb_prepare",
    "scrape_site",
    "search_site",
    "slug_variants",
    "title_from_slug",
]
