#!/usr/bin/env python3
"""
scrapers/monoschinos.py
------------------------------------------------------------------------------
Proveedor real para https://monoschinos.st/ (MonosChinos, antes monoschinos2.com).

Cómo publica el sitio la señal
------------------------------
    1. Cada episodio tiene su página:

           /ver/<slug>-episodio-<n>        p. ej. /ver/one-piece-episodio-1

    2. La página no trae URLs: trae botones, uno por servidor, con el
       reproductor en un atributo:

           <button ... data-player="aHR0cHM6Ly92b2Uuc3gv…">voe</button>

    3. Hay que pedir el reproductor del sitio para obtener el embed final:

           GET /reproductor?url=<data-player>
              → <script>window.location = atob("aHR0cHM6Ly92b2Uuc3gv…")</script>

       `data-player` puede venir ya en base64 o en claro; se aceptan ambos y
       también el `atob(...)` en base64 puro.

    4. El embed final (VOE, Filemoon, Doodstream, Mp4upload, Uqload, Obeywish,
       Lulustream…) se resuelve con `_hosters.py`.

Los servidores de descarga (Mega, Mediafire, 1fichier) se descartan: no son
reproducibles.

IDs aceptados en `/stream/anime/<id>.json`
------------------------------------------
    monoschinos:one-piece-sub-espanol:1
    monoschinos:one-piece-sub-espanol:1:1180    (temporada:episodio)
    anime:monoschinos:one-piece-sub-espanol:1
    https://monoschinos.st/ver/one-piece-episodio-1
    monoschinos:one piece:1                      (slug resuelto por búsqueda)

Uso desde la CLI
----------------
    python3 scrapers/monoschinos.py --list
    python3 scrapers/monoschinos.py --list --query "one piece"
    python3 scrapers/monoschinos.py --list --json
    echo '{"type":"anime","id":"monoschinos:one-piece-sub-espanol:1"}' \
        | python3 scrapers/monoschinos.py

Dependencias: sólo la librería estándar (vía `_htmlkit.py`, `_animekit.py`).
"""

from __future__ import annotations

import base64
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
    parse_id,
    search_hits,
    server_entry,
)
from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _hosters import resolve_all
from _htmlkit import ANCHOR_RE, Deadline, HREF_RE, clean_title, http_get, pretty_name, unescape_markup
from _peliskit import fallback_episode_servers

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------

LABEL = "MonosChinos"
PROVIDER = "monoschinos"

#: El sitio cambia de dominio con frecuencia (monoschinos2.com → monoschinos.st).
BASE_URL = os.getenv("MONOSCHINOS_BASE", "https://monoschinos.st").rstrip("/")
EPISODE_PATH = os.getenv("MONOSCHINOS_EPISODE_PATH", "/ver/{slug}-episodio-{episode}")
ANIME_PATH = os.getenv("MONOSCHINOS_ANIME_PATH", "/anime/{slug}")
SEARCH_PATH = os.getenv("MONOSCHINOS_SEARCH_PATH", "/buscar?q={query}")
PLAYER_PATH = os.getenv("MONOSCHINOS_PLAYER_PATH", "/reproductor?url={url}")

MAX_SERVERS = int(os.getenv("MONOSCHINOS_MAX_SERVERS", "6"))
PARALLEL_SERVERS = int(os.getenv("MONOSCHINOS_PARALLEL", "4"))

