#!/usr/bin/env python3
"""
scrapers/peliculasflix.py
------------------------------------------------------------------------------
Proveedor real para **PeliculasFlix** (https://peliculasflix.co/).

Este sitio no publica el reproductor en el HTML: todo el catálogo se sirve por
una API GraphQL propia.

Cómo publica el sitio la señal
------------------------------
    1. Búsqueda (POST a la API del sitio):

           POST https://fluxcedene.net/api/gql
           x-access-platform: <clave pública del sitio>

           query searchAll($input: String!) {
             searchMovie(input: $input, limit: 10) { _id slug title name name_es }
           }

    2. Ficha + enlaces, con el `_id` y el `slug` del resultado:

           query getMovieLinks($id: MongoID!, $slug: String!) {
             getMovieLinks(id: $id, slug: $slug) { name links_online }
           }

       `links_online` es la lista de servidores. Cada entrada trae el código del
       servidor, el idioma (`37` = español, `192` = subtitulado, resto latino) y
       `link`, que a veces es directamente un `<iframe src="…">` en texto.

    3. Cada `link` es un servidor conocido: de resolverlo se encarga
       `_hosters.py` (Streamwish, VOE, Filemoon, Streamtape, Mixdrop…).

Si la API cambia (le pasa cada pocos meses) el proveedor no se rompe: devuelve
`streams: []` con `meta.reason`, y la base y la clave de plataforma se pueden
mover a `PELICULASFLIX_API` / `PELICULASFLIX_ACCESS` sin tocar código.

IDs aceptados en `/stream/movie/<id>.json`
------------------------------------------
    peliculasflix:spider-man-de-regreso-a-casa
    peliculasflix:spider-man-de-regreso-a-casa:1:2   (temporada:episodio)
    movie:peliculasflix:spider-man-de-regreso-a-casa
    peliculasflix:tt0145487                          (IMDb → TMDB, con clave)
    peliculasflix:spider man                         (texto libre → búsqueda)

Uso desde la CLI
----------------
    python3 scrapers/peliculasflix.py --list --query "spider man" --json
    echo '{"type":"movie","id":"peliculasflix:spider-man-de-regreso-a-casa"}' \\
        | python3 scrapers/peliculasflix.py

Variables de entorno
--------------------
    PELICULASFLIX_BASE    https://peliculasflix.co   (sólo para el Referer)
    PELICULASFLIX_API     https://fluxcedene.net/api/gql
    PELICULASFLIX_ACCESS  clave pública x-access-platform
    TMDB_API_KEY          opcional, para ids IMDb

Dependencias: sólo la librería estándar (vía `_htmlkit`, `_peliskit`, `_hosters`).
"""

from __future__ import annotations

import os
import re
from typing import Any, Dict, List, Optional

from _animekit import best_hit, normalize_query, server_entry
from _base import log, run
from _htmlkit import Deadline, http_post_json, unescape_markup
from _peliskit import (
    apply_payload,
    build_streams,
    display_name,
    env_bases,
    parse_ref,
    resolve_signals,
    slug_variants,
    tmdb_prepare,
)

BASE = "https://peliculasflix.co"
API_URL = os.environ.get("PELICULASFLIX_API", "https://fluxcedene.net/api/gql")
#: Clave pública que el propio sitio manda en `x-access-platform`.
ACCESS_PLATFORM = os.environ.get(
    "PELICULASFLIX_ACCESS", "lDakkGUZx7_nX25Nv1CJVbz_ZAjMKMTcwNTQyMzU4Nw=="
)

SEARCH_QUERY = """query searchAll($input: String!) {
  %s(input: $input, limit: 10) {
    _id slug title name name_es overview __typename
  }
}"""

DETAIL_QUERY = """query getMovieLinks($id: MongoID!, $slug: String!) {
  getMovieLinks(id: $id, slug: $slug) {
    _id name title name_es slug overview links_online __typename
  }
}"""

