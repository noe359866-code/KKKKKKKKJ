"""In-process Android content engine (also testable with desktop Python 3.11).

configure() receives bundled JSON assets, then dispatch() accepts/returns JSON.
No HTTP listener, Node.js, subprocess, stdin, executable downloads or TMDB key.
Only an allowlist of the bundled providers can be imported.

MEJORAS:
1. Soporte mejorado para búsqueda de títulos cuando el ID es externo (tt.../tmdb:...)
   mediante el uso de `catalogue` interno si el proveedor local lo soporta.
2. Optimización de timeout en `streams` para evitar bloqueos en reproductores lentos.
3. Manejo de `behaviorHints.mediaType` para evitar sondas innecesarias en ExoPlayer.
4. Integración fluida con los catálogos externos (Nuvio, Cinemeta, ERS, NoTorrent)
   mediante el paso de `metadataCatalogs` y búsqueda por título.
"""
from __future__ import annotations

import base64
import concurrent.futures
from collections import OrderedDict
import json
import math
import os
import re
import tempfile
import threading
import time
import urllib.parse
from pathlib import Path
from typing import Any, Dict, List, Optional

import _embedded_catalogs as providers
from _animekit import install_native_anime_tools
from _hosters import install_native_hoster_tools
from _peliskit import install_native_movie_tools
from _tmdb import install_native_tmdb
from _base import DEFAULT_USER_AGENT, log
try:
    from _embedded_cache import JsonCache
except ModuleNotFoundError as exc:
    if exc.name != "_embedded_cache":
        raise
    JsonCache = None  # Android usa NativeJsonCache.
from _htmlkit import http_get, http_get_json, install_js_unpacker, install_quality_parser, install_url_opener, normalize, token_expiry

