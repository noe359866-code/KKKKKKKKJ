#!/usr/bin/env python3
"""
scrapers/novahd.py
------------------------------------------------------------------------------
Proveedor **multilingüe** para **NOVA / NovaHD** (https://novahd.cc).

Qué se comprobó del sitio (2026-10-30, ver `RECON.md`)
------------------------------------------------------
NOVA es una SPA con un backend Fastify propio y **público**; nada de eso exige
navegador ni cuenta:

    GET /api/sources?tmdbId=155&type=movie
    GET /api/sources?tmdbId=1396&type=show&season=1&episode=1
    GET /api/health

La respuesta es lo interesante, porque es lo que este proveedor existe para
publicar: **varias señales por título y en varios idiomas**.

    {
      "sources": [
        {"url": "https://nova-edge-…/master.m3u8", "type": "hls",
         "quality": "FHD", "provider": "Swift", "language": "English",
         "name": "Swift · OrionC", "origAudio": true, "codecs": "hevc"},
        {"url": "https://nova-edge-…/?e=…", "type": "hls", "quality": "720p",
         "provider": "Orion", "language": "", …}
      ],
      "subtitles": [{"url": "https://…/vtt?e=…", "label": "Chinese", "lang": "zh"}]
    }

Notas que condicionan el diseño:

- `tmdbId` **tiene que ser numérico** (`^[0-9]{1,8}$`): la API rechaza IMDb.
  Como el addon recibe ids IMDb (`tt0468569`), se traducen con **Cinemeta**
  (`moviedb_id`), que no necesita clave, y —cuando sólo llega el título, como
  hace el motor del APK— con su buscador (`/catalog/{tipo}/top/search=…`).
- `type` sólo acepta `movie` y `show` (probado: `series`, `tv` → 400).
- Algunas URLs no llevan extensión (`/…/?e=…`), así que el `type` de la API se
  publica como `behaviorHints.mediaType`; sin eso ExoPlayer intentaría abrir un
  HLS como MP4.
- Las URLs van firmadas **con la IP del cliente** (`&ip=…&ipsig=…`): se piden en
  el momento de reproducir, nunca se inventan ni se reescriben.

Uso desde la CLI
----------------
    python3 scrapers/novahd.py --sources 155          # The Dark Knight
    python3 scrapers/novahd.py --sources tt0468569     # vía Cinemeta
    python3 scrapers/novahd.py --sources 1396 --season 1 --episode 1
    echo '{"type":"movie","id":"tt0468569"}' | python3 scrapers/novahd.py

Variables de entorno (todas opcionales)
---------------------------------------
    NOVAHD_BASE          https://novahd.cc
    NOVAHD_MIRRORS       espejos separados por comas
    CINEMETA_BASE        https://v3-cinemeta.strem.io
    NOVAHD_MAX_STREAMS   12
    NOVAHD_LANGUAGES     "Español,Latino,Castellano,Spanish"  (orden de preferencia)
    NOVAHD_ONLY_LANGUAGES "" (si se define, sólo se publican esas)
"""

from __future__ import annotations

import json
import os
import re
import urllib.error
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run, stream
from _htmlkit import Deadline, http_get, http_get_json, media_kind_of_url, normalize

LABEL = "NOVA"
PROVIDER = "novahd"

BASE_URL = os.getenv("NOVAHD_BASE", "https://novahd.cc").rstrip("/")
CINEMETA = os.getenv("CINEMETA_BASE", "https://v3-cinemeta.strem.io").rstrip("/")
SOURCES_PATH = "/api/sources"

MAX_STREAMS = int(os.getenv("NOVAHD_MAX_STREAMS", "12"))
DEFAULT_PREFERRED = "Español,Latino,Castellano,Spanish"

#: Para `_embedded_catalogs.site_for()` (el motor del APK lee esto).
SITE = {"base": BASE_URL, "search_hints": ()}

#: La API sólo acepta estos dos valores de `type`.
QUERY_TYPES = {"movie": "movie", "show": "show"}

