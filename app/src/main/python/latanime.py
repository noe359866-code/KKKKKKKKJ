#!/usr/bin/env python3
"""
scrapers/latanime.py
------------------------------------------------------------------------------
Proveedor para https://latanime.com/ (anime latino).

Basado en la lógica de animeflv.py / _animekit.py:
- El sitio publica el episodio en /anime/<slug> o /ver/<slug>-<episodio>
- Los servidores (Streamwish, VOE, Filemoon, etc.) se resuelven vía _hosters.py
"""

from __future__ import annotations

import os
import re
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

LABEL = "Latanime"
PROVIDER = "latanime"

BASE_URL = os.getenv("LATANIME_BASE", "https://latanime.com").rstrip("/")
EPISODE_PATH = os.getenv("LATANIME_EPISODE_PATH", "/ver/{slug}-{episode}")
ANIME_PATH = os.getenv("LATANIME_ANIME_PATH", "/anime/{slug}")
SEARCH_PATH = os.getenv("LATANIME_SEARCH_PATH", "/buscar?q={query}")

MAX_SERVERS = int(os.getenv("LATANIME_MAX_SERVERS", "6"))
PARALLEL_SERVERS = int(os.getenv("LATANIME_PARALLEL", "4"))

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

LANG_LABELS = {"sub": "Sub", "lat": "Latino", "dub": "Latino", "jap": "Jap"}

NON_ANIME_SLUGS = {
    "directorio", "buscar", "horario", "genero", "temporada", "studio", "idioma",
    "anime", "ver", "perfil", "login", "registro", "dmca", "contacto", "donacion",
    "nosotros", "terminos", "privacidad", "historial", "notificaciones",
}

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
        "script": "latanime.py",
        "priority": 15,
        "enabled": True,
        "referer": BASE_URL + "/",
        "userAgent": DEFAULT_USER_AGENT,
    }


def search_url(query: str) -> str:
    return BASE_URL + SEARCH_PATH.format(query=urllib.parse.quote(query))


def parse_episodes(html: str) -> List[int]:
    """Extrae los números de episodio de la lista de la ficha."""
    return [
        int(m.group(1))
        for m in re.finditer(r"/ver/[a-zA-Z0-9_-]+-(\d+)", html)
    ]


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Resuelve streams. No publica catálogo (retorna streams vacíos en catálogo)."""
    media_type = payload.get("type", "anime")
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
    if media_type == "anime":
        page = BASE_URL + ANIME_PATH.format(slug=slug)
    else:
        page = BASE_URL + EPISODE_PATH.format(slug=slug, episode=episode)

    html, final_url, _ = http_get(page, headers, deadline.remaining(6))

    if not html or any(marker in html.lower() for marker in BLOCK_MARKERS):
        log("latanime blocked or empty:", final_url)
        return [], {}

    # Extraer servidores
    servers: List[Dict[str, Any]] = []
    for entry in objects(html, "servers"):
        embed = ""
        for field in ("remote", "url", "code", "embed", "file"):
            value = entry.get(field)
            if isinstance(value, str) and value.strip():
                candidate = decode_maybe_base64(value.strip())
                if candidate.startswith(("http://", "https://")):
                    embed = candidate
                    break
        if embed:
            name = entry.get("server") or entry.get("name") or ""
            lang = entry.get("lang") or entry.get("language") or ""
            servers.append(
                server_entry(
                    url=embed,
                    name=SERVER_ALIASES.get(name.lower(), pretty_name(name)) or "Servidor",
                    label=LANG_LABELS.get(lang.lower(), "") or "",
                    quality="",
                    referer=final_url,
                    user_agent=headers.get("User-Agent", DEFAULT_USER_AGENT),
                )
            )

    # Resolver con _hosters.py
    streams = build_streams(
        resolve_all(servers, deadline, PARALLEL_SERVERS),
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
