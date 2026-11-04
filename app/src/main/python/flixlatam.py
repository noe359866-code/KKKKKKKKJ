#!/usr/bin/env python3
"""
scrapers/flixlatam.py
------------------------------------------------------------------------------
Proveedor para FlixLatam (Películas/Series - Latino/Castellano/Sub).

Basado en la lógica de _peliskit.py / gnula.py:
- El sitio publica la serie/película en /serie/<slug> o /pelicula/<slug>
- Los servidores (Streamwish, VOE, Filemoon, etc.) se resuelven vía _hosters.py
"""

from __future__ import annotations

import os
import re
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _hosters import resolve_all
from _htmlkit import Deadline, clean_title, http_get, pretty_name, unescape_markup
from _peliskit import fallback_episode_servers, search_site

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------

LABEL = "FlixLatam"
PROVIDER = "flixlatam"

BASE_URL = os.getenv("FLIXLATAM_BASE", "https://flixlatam.com").rstrip("/")
SERIES_PATH = os.getenv("FLIXLATAM_SERIES_PATH", "/serie/{slug}")
MOVIE_PATH = os.getenv("FLIXLATAM_MOVIE_PATH", "/pelicula/{slug}")
SEARCH_PATH = os.getenv("FLIXLATAM_SEARCH_PATH", "/buscar?q={query}")

MAX_SERVERS = int(os.getenv("FLIXLATAM_MAX_SERVERS", "6"))
PARALLEL_SERVERS = int(os.getenv("FLIXLATAM_PARALLEL", "4"))

SERVER_ALIASES = {
    "streamwish": "Streamwish",
    "sw": "Streamwish",
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
}

LANG_LABELS = {"sub": "Sub", "lat": "Latino", "dub": "Latino", "esp": "Español", "cast": "Castellano"}

BLOCK_MARKERS = ("just a moment", "checking your browser", "attention required", "cloudflare")


# ---------------------------------------------------------------------------
# Extracción
# ---------------------------------------------------------------------------


def site_config() -> Dict[str, Any]:
    return {
        "base": BASE_URL,
        "label": LABEL,
        "id": PROVIDER,
        "kind": "vod",
        "script": "flixlatam.py",
        "priority": 14,
        "enabled": True,
        "referer": BASE_URL + "/",
        "userAgent": DEFAULT_USER_AGENT,
    }


def search_url(query: str) -> str:
    return BASE_URL + SEARCH_PATH.format(query=urllib.parse.quote(query))


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Resuelve streams. No publica catálogo (retorna streams vacíos en catálogo)."""
    media_type = payload.get("type", "movie")
    content_id = payload.get("id", "")
    season = payload.get("season")
    episode = payload.get("episode")
    is_live = payload.get("isLive", False)
    timeout = payload.get("timeoutMs", 8000) / 1000.0
    headers = default_headers(site_config())
    deadline = Deadline(timeout)

    # Parse ID
    slug, ep_s, ep_e = parse_id(content_id, media_type, PROVIDER)
    if not slug:
        return [], {}

    episode = ep_e or episode or 1
    season = ep_s or season or 1

    # Construir URL
    if media_type == "series":
        page = BASE_URL + SERIES_PATH.format(slug=slug)
    else:
        page = BASE_URL + MOVIE_PATH.format(slug=slug)

    html, final_url, _ = http_get(page, headers, deadline.remaining(6))

    if not html or any(marker in html.lower() for marker in BLOCK_MARKERS):
        log("flixlatam blocked or empty:", final_url)
        return [], {}

    # Extraer servidores
    servers: List[Dict[str, Any]] = []
    
    for match in re.finditer(r"(https?://[^\s\"'>]+(?:streamwish|voe|filemoon|mixdrop|mp4upload|streamtape|doodstream|okru)[^\s\"'>]*)", html):
        url = match.group(1)
        servers.append(
            server_entry(
                url=url,
                name="Servidor",
                label="",
                quality="",
                referer=final_url,
                user_agent=headers.get("User-Agent", DEFAULT_USER_AGENT),
            )
        )

    # Resolver con _hosters.py
    streams = build_streams(
        resolve_all(servers, headers, deadline, final_url, PARALLEL_SERVERS),
        deadline,
        PROVIDER,
        LABEL,
        is_live,
        headers,
    )

    # Meta
    meta = {
        "id": content_id,
        "type": media_type,
        "name": clean_title(slug.replace("-", " ")) or pretty_name(slug),
        "sourceName": LABEL,
        "_page": final_url,
    }

    return streams, meta


def site() -> Dict[str, Any]:
    return site_config()


if __name__ == "__main__":
    run(scrape, site_config())