#: Nombre del idioma en español, para que la lista se lea en el televisor.
LANGUAGES = {
    "english": "Inglés", "en": "Inglés",
    "spanish": "Español", "es": "Español", "es-la": "Español (Latino)", "latino": "Español (Latino)",
    "portuguese": "Portugués", "pt": "Portugués", "pt-br": "Portugués (Brasil)",
    "french": "Francés", "fr": "Francés",
    "italian": "Italiano", "it": "Italiano",
    "german": "Alemán", "de": "Alemán",
    "japanese": "Japonés", "ja": "Japonés",
    "korean": "Coreano", "ko": "Coreano",
    "chinese": "Chino", "zh": "Chino", "zh-cn": "Chino", "cantonese": "Cantonés",
    "hindi": "Hindi", "hi": "Hindi",
    "arabic": "Árabe", "ar": "Árabe",
    "russian": "Ruso", "ru": "Ruso",
    "turkish": "Turco", "tr": "Turco",
    "thai": "Tailandés", "th": "Tailandés",
    "indonesian": "Indonesio", "id": "Indonesio",
    "vietnamese": "Vietnamita", "vi": "Vietnamita",
    "polish": "Polaco", "pl": "Polaco",
    "dutch": "Neerlandés", "nl": "Neerlandés",
    "swedish": "Sueco", "sv": "Sueco",
    "danish": "Danés", "da": "Danés",
    "norwegian": "Noruego", "no": "Noruego",
    "finnish": "Finés", "fi": "Finés",
    "greek": "Griego", "el": "Griego",
    "hebrew": "Hebreo", "he": "Hebreo",
    "czech": "Checo", "cs": "Checo",
    "hungarian": "Húngaro", "hu": "Húngaro",
    "romanian": "Rumano", "ro": "Rumano",
    "ukrainian": "Ucraniano", "uk": "Ucraniano",
    "filipino": "Filipino", "tl": "Filipino",
    "malay": "Malayo", "ms": "Malayo",
    "tamil": "Tamil", "ta": "Tamil",
    "telugu": "Telugu", "te": "Telugu",
}

#: Orden de calidad para que el primer stream sea el mejor (Play = reproduce).
QUALITY_RANK = {
    "4k": 5, "2160p": 5, "1080p": 4, "fhd": 4, "720p": 3, "hd": 3,
    "480p": 2, "sd": 2, "360p": 1,
}


def _bases() -> List[str]:
    """Dominios del sitio; `NOVAHD_BASE` manda y nunca se cae al real."""
    explicit = (os.getenv("NOVAHD_BASE") or "").strip().rstrip("/")
    bases = [explicit] if explicit else [BASE_URL]
    for value in (os.getenv("NOVAHD_MIRRORS", "") or "").split(","):
        cleaned = value.strip().rstrip("/")
        if cleaned and cleaned not in bases:
            bases.append(cleaned)
    return bases


def _headers(payload: Dict[str, Any]) -> Dict[str, str]:
    headers = default_headers(payload)
    headers.setdefault("Accept", "application/json, text/plain, */*")
    if not headers.get("Referer"):
        headers["Referer"] = f"{BASE_URL}/"
    return headers


def preferred_languages(payload: Optional[Dict[str, Any]] = None) -> List[str]:
    """Idiomas que se ponen primero, en orden (`NOVAHD_LANGUAGES`)."""
    raw = str(
        (payload or {}).get("novahdLanguages")
        or os.getenv("NOVAHD_LANGUAGES", DEFAULT_PREFERRED)
    )
    return [normalize(item) for item in raw.split(",") if item.strip()]


def only_languages(payload: Optional[Dict[str, Any]] = None) -> List[str]:
    """Filtro opcional: si está vacío se publican todos los idiomas."""
    raw = str(
        (payload or {}).get("novahdOnlyLanguages")
        or os.getenv("NOVAHD_ONLY_LANGUAGES", "")
    )
    return [normalize(item) for item in raw.split(",") if item.strip()]


# ---------------------------------------------------------------------------
# id del cliente → tmdbId de NOVA
# ---------------------------------------------------------------------------

