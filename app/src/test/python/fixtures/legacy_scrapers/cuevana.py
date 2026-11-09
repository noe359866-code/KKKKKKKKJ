#!/usr/bin/env python3
"""
scrapers/cuevana.py
------------------------------------------------------------------------------
Proveedor real para **Cuevana** (https://cue.cuevana3.nu/): películas y series
en latino, subtitulado y castellano.

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

IDs aceptados en `/stream/movie/<id>.json`
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

Variables de entorno
--------------------
    CUEVANA_BASE     https://cue.cuevana3.nu
    CUEVANA_MIRRORS  otros dominios separados por comas
    TMDB_API_KEY     opcional, para ids IMDb

Dependencias: sólo la librería estándar (vía `_htmlkit`, `_peliskit`, `_hosters`).
"""

from __future__ import annotations

import re
import urllib.parse
from typing import Any, Dict, List

from _animekit import decode_maybe_base64, server_entry
from _base import log, run
from _htmlkit import Deadline
from _peliskit import cli, env_bases, fetch_html, harvest_servers, join, scrape_site

#: Cuevana3 se replica en decenas de dominios y cada pocos meses cambia; el que
#: estaba por defecto (`cue.cuevana3.nu`) no respondió el 2026-10-30 y este sí
#: (misma estructura `/peliculas/<slug>-<año>/` con `showEmbed`). `CUEVANA_BASE`
#: y `CUEVANA_MIRRORS` siguen mandando.
BASE = "https://cuevana3.gs"

#: Espejos conocidos de la misma familia, por si el primero cae.
LEGACY_BASES = (
    "https://cuevana-3.fun",
    "https://cuevana.gs",
    "https://cue.cuevana3.nu",
)

#: `showEmbed=<base64>` — el embed real, en base64 dentro de la URL.
SHOW_EMBED_RE = re.compile(r"""showEmbed=([A-Za-z0-9+/=_\-%]{8,})""", re.IGNORECASE)
#: `<iframe src="…/player.php?t=<token>">` — versiones con token opaco.
PLAYER_TOKEN_RE = re.compile(r"""player\.php\?[^"'\s>]*?\bt=([A-Za-z0-9_\-]{16,})""", re.IGNORECASE)


def _bases() -> List[str]:
    """Dominio configurado (+ espejos) o, si nadie configuró nada, con la familia."""
    bases = env_bases(BASE, "CUEVANA_BASE", "CUEVANA_MIRRORS")
    explicit = len(bases) > 1 or bases[0] != BASE
    if not explicit:
        for base in LEGACY_BASES:
            if base not in bases:
                bases.append(base)
    return bases


def show_embed_servers(html: str) -> List[Dict[str, Any]]:
    """Decodifica los `showEmbed` de la ficha."""
    servers: List[Dict[str, Any]] = []
    for match in SHOW_EMBED_RE.finditer(html or ""):
        token = urllib.parse.unquote(match.group(1)).strip()
        url = decode_maybe_base64(token)
        if url.startswith("//"):
            url = "https:" + url
        if not url.lower().startswith(("http://", "https://")):
            continue
        servers.append(server_entry("", url))
    return servers


def token_servers(html: str, page_url: str, headers: Dict[str, str], deadline: Deadline) -> List[Dict[str, Any]]:
    """Sigue `player.php?t=<token>` hasta su `<iframe src>`."""
    servers: List[Dict[str, Any]] = []
    tokens = []
    for match in PLAYER_TOKEN_RE.finditer(html or ""):
        if match.group(1) not in tokens:
            tokens.append(match.group(1))
    for token in tokens[:3]:
        if deadline.expired():
            break
        url = join(_origin(page_url), f"/player.php?{urllib.parse.urlencode({'t': token})}")
        body, final_url = fetch_html(url, {**headers, "Referer": page_url}, deadline)
        if not body:
            continue
        servers.extend(harvest_servers(body, final_url or url, {"skip_hosts": ("player.php",)}))
    return servers


def _origin(url: str) -> str:
    parsed = urllib.parse.urlparse(url)
    if not parsed.netloc:
        return _bases()[0]
    return f"{parsed.scheme}://{parsed.netloc}"


def extras(html: str, page_url: str, headers: Dict[str, str], deadline: Deadline) -> List[Dict[str, Any]]:
    """Servidores que sólo se ven al decodificar `showEmbed` o al seguir el token."""
    servers = show_embed_servers(html)
    if not servers:
        log("cuevana: no showEmbed, probando player.php")
        servers = token_servers(html, page_url, headers, deadline)
    return servers


#: Metadatos del sitio. **Sin `base`**: el dominio se resuelve al usarlo
#: ([`site()`]), no al importar. Un `SITE["base"]` congelado en el import se
#: queda con el dominio (o el espejo) que hubiera en ese instante, y un
#: consumidor que leyera el diccionario directamente acabaría yendo a un host
#: viejo mientras `scrape()` sí usa el bueno.
SITE: Dict[str, Any] = {
    "id": "cuevana",
    "label": "Cuevana",
    "home_path": "/",
    "movie_paths": ["/peliculas/{slug}", "/pelicula/{slug}", "/ver/{slug}"],
    "series_paths": [
        "/series/{slug}/season/{season}/episode/{episode}",
        "/series/{slug}/temporada/{season}/capitulo/{episode}",
        "/series/{slug}/{season}x{episode2}",
        "/series/{slug}/{season}/{episode}",
        "/serie/{slug}/{season}/{episode}",
        "/series/{slug}",
    ],
    "search_paths": ["/?s={query}", "/search?q={query}"],
    "search_hints": ["/peliculas/", "/pelicula/", "/series/", "/serie/", "/ver/"],
    "player_hints": ("embed", "player", "repro", "vimeos", "streamwish"),
    "max_servers": 6,
    "parallel": 4,
    "tmdb": True,
    "extras": extras,
}


def site() -> Dict[str, Any]:
    """`SITE` con el dominio resuelto **ahora** (env + espejos), listo para usar.

    Es una función y no un valor: `CUEVANA_BASE`/`CUEVANA_MIRRORS` se leen en
    cada llamada, así que un despliegue puede cambiar de espejo sin reinstalar y
    un test puede apuntar el scraper a su fixture sin tocar el import.
    """
    bases = _bases()
    return {**SITE, "base": bases[0], "bases": bases}


def scrape(payload: Dict[str, Any]) -> Any:
    return scrape_site(payload, site())


def main() -> None:
    site_config = site()
    if cli(site_config):
        return
    run(scrape)


if __name__ == "__main__":
    main()
