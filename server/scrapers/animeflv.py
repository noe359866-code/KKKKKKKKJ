#!/usr/bin/env python3
"""
scrapers/animeflv.py
------------------------------------------------------------------------------
Proveedor real para https://www3.animeflv.net/ (anime subtitulado en español).

Cómo publica el sitio la señal
------------------------------
    1. Cada episodio tiene su página:

           /ver/<slug>-<episodio>          p. ej. /ver/one-piece-1180

    2. Esa página trae la lista de servidores en un literal JavaScript:

           var videos = [{"server":"stape","title":"…",
                          "code":"aHR0cHM6Ly9zdHJlYW10YXBlLmNvbS9lL2FiYw=="}, …]

       `code` viene **en base64** y decodifica a la URL del embed
       (Streamtape, Streamwish, Filemoon, VOE, Hqq, Mixdrop, Mp4upload…).

    3. Cada embed esconde el `.m3u8`/`.mp4` a su manera: de eso se encarga
       `_hosters.py` (incluye el desempaquetado del JS empaquetado).

Por eso el scraper: resuelve el id → pide la página del episodio → decodifica
los servidores → resuelve cada uno en paralelo → devuelve **un stream por
servidor**, en el orden del sitio (de mejor a peor), que es el orden de failover
que espera ExoPlayer.

IDs aceptados en `/stream/anime/<id>.json`
------------------------------------------
    animeflv:one-piece:1180            (episodio)
    animeflv:one-piece:1:1180          (temporada:episodio, como manda Stremio)
    animeflv:one-piece                 (episodio 1, o el `episode` del payload)
    anime:animeflv:one-piece:1180      (prefijo del addon)
    https://www3.animeflv.net/ver/one-piece-1180
    animeflv:dragon ball z:291         (texto libre: se busca por nombre)

Uso desde la CLI
----------------
    python3 scrapers/animeflv.py --list                 # últimos episodios
    python3 scrapers/animeflv.py --list --query "one piece"
    python3 scrapers/animeflv.py --list --json
    echo '{"type":"anime","id":"animeflv:one-piece:1180"}' \
        | python3 scrapers/animeflv.py

Dependencias: sólo la librería estándar (vía `_htmlkit.py` y `_animekit.py`).
"""

from __future__ import annotations

import os
import re
import sys
import urllib.error
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

from _animekit import (
    apply_payload_episode,
    best_hit,
    build_streams,
    decode_maybe_base64,
    dedupe_servers,
    iframe_servers,
    js_literal,
    normalize_query,
    objects,
    parse_id,
    search_hits,
    server_entry,
)
from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _hosters import resolve_all
from _htmlkit import Deadline, clean_title, http_get, pretty_name, unescape_markup
from _peliskit import fallback_episode_servers

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------

LABEL = "AnimeFLV"
PROVIDER = "animeflv"

BASE_URL = os.getenv("ANIMEFLV_BASE", "https://www3.animeflv.net").rstrip("/")
VER_PATH = os.getenv("ANIMEFLV_VER_PATH", "/ver/{slug}-{episode}")
ANIME_PATH = os.getenv("ANIMEFLV_ANIME_PATH", "/anime/{slug}")
SEARCH_PATH = os.getenv("ANIMEFLV_SEARCH_PATH", "/browse?q={query}")

#: Tope de servidores que se resuelven por episodio.
MAX_SERVERS = int(os.getenv("ANIMEFLV_MAX_SERVERS", "6"))
PARALLEL_SERVERS = int(os.getenv("ANIMEFLV_PARALLEL", "4"))

#: Nombres del sitio → etiqueta legible (`stape`, `sw`, `hqq`…).
SERVER_ALIASES = {
    "stape": "Streamtape",
    "streamtape": "Streamtape",
    "sw": "Streamwish",
    "streamwish": "Streamwish",
    "filemoon": "Filemoon",
    "vidhide": "Vidhide",
    "voe": "VOE",
    "hqq": "HQQ",
    "hqqto": "HQQ",
    "mixdrop": "Mixdrop",
    "mp4upload": "Mp4upload",
    "yourupload": "YourUpload",
    "dood": "Doodstream",
    "doodstream": "Doodstream",
    "okru": "Okru",
    "hls": "HLS",
    "fembed": "Fembed",
    "uqload": "Uqload",
    "mega": "Mega",
    "mediafire": "Mediafire",
}

#: Pistas de que el sitio devolvió un reto anti-bot en vez del episodio.
BLOCK_MARKERS = ("just a moment", "checking your browser", "cf-browser-verification", "attention required")


# ---------------------------------------------------------------------------
# Extracción
# ---------------------------------------------------------------------------


