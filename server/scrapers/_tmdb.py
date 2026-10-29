"""
scrapers/_tmdb.py
------------------------------------------------------------------------------
Resolución **opcional** de títulos con TMDB.

¿Para qué? Los sitios de películas se identifican por slug
(`gnula:spider-man-un-nuevo-dia-2026`), pero Stremio suele enviar ids IMDb
(`gnula:tt0145487`) o títulos. TMDB traduce un id IMDb → título + año, y
título → título en español + año, que es justo lo que hace falta para acertar
el slug.

Sin clave no se rompe nada: cada función devuelve `{}` y el proveedor sigue con
el slug que traía el id (o con la búsqueda del propio sitio).

La clave se lee de `TMDB_API_KEY` (entorno) o de `tmdbApiKey` en el payload
(lo puede inyectar `config/providers.json` → `options`).
"""

from __future__ import annotations

import os
import urllib.parse
from typing import Any, Dict, Optional

from _base import log
from _htmlkit import Deadline, http_get_json, normalize

TMDB_BASE = "https://api.themoviedb.org/3"
LANGUAGE = "es-MX"
_TIMEOUT = 5.0


def api_key(payload: Optional[Dict[str, Any]] = None) -> str:
    """Clave de TMDB, si el usuario configuró una."""
    payload = payload or {}
    return str(
        payload.get("tmdbApiKey")
        or os.environ.get("TMDB_API_KEY")
        or os.environ.get("TMDB_APIKEY")
        or ""
    ).strip()


def _pick(entry: Dict[str, Any], media_type: str) -> Dict[str, Any]:
    """Normaliza una ficha de TMDB a lo que necesitan los proveedores."""
    if not isinstance(entry, dict):
        return {}
    title = (
        entry.get("title")
        or entry.get("name")
        or entry.get("original_title")
        or entry.get("original_name")
        or ""
    )
    date = entry.get("release_date") or entry.get("first_air_date") or ""
    year = str(date)[:4] if date else ""
    return {
        "title": str(title).strip(),
        "original_title": str(entry.get("original_title") or entry.get("original_name") or "").strip(),
        "year": year,
        "media_type": media_type or ("tv" if entry.get("first_air_date") else "movie"),
        "tmdb_id": entry.get("id"),
        "overview": str(entry.get("overview") or "").strip(),
    }


def find_by_imdb(
    imdb_id: str,
    media_type: str = "movie",
    key: Optional[str] = None,
    deadline: Optional[Deadline] = None,
) -> Dict[str, Any]:
    """`tt0145487` → `{title, original_title, year, media_type}`."""
    key = key or api_key()
    if not key or not imdb_id:
        return {}
    query = urllib.parse.urlencode({"external_source": "imdb_id", "language": LANGUAGE})
    url = f"{TMDB_BASE}/find/{urllib.parse.quote(imdb_id)}?{query}&api_key={key}"
    try:
        data = http_get_json(url, {"Accept": "application/json"}, _remaining(deadline))
    except Exception as exc:  # noqa: BLE001 - TMDB es opcional
        log("tmdb find failed:", exc)
        return {}
    if not isinstance(data, dict):
        return {}
    bucket = "tv_results" if str(media_type).lower() in ("tv", "series") else "movie_results"
    for entry in data.get(bucket) or []:
        found = _pick(entry, "tv" if bucket == "tv_results" else "movie")
        if found.get("title"):
            return found
    # El id puede ser de una serie aunque el cliente diga "movie": se prueba el otro lado.
    other = "movie_results" if bucket == "tv_results" else "tv_results"
    for entry in data.get(other) or []:
        found = _pick(entry, "movie" if other == "movie_results" else "tv")
        if found.get("title"):
            return found
    return {}


def search(
    title: str,
    year: Optional[str] = None,
    media_type: str = "movie",
    key: Optional[str] = None,
    deadline: Optional[Deadline] = None,
) -> Dict[str, Any]:
    """Título en español del sitio a partir del título que mande el cliente."""
    key = key or api_key()
    if not key or not (title or "").strip():
        return {}
    section = "tv" if str(media_type).lower() in ("tv", "series") else "movie"
    params = {"query": title, "language": LANGUAGE, "include_adult": "false"}
    if year and str(year)[:4].isdigit():
        params["year" if section == "movie" else "first_air_date_year"] = str(year)[:4]
    url = f"{TMDB_BASE}/search/{section}?{urllib.parse.urlencode(params)}&api_key={key}"
    try:
        data = http_get_json(url, {"Accept": "application/json"}, _remaining(deadline))
    except Exception as exc:  # noqa: BLE001
        log("tmdb search failed:", exc)
        return {}
    results = (data or {}).get("results") if isinstance(data, dict) else None
    for entry in results or []:
        found = _pick(entry, section)
        if found.get("title"):
            return found
    return {}


def describe(query: str) -> str:
    """Texto de diagnóstico (nunca incluye la clave)."""
    return " ".join(normalize(query or "").split())


def _remaining(deadline: Optional[Deadline]) -> float:
    if deadline is None:
        return _TIMEOUT
    return max(1.0, min(_TIMEOUT, deadline.remaining(_TIMEOUT)))


__all__ = ["api_key", "find_by_imdb", "search", "describe", "TMDB_BASE"]