#: Botones de servidor: nombre visible + `data-player`.
PLAYER_BUTTON_RE = re.compile(
    r"""<button[^>]*data-player\s*=\s*["']([^"']+)["'][^>]*>(.*?)</button>""",
    re.IGNORECASE | re.DOTALL,
)
#: El reproductor del sitio devuelve el embed en un `atob("…")`.
ATOB_RE = re.compile(r"""atob\(\s*["']([^"']+)["']\s*\)""", re.IGNORECASE)
#: Alternativa: `<iframe src="…">` dentro del reproductor.
PLAYER_IFRAME_RE = re.compile(r"""<iframe[^>]+?src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)

#: Servidores de descarga que nunca son una señal.
DOWNLOAD_SERVERS = ("1fichier", "mega", "mediafire", "zippyshare", "drive")

BLOCK_MARKERS = ("just a moment", "checking your browser", "attention required", "cloudflare")


# ---------------------------------------------------------------------------
# Extracción
# ---------------------------------------------------------------------------


def parse_player_buttons(html: str) -> List[Dict[str, str]]:
    """Botones de servidor: ``[{name, player}]`` (sin resolver todavía)."""
    buttons: List[Dict[str, str]] = []
    seen = set()
    for match in PLAYER_BUTTON_RE.finditer(unescape_markup(html)):
        player = match.group(1).strip()
        name = clean_title(match.group(2)) or "Servidor"
        if not player or name.lower() in DOWNLOAD_SERVERS:
            continue
        if player in seen:
            continue
        seen.add(player)
        buttons.append({"name": name, "player": player})
    return buttons[:MAX_SERVERS]


def decode_player_payload(html: str) -> str:
    """URL del embed a partir de la respuesta de `/reproductor?url=…`."""
    match = ATOB_RE.search(html or "")
    if match:
        token = match.group(1).strip()
        try:
            decoded = base64.b64decode(token + "=" * (-len(token) % 4)).decode("utf-8", "replace")
        except (ValueError, TypeError):
            decoded = ""
        if decoded.startswith(("http://", "https://", "//")):
            return "https:" + decoded if decoded.startswith("//") else decoded.strip()

    iframe = PLAYER_IFRAME_RE.search(html or "")
    if iframe:
        url = iframe.group(1).strip()
        if url.startswith(("http://", "https://", "//")):
            return "https:" + url if url.startswith("//") else url

    # Sin `atob`: el propio HTML puede ser la URL, o venir ya en claro.
    text = (html or "").strip()
    if text.startswith(("http://", "https://", "//")):
        return "https:" + text if text.startswith("//") else text
    return ""


def expand_servers(
    buttons: List[Dict[str, str]], headers: Dict[str, str], deadline: Deadline
) -> List[Dict[str, Any]]:
    """`/reproductor?url=…` → embed final, en orden y sin salir del deadline."""
    servers: List[Dict[str, Any]] = []
    for button in buttons:
        if deadline.expired():
            break
        player = button["player"]
        # Ya es una URL: no hace falta pasar por el reproductor del sitio.
        direct = decode_maybe_base64(player)
        if direct.startswith(("http://", "https://")):
            servers.append(server_entry(button["name"], direct))
            continue

        endpoint = f"{BASE_URL}{PLAYER_PATH.format(url=urllib.parse.quote(player, safe=''))}"
        try:
            body, final_url, _ = http_get(endpoint, headers, deadline.remaining())
        except urllib.error.HTTPError as exc:
            log("player", button["name"], "->", exc.code)
            continue
        except (urllib.error.URLError, OSError, ValueError) as exc:
            log("player", button["name"], "failed:", exc)
            continue

        embed = decode_player_payload(body)
        if embed.startswith(("http://", "https://")):
            servers.append(server_entry(button["name"], embed))
        else:
            log("player", button["name"], "sin embed en", final_url)

    return dedupe_servers(servers)


def episode_url(slug: str, episode: int) -> str:
    return f"{BASE_URL}{EPISODE_PATH.format(slug=slug, episode=int(episode))}"


def series_url(slug: str) -> str:
    return f"{BASE_URL}{ANIME_PATH.format(slug=slug)}"


def _native_servers(
    html: str, page_url: str, headers: Dict[str, str], deadline: Deadline
) -> List[Dict[str, Any]]:
    """Servidores de una página en el formato del sitio (para el respaldo)."""
    buttons = parse_player_buttons(html)
    return expand_servers(buttons, headers, deadline) if buttons else []


def search_url(query: str) -> str:
    return f"{BASE_URL}{SEARCH_PATH.format(query=urllib.parse.quote_plus(query))}"


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

    if found is None and ref.get("slug"):
        query = normalize_query(ref["slug"])
        searched = fetch(search_url(query), headers, deadline)
        hit = best_hit(query, search_hits(*searched, "/anime/")) if searched else None
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
    title = re.sub(r"^(?:ver|watch)\s+", "", title, flags=re.IGNORECASE)
    title = re.sub(r"\s*[-–:]\s*\d{1,5}\s*$", "", title).strip(" -–")
    title = title or (meta.get("title") or "") or pretty_name(ref["slug"])
    meta["title"] = title

    buttons = parse_player_buttons(html)
    servers = expand_servers(buttons, headers, deadline) if buttons else []
    if not servers:
        servers = iframe_servers(
            html, final_url, skip_hosts=("monoschinos", "google", "disqus", "facebook")
        )
    if not servers and ref.get("slug"):
        # Sin reproductores en el episodio: la ficha del anime los tiene (o el
        # enlace al episodio en otra ruta).
        servers, servers_page = fallback_episode_servers(
            html,
            final_url,
            # El respaldo parsea con el lector de botones del sitio (los
            # reproductores de MonosChinos viajan en `/reproductor?url=`).
            {"extras": _native_servers},
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
    """Últimos episodios del home (`/ver/<slug>-episodio-<n>`)."""
    out: List[Dict[str, Any]] = []
    seen = set()
    for match in ANCHOR_RE.finditer(unescape_markup(html)):
        href_match = HREF_RE.search(match.group(1))
        if not href_match:
            continue
        url = urllib.parse.urljoin(page_url, href_match.group(1).strip())
        found = re.search(r"/ver/([a-z0-9._-]+?)-episodio-(\d{1,5})", urllib.parse.urlparse(url).path)
        if not found:
            continue
        key = (found.group(1), int(found.group(2)))
        if key in seen:
            continue
        seen.add(key)
        title = clean_title(re.sub(r"<[^>]+>", " ", match.group(2))) or pretty_name(found.group(1))
        out.append({
            "id": f"{PROVIDER}:{found.group(1)}:{found.group(2)}",
            "slug": found.group(1),
            "episode": int(found.group(2)),
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
            for hit in (search_hits(*found, "/anime/")[:30] if found else [])
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
        print(f"Sin {label} en {BASE_URL} (¿cambió el dominio o no hay red?).")
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