_URL_RE = re.compile(r"^/(?P<kind>[ms])/(?P<id>\d+)(?:$|[/?#])", re.IGNORECASE)
_IMDB_RE = re.compile(r"^tt\d{5,9}$", re.IGNORECASE)
_TMDB_RE = re.compile(r"^\d{1,8}$")
_KIND_TOKENS = {"movie": "movie", "movies": "movie", "film": "movie", "pelicula": "movie",
                "película": "movie", "show": "show", "shows": "show", "series": "show",
                "serie": "show", "tv": "show"}


def parse_content_id(value: str, media_type: str = "") -> Dict[str, str]:
    """`novahd:155`, `movie:155`, `tt0468569`, `https://novahd.cc/m/155` o título.

    Devuelve ``{"kind": tmdb|imdb|title, "value": …, "type": movie|show}``.
    """
    text = str(value or "").strip()
    media = "show" if str(media_type).lower() in ("series", "show", "tv", "anime") else "movie"

    # Los ids pueden venir encadenados (`tv:novahd:155`): se quitan todos.
    for _ in range(3):
        lowered = text.lower()
        for prefix in ("novahd:", "tv:", "vod:"):
            if lowered.startswith(prefix):
                text = text[len(prefix):].strip()
                break
        else:
            break

    match = _URL_RE.search(urllib.parse.urlsplit(text).path or "") if text.lower().startswith("http") else None
    if match:
        return {"kind": "tmdb", "value": match.group("id"),
                "type": "show" if match.group("kind").lower() == "s" else "movie"}

    # `movie:155` / `show:1396` — el tipo explícito gana al del payload.
    if ":" in text:
        head, _, rest = text.partition(":")
        head_type = _KIND_TOKENS.get(normalize(head))
        if head_type and rest.strip():
            text = rest.strip()
            media = head_type
            lowered = text.lower()

    text = text.strip().strip("/")
    if _TMDB_RE.match(text):
        return {"kind": "tmdb", "value": text, "type": media}
    if _IMDB_RE.match(text):
        return {"kind": "imdb", "value": text.lower(), "type": media}
    return {"kind": "title", "value": text, "type": media}


def cinemeta_meta(imdb_id: str, media_type: str, deadline: Deadline) -> Dict[str, Any]:
    """`tt0468569` → meta de Cinemeta (trae `moviedb_id`, sin clave)."""
    kind = "series" if media_type == "show" else "movie"
    url = f"{CINEMETA}/meta/{kind}/{urllib.parse.quote(imdb_id)}.json"
    try:
        data = http_get_json(url, {"Accept": "application/json"}, deadline.remaining(6))
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("cinemeta meta failed:", exc)
        return {}
    meta = (data or {}).get("meta") if isinstance(data, dict) else None
    return meta if isinstance(meta, dict) else {}


def tmdb_from_imdb(imdb_id: str, media_type: str, deadline: Deadline) -> str:
    """id TMDB a partir del IMDb, vía Cinemeta (probando el otro tipo si falla)."""
    for kind in ([media_type, "movie" if media_type == "show" else "show"]):
        meta = cinemeta_meta(imdb_id, kind, deadline)
        value = meta.get("moviedb_id") or meta.get("tmdb_id") or meta.get("moviedbId")
        if value:
            return str(value).strip()
    return ""


def imdb_from_title(title: str, media_type: str, deadline: Deadline) -> Tuple[str, Dict[str, Any]]:
    """Busca el título en el catálogo de Cinemeta y devuelve el mejor `tt…`."""
    kind = "series" if media_type == "show" else "movie"
    query = urllib.parse.quote(title)
    url = f"{CINEMETA}/catalog/{kind}/top/search={query}.json"
    try:
        data = http_get_json(url, {"Accept": "application/json"}, deadline.remaining(6))
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("cinemeta search failed:", exc)
        return "", {}

    metas = [item for item in (data or {}).get("metas") or [] if isinstance(item, dict)]
    if not metas:
        return "", {}
    wanted = normalize(title)
    best = None
    for item in metas:
        name = normalize(str(item.get("name") or ""))
        identifier = str(item.get("imdb_id") or item.get("id") or "")
        if not _IMDB_RE.match(identifier):
            continue
        if name == wanted:
            best = item
            break
        if best is None and (name.startswith(wanted) or wanted in name):
            best = item
    best = best or next((item for item in metas if _IMDB_RE.match(str(item.get("id") or ""))), None)
    return (str(best.get("imdb_id") or best.get("id") or "") if best else ""), (best or {})