def server_name(raw_name: str, url: str) -> str:
    """Etiqueta del servidor: alias conocido del sitio o el host del embed."""
    key = clean_title(raw_name or "").lower().replace(" ", "")
    if key in SERVER_ALIASES:
        return SERVER_ALIASES[key]
    host = urllib.parse.urlparse(url).netloc.replace("www.", "")
    if not host:
        return pretty_name(raw_name) or "Servidor"
    root = host.split(".")
    return pretty_name(root[-2] if len(root) > 1 else root[0]) or "Servidor"


def parse_servers(html: str, page_url: str) -> List[Dict[str, Any]]:
    """Servidores del episodio a partir de `var videos = [...]`."""
    servers: List[Dict[str, Any]] = []

    for entry in objects(html, "videos"):
        embed = ""
        for field in ("code", "url", "embed", "file", "remote"):
            value = entry.get(field)
            if isinstance(value, str) and value.strip():
                embed = decode_maybe_base64(value.strip())
                if embed:
                    break
        if not embed:
            continue
        if embed.startswith("//"):
            embed = "https:" + embed
        embed = unescape_markup(embed).strip()
        if not embed.startswith(("http://", "https://")):
            continue
        name = server_name(str(entry.get("server") or entry.get("title") or ""), embed)
        lang = str(entry.get("lang") or entry.get("language") or "").strip()
        servers.append(server_entry(name, embed, {"lang": lang} if lang else None))

    if not servers:
        # Respaldo: algunos episodios sólo traen el iframe en el HTML.
        servers = iframe_servers(html, page_url, skip_hosts=("animeflv.net", "google", "disqus"))

    return dedupe_servers(servers)[:MAX_SERVERS]


def parse_episodes(html: str) -> List[int]:
    """`var episodes = [[1,"one-piece-1"], …]` → [1, 2, …]."""
    value = js_literal(html, "episodes")
    numbers: List[int] = []
    if isinstance(value, list):
        for item in value:
            if isinstance(item, list) and item:
                try:
                    numbers.append(int(item[0]))
                except (TypeError, ValueError):
                    continue
            elif isinstance(item, (int, str)) and str(item).isdigit():
                numbers.append(int(item))
    return sorted(set(numbers))


def episode_url(slug: str, episode: int) -> str:
    return f"{BASE_URL}{VER_PATH.format(slug=slug, episode=int(episode))}"


def series_url(slug: str) -> str:
    return f"{BASE_URL}{ANIME_PATH.format(slug=slug)}"


def search_url(query: str) -> str:
    return f"{BASE_URL}{SEARCH_PATH.format(query=urllib.parse.quote_plus(query))}"


def looks_blocked(html: str) -> bool:
    head = (html or "")[:4000].lower()
    return any(marker in head for marker in BLOCK_MARKERS)


def fetch(page_url: str, headers: Dict[str, str], deadline: Deadline) -> Optional[Tuple[str, str]]:
    """GET tolerante: devuelve ``(html, url_final)`` o ``None`` (ya logueado)."""
    try:
        html, final_url, _ = http_get(page_url, headers, deadline.remaining())
        return html, final_url
    except urllib.error.HTTPError as exc:
        log("fetch", page_url, "->", exc.code)
        return None
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("fetch", page_url, "failed:", exc)
        return None


