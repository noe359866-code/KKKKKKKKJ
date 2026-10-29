#!/usr/bin/env python3
"""
scrapers/pelisplushd.py
------------------------------------------------------------------------------
Proveedor real para **PelisPlus** (https://www.pelisplushd.la/ (antes pelisplus.lat) — películas, series y anime en HD latino).

También conocido como **PelisPlusHD**. Es el sitio con la ficha más
clásica de todos: una página por película y otra por capítulo, con los
reproductores repartidos en pestañas de idioma.

Cómo publica el sitio la señal
------------------------------
    1. Ficha de la película:

           /pelicula/<slug>

       Ficha del capítulo (una URL por temporada y episodio):

           /serie/<slug>/temporada/<temporada>/capitulo/<episodio>

    2. Cada pestaña de idioma es un botón con la URL del embed en un atributo:

           <li class="player-option" data-player="https://streamwish.to/e/…">
           <a data-option="aHR0cHM6Ly92b2Uuc3gvZS8…">   (a veces, en base64)

       Se leen además los `<iframe>`, `data-src` de carga diferida y cualquier
       literal JS con la lista de servidores (`var servers = [...]`).

    …y de resolver cada servidor (Streamwish, VOE, Filemoon, Streamtape,
    Mixdrop, Mp4upload…) se encarga `_hosters.py`, que ya conoce esas cadenas.

IDs aceptados en `/stream/movie/<id>.json`
------------------------------------------
    pelisplushd:spider-man-de-regreso-a-casa
    pelisplushd:spider-man-de-regreso-a-casa:1:2          (temporada:episodio, series)
    movie:pelisplushd:spider-man-de-regreso-a-casa        (prefijo de tipo del addon)
    pelisplushd:tt0145487                   (IMDb → TMDB → slug, con TMDB_API_KEY)
    pelisplushd:spider man             (texto libre: se busca en el sitio)
    https://www.pelisplushd.la/pelicula/spider-man-de-regreso-a-casa

Uso desde la CLI
----------------
    python3 scrapers/pelisplushd.py --list
    python3 scrapers/pelisplushd.py --list --query "spider man" --json
    echo '{"type":"movie","id":"pelisplushd:spider-man-de-regreso-a-casa"}' \
        | python3 scrapers/pelisplushd.py

Variables de entorno
--------------------
    PELISPLUSHD_BASE     https://www.pelisplushd.la
    PELISPLUSHD_MIRRORS  otros dominios separados por comas
    TMDB_API_KEY   opcional, para ids IMDb

Dependencias: sólo la librería estándar (vía `_htmlkit`, `_peliskit`, `_hosters`).
"""

from __future__ import annotations

from typing import Any, Dict, List

from _base import run
from _peliskit import cli, env_bases, scrape_site

BASE = "https://www.pelisplushd.la"


def _bases() -> List[str]:
    return env_bases(BASE, "PELISPLUSHD_BASE", "PELISPLUSHD_MIRRORS")


SITE: Dict[str, Any] = {
    "id": "pelisplushd",
    "label": "PelisPlus",
    "base": BASE,
    "home_path": "/",
    "movie_paths": ["/pelicula/{slug}", "/peliculas/{slug}"],
    "series_paths": [
        "/serie/{slug}/temporada/{season}/capitulo/{episode}",
        "/serie/{slug}/temporada-{season}/capitulo-{episode}",
        "/serie/{slug}/{season}/{episode}",
        "/serie/{slug}",
    ],
    "search_paths": ["/search?s={query}", "/buscar?s={query}", "/search?q={query}"],
    "search_hints": ["/pelicula/", "/serie/", "/anime/"],
    "player_hints": ("embed", "player", "repro", "capitulo", "episodio"),
    "max_servers": 6,
    "parallel": 4,
    "tmdb": True,
}


def scrape(payload: Dict[str, Any]) -> Any:
    return scrape_site(payload, {**SITE, "base": _bases()[0], "bases": _bases()})


def main() -> None:
    site = {**SITE, "base": _bases()[0], "bases": _bases()}
    if cli(site):
        return
    run(scrape)


if __name__ == "__main__":
    main()
