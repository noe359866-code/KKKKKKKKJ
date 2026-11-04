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
       Mixdrop, Mp4upload, Streamtape, Doodstream…) esconde el .m3u8/.mp4
       a su manera: lo resuelve _hosters.py.

IDs aceptados en /stream/anime/<id>.json
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
    echo '{"type":"anime","id":"jkanime:one-piece:1180"}' \\
        | python3 scrapers/jkanime.py

Dependencias: sólo la librería estándar (vía _htmlkit.py, _animekit.py, _hosters.py).
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


def site_config() -> Dict[str, Any]:
    return {
        "base": BASE_URL,
        "label": LABEL,
        "id": PROVIDER,
        "kind": "vod",
        "script": "jkanime.py",
        "priority": 11,
        "enabled": True,
        "referer": BASE_URL + "/",
        "userAgent": DEFAULT_USER_AGENT,
    }


def search_url(query: str) -> str:
    return BASE_URL + SEARCH_PATH.format(query=urllib.parse.quote(query))


def parse_servers(html: str, page_url: str) -> List[Dict[str, Any]]:
    """Servidores del episodio: `var servers`, `video[N]` e iframes."""
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
                    referer=page_url,
                    user_agent=headers.get("User-Agent", DEFAULT_USER_AGENT),
                )
            )

    for match in iframe_servers(html):
        url = match.get("url")
        if url and url.startswith(("http://", "https://")):
            name = match.get("name") or hoster_label(url)
            servers.append(
                server_entry(
                    url=url,
                    name=name,
                    label="",
                    quality="",
                    referer=page_url,
                    user_agent=headers.get("User-Agent", DEFAULT_USER_AGENT),
                )
            )

    return dedupe_servers(servers)


def parse_episodes(html: str, slug: str = "") -> List[int]:
    """Extrae los números de episodio de la lista de la ficha."""
    # /one-piece/1180/
    return [
        int(m.group(1))
        for m in re.finditer(rf"/{slug}/(\d+)/?", html)
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
        log("jkanime blocked or empty:", final_url)
        return [], {}

    # Extraer servidores
    servers = parse_servers(html, final_url)

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