def find_slug(query: str, headers: Dict[str, str], deadline: Deadline) -> Optional[Dict[str, str]]:
    """Búsqueda por nombre cuando el slug del id no existe (`/browse?q=`)."""
    found = fetch(search_url(query), headers, deadline)
    if not found:
        return None
    html, final_url = found
    hits = search_hits(html, final_url, "/anime/")
    return best_hit(query, hits)


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    if payload.get("isLive"):
        return [], {"reason": "not_vod", "provider": PROVIDER}

    headers = default_headers(payload)
    headers["Accept"] = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT
    deadline = Deadline(payload.get("timeoutMs"))

    content_id = str(payload.get("id") or "")
    ref = apply_payload_episode(parse_id(content_id, PROVIDER), payload)
    meta: Dict[str, Any] = {
        "provider": PROVIDER,
        "slug": ref.get("slug"),
        "season": ref.get("season"),
        "episode": ref.get("episode"),
    }

    if not ref.get("slug") and not ref.get("url"):
        return [], {**meta, "reason": "no_id"}

    # 1. Página del episodio (o la URL tal cual si el id era una URL).
    page = ref.get("url") or episode_url(ref["slug"], ref.get("episode") or 1)
    meta["page"] = page
    found = fetch(page, headers, deadline)

    # 2. Si el slug no existe, se intenta la búsqueda por nombre.
    if found is None and ref.get("slug"):
        hit = find_slug(normalize_query(ref["slug"]), headers, deadline)
        if hit:
            meta["resolved_from_search"] = hit["slug"]
            meta["title"] = hit.get("title")
            ref = {**ref, "slug": hit["slug"]}
            page = episode_url(hit["slug"], ref.get("episode") or 1)
            meta["page"] = page
            found = fetch(page, headers, deadline)

    if found is None:
        return [], {**meta, "reason": "episode_unavailable"}

    html, final_url = found
    if looks_blocked(html):
        return [], {**meta, "reason": "blocked"}

    # 3. Título del anime: `h1` del episodio ("One Piece" / encabezado del sitio).
    heading = re.search(r"<h1[^>]*>(.*?)</h1>", unescape_markup(html), re.IGNORECASE | re.DOTALL)
    title = clean_title(heading.group(1)) if heading else ""
    title = title or (meta.get("title") or "") or pretty_name(ref["slug"])
    meta["title"] = title

    # 4. Servidores → señales.
    servers = parse_servers(html, final_url)
    if not servers and ref.get("slug"):
        # Episodio sin botones: la ficha del anime puede tener el reproductor o
        # un enlace al mismo episodio en otra ruta (`/ver/one-piece-1180-…`).
        servers, servers_page = fallback_episode_servers(
            html,
            final_url,
            {"extras": lambda body, url, hdrs, dl: parse_servers(body, url)},
            headers,
            deadline,
            episode=int(ref.get("episode") or 1),
            anime_url=series_url(ref["slug"]),
        )
        if servers:
            meta["servers_from"] = servers_page
            meta["servers_via"] = "anime_page"
    meta["servers"] = [server["name"] for server in servers]
    if not servers:
        return [], {**meta, "reason": "no_servers"}

    signals, info = resolve_all(
        servers, headers, deadline, episode_url=final_url, parallel=PARALLEL_SERVERS
    )
    meta.update({"unresolved": info.get("unresolved", [])})

    if not signals:
        return [], {**meta, "reason": "no_streams"}

    streams = build_streams(LABEL, title, ref.get("episode"), signals, user_agent)
    meta["streams"] = len(streams)
    return streams, meta


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _recent_episodes(html: str, page_url: str, limit: int = 20) -> List[Dict[str, Any]]:
    """Últimos episodios del home (`/ver/<slug>-<n>`)."""
    out: List[Dict[str, Any]] = []
    seen = set()
    pattern = re.compile(
        r"""<a[^>]+href\s*=\s*["']([^"']*/ver/([a-z0-9._-]+?)-(\d{1,5}))/?["'][^>]*>(.*?)</a>""",
        re.IGNORECASE | re.DOTALL,
    )
    for match in pattern.finditer(unescape_markup(html)):
        url = urllib.parse.urljoin(page_url, match.group(1))
        slug = match.group(2)
        episode = int(match.group(3))
        key = (slug, episode)
        if key in seen:
            continue
        seen.add(key)
        title = clean_title(re.sub(r"<[^>]+>", " ", match.group(4)))
        out.append({
            "id": f"{PROVIDER}:{slug}:{episode}",
            "slug": slug,
            "episode": episode,
            "title": title or pretty_name(slug),
            "url": url,
        })
        if len(out) >= limit:
            break
    return out


def _print_list(query: str, as_json: bool) -> None:
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-ES,es;q=0.9",
    }
    deadline = Deadline(20000)

    if query:
        found = fetch(search_url(query), headers, deadline)
        rows = []
        if found:
            html, final_url = found
            rows = [
                {"id": f"{PROVIDER}:{hit['slug']}:1", **hit}
                for hit in search_hits(html, final_url, "/anime/")[:30]
            ]
        label = f"resultados para «{query}»"
    else:
        found = fetch(f"{BASE_URL}/", headers, deadline)
        rows = _recent_episodes(*found) if found else []
        label = "últimos episodios"

    if as_json:
        import json

        print(json.dumps({"site": LABEL, "base": BASE_URL, "query": query, "items": rows},
                         ensure_ascii=False, indent=2))
        return

    if not rows:
        print(f"Sin {label} en {BASE_URL} (¿cambió el sitio o no hay red?).")
        return

    print(f"{len(rows)} {label} en {BASE_URL}\n")
    for row in rows:
        if "episode" in row:
            print(f"  {row['title'][:52]:<52} {row['id']}")
        else:
            print(f"  {row['title'][:52]:<52} {row['id']}")
    print("\nÚsalo en Stremio:  /stream/anime/<id>.json")


def main() -> None:
    args = sys.argv[1:]
    if args and args[0] in ("-h", "--help"):
        print(__doc__)
        return
    if args and args[0] == "--list":
        query = ""
        if "--query" in args:
            index = args.index("--query")
            query = args[index + 1] if len(args) > index + 1 else ""
        _print_list(query, as_json="--json" in args)
        return
    run(scrape)


if __name__ == "__main__":
    main()
