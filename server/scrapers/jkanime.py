#!/usr/bin/env python3
"""
scrapers/jkanime.py
------------------------------------------------------------------------------
Proveedor real para https://jkanime.net/ (anime subtitulado, español/Latino).

Cómo publica el sitio la señal
------------------------------
    1. Cada episodio tiene su página, con el número como último segmento:

           /<slug>/<episodio>              p. ej. /one-piece/1180/

    2. La página trae la lista de servidores en JS, con el embed en base64:

           var servers = [{server:"desu", remote:"aHR0cHM6Ly8…", lang:"sub"}, …]
           var remote  = "https://c1.jkplayers.com"     ← sólo descargas

       También aparecen reproductores sueltos como
       `video[0] = '<iframe src="https://…"></iframe>'`, que se leen igual.

    3. Cada servidor (Desu, Magi, Mega, Streamwish, Vidhide, VOE, Filemoon,
       Mixdrop, Mp4upload, Streamtape, Doodstream…) esconde el `.m3u8`/`.mp4`
       a su manera: lo resuelve `_hosters.py`.

Los servidores de descarga (`/d/<slug>`) se ignoran: no son señales
reproducibles.

IDs aceptados en `/stream/anime/<id>.json`
------------------------------------------
    jkanime:one-piece:1180
    jkanime:one-piece:1:1180          (temporada:episodio)
    anime:jkanime:one-piece:1180
    https://jkanime.net/one-piece/1180/
    jkanime:one piece:1180            (el slug se resuelve por búsqueda)

Uso desde la CLI
----------------
    python3 scrapers/jkanime.py --list                 # últimos episodios
    python3 scrapers/jkanime.py --list --query "one piece"
    python3 scrapers/jkanime.py --list --json
    echo '{"type":"anime","id":"jkanime:one-piece:1180"}' \
        | python3 scrapers/jkanime.py

Dependencias: sólo la librería estándar (vía `_htmlkit.py`, `_animekit.py`).
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
    normalize_query,
    objects,
    parse_id,
    server_entry,
    slugify,
)
from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _hosters import resolve_all
from _htmlkit import Deadline, clean_title, http_get, pretty_name, unescape_markup

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------

LABEL = "JkAnime"
PROVIDER = "jkanime"

BASE_URL = os.getenv("JKANIME_BASE", "https://jkanime.net").rstrip("/")
EPISODE_PATH = os.getenv("JKANIME_EPISODE_PATH", "/{slug}/{episode}/")
ANIME_PATH = os.getenv("JKANIME_ANIME_PATH", "/{slug}/")
SEARCH_PATH = os.getenv("JKANIME_SEARCH_PATH", "/buscar/{query}/")

MAX_SERVERS = int(os.getenv("JKANIME_MAX_SERVERS", "6"))
PARALLEL_SERVERS = int(os.getenv("JKANIME_PARALLEL", "4"))

#: Nombres del sitio → etiqueta legible.
SERVER_ALIASES = {
    "desu": "Desu",
    "magi": "Magi",
    "mega": "Mega",
    "streamwish": "Streamwish",
    "sw": "Streamwish",
    "vidhide": "Vidhide",
    "voe": "VOE",
    "filemoon": "Filemoon",
    "mixdrop": "Mixdrop",
    "mp4upload": "Mp4upload",
    "streamtape": "Streamtape",
    "stape": "Streamtape",
    "doodstream": "Doodstream",
    "dood": "Doodstream",
    "yourupload": "YourUpload",
    "okru": "Okru",
    "hls": "HLS",
    "jkplayer": "JKPlayer",
}

#: Idiomas que publica el sitio, para la etiqueta del stream.
LANG_LABELS = {"sub": "Sub", "lat": "Latino", "dub": "Latino", "jap": "Jap"}

#: Rutas del sitio que no son animes (para no confundirlas con slugs).
NON_ANIME_SLUGS = {
    "directorio", "buscar", "horario", "genero", "temporada", "studio", "idioma",
    "anime", "ver", "perfil", "login", "registro", "dmca", "contacto", "donacion",
    "nosotros", "terminos", "privacidad", "historial", "notificaciones",
}

BLOCK_MARKERS = ("just a moment", "checking your browser", "attention required", "cloudflare")


# ---------------------------------------------------------------------------
# Extracción
# ---------------------------------------------------------------------------


def server_name(raw_name: str, url: str) -> str:
    key = clean_title(raw_name or "").lower().replace(" ", "")
    if key in SERVER_ALIASES:
        return SERVER_ALIASES[key]
    host = urllib.parse.urlparse(url).netloc.replace("www.", "")
    if not host:
        return pretty_name(raw_name) or "Servidor"
    root = host.split(".")
    return pretty_name(root[-2] if len(root) > 1 else root[0]) or "Servidor"


def parse_servers(html: str, page_url: str) -> List[Dict[str, Any]]:
    """Servidores del episodio: `var servers`, `video[N]` e iframes."""
    servers: List[Dict[str, Any]] = []

    for entry in objects(html, "servers"):
        embed = ""
        for field in ("remote", "url", "code", "embed", "file"):
            value = entry.get(field)
            if isinstance(value, str) and value.strip():
                candidate = decode_maybe_base64(value.strip())
                if candidate.startswith(("http://", "https://", "//")):
                    embed = candidate
                    break
        if not embed:
            continue
        if embed.startswith("//"):
            embed = "https:" + embed
        name = server_name(str(entry.get("server") or entry.get("name") or ""), embed)
        lang = LANG_LABELS.get(str(entry.get("lang") or "").strip().lower(), "")
        servers.append(server_entry(name, embed, {"lang": lang} if lang else None))

    # Reproductores sueltos: video[0] = '<iframe src="…"></iframe>'.
    for fragment in re.findall(r"""video\[\d+\]\s*=\s*(['"])(.*?)\1""", unescape_markup(html), re.DOTALL):
        inner = fragment[1]
        src = re.search(r"""src=['"]([^'"]+)['"]""", inner, re.IGNORECASE)
        if not src:
            continue
        embed = urllib.parse.urljoin(page_url, src.group(1).strip())
        if embed.startswith(("http://", "https://")):
            servers.append(server_entry("JKPlayer", embed))

    if not servers:
        servers = iframe_servers(
            html, page_url, skip_hosts=("jkanime.net", "google", "disqus", "facebook")
        )

    return dedupe_servers(servers)[:MAX_SERVERS]


def parse_episodes(html: str, slug: str) -> List[int]:
    """Números de episodio enlazados desde la página del anime."""
    numbers = set()
    for match in re.finditer(
        rf"""href\s*=\s*["'][^"']*/{re.escape(slug)}/(\d{{1,5}})/?["']""", unescape_markup(html)
    ):
        numbers.add(int(match.group(1)))
    return sorted(numbers)


def episode_url(slug: str, episode: int) -> str:
    return f"{BASE_URL}{EPISODE_PATH.format(slug=slug, episode=int(episode))}"


def search_url(query: str) -> str:
    return f"{BASE_URL}{SEARCH_PATH.format(query=urllib.parse.quote(query.strip()))}"


def looks_blocked(html: str) -> bool:
    head = (html or "")[:4000].lower()
    return any(marker in head for marker in BLOCK_MARKERS)


def fetch(page_url: str, headers: Dict[str, str], deadline: Deadline) -> Optional[Tuple[str, str]]:
    try:
        html, final_url, _ = http_get(page_url, headers, deadline.remaining())
        return html, final_url
    except urllib.error.HTTPError as exc:
        log("fetch", page_url, "->", exc.code)
        return None
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("fetch", page_url, "failed:", exc)
        return None


def parse_search(html: str, page_url: str) -> List[Dict[str, str]]:
    """Resultados de `/buscar/<query>/`: enlaces `/<slug>/` de un solo nivel."""
    hits: List[Dict[str, str]] = []
    seen = set()
    host = urllib.parse.urlparse(page_url).netloc

    for match in re.finditer(
        r"""<a[^>]+href\s*=\s*["'](https?://[^"']+|[^"']*)["'][^>]*>(.*?)</a>""",
        unescape_markup(html),
        re.IGNORECASE | re.DOTALL,
    ):
        url = urllib.parse.urljoin(page_url, match.group(1).strip())
        parsed = urllib.parse.urlparse(url)
        if parsed.netloc != host:
            continue
        parts = [part for part in parsed.path.split("/") if part]
        if len(parts) != 1:
            continue
        slug = slugify(parts[0])
        if not slug or slug in seen or slug in NON_ANIME_SLUGS:
            continue
        title = clean_title(match.group(2))
        if not title or len(title) < 2:
            continue
        seen.add(slug)
        hits.append({"slug": slug, "title": title, "url": url})
    return hits


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

    ref = apply_payload_episode(parse_id(str(payload.get("id") or ""), PROVIDER), payload)
    meta: Dict[str, Any] = {
        "provider": PROVIDER,
        "slug": ref.get("slug"),
        "season": ref.get("season"),
        "episode": ref.get("episode"),
    }
    if not ref.get("slug") and not ref.get("url"):
        return [], {**meta, "reason": "no_id"}

    page = ref.get("url") or episode_url(ref["slug"], ref.get("episode") or 1)
    meta["page"] = page
    found = fetch(page, headers, deadline)

    # Slug inexistente → búsqueda por nombre.
    if found is None and ref.get("slug"):
        searched = fetch(search_url(normalize_query(ref["slug"])), headers, deadline)
        hit = best_hit(normalize_query(ref["slug"]), parse_search(*searched)) if searched else None
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

    heading = re.search(r"<h1[^>]*>(.*?)</h1>", unescape_markup(html), re.IGNORECASE | re.DOTALL)
    title = clean_title(heading.group(1)) if heading else ""
    # "Episodio 1180 Japones - One Piece" → "One Piece"
    title = re.sub(r"^(?:episodio|cap[ií]tulo)\s*\d+\s*[-–:]?\s*", "", title, flags=re.IGNORECASE)
    title = re.sub(
        r"^(?:japones|japon[eé]s|latino|castellano|espa[nñ]ol|sub(?:titulado)?)\s*[-–:]?\s*",
        "", title, flags=re.IGNORECASE,
    )
    title = title or (meta.get("title") or "") or pretty_name(ref["slug"])
    meta["title"] = title

    servers = parse_servers(html, final_url)
    meta["servers"] = [server["name"] for server in servers]
    if not servers:
        return [], {**meta, "reason": "no_servers"}

    signals, info = resolve_all(
        servers, headers, deadline, episode_url=final_url, parallel=PARALLEL_SERVERS
    )
    meta["unresolved"] = info.get("unresolved", [])
    if not signals:
        return [], {**meta, "reason": "no_streams"}

    streams = build_streams(LABEL, title, ref.get("episode"), signals, user_agent)
    meta["streams"] = len(streams)
    return streams, meta


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _recent_episodes(html: str, page_url: str, limit: int = 20) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    seen = set()
    host = urllib.parse.urlparse(page_url).netloc
    for match in re.finditer(
        r"""<a[^>]+href\s*=\s*["'](https?://[^"']+|[^"']*)["'][^>]*>(.*?)</a>""",
        unescape_markup(html),
        re.IGNORECASE | re.DOTALL,
    ):
        url = urllib.parse.urljoin(page_url, match.group(1).strip())
        parsed = urllib.parse.urlparse(url)
        if parsed.netloc != host:
            continue
        parts = [part for part in parsed.path.split("/") if part]
        if len(parts) != 2 or not parts[1].isdigit():
            continue
        slug = slugify(parts[0])
        if slug in NON_ANIME_SLUGS:
            continue
        key = (slug, int(parts[1]))
        if key in seen:
            continue
        seen.add(key)
        title = clean_title(re.sub(r"<[^>]+>", " ", match.group(2))) or pretty_name(slug)
        out.append({
            "id": f"{PROVIDER}:{slug}:{parts[1]}",
            "slug": slug,
            "episode": int(parts[1]),
            "title": title,
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
        rows = [
            {"id": f"{PROVIDER}:{hit['slug']}:1", **hit}
            for hit in (parse_search(*found)[:30] if found else [])
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