def resolve_tmdb(
    request: Dict[str, str],
    payload: Dict[str, Any],
    deadline: Deadline,
    meta: Dict[str, Any],
) -> str:
    """Lleva el id del cliente al `tmdbId` numérico que exige la API."""
    kind, value = request["kind"], request["value"]
    if kind == "tmdb":
        return value

    # El payload del servidor trae el IMDb explícito; úsalo si el id es un título.
    imdb = str(payload.get("imdbId") or payload.get("baseId") or "").strip()
    if not _IMDB_RE.match(imdb) and kind == "imdb":
        imdb = value
    if _IMDB_RE.match(imdb):
        found = tmdb_from_imdb(imdb, request["type"], deadline)
        meta["imdb"] = imdb
        if found:
            return found

    if kind == "title" and value:
        imdb_id, hit = imdb_from_title(value, request["type"], deadline)
        meta["cinemeta_hit"] = str(hit.get("name") or "")
        if imdb_id:
            found = tmdb_from_imdb(imdb_id, request["type"], deadline)
            meta["imdb"] = imdb_id
            if found:
                return found
    return ""


# ---------------------------------------------------------------------------
# API de NOVA
# ---------------------------------------------------------------------------


def sources_url(base: str, tmdb_id: str, media_type: str, season: Any, episode: Any) -> str:
    """URL de `/api/sources` con los parámetros que acepta el backend."""
    params: List[Tuple[str, str]] = [
        ("tmdbId", str(tmdb_id)),
        ("type", QUERY_TYPES["show"] if media_type == "show" else QUERY_TYPES["movie"]),
    ]
    if media_type == "show":
        if season not in (None, "") and episode not in (None, ""):
            params.append(("season", str(season)))
            params.append(("episode", str(episode)))
    return f"{base}{SOURCES_PATH}?{urllib.parse.urlencode(params)}"


def fetch_sources(
    tmdb_id: str,
    media_type: str,
    season: Any,
    episode: Any,
    headers: Dict[str, str],
    deadline: Deadline,
    meta: Dict[str, Any],
) -> Dict[str, Any]:
    """Pide la lista de señales, probando los espejos en orden."""
    tried: List[str] = []
    for base in _bases():
        if deadline.expired():
            break
        url = sources_url(base, tmdb_id, media_type, season, episode)
        tried.append(url)
        try:
            data = http_get_json(url, headers, deadline.remaining(6))
        except urllib.error.HTTPError as exc:
            meta.setdefault("errors", []).append(f"http_{exc.code}")
            continue
        except (urllib.error.URLError, OSError, ValueError) as exc:
            meta.setdefault("errors", []).append("request_failed")
            log("novahd sources failed:", exc)
            continue
        if isinstance(data, dict) and isinstance(data.get("sources"), list):
            meta["page"] = url
            return data
        if isinstance(data, dict) and data.get("error"):
            meta.setdefault("errors", []).append(str(data["error"])[:160])
    meta["tried"] = tried
    return {}


# ---------------------------------------------------------------------------
# Respuesta → streams
# ---------------------------------------------------------------------------


def language_label(raw: str) -> str:
    """`English` / `en` → `Inglés`; lo desconocido se deja tal cual."""
    value = str(raw or "").strip()
    if not value:
        return ""
    key = value.lower().replace("_", "-")
    if key in LANGUAGES:
        return LANGUAGES[key]
    # "English (US)", "Spanish Latino"…: se prueba el primer trozo.
    head = re.split(r"[\s(/]", key)[0]
    return LANGUAGES.get(head) or value


def language_keys(label: str, raw: str) -> List[str]:
    """Claves normalizadas para comparar con la preferencia del usuario."""
    keys = {normalize(label), normalize(raw)}
    for value in (label, raw):
        mapped = LANGUAGES.get(str(value or "").lower().replace("_", "-"))
        if mapped:
            keys.add(normalize(mapped))
    return [key for key in keys if key]


def quality_rank(value: str) -> int:
    return QUALITY_RANK.get(normalize(value or "").split()[0] if value else "", 0)


