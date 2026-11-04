#!/usr/bin/env python3
"""
scrapers/cuevana.py
------------------------------------------------------------------------------
Proveedor para **Cuevana** (Películas/Series - Español/Latino/Castellano).

Basado en la lógica del servidor (server/scrapers/cuevana.py) y adaptado al
motor local (arena/d7c4af22-kkkkkkkkj).

Cómo publica el sitio la señal
------------------------------
    1. Cada título tiene su ficha:

           /peliculas/<slug>          película
           /series/<slug>/…           serie (un capítulo por URL)

    2. El reproductor no está en la ficha: la página carga un iframe del propio
       dominio con el embed **en base64** dentro de la URL:

           <iframe src="https://cue.cuevana3.nu/embed-page?showEmbed=BASE64">

       `showEmbed` decodifica a la URL directa del servidor:

           https://vimeos.net/embed-xxxxxxxx.html
           https://streamwish.to/e/xxxxxxxx

    3. Las versiones antiguas usan un token opaco:

           <iframe src="…/player.php?t=<token>">

       `player.php` responde con un `<iframe src="…">` al embed real, así que se
       sigue un salto y se recoge el src.

    4. Cada embed es un servidor conocido: de resolverlo se encarga
       `_hosters.py`. El scraper también recoge cualquier otro `<iframe>` o
       atributo `data-*` que aparezca en la ficha (respaldo genérico).

IDs aceptados en /stream/movie/<id>.json
------------------------------------------
    cuevana:spider-man-de-regreso-a-casa
    cuevana:the-last-of-us:1:2          (temporada:episodio)
    movie:cuevana:spider-man-de-regreso-a-casa
    cuevana:tt0145487                   (IMDb → TMDB → slug, con TMDB_API_KEY)
    cuevana:spider man                  (texto libre: se busca en el sitio)
    https://cue.cuevana3.nu/peliculas/spider-man-de-regreso-a-casa

Uso desde la CLI
----------------
    python3 scrapers/cuevana.py --list
    python3 scrapers/cuevana.py --list --query "spider man" --json
    echo '{"type":"movie","id":"cuevana:spider-man-de-regreso-a-casa"}' \\
        | python3 scrapers/cuevana.py

Dependencias: sólo la librería estándar (vía _htmlkit.py, _peliskit.py, _hosters.py).
"""

from __future__ import annotations

import os
import re
import urllib.error
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _hosters import resolve_all
from _htmlkit import Deadline, clean_title, http_get, pretty_name, unescape_markup
from _peliskit import fallback_episode_servers, search_site

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------

LABEL = "Cuevana"
PROVIDER = "cuevana"

BASE_URL = os.getenv("CUEVANA_BASE", "https://cue.cuevana3.nu").rstrip("/")
SERIES_PATH = os.getenv("CUEVANA_SERIES_PATH", "/series/{slug}/{season}/{episode}")
MOVIE_PATH = os.getenv("CUEVANA_MOVIE_PATH", "/peliculas/{slug}")
SEARCH_PATH = os.getenv("CUEVANA_SEARCH_PATH", "/buscar?q={query}")

MAX_SERVERS = int(os.getenv("CUEVANA_MAX_SERVERS", "6"))
PARALLEL_SERVERS = int(os.getenv("CUEVANA_PARALLEL", "4"))

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
    "vimeos": "Vimeos",
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
        "script": "cuevana.py",
        "priority": 12,  # Prioridad similar a Gnula/Cuevana del servidor
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
        page = BASE_URL + SERIES_PATH.format(slug=slug, season=season, episode=episode)
    else:
        page = BASE_URL + MOVIE_PATH.format(slug=slug)

    html, final_url, _ = http_get(page, headers, deadline.remaining(6))

    if not html or any(marker in html.lower() for marker in BLOCK_MARKERS):
        log("cuevana blocked or empty:", final_url)
        return [], {}

    # Extraer servidores (basado en _peliskit.py / _hosters.py)
    servers: List[Dict[str, Any]] = []
    
    # Búsqueda de iframes y embeds en base64 (como en el servidor)
    for match in re.finditer(r'<iframe[^>]+src=["\']([^"\']+)["\']', html, re.IGNORECASE):
        url = match.group(1)
        if url.startswith(("http://", "https://")):
            # Si es base64 o embed, _hosters.py lo resolverá
            servers.append(
                server_entry(
                    url=url,
                    name="Cuevana Embed",
                    label="",
                    quality="",
                    referer=final_url,
                    user_agent=headers.get("User-Agent", DEFAULT_USER_AGENT),
                )
            )

    # También buscar URLs directas de reproductores en el texto (Streamwish, VOE, etc.)
    for match in re.finditer(r"(https?://[^\s\"'>]+(?:streamwish|voe|filemoon|mixdrop|mp4upload|streamtape|doodstream|okru|vimeos)[^\s\"'>]*)", html):
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
