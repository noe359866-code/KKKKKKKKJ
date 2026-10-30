#!/usr/bin/env python3
"""
scrapers/pelisjuanita.py
------------------------------------------------------------------------------
Proveedor real para **PelisJuanita** (https://pelisjuanita.org/ — películas, series, anime y eventos WWE).

Catálogo alimentado por TMDB (portadas, notas, reparto) con las fichas
en `/pelicula/<slug>` y `/serie/<slug>`, y también `/anime/` y `/wwe/`.

Cómo publica el sitio la señal
------------------------------
    1. Ficha del título:

           /pelicula/<slug>      (película)
           /serie/<slug>         (serie: los capítulos cuelgan de la ficha)
           /anime/<slug>         (anime)
           /wwe/<slug>           (eventos WWE)
           /ver/<slug>           (página "ver", último recurso)

       Para series se prueba primero la ruta del capítulo con dos dígitos
       (`/serie/<slug>/1x03`) y después `temporada/1/capitulo/3`.

    2. El reproductor se inyecta en el cliente, así que se recogen los
       `<iframe>`, los atributos `data-player`/`data-video`/`data-url`, los
       literales JS con la lista de servidores y `og:video`. Si la ficha de la
       serie no trae reproductores, se sigue el enlace del capítulo pedido
       (`1x05`, `temporada/1/capitulo/5`, `episodio-5`…) y se lee ahí.

    3. El sitio suele apuntar a **su propia** página de reproductor
       (`/player/<id>`, `/reproductor/<hash>`) y es ésa la que lista los embeds
       externos. Por eso el proveedor activa `expand_players`: si un servidor
       recogido es una página del propio sitio, se abre una vez más y se leen
       los embeds de dentro (`meta.opened_players` deja constancia).

    …y de resolver cada servidor (Streamwish, VOE, Filemoon, Streamtape,
    Mixdrop, Mp4upload…) se encarga `_hosters.py`, que ya conoce esas cadenas.

IDs aceptados en `/stream/movie/<id>.json`
------------------------------------------
    pelisjuanita:spider-man-un-nuevo-dia-2026
    pelisjuanita:spider-man-un-nuevo-dia-2026:1:2          (temporada:episodio, series)
    movie:pelisjuanita:spider-man-un-nuevo-dia-2026        (prefijo de tipo del addon)
    pelisjuanita:tt0145487                   (IMDb → TMDB → slug, con TMDB_API_KEY)
    pelisjuanita:spider man             (texto libre: se busca en el sitio)
    https://pelisjuanita.org/pelicula/spider-man-un-nuevo-dia-2026

Uso desde la CLI
----------------
    python3 scrapers/pelisjuanita.py --list
    python3 scrapers/pelisjuanita.py --list --query "spider man" --json
    echo '{"type":"movie","id":"pelisjuanita:spider-man-un-nuevo-dia-2026"}' \
        | python3 scrapers/pelisjuanita.py

Variables de entorno
--------------------
    PELISJUANITA_BASE     https://pelisjuanita.org
    PELISJUANITA_MIRRORS  otros dominios separados por comas
    TMDB_API_KEY   opcional, para ids IMDb

Dependencias: sólo la librería estándar (vía `_htmlkit`, `_peliskit`, `_hosters`).
"""

from __future__ import annotations

from typing import Any, Dict, List

from _base import run
from _peliskit import cli, env_bases, scrape_site

BASE = "https://pelisjuanita.org"


def _bases() -> List[str]:
    return env_bases(BASE, "PELISJUANITA_BASE", "PELISJUANITA_MIRRORS")


SITE: Dict[str, Any] = {
    "id": "pelisjuanita",
    "label": "PelisJuanita",
    "base": BASE,
    "home_path": "/",
    # El sitio separa el catálogo en secciones: películas, anime y WWE. Sin
    # estas rutas, un id de anime o de un evento WWE no abría nunca su ficha.
    "movie_paths": [
        "/pelicula/{slug}",
        "/anime/{slug}",
        "/wwe/{slug}",
        "/peliculas/{slug}",
        "/ver/{slug}",
    ],
    # `{episode2}` estrena la ruta real del sitio (`/serie/x/1x03`): un solo
    # pedido cuando el capítulo existe, y si no se cae a la ficha.
    "series_paths": [
        "/serie/{slug}/{season}x{episode2}",
        "/serie/{slug}/temporada/{season}/capitulo/{episode}",
        "/serie/{slug}",
        "/anime/{slug}",
    ],
    "search_paths": ["/buscar?q={query}", "/search?q={query}"],
    "search_hints": ["/pelicula/", "/peliculas/", "/serie/", "/anime/", "/wwe/", "/ver/"],
    "player_hints": ("embed", "player", "repro", "reproductor", "video", "stream",
                     "capitulo", "episodio"),
    # Abre la página de reproductor del propio sitio (una vez, como mucho dos).
    "expand_players": 2,
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