IFRAME_SRC_RE = re.compile(r"""<iframe[^>]+src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
#: Idiomas tal como los codifica el sitio.
LANG_LABELS = {"37": "Español", "192": "Subtitulado"}
#: Hosts que no son reproducibles o están muertos en este sitio.
SKIP_HOSTS = ("1fichier", "mega.nz", "fireload", "fembed", "vanfem", "lvturbo", "sbcom")


def _bases() -> List[str]:
    return env_bases(BASE, "PELICULASFLIX_BASE", "PELICULASFLIX_MIRRORS")


def _headers(payload: Dict[str, Any]) -> Dict[str, str]:
    return {
        "User-Agent": str(payload.get("userAgent") or "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                                                        "AppleWebKit/537.36 (KHTML, like Gecko) "
                                                        "Chrome/126.0.0.0 Safari/537.36"),
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "es-MX,es;q=0.9,en;q=0.8",
        "Origin": BASE,
        "Referer": BASE + "/",
        "x-access-platform": ACCESS_PLATFORM,
    }


def gql(
    query: str,
    operation: str,
    variables: Dict[str, Any],
    headers: Dict[str, str],
    deadline: Deadline,
) -> Optional[Dict[str, Any]]:
    """Una llamada a la API GraphQL del sitio."""
    body = {"operationName": operation, "variables": variables, "query": query}
    try:
        data = http_post_json(API_URL, body, headers, deadline.remaining(8.0))
    except Exception as exc:  # noqa: BLE001 - API caída = resultado vacío
        log("peliculasflix: gql failed", operation, exc)
        return None
    if not isinstance(data, dict):
        return None
    errors = data.get("errors")
    if errors:
        log("peliculasflix: gql errors", operation, str(errors)[:200])
    payload = data.get("data")
    return payload if isinstance(payload, dict) else None


def search(query: str, headers: Dict[str, str], deadline: Deadline) -> List[Dict[str, Any]]:
    """Búsqueda por título; `searchMovie` responde exacto y `searchFilm` es el respaldo."""
    for field in ("searchMovie", "searchFilm"):
        data = gql(SEARCH_QUERY % field, "searchAll", {"input": query}, headers, deadline)
        hits = (data or {}).get(field) or []
        if hits:
            return [hit for hit in hits if isinstance(hit, dict)]
    return []


def pick_hit(query: str, hits: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Mejor coincidencia entre los resultados del sitio."""
    candidates = []
    for hit in hits:
        slug = str(hit.get("slug") or "").strip()
        if not slug:
            continue
        title = str(hit.get("name_es") or hit.get("title") or hit.get("name") or slug)
        candidates.append({"slug": slug, "title": title, "url": slug, "raw": hit})
    found = best_hit(query, candidates)
    if found:
        return found.get("raw") or found
    return hits[0] if hits else None


def detail(movie_id: str, slug: str, headers: Dict[str, str], deadline: Deadline) -> Dict[str, Any]:
    """Ficha con `links_online`."""
    data = gql(DETAIL_QUERY, "getMovieLinks", {"id": movie_id or "", "slug": slug}, headers, deadline)
    node = (data or {}).get("getMovieLinks")
    return node if isinstance(node, dict) else {}


def links_to_servers(node: Dict[str, Any]) -> List[Dict[str, Any]]:
    """`links_online` → `[{name, url}]`, descartando hosts no reproducibles."""
    servers: List[Dict[str, Any]] = []
    for link in node.get("links_online") or []:
        if isinstance(link, str):
            value, lang, code = link, "", ""
        elif isinstance(link, dict):
            value = str(link.get("link") or link.get("url") or "")
            lang = str(link.get("lang") or "")
            code = str(link.get("server") or "")
        else:
            continue
        value = unescape_markup(value).strip().replace("\\/", "/")
        if "<iframe" in value.lower():
            match = IFRAME_SRC_RE.search(value)
            value = match.group(1) if match else ""
        value = value.strip()
        if not value.lower().startswith(("http://", "https://")):
            continue
        if any(host in value.lower() for host in SKIP_HOSTS):
            continue
        label = LANG_LABELS.get(lang, "Latino")
        servers.append(server_entry(label, value, {"server_code": code} if code else None))
    return servers


def scrape(payload: Dict[str, Any]) -> Any:
    """Búsqueda GraphQL → `links_online` → servidores → señales."""
    if payload.get("isLive"):
        return [], {"reason": "not_vod", "site": "peliculasflix"}

    ref = apply_payload(parse_ref(payload.get("id") or "", "peliculasflix"), payload)
    is_series = ref.get("season") is not None and ref.get("episode") is not None
    ref = tmdb_prepare(ref, payload, "tv" if is_series else "movie")

    headers = _headers(payload)
    deadline = Deadline(payload.get("timeoutMs") or 15000)
    meta: Dict[str, Any] = {"site": "peliculasflix", "kind": "series" if is_series else "movie"}
    if is_series:
        meta["season"], meta["episode"] = ref.get("season"), ref.get("episode")

    query = normalize_query(ref.get("title") or ref.get("title_hint") or ref.get("slug") or "")
    hit: Optional[Dict[str, Any]] = None
    from_search = False
    if ref.get("slug") and not query:
        query = " ".join(str(ref["slug"]).split("-"))
    if query:
        hit = pick_hit(query, search(query, headers, deadline))
        from_search = hit is not None
    if not hit and ref.get("slug"):
        # Sin resultados por nombre: se intenta el slug tal cual.
        hit = {"_id": "", "slug": ref["slug"]}
    if not hit:
        meta["reason"] = "not_found"
        meta["query"] = query
        return [], meta

    slug = str(hit.get("slug") or ref.get("slug") or "")
    movie_id = str(hit.get("_id") or "")
    meta["slug"] = slug
    node = detail(movie_id, slug, headers, deadline)
    if not node:
        # Último intento: probar las variantes de slug del título.
        for variant in slug_variants(query or slug)[:2]:
            if variant == slug:
                continue
            node = detail("", variant, headers, deadline)
            if node:
                slug = variant
                break
    if not node:
        # El título existe en el buscador pero la API no da enlaces: eso es
        # "sin servidores", no "no existe".
        meta["reason"] = "no_servers" if from_search else "not_found"
        return [], meta

    meta["page"] = f"{BASE}/pelicula/{slug}"
    servers = links_to_servers(node)
    meta["servers"] = [server["name"] for server in servers]
    if not servers:
        meta["reason"] = "no_servers"
        return [], meta

    signals, resolve_meta = resolve_signals(
        servers, headers, deadline, f"{BASE}/pelicula/{slug}", parallel=4, max_servers=6
    )
    meta.update(resolve_meta)
    if not signals:
        meta["reason"] = "no_streams"
        return [], meta

    title = str(node.get("name_es") or node.get("title") or node.get("name") or ref.get("title") or "")
    display = display_name(title or slug.replace("-", " "), ref.get("year"), ref.get("season"), ref.get("episode"))
    meta["title"] = display
    return build_streams("PeliculasFlix", display, signals, str(payload.get("userAgent") or "")), meta


def list_items(query: str, limit: int = 20) -> List[Dict[str, str]]:
    """`--list`: resultados de la API para un texto."""
    headers = _headers({})
    deadline = Deadline(10000)
    items: List[Dict[str, str]] = []
    for hit in search(query, headers, deadline):
        slug = str(hit.get("slug") or "").strip()
        if not slug:
            continue
        items.append({
            "id": f"peliculasflix:{slug}",
            "slug": slug,
            "title": str(hit.get("name_es") or hit.get("title") or hit.get("name") or slug),
            "url": f"{BASE}/pelicula/{slug}",
        })
        if len(items) >= limit:
            break
    return items


def main() -> None:
    import json
    import sys

    args = sys.argv[1:]
    if args and args[0] in ("-h", "--help"):
        print(__doc__)
        return
    if args and args[0] == "--list":
        query = ""
        if "--query" in args:
            index = args.index("--query")
            query = args[index + 1] if len(args) > index + 1 else ""
        items = list_items(query) if query else []
        if "--json" in args:
            print(json.dumps({"site": "peliculasflix", "items": items}, ensure_ascii=False, indent=2))
            return
        if not items:
            print('Uso: peliculasflix.py --list --query "un título"')
            return
        for item in items:
            print(f"  {item['id']:<48} {item['title']}")
        return
    run(scrape)


if __name__ == "__main__":
    main()