def build_streams(
    data: Dict[str, Any],
    payload: Dict[str, Any],
    referer: str,
    meta: Dict[str, Any],
) -> List[Dict[str, Any]]:
    """`sources[]` → streams etiquetados por idioma, calidad y proveedor."""
    preferred = preferred_languages(payload)
    only = only_languages(payload)
    user_agent = str(payload.get("userAgent") or DEFAULT_USER_AGENT)

    rows: List[Dict[str, Any]] = []
    seen: set = set()
    for order, item in enumerate(data.get("sources") or []):
        if not isinstance(item, dict):
            continue
        url = str(item.get("url") or "").strip()
        if not url or not url.lower().startswith(("http://", "https://")) or url in seen:
            continue
        seen.add(url)

        label = language_label(str(item.get("language") or ""))
        orig = bool(item.get("origAudio"))
        if not label:
            label = "Audio original" if orig else ""

        lookup = language_keys(label, str(item.get("language") or ""))
        if only and not any(key in only for key in lookup):
            continue

        rank = next((index for index, want in enumerate(preferred)
                     if any(key == want or key.startswith(want) for key in lookup)), len(preferred))
        quality = str(item.get("quality") or "").strip()
        codec = str(item.get("codecs") or "").strip()
        kind = str(item.get("type") or "").strip().lower()
        if kind not in ("hls", "dash", "mp4"):
            kind = media_kind_of_url(url) or ""

        rows.append({
            "url": url,
            "priority": (0 if rank < len(preferred) else (1 if orig else 2), rank),
            "quality_rank": quality_rank(quality),
            "codec_rank": 0 if codec.lower() in ("", "h264", "avc", "avc1") else 1,
            "order": order,
            "language": label,
            "quality": quality,
            "codec": codec,
            "provider": str(item.get("provider") or "").strip(),
            "kind": kind,
        })

    rows.sort(key=lambda row: (row["priority"], -row["quality_rank"], row["codec_rank"], row["order"]))

    streams: List[Dict[str, Any]] = []
    for row in rows[:MAX_STREAMS]:
        provider = row["provider"]
        tags = [tag for tag in (row["language"], provider) if tag]
        entry = stream(
            row["url"],
            name=" · ".join(tags) or LABEL,
            quality=row["quality"] or None,
            referer=referer,
            user_agent=user_agent,
            media_type=row["kind"] or None,
        )
        # El título usa la calidad ya normalizada (`FHD` → `1080p`) para que la
        # lista se lea igual que la del resto de proveedores.
        title_parts = [LABEL] + tags
        if entry.get("quality"):
            title_parts.append(str(entry["quality"]))
        if row["codec"] and row["codec"].lower() not in ("h264", "avc", "avc1"):
            title_parts.append(row["codec"].upper())
        entry["title"] = " · ".join(title_parts)
        streams.append(entry)

    languages: List[str] = []
    for row in rows:
        if row["language"] and row["language"] not in languages:
            languages.append(row["language"])
    meta["languages"] = languages

    subtitles = []
    for item in data.get("subtitles") or []:
        if not isinstance(item, dict) or not item.get("url"):
            continue
        subtitles.append({
            "url": str(item["url"]),
            "label": str(item.get("label") or item.get("language") or ""),
            "lang": str(item.get("lang") or ""),
        })
    meta["subtitles"] = subtitles
    meta["providers"] = sorted({row["provider"] for row in rows if row["provider"]})
    return streams


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    media_type = "show" if str(payload.get("type") or "").lower() in (
        "series", "show", "tv", "anime") else "movie"
    headers = _headers(payload)
    deadline = Deadline(payload.get("timeoutMs"))
    meta: Dict[str, Any] = {"provider": PROVIDER, "type": media_type}

    request = parse_content_id(str(payload.get("id") or payload.get("canal") or ""), media_type)
    meta["requested"] = {"kind": request["kind"], "value": request["value"]}
    media_type = request["type"]
    meta["type"] = media_type

    season, episode = payload.get("season"), payload.get("episode")
    if media_type == "show" and (season in (None, "") or episode in (None, "")):
        return [], {**meta, "reason": "no_episode"}

    tmdb_id = resolve_tmdb(request, payload, deadline, meta)
    if not tmdb_id:
        return [], {**meta, "reason": "not_found"}

    meta["tmdb"] = tmdb_id
    data = fetch_sources(tmdb_id, media_type, season, episode, headers, deadline, meta)
    if not data:
        # El backend contesta `{"error": "…"}`: es un rechazo, no un sitio caído.
        reason = "api_error" if meta.get("errors") else "site_unreachable"
        return [], {**meta, "reason": reason}

    referer = str(payload.get("referer") or f"{BASE_URL}/")
    streams = build_streams(data, payload, referer, meta)
    meta["sources"] = len(data.get("sources") or [])
    meta["streams"] = len(streams)
    if not streams:
        return [], {**meta, "reason": "no_streams"}
    return streams, meta


