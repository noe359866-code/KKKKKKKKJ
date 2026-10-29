#!/usr/bin/env python3
"""
scrapers/comodin.py
------------------------------------------------------------------------------
Proveedor para **Comodín Libre** (dominio configurable).

Este sitio entra por la puerta genérica: no publica el reproductor en un solo
lugar conocido, así que se recoge todo lo que la ficha exponga —`<iframe>`,
`data-src`, `data-player`, `data-video`, literales JS `var servers`, `og:video`—
y cada servidor se resuelve con `_hosters.py` (Streamwish, VOE, Filemoon,
Streamtape, Mixdrop, Mp4upload…).

⚠️  Falta el dominio
--------------------
No hay un dominio verificado de "Comodín Libre": `comodinlibre.com` y
`comodinlibre.net` no resuelven y el nombre no aparece en los índices de sitios
de cine. Por eso el proveedor **no trae una base por defecto**: hasta que se
configure, responde `streams: []` con `meta.reason: "not_configured"` y el addon
sigue con el resto de proveedores.

Para activarlo, basta con una variable de entorno:

    COMODIN_BASE=https://comodin.ejemplo
    COMODIN_MIRRORS=https://comodin2.ejemplo,https://comodin3.ejemplo

(Está registrado con `"enabled": false` en `config/providers.json`; cámbialo a
`true` cuando la base esté puesta.)

IDs aceptados una vez configurado
---------------------------------
    comodin:spider-man-de-regreso-a-casa
    comodin:spider-man-de-regreso-a-casa:1:2   (temporada:episodio)
    movie:comodin:spider-man-de-regreso-a-casa
    comodin:tt0145487                          (IMDb → TMDB, con TMDB_API_KEY)
    comodin:spider man                         (texto libre: se busca en el sitio)

Uso desde la CLI
----------------
    COMODIN_BASE=https://… python3 scrapers/comodin.py --list
    COMODIN_BASE=https://… python3 scrapers/comodin.py --list --query "spider man" --json
    echo '{"type":"movie","id":"comodin:spider-man-de-regreso-a-casa"}' \\
        | COMODIN_BASE=https://… python3 scrapers/comodin.py

Dependencias: sólo la librería estándar (vía `_htmlkit`, `_peliskit`, `_hosters`).
"""

from __future__ import annotations

from typing import Any, Dict, List

from _base import run
from _peliskit import cli, env_bases, scrape_site

#: Sin base no hay proveedor: se configura por entorno.
BASE = ""


def _bases() -> List[str]:
    return env_bases(BASE, "COMODIN_BASE", "COMODIN_MIRRORS")


SITE: Dict[str, Any] = {
    "id": "comodin",
    "label": "Comodín Libre",
    "base": BASE,
    "home_path": "/",
    "movie_paths": ["/pelicula/{slug}", "/peliculas/{slug}", "/ver/{slug}", "/movie/{slug}"],
    "series_paths": [
        "/serie/{slug}/temporada/{season}/capitulo/{episode}",
        "/serie/{slug}/{season}/{episode}",
        "/serie/{slug}",
        "/series/{slug}",
    ],
    "search_paths": ["/buscar?q={query}", "/search?s={query}", "/search?q={query}", "/?s={query}"],
    "search_hints": ["/pelicula/", "/peliculas/", "/serie/", "/series/", "/ver/"],
    "player_hints": ("embed", "player", "repro", "capitulo", "episodio"),
    "max_servers": 6,
    "parallel": 4,
    "tmdb": True,
}


def scrape(payload: Dict[str, Any]) -> Any:
    """Sin dominio configurado no se hace ni una petición."""
    if payload.get("isLive"):
        return [], {"reason": "not_vod", "site": "comodin"}
    bases = _bases()
    if not bases:
        return [], {
            "reason": "not_configured",
            "site": "comodin",
            "hint": "define COMODIN_BASE (y opcionalmente COMODIN_MIRRORS)",
        }
    return scrape_site(payload, {**SITE, "base": bases[0], "bases": bases})


def main() -> None:
    bases = _bases()
    if cli({**SITE, "base": bases[0] if bases else "", "bases": bases}):
        return
    run(scrape)


if __name__ == "__main__":
    main()
