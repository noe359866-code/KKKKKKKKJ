#!/usr/bin/env python3
"""
scrapers/repelis.py
------------------------------------------------------------------------------
Proveedor real para **Repelis** (dominio configurable: el histórico (repelis.live) ya no responde, así que REPELIS_BASE / REPELIS_MIRRORS deciden a dónde se pide).

Repelis es un nombre muy replicado: cada pocos meses cambia de dominio y
conviven varios clones con el mismo motor. Por eso este proveedor no lleva el
dominio grabado a fuego: prueba `REPELIS_BASE` y luego `REPELIS_MIRRORS` (lista
separada por comas) hasta que una ficha responda.

    REPELIS_BASE=https://repelis.example
    REPELIS_MIRRORS=https://repelis2.example,https://repelis3.example

Cómo publica el sitio la señal
------------------------------
    1. Ficha del título:

           /pelicula/<slug>      (película)
           /serie/<slug>/…       (serie, un capítulo por URL)

    2. Los reproductores aparecen como `<iframe>` (también en `data-src`),
       botones `data-player`/`data-option` o literales JS `var servers`; se
       recogen todos y, si la ficha de la serie no trae ninguno, se sigue el
       enlace del capítulo pedido.

    …y de resolver cada servidor (Streamwish, VOE, Filemoon, Streamtape,
    Mixdrop, Mp4upload…) se encarga `_hosters.py`, que ya conoce esas cadenas.

IDs aceptados en `/stream/movie/<id>.json`
------------------------------------------
    repelis:spider-man-de-regreso-a-casa
    repelis:spider-man-de-regreso-a-casa:1:2          (temporada:episodio, series)
    movie:repelis:spider-man-de-regreso-a-casa        (prefijo de tipo del addon)
    repelis:tt0145487                   (IMDb → TMDB → slug, con TMDB_API_KEY)
    repelis:spider man             (texto libre: se busca en el sitio)
    https://repelis.live/pelicula/spider-man-de-regreso-a-casa

Uso desde la CLI
----------------
    python3 scrapers/repelis.py --list
    python3 scrapers/repelis.py --list --query "spider man" --json
    echo '{"type":"movie","id":"repelis:spider-man-de-regreso-a-casa"}' \
        | python3 scrapers/repelis.py

Variables de entorno
--------------------
    REPELIS_BASE     https://repelis.live
    REPELIS_MIRRORS  otros dominios separados por comas
    TMDB_API_KEY   opcional, para ids IMDb

Dependencias: sólo la librería estándar (vía `_htmlkit`, `_peliskit`, `_hosters`).
"""

from __future__ import annotations

from typing import Any, Dict, List

from _base import run
from _peliskit import cli, env_bases, scrape_site

#: `repelis.live` (el histórico) ya no responde. El 2026-10-30 respondía
#: `www.repelis.net`; sigue siendo el proveedor más frágil de todos, así que la
#: lista de espejos se prueba en orden y `REPELIS_BASE`/`REPELIS_MIRRORS` mandan.
BASE = "https://www.repelis.net"

#: Clones conocidos con el mismo motor (ficha `/pelicula/<slug>`).
LEGACY_BASES = (
    "https://repelishd.run",
    "https://repelis.ink",
    "https://repelis.live",
)


def _bases() -> List[str]:
    """Dominio configurado (+ espejos) o, sin configuración, toda la familia."""
    bases = env_bases(BASE, "REPELIS_BASE", "REPELIS_MIRRORS")
    explicit = len(bases) > 1 or bases[0] != BASE
    if not explicit:
        for base in LEGACY_BASES:
            if base not in bases:
                bases.append(base)
    return bases


SITE: Dict[str, Any] = {
    "id": "repelis",
    "label": "Repelis",
    "base": BASE,
    "home_path": "/",
    "movie_paths": ["/pelicula/{slug}", "/peliculas/{slug}", "/ver/{slug}"],
    "series_paths": [
        "/serie/{slug}/temporada/{season}/capitulo/{episode}",
        "/serie/{slug}/{season}x{episode2}",
        "/serie/{slug}/{season}/{episode}",
        "/serie/{slug}",
    ],
    "search_paths": ["/search?s={query}", "/buscar?s={query}", "/search?q={query}"],
    "search_hints": ["/pelicula/", "/peliculas/", "/serie/", "/series/", "/ver/"],
    "player_hints": ("embed", "player", "repro", "capitulo", "episodio"),
    "max_servers": 6,
    "parallel": 4,
    "tmdb": True,
}


def scrape(payload: Dict[str, Any]) -> Any:
    bases = _bases()
    streams, meta = scrape_site(payload, {**SITE, "base": bases[0], "bases": bases})
    if not streams and len(bases) == 1:
        # Un solo dominio y vacío: si además está caído, el motivo lo dice.
        meta["hint"] = "dominio por defecto caído: define REPELIS_BASE o REPELIS_MIRRORS"
    elif not streams:
        meta["tried_bases"] = bases
    return streams, meta


def main() -> None:
    site = {**SITE, "base": _bases()[0], "bases": _bases()}
    if cli(site):
        return
    run(scrape)


if __name__ == "__main__":
    main()