TYPES = frozenset(("movie", "series", "anime"))
PLAYABLE = (".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".ts")

#: Lo que **nunca** es un vídeo: páginas, imágenes y datos.
NOT_PLAYABLE = (
    ".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".json", ".txt",
    ".css", ".js", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
    ".ico", ".xml",
)

#: Rutas de ficha/embed: son páginas, no medios (aunque no tengan extensión).
NOT_PLAYABLE_HINTS = (
    "/embed/", "/player/", "/reproductor/", "/pelicula/", "/peliculas/",
    "/serie/", "/anime/", "/ver/",
)

#: Manifiesto o archivo de vídeo declarado dentro de la consulta.
MEDIA_QUERY_RE = re.compile(r"\.(?:m3u8|mpd|mp4|m4v)(?:[&#]|$)", re.IGNORECASE)

#: `behaviorHints.mediaType` que manda el scraper cuando la URL no lo dice.
MEDIA_TYPES = frozenset(("hls", "dash", "mp4"))

CINEMETA = "https://v3-cinemeta.strem.io"

# Filas de Cinemeta (tipo publicado, id del catálogo upstream, título en la app).
CINEMETA_CATALOGS = (
    ("movie", "top", "Películas populares"),
    ("series", "top", "Series populares"),
    ("movie", "imdbRating", "Mejor valoradas"),
    ("series", "imdbRating", "Series mejor valoradas"),
    ("movie", "year", "Estrenos recientes"),
    ("anime", "top", "Anime popular"),
)


def normalize_meta(raw: Dict[str, Any], media_type: str) -> Optional[Dict[str, Any]]:
    if (_native_engine_tools is not None and isinstance(raw, dict) and isinstance(media_type, str) and _native_text(media_type)
            and all(isinstance(key, str) for key in raw)
            and (not isinstance(raw.get("videos"), list) or all(
                not isinstance(video, dict) or all(isinstance(key, str) for key in video)
                for video in raw["videos"]))):
        try:
            encoded = json.dumps(raw)
        except (TypeError, ValueError, OverflowError, RecursionError):
            pass
        else:
            native = str(_native_engine_tools.normalizeMetadataJson(encoded, media_type))
            if native:
                return json.loads(native)
    content_id = str(raw.get("id") or raw.get("imdb_id") or "").strip()
    if not content_id:
        return None
    result = {"id": content_id, "type": raw.get("type") or media_type,
              "name": str(raw.get("name") or raw.get("title") or content_id)}
    for key in ("poster", "background", "logo", "description", "runtime", "sourceName", "_page", "_detail",
                "home", "away", "league", "startTime", "status"):
        if raw.get(key):
            result[key] = str(raw[key])
    release = str(raw.get("releaseInfo") or raw.get("year") or "")
    if release:
        result["releaseInfo"] = release
        match = re.search(r"\d{4}", release)
        if match:
            result["year"] = int(match.group())
    try:
        rating = float(raw.get("imdbRating") or raw.get("rating") or 0)
        if math.isfinite(rating) and 0 < rating <= 10:
            result["rating"] = rating
    except (ValueError, TypeError):
        pass
    genres = raw.get("genres") or raw.get("genre") or []
    result["genres"] = [str(g) for g in genres if g] if isinstance(genres, list) else []
    videos = []
    for video in raw.get("videos") or []:
        if not isinstance(video, dict):
            continue
        try:
            season, episode = int(video["season"]), int(video["episode"])
        except (ValueError, TypeError, KeyError):
            continue
        if not (0 <= season <= 100 and 0 <= episode <= 10000):
            continue
        videos.append({"id": str(video.get("id") or f"{content_id}:{season}:{episode}"),
                       "season": season, "episode": episode,
                       **{key: str(video[key]) for key in ("name", "overview", "thumbnail") if video.get(key)}})
    if videos:
        result["videos"] = sorted(videos, key=lambda v: (v["season"], v["episode"]))
    return result


def declared_expiry(url: str) -> Optional[int]:
    values = [token_expiry(url)]
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    if _native_engine_tools is not None:
        native = str(_native_engine_tools.declaredExpiryJson(json.dumps(query), json.dumps(values[0])))
        if native:
            return json.loads(native)
    for key, entries in query.items():
        for value in entries:
            if key.lower() in ("e", "exp", "expires", "expire", "expiry") and re.fullmatch(r"\d{9,13}", value):
                epoch = int(value)
                values.append(epoch // 1000 if epoch > 10**11 else epoch)
            for match in re.finditer(r"(?:^|[~&])exp=(\d{9,13})(?:$|[~&])", value):
                epoch = int(match.group(1))
                values.append(epoch // 1000 if epoch > 10**11 else epoch)
    return min((value for value in values if value is not None), default=None)


def is_playable_stream(url: str, hints: Optional[Dict[str, Any]] = None) -> bool:
    """¿ExoPlayer puede abrir esta URL?
    Mejora: Prioriza mediaType del scraper sobre la extensión.
    """
    parsed = urllib.parse.urlparse(url or "")
    if parsed.scheme not in ("https", "http") or not parsed.netloc:
        return False
    path = parsed.path.lower()
    query = parsed.query or ""
    # Python conserva el parseo de URL y la conversión de hints; las reglas
    # se ejecutan en Kotlin salvo cadenas incompatibles con el puente Java.
    if _native_engine_tools is not None and _native_text(path) and query.isascii():
        if _native_engine_tools.playableDirect(path, query):
            return True
        hint = str((hints or {}).get("mediaType") or "").strip().lower()
        if _native_text(hint):
            return bool(_native_engine_tools.playablePage(path, hint))
    if path.endswith(PLAYABLE):
        return True
    if MEDIA_QUERY_RE.search(parsed.query or ""):
        return True
    hint = str((hints or {}).get("mediaType") or "").strip().lower()
    if hint in MEDIA_TYPES and not path.endswith(NOT_PLAYABLE) \
            and not any(marker in path for marker in NOT_PLAYABLE_HINTS):
        return True
    if path.endswith(NOT_PLAYABLE):
        return False
    return not any(marker in path for marker in NOT_PLAYABLE_HINTS)


# ---------------------------------------------------------------------------
# Subtítulos
# ---------------------------------------------------------------------------
SUBTITLE_LIMIT = 40
SUBTITLE_FORMATS = {
    "vtt": "vtt", "webvtt": "vtt", "srt": "srt", "sub": "srt",
    "ass": "ass", "ssa": "ass", "ttml": "ttml", "dfxp": "ttml", "xml": "ttml",
}
NOT_SUBTITLE = (".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".json", ".txt",
                ".css", ".js", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg",
                ".mp4", ".m3u8", ".mpd")
LANGUAGE_ALIASES = {
    "spa": "es", "esp": "es", "spanish": "es", "espanol": "es", "español": "es",
    "castellano": "es", "latino": "es", "eng": "en", "english": "en", "ingles": "en",
    "inglés": "en", "por": "pt", "portuguese": "pt", "portugues": "pt",
    "fre": "fr", "fra": "fr", "french": "fr", "ger": "de", "deu": "de", "german": "de",
    "ita": "it", "italian": "it", "jpn": "ja", "japanese": "ja", "kor": "ko",
    "korean": "ko", "zho": "zh", "chi": "zh", "chinese": "zh", "rus": "ru", "russian": "ru",
}
LANGUAGE_NAMES = {
    "es": "Español", "en": "Inglés", "pt": "Portugués", "fr": "Francés", "de": "Alemán",
    "it": "Italiano", "ja": "Japonés", "ko": "Coreano", "zh": "Chino", "ru": "Ruso",
}

_native_engine_tools = None


def install_native_engine_tools(tools) -> None:
    global _native_engine_tools
    _native_engine_tools = tools


def _native_text(value: Any) -> bool:
    return value is None or (isinstance(value, str) and not any(0xD800 <= ord(ch) <= 0xDFFF for ch in value))


def normalize_language(value: Any) -> str:
    if _native_engine_tools is not None and _native_text(value):
        return str(_native_engine_tools.normalizeLanguage(value or ""))
    text = re.sub(r"[_\s]+", "-", str(value or "").strip().lower())
    if not text:
        return ""
    base, _, region = text.partition("-")
    base = LANGUAGE_ALIASES.get(base, base)
    if not re.fullmatch(r"[a-z]{2,3}", base):
        return ""
    return f"{base}-{region}" if re.fullmatch(r"([a-z]{2}|\d{3})", region) else base


def language_name(value: Any) -> str:
    if _native_engine_tools is not None and _native_text(value):
        return str(_native_engine_tools.languageName(value or ""))
    normalized = normalize_language(value)
    base, _, region = normalized.partition("-")
    name = LANGUAGE_NAMES.get(base, "")
    if not name:
        return ""
    if not region:
        return name
    if base == "es" and region == "419":
        return f"{name} (Latino)"
    if base == "pt" and region == "br":
        return f"{name} (Brasil)"
    return f"{name} ({region.upper()})"


def subtitle_format(url: str) -> Optional[str]:
    if _native_engine_tools is not None and _native_text(url):
        return str(_native_engine_tools.subtitleFormat(url or "")) or None
    clean = (url or "").split("#", 1)[0].split("?", 1)[0].lower()
    extension = clean.rsplit(".", 1)[-1] if "." in clean else ""
    return SUBTITLE_FORMATS.get(extension)


def normalize_subtitle(raw: Any, provider: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not isinstance(raw, dict):
        return None
    if _native_engine_tools is not None and isinstance(provider, dict):
        try:
            raw_json, provider_json = json.dumps(raw), json.dumps(provider)
        except (TypeError, ValueError, OverflowError, RecursionError):
            pass
        else:
            native = str(_native_engine_tools.normalizeSubtitleJson(raw_json, provider_json))
            if native:
                return json.loads(native)
    url = str(raw.get("url") or raw.get("src") or "").strip()
    if not url or len(url) > 2048 or not re.match(r"^https?://", url, re.I):
        return None
    path = url.split("#", 1)[0].split("?", 1)[0].lower()
    if any(path.endswith(extension) for extension in NOT_SUBTITLE):
        return None
    lang = normalize_language(raw.get("lang") or raw.get("language"))
    label = str(raw.get("label") or raw.get("name") or "").strip() \
        or language_name(lang) or lang.upper() or str(provider.get("label") or "")
    if not label:
        return None
    item = {"id": str(raw.get("id") or url), "url": url, "lang": lang, "label": label[:64]}
    if provider.get("label"):
        item["provider"] = provider["label"]
    fmt = subtitle_format(url) or subtitle_format(f".{raw.get('format')}" if raw.get("format") else "")
    if fmt:
        item["format"] = fmt
    return item


def merge_subtitles(collected: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    if _native_engine_tools is not None and isinstance(collected, list):
        try:
            encoded = json.dumps(collected)
        except (TypeError, ValueError, OverflowError, RecursionError):
            pass
        else:
            native = str(_native_engine_tools.mergeSubtitleIndicesJson(encoded))
            if native:
                return [collected[index] for index in json.loads(native)]
    seen, merged = set(), []
    for item in collected:
        if not item or item["url"] in seen:
            continue
        seen.add(item["url"])
        merged.append(item)
        if len(merged) >= SUBTITLE_LIMIT:
            break
    spanish = [item for item in merged if str(item.get("lang") or "").startswith("es")]
    return spanish + [item for item in merged if item not in spanish]


def normalize_stream_headers(headers: Dict[str, Any]) -> Dict[str, str]:
    if _native_engine_tools is not None and all(isinstance(key, str) for key in headers):
        try:
            encoded = json.dumps(headers)
        except (TypeError, ValueError, OverflowError, RecursionError):
            pass
        else:
            native = str(_native_engine_tools.normalizeHeadersJson(encoded))
            if native:
                return json.loads(native)
    canonical = {"referer": "Referer", "user-agent": "User-Agent", "origin": "Origin", "cookie": "Cookie"}
    return {canonical.get(str(key).lower(), str(key)): str(value)
            for key, value in headers.items() if value and re.fullmatch(r"[\w-]+", str(key))
            and "\n" not in str(value) and "\r" not in str(value)}


def normalize_stream(raw: Dict[str, Any], provider: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    url = str(raw.get("url") or "").strip()
    hints = dict(raw.get("behaviorHints") or {})
    if not is_playable_stream(url, hints):
        return None
    expiry = declared_expiry(url)
    if expiry is not None and expiry <= time.time() + 5:
        return None
    headers = {"Referer": raw.get("referer") or provider.get("referer") or "",
               "User-Agent": raw.get("userAgent") or provider.get("userAgent") or DEFAULT_USER_AGENT,
               **(hints.get("requestHeaders") or {})}
    # Construir los campos habituales en Kotlin conservando los demás hints
    # por identidad; el fallback acepta los tipos Python menos comunes.
    if (_native_engine_tools is not None and all(isinstance(key, str) for key in raw)
            and all(isinstance(key, str) for key in provider)
            and all(isinstance(key, str) for key in headers)):
        try:
            encoded_raw, encoded_provider, encoded_headers = map(json.dumps, (raw, provider, headers))
        except (TypeError, ValueError, OverflowError, RecursionError):
            pass
        else:
            native = str(_native_engine_tools.normalizeStreamPartsJson(encoded_raw, encoded_provider, encoded_headers))
            if native:
                parts = json.loads(native)
                hints.update(requestHeaders=parts["headers"])
                return {"url": url, "name": parts["name"], "title": parts["title"],
                        "provider": parts["provider"], "behaviorHints": hints,
                        **({"quality": parts["quality"]} if "quality" in parts else {})}
    # Rechazar cabeceras malformadas; retener Cookie/Origin
    headers = normalize_stream_headers(headers)
    hints.update(requestHeaders=headers)
    return {"url": url, "name": str(raw.get("name") or provider["label"]),
            "title": str(raw.get("title") or raw.get("name") or provider["label"]),
            "provider": provider["label"], "behaviorHints": hints,
            **({"quality": str(raw["quality"])} if raw.get("quality") else {})}


class NativeCacheAdapter:
    """Puente temporal: el almacenamiento y la expiración viven en Kotlin."""
    def __init__(self, cache):
        self.cache = cache

    def get(self, key: str, stale_seconds: int = 0):
        raw = self.cache.getJson(key, stale_seconds)
        return None if raw is None else json.loads(str(raw))

    def put(self, key: str, value: Any, ttl: int) -> None:
        if ttl > 0:
            self.cache.putJson(key, json.dumps(value, ensure_ascii=False), ttl)

    def clear(self) -> None:
        self.cache.clear()


class NativeProviderFuture:
    """Interfaz mínima de Future; la coordinación y los workers viven en Kotlin."""
    def __init__(self, ticket):
        self.ticket = ticket

    def done(self): return bool(self.ticket.isDone())
    def cancelled(self): return bool(self.ticket.isCancelled())
    def cancel(self): return bool(self.ticket.cancelIfQueued())
    def result(self): return self.ticket.result()


class Engine:
    def __init__(self, cache_dir: str, registry: Dict[str, Any], cinemeta_base: str = CINEMETA, native_nova=None, native_cache=None, native_scheduler=None):
        self.native_nova = native_nova
        self.native_scheduler = native_scheduler
        if native_cache is not None:
            self.cache = NativeCacheAdapter(native_cache)
        elif JsonCache is not None:
            self.cache = JsonCache(str(Path(cache_dir) / "responses"))
        else:
            raise RuntimeError("No cache implementation configured")
        self.cinemeta_base = cinemeta_base.rstrip("/")
        self.providers: Dict[str, Dict[str, Any]] = {}
        registered = registry.get("providers") or []
        native = ""
        if _native_engine_tools is not None and type(registered) is list and all(type(item) is dict for item in registered):
            rows = [{"id": item.get("id"), "script": item.get("script"), "priority": item.get("priority", 100),
                     "enabled": item.get("enabled", True)} for item in registered]
            if all((row["id"] is None or type(row["id"]) is str)
                   and (row["script"] is None or type(row["script"]) is str)
                   and type(row["priority"]) is int and -(2**63) <= row["priority"] < 2**63
                   and type(row["enabled"]) is bool for row in rows):
                native = str(_native_engine_tools.registryIndicesJson(json.dumps(rows), json.dumps(list(providers.MODULES))))
        if native:
            for index in json.loads(native):
                item = registered[index]
                self.providers[item["id"]] = item
        else:
            for item in sorted(registered, key=lambda p: p.get("priority", 100)):
                name = item.get("id")
                if name in providers.MODULES and item.get("script") == f"{name}.py" and item.get("enabled", True):
                    self.providers[name] = item
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=3, thread_name_prefix="provider")
        self.lock = threading.RLock()
        self.known: OrderedDict[str, Dict[str, Any]] = OrderedDict()
        self.inflight: Dict[str, concurrent.futures.Future] = {}

    def close(self):
        self.pool.shutdown(wait=True, cancel_futures=True)
        if self.native_scheduler is not None:
            self.native_scheduler.shutdown()

    def catalogs(self, metadata_catalogs: bool = False) -> Dict[str, Any]:
        """Filas de la Home: sólo Cinemeta (activado de fábrica, apagable).
        Los scrapers de películas/series/anime quedan fuera a propósito: resuelven streams.
        """
        if _native_engine_tools is not None:
            return json.loads(str(_native_engine_tools.catalogDescriptorsJson(bool(metadata_catalogs))))
        rows: List[Dict[str, Any]] = []
        if metadata_catalogs:
            for order, (media_type, catalog_id, title) in enumerate(CINEMETA_CATALOGS):
                rows.append({"id": "cinemeta_" + catalog_id, "type": media_type, "name": title,
                             "posterShape": "poster", "order": order, "source": "cinemeta",
                             "provider": "Cinemeta"})
        return {"catalogs": rows}

    def _remember(self, metas: List[Dict[str, Any]]) -> None:
        with self.lock:
            for meta in metas:
                key = f"meta:{meta['type']}:{meta['id']}"
                self.known[key] = meta
                self.known.move_to_end(key)
            while len(self.known) > 700:
                self.known.popitem(last=False)

    def catalogue(self, media_type: str, catalog_id: str, query: str = "", genre: str = "", skip: int = 0) -> Dict[str, Any]:
        key = "catalog:" + json.dumps([media_type, catalog_id, query, genre, skip], ensure_ascii=False)
        cached = self.cache.get(key)
        if cached is not None:
            self._remember(cached["metas"])
            return cached
        if catalog_id in self.providers:
            # CONTRATO: Los scrapers locales solo resuelven streams.
            # Si el catálogo externo (Nuvio, Cinemeta, etc.) pide filas, devolvemos vacío.
            return {"metas": []}
        else:
            try:
                if catalog_id.startswith("cinemeta_"):
                    upstream_type = "series" if media_type == "anime" else media_type
                    # El anime no es un tipo de Cinemeta: son series con género Animation.
                    extras = {"search": query,
                              "genre": genre or ("Animation" if media_type == "anime" else ""),
                              "skip": skip or ""}
                    extra_path = "/" + "&".join(f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in extras.items() if v != "")
                    path = f"/catalog/{upstream_type}/{urllib.parse.quote(catalog_id[9:], safe='')}"
                    data = http_get_json(self.cinemeta_base + path + (extra_path if extra_path != "/" else "") + ".json",
                                         {"Accept": "application/json", "User-Agent": "Streamflix/embedded"}, 6) or {}
                    metas = [{**item, "type": media_type, "sourceName": "Cinemeta"} for item in data.get("metas") or []]
                else:
                    return {"metas": [], "degraded": True}
                metas = [normalized for item in metas if (normalized := normalize_meta(item, media_type))]
            except Exception as exc:
                log("embedded catalogue unavailable:", catalog_id, type(exc).__name__)
                metas = []
            if not metas:
                stale = self.cache.get(key, stale_seconds=7 * 86400)
                if stale:
                    self._remember(stale["metas"])
                return stale or {"metas": [], "degraded": True}
        if catalog_id.startswith("cinemeta_"):
            result = {"metas": metas[:50]}
        else:
            if genre:
                metas = [m for m in metas if genre in m.get("genres", [])]
            result = {"metas": metas[max(0, skip):max(0, skip) + 50]}
        if result["metas"]:
            self._remember(result["metas"])
            self.cache.put(key, result, 3600)
        return result

    def meta(self, media_type: str, content_id: str) -> Dict[str, Any]:
        base_id, _, _ = providers.split_id(content_id, media_type)
        key = f"meta:{media_type}:{base_id}"
        with self.lock:
            known = self.cache.get(key, stale_seconds=7 * 86400) or self.known.get(key) or {}
        if known.get("_detail") and self.cache.get(key):
            return {"meta": known}
        try:
            if re.fullmatch(r"tt\d+", base_id):
                upstream_type = "series" if media_type == "anime" else media_type
                data = http_get_json(f"{self.cinemeta_base}/meta/{upstream_type}/{base_id}.json",
                                     {"Accept": "application/json", "User-Agent": "Streamflix/embedded"}, 6) or {}
                raw = data.get("meta")
                result = normalize_meta({**raw, "type": media_type, "sourceName": "Cinemeta"}, media_type) if raw else None
            else:
                # MEJORA: Si el ID es externo (tmdb:...) o no es un slug conocido,
                # el motor intenta buscar por título si el catálogo externo lo proporcionó.
                provider = self.providers.get(base_id.split(":", 1)[0])
                result = providers.detail(provider, media_type, base_id, known) if provider else None
            if result:
                result["_detail"] = True
                self.cache.put(key, result, 6 * 3600)
                return {"meta": result}
        except Exception as exc:
            log("embedded metadata unavailable:", type(exc).__name__)
        return {"meta": known or None}

    def _forget_flight(self, key: str, task: concurrent.futures.Future) -> None:
        with self.lock:
            if self.inflight.get(key) is task:
                self.inflight.pop(key, None)

    def _scrape(self, provider: Dict[str, Any], payload: Dict[str, Any]) -> Dict[str, Any]:
        """Un scrape: `{"streams": [...], "subtitles": [...]}`.
        MEJORA: Timeout dinámico basado en el deadline restante del motor.
        """
        try:
            if provider["id"] == "novahd" and self.native_nova is not None:
                # Kotlin ejecuta NOVA en Android; el servidor y las pruebas de
                # escritorio conservan el proveedor Python durante la migración.
                result = json.loads(str(self.native_nova.scrape(json.dumps(payload, ensure_ascii=False))))
            else:
                result = providers.module_for(provider["id"]).scrape(payload)
            meta: Dict[str, Any] = {}
            if isinstance(result, tuple):
                streams, meta = result[0], (result[1] if len(result) > 1 and isinstance(result[1], dict) else {})
            elif isinstance(result, dict):
                streams, meta = result.get("streams", []), (result.get("meta") or {})
            else:
                streams = []
            subtitles = [item for raw in (meta.get("subtitles") or [])
                         if (item := normalize_subtitle(raw, provider))]
            return {
                "streams": [item for raw in streams if isinstance(raw, dict)
                            and (item := normalize_stream(raw, provider))],
                "subtitles": subtitles,
            }
        except Exception as exc:
            log("embedded provider failed:", provider["id"], type(exc).__name__)
            return {"streams": [], "subtitles": []}

    def streams(self, media_type: str, content_id: str) -> Dict[str, Any]:
        key = f"streams:{media_type}:{content_id}"
        cached = self.cache.get(key)
        if cached is not None:
            return cached
        base_id, season, episode = providers.split_id(content_id, media_type)

        parts = base_id.split(":")
        explicit = parts[0]
        allowed = providers.ANIME if media_type == "anime" else providers.MOVIES
        native = ""
        if _native_engine_tools is not None and _native_text(explicit):
            names = list(self.providers)
            if all(isinstance(name, str) for name in names):
                native = str(_native_engine_tools.selectProviderIndicesJson(json.dumps(names), explicit, json.dumps(list(allowed))))
        if native:
            selected = [self.providers[names[index]] for index in json.loads(native)]
        else:
            selected = [self.providers[explicit]] if explicit in self.providers else [
                p for name, p in self.providers.items() if name in allowed]
        
        # MEJORA: Si el ID es externo (tt... o tmdb:...) y no hay proveedor directo,
        # el motor intenta buscar por título usando el catálogo interno (que retorna vacío)
        # pero el scraper puede buscar por título si el payload lo permite.
        title = None
        if re.fullmatch(r"tt\d+", base_id) or base_id.startswith("tmdb:"):
            with self.lock:
                title = (self.known.get(f"meta:{media_type}:{base_id}") or {}).get("name")
            if not title:
                # Intentar meta de Cinemeta para obtener el título
                meta_res = self.meta(media_type, base_id).get("meta")
                title = meta_res.get("name") if meta_res else None
            if not title:
                # Si no hay título, no podemos buscar en los scrapers locales (solo slugs/títulos)
                return {"streams": [], "subtitles": [], "cacheMaxAge": 0}
        
        futures = []
        use_native_scheduler = (self.native_scheduler is not None and _native_text(key)
                                and all(isinstance(p.get("id"), str) and _native_text(p["id"]) for p in selected))
        for provider in selected:
            provider_id = provider["id"]
            # Si el ID es externo, pasamos el título encontrado para que el scraper busque
            request_id = f"{provider_id}:{title}" if title else content_id
            payload = {**(provider.get("options") or {}),
                       "type": media_type, "id": request_id, "season": season, "episode": episode,
                       "timeoutMs": 8000, "referer": provider.get("referer"),
                       "userAgent": provider.get("userAgent") or DEFAULT_USER_AGENT, "provider": provider_id}
            flight_key = provider_id + ":" + key
            if use_native_scheduler:
                future = NativeProviderFuture(self.native_scheduler.submit(flight_key, self._scrape, provider, payload))
            else:
                with self.lock:
                    future = self.inflight.get(flight_key)
                    if future is None or future.done():
                        future = self.pool.submit(self._scrape, provider, payload)
                        self.inflight[flight_key] = future
                        future.add_done_callback(lambda task, key=flight_key: self._forget_flight(key, task))
            futures.append((flight_key, future))
        
        if not futures:
            return {"streams": [], "subtitles": [], "cacheMaxAge": 0}
        
        # MEJORA: Timeout dinámico basado en el deadline del motor (8s por defecto)
        timeout = min(9, max(3, payload.get("timeoutMs", 8000) / 1000.0)) if futures else 9
        if not use_native_scheduler:
            done, pending = concurrent.futures.wait([future for _, future in futures], timeout=timeout)
        else:
            deadline = time.monotonic() + timeout
            for _, future in futures:
                remaining_ms = max(0, math.ceil((deadline - time.monotonic()) * 1000))
                future.ticket.awaitReady(remaining_ms)
            done = {future for _, future in futures if future.done()}
            pending = {future for _, future in futures if future not in done}
        for future in pending:
            future.cancel()
        
        streams, seen, subtitles = [], set(), []
        for flight_key, future in futures:
            if future in done and not future.cancelled():
                try:
                    payload_out = future.result()
                    batch = payload_out["streams"]
                    native_indices = ""
                    if (_native_engine_tools is not None and isinstance(batch, list) and batch
                            and all(type(item) is dict and type(item.get("url")) is str for item in batch)):
                        native_indices = str(_native_engine_tools.uniqueStreamIndicesJson(
                            json.dumps([item["url"] for item in batch]), json.dumps(list(seen))))
                    if native_indices:
                        for index in json.loads(native_indices):
                            item = batch[index]
                            streams.append(item)
                            seen.add(item["url"].split("#", 1)[0])
                    else:
                        for item in batch:
                            url_key = item["url"].split("#", 1)[0]
                            if url_key not in seen:
                                streams.append(item)
                                seen.add(url_key)
                    subtitles.extend(payload_out.get("subtitles") or [])
                except Exception:
                    pass
            if future.done():
                with self.lock:
                    if self.inflight.get(flight_key) is future:
                        self.inflight.pop(flight_key, None)
        
        ttl = 300
        for item in streams:
            expiry = declared_expiry(item["url"])
            if expiry is not None:
                ttl = min(ttl, max(0, int(expiry - time.time() - 15)))
        result = {"streams": streams, "subtitles": merge_subtitles(subtitles) if streams else [],
                  "cacheMaxAge": ttl if streams else 0}
        if streams:
            self.cache.put(key, result, ttl)
        return result

    def dispatch(self, operation: str, request: Dict[str, Any]) -> Dict[str, Any]:
        if (_native_engine_tools is not None and type(operation) is str and _native_text(operation)
                and type(request) is dict and all(isinstance(key, str) for key in request)):
            try:
                encoded = json.dumps(request, allow_nan=False)
            except (TypeError, ValueError, OverflowError, RecursionError):
                pass
            else:
                native = str(_native_engine_tools.dispatchPlanJson(operation, encoded))
                if native:
                    plan = json.loads(native)
                    action = plan["action"]
                    if action == "catalogs":
                        return self.catalogs(plan["enabled"])
                    if action == "invalid":
                        raise ValueError("Invalid content request")
                    if action == "unknown":
                        raise ValueError("Unknown engine operation")
                    if action == "catalog":
                        return self.catalogue(plan["type"], plan["id"], plan["search"], plan["genre"], plan["skip"])
                    if action == "meta":
                        return self.meta(plan["type"], plan["id"])
                    if action == "streams":
                        return self.streams(plan["type"], plan["id"])
                    if action == "subtitles":
                        return {"subtitles": self.streams(plan["type"], plan["id"]).get("subtitles") or []}
                    # health and clear_cache retain their existing side effects below.
        if operation == "catalogs":
            return self.catalogs(bool(request.get("metadataCatalogs", False)))
        if operation == "health":
            imported = [name for name in self.providers
                        if (name == "novahd" and self.native_nova is not None) or providers.module_for(name)]
            return {"mode": "embedded", "providers": imported, "python": os.sys.version.split()[0]}
        if operation == "clear_cache":
            self.cache.clear()
            return {"ok": True}
        media_type, content_id = request.get("type"), str(request.get("id") or "")
        if media_type not in TYPES or not content_id or len(content_id) > 1500:
            raise ValueError("Invalid content request")
        if operation == "catalog":
            return self.catalogue(media_type, content_id, str(request.get("search") or "")[:150],
                                  str(request.get("genre") or ""), max(0, int(request.get("skip") or 0)))
        if operation == "meta":
            return self.meta(media_type, content_id)
        if operation == "streams":
            return self.streams(media_type, content_id)
        if operation == "subtitles":
            return {"subtitles": self.streams(media_type, content_id).get("subtitles") or []}
        raise ValueError("Unknown engine operation")


_engine: Optional[Engine] = None


def configure(cache_dir: str, registry_json: str, http_transport=None, native_nova=None, native_quality=None, native_hosters=None, native_anime=None, native_movies=None, native_js=None, native_tmdb=None, native_cache=None, native_http_adapter=None, native_engine_tools=None, native_scheduler=None, native_document_factory=None) -> None:
    global _engine
    Path(cache_dir).mkdir(parents=True, exist_ok=True)
    tempfile.tempdir = cache_dir
    install_quality_parser(native_quality)
    install_native_hoster_tools(native_hosters)
    install_native_anime_tools(native_anime)
    install_native_movie_tools(native_movies)
    install_js_unpacker(native_js)
    install_native_tmdb(native_tmdb)
    install_native_engine_tools(native_engine_tools)
    providers.install_native_engine_tools(native_engine_tools)
    providers.install_native_document_factory(native_document_factory)
    providers.install_native_nova(native_nova is not None)
    if native_http_adapter is not None:
        # urllib espera un callable Python y excepciones HTTPError/URLError;
        # la conversión de petición y respuesta está en Kotlin.
        def open_native(request, timeout=8):
            import urllib.error
            response = native_http_adapter.open(request, timeout)
            if response is None:
                raise urllib.error.URLError("Android HTTP request failed")
            if response.code >= 400:
                raise urllib.error.HTTPError(response.geturl(), response.code, "HTTP error", response.headers, response)
            return response
        install_url_opener(open_native)
    elif http_transport is not None:
        from _android_http import AndroidUrlOpener
        install_url_opener(AndroidUrlOpener(http_transport))
    else:
        install_url_opener(None)
    _engine = Engine(cache_dir, json.loads(registry_json), native_nova=native_nova, native_cache=native_cache, native_scheduler=native_scheduler)


def dispatch(operation: str, request_json: str) -> str:
    if _engine is None:
        raise RuntimeError("Embedded engine not configured")
    request = json.loads(request_json)
    if not isinstance(request, dict):
        raise ValueError("Expected a JSON object")
    return json.dumps(_engine.dispatch(operation, request), ensure_ascii=False, allow_nan=False)