# ---------------------------------------------------------------------------
# Listado (para armar catálogos)
# ---------------------------------------------------------------------------

_CARD_RE = re.compile(
    r"<a\b[^>]*href=[\"'](?:https?://[^\"']*)?/(?P<kind>[ms])/(?P<id>\d+)[\"'][^>]*>(?P<inner>.*?)</a>",
    re.IGNORECASE | re.DOTALL,
)
_ALT_RE = re.compile(r"alt=[\"']([^\"']+)[\"']", re.IGNORECASE)


def list_items(query: str = "", limit: int = 30) -> List[Dict[str, str]]:
    """Títulos del listado del sitio (`/movies`), listos para el catálogo.

    La portada se renderiza en el servidor, así que basta un GET: no se usa
    como catálogo del addon (los scrapers sólo resuelven streams), pero sirve
    para pegar ids en una fila propia.
    """
    headers = {"User-Agent": DEFAULT_USER_AGENT, "Referer": f"{BASE_URL}/",
               "Accept": "text/html,application/xhtml+xml"}
    deadline = Deadline(15000)
    wanted = normalize(query)
    items: List[Dict[str, str]] = []
    seen: set = set()

    for base in _bases():
        try:
            html, _final, _ctype = http_get(f"{base}/movies", headers, deadline.remaining(8))
        except (urllib.error.HTTPError, urllib.error.URLError, OSError, ValueError) as exc:
            log("novahd list failed:", exc)
            continue
        for match in _CARD_RE.finditer(html or ""):
            tmdb_id = match.group("id")
            if tmdb_id in seen:
                continue
            inner = match.group("inner")
            alt = _ALT_RE.search(inner)
            name = " ".join((alt.group(1) if alt else re.sub(r"<[^>]+>", " ", inner)).split())
            name = re.sub(r"^(?:\d+\.?|#\d+)\s*", "", name).strip(" ·-|")
            if not name or (wanted and wanted not in normalize(name)):
                continue
            seen.add(tmdb_id)
            items.append({"id": f"tv:{PROVIDER}:{tmdb_id}", "name": name})
            if len(items) >= limit:
                return items
        break
    return items


def cli_list(query: str, as_json: bool, limit: int = 30) -> None:
    items = list_items(query, limit)
    if as_json:
        print(json.dumps({"items": items}, ensure_ascii=False, indent=2))
    else:
        for item in items:
            print(f"{item['id']}\t{item['name']}")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def _cli() -> None:
    import sys

    argv = sys.argv[1:]
    if "--list" in argv:
        query = ""
        if "--query" in argv:
            index = argv.index("--query")
            query = argv[index + 1] if index + 1 < len(argv) else ""
        cli_list(query, "--json" in argv)
        return
    if "--sources" not in argv:
        run(scrape)
        return

    index = argv.index("--sources")
    value = argv[index + 1] if index + 1 < len(argv) else ""
    payload: Dict[str, Any] = {"id": f"{PROVIDER}:{value}", "type": "movie", "timeoutMs": 12000}
    if "--series" in argv or "--season" in argv:
        payload["type"] = "series"
    if "--season" in argv:
        payload["season"] = int(argv[argv.index("--season") + 1])
    if "--episode" in argv:
        payload["episode"] = int(argv[argv.index("--episode") + 1])
    streams, meta = scrape(payload)
    print(json.dumps({"streams": streams, "meta": meta}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    _cli()
