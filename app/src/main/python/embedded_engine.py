"""In-process Android content engine (also testable with desktop Python 3.11).

configure() receives bundled JSON assets, then dispatch() accepts/returns JSON.
No HTTP listener, Node.js, subprocess, stdin, executable downloads or TMDB key.
Only an allowlist of the bundled providers can be imported.
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
import _liveshelf
from _base import DEFAULT_USER_AGENT, log
from _embedded_cache import JsonCache
from _htmlkit import http_get, http_get_json, install_url_opener, normalize, token_expiry

TYPES = frozenset(("movie", "series", "anime", "tv"))
PLAYABLE = (".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".ts")

#: Lo que **nunca** es un vídeo: páginas, imágenes y datos.
NOT_PLAYABLE = (
    ".html", ".htm", ".php", ".asp", ".aspx", ".jsp", ".json", ".txt", ".css",
    ".js", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico", ".xml",
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
# Se publican antes que las de los proveedores: sus pósters y backdrops son los
# únicos de calidad cinematográfica que no dependen de la web de origen.
CINEMETA_CATALOGS = (
    ("movie", "top", "Películas populares"),
    ("series", "top", "Series populares"),
    ("movie", "imdbRating", "Mejor valoradas"),
    ("series", "imdbRating", "Series mejor valoradas"),
    ("movie", "year", "Estrenos recientes"),
    ("anime", "top", "Anime popular"),
)

# --------------------------------------------------------------------------- #
# Deportes (agenda del día)
# --------------------------------------------------------------------------- #
# Área de deportes al estilo del Sports de Apple TV: los partidos que hoy
# publica la agenda de los scrapers en vivo (título, liga, hora y estado) y los
# canales deportivos que aparezcan en la lista pública. No se inventa nada: si
# la agenda no trae partidos, la fila se queda vacía y el cliente la descarta.
SPORTS_CATALOG_ID = "deportes"
#: Canales que publican los proveedores de directo (se recogen solos).
CHANNELS_CATALOG_ID = "canales"
SHELF_TTL_SECONDS = 20 * 60
SHELF_STALE_SECONDS = 7 * 86400
SPORTS_EVENT_LIMIT = 24
SPORTS_CHANNEL_LIMIT = 8
SPORTS_KEYWORDS = (
    "espn", "fox sports", "tudn", "tyc", "dsports", "directv sports", "dazn",
    "gol tv", "tnt sports", "claro sports", "mlb", "nba", "nfl", "nhl", "ufc",
    "bein", "win sports", "sky sports", "eurosport", "deportes", "depor",
    "movistar deportes", "premier sports", "laliga", "liga mx",
)

# --------------------------------------------------------------------------- #
# Canales abiertos (iptv-org)
# --------------------------------------------------------------------------- #
# Las webs scrapeadas cambian cada semana y muchos días no devuelven ni un
# `.m3u8`. Para que la TV en vivo reproduzca siempre algo se añade una fila con
# la lista pública de iptv-org (https://github.com/iptv-org/iptv, datos CC0):
# señales HLS de emisión libre, sin registro ni DRM. Si GitHub no responde se
# usa esta copia mínima empaquetada.
IPTV_PREFIX = "iptvorg:"
IPTV_PLAYLISTS = (
    "https://iptv-org.github.io/iptv/languages/spa.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/mx.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/es.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/ar.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/co.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/cl.m3u",
    "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/pe.m3u",
)
BUNDLED_IPTV = (
    ("ADN 40", "https://mdstrm.com/live-stream-playlist/60b578b060947317de7b57ac.m3u8"),
    ("Azteca Internacional", "https://azt-mun.otteravision.com/azt/mun/mun.m3u8"),
    ("Imagen TV", "https://igd-it-runtime.otteravision.com/igd/it/it.m3u8"),
    ("Las Estrellas", "https://channel01-onlymex.akamaized.net/hls/live/2022749/event01/index.m3u8"),
    ("TeleFormula", "https://mdstrm.com/live-stream-playlist/62f2c855f7981b5a5a2d8763.m3u8"),
    ("Teleritmo", "https://mdstrm.com/live-stream-playlist/57b4dc126338448314449d0c.m3u8"),
    ("Popcorn Central", "https://oly-ftvpc.otteravision.com/oly/ftvpc/ftvpc.m3u8"),
)


def iptv_encode(url: str) -> str:
    """Id estable y sin caracteres problemáticos para una señal abierta."""
    return IPTV_PREFIX + base64.urlsafe_b64encode(url.encode("utf-8")).decode("ascii").rstrip("=")


def iptv_decode(content_id: str) -> Optional[str]:
    if not content_id.startswith(IPTV_PREFIX):
        return None
    payload = content_id[len(IPTV_PREFIX):]
    try:
        padded = payload + "=" * (-len(payload) % 4)
        return base64.urlsafe_b64decode(padded.encode("ascii")).decode("utf-8")
    except (ValueError, UnicodeDecodeError):
        return None


def parse_playlist(text: str, limit: int = 120) -> List[Dict[str, str]]:
    """M3U -> [{name, logo, url}] sin duplicados y sólo señales reproducibles."""
    channels: List[Dict[str, str]] = []
    seen = set()
    name, logo, blocked = "", "", False
    for raw in (text or "").splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            meta, _, title = line.partition(",")
            name = title.strip()
            logo_match = re.search(r'tvg-logo="([^"]*)"', meta)
            logo = logo_match.group(1) if logo_match else ""
            blocked = "geo-blocked" in line.lower()
        elif line and not line.startswith("#"):
            if blocked or not name or line in seen:
                continue
            if not line.lower().split("?")[0].endswith((".m3u8", ".mpd")):
                continue
            seen.add(line)
            channels.append({"name": name[:120], "logo": logo[:400], "url": line[:1500]})
            if len(channels) >= limit:
                break
    return channels


def iptv_channels(budget: float = 12.0) -> List[Dict[str, str]]:
    """Primera lista que responda; si ninguna, la copia empaquetada.

    `budget` es el tiempo TOTAL que se puede gastar sondeando listas: un
    televisor con DNS lento no debe quedarse 40 s en blanco la primera vez que
    se abre el Inicio. En cuanto se acaba el presupuesto se devuelve la copia
    empaquetada, que se reproduce sin más.
    """
    headers = {"Accept": "application/x-mpegURL,text/plain,*/*", "User-Agent": DEFAULT_USER_AGENT}
    deadline = time.monotonic() + budget
    for url in IPTV_PLAYLISTS:
        remaining = deadline - time.monotonic()
        if remaining <= 0.5:
            break
        try:
            text, _, _ = http_get(url, headers, min(6.0, remaining))
        except Exception:
            continue
        channels = parse_playlist(text)
        if channels:
            return channels
    return [{"name": name, "logo": "", "url": url} for name, url in BUNDLED_IPTV]



def normalize_meta(raw: Dict[str, Any], media_type: str) -> Optional[Dict[str, Any]]:
    content_id = str(raw.get("id") or raw.get("imdb_id") or "").strip()
    if not content_id:
        return None
    result = {"id": content_id, "type": raw.get("type") or media_type,
              "name": str(raw.get("name") or raw.get("title") or content_id)}
    for key in ("poster", "background", "logo", "description", "runtime", "sourceName", "_page", "_detail",
                "home", "away", "league", "startTime", "status"):
        if raw.get(key):
            result[key] = str(raw[key])
    # `onAir` es tri-state: True/False sólo para la agenda de deportes; si falta,
    # el cliente deduce el directo del tipo (`tv`).
    if isinstance(raw.get("onAir"), bool):
        result["onAir"] = raw["onAir"]
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


def is_sports_channel(name: str) -> bool:
    """¿El nombre del canal es de deportes? (ESPN, TUDN, MLB, DAZN…)."""
    lowered = (name or "").lower()
    return any(keyword in lowered for keyword in SPORTS_KEYWORDS)


def sports_event_meta(provider: Dict[str, Any], event: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Partido de la agenda → meta de la fila `deportes`.

    La lógica vive en `_liveshelf.event_meta` (la comparten el motor, el
    servidor y las pruebas); aquí queda el nombre histórico del motor.
    """
    return _liveshelf.event_meta(provider, event)


def declared_expiry(url: str) -> Optional[int]:
    values = [token_expiry(url)]
    query = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
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

    Antes se exigía una extensión de vídeo, y eso borraba la mitad de las
    fuentes reales: los CDN que sirven HLS en `/v/abc123` y los reproductores
    que declaran el tipo con `type: "application/x-mpegURL"`. Ahora se acepta
    lo que no es claramente una página, con la pista `mediaType` del scraper por
    delante.
    """
    parsed = urllib.parse.urlparse(url or "")
    if parsed.scheme not in ("https", "http") or not parsed.netloc:
        return False
    path = parsed.path.lower()
    if path.endswith(PLAYABLE):
        return True
    # `/player.php?id=7&file=master.m3u8`: el manifiesto viaja en la consulta.
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
#: Tope defensivo: ninguna película necesita 40 pistas.
SUBTITLE_LIMIT = 40

#: Formatos que Media3 lee de forma nativa (`.vtt` → TEXT_VTT, etc.).
SUBTITLE_FORMATS = {
    "vtt": "vtt", "webvtt": "vtt", "srt": "srt", "sub": "srt",
    "ass": "ass", "ssa": "ass", "ttml": "ttml", "dfxp": "ttml", "xml": "ttml",
}

#: Lo que nunca es una pista, aunque venga del mismo host.
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


def normalize_language(value: Any) -> str:
    """`SPA`, `es-419`, `Spanish`… → `es` / `es-419` (lo que espera ExoPlayer)."""
    text = re.sub(r"[_\s]+", "-", str(value or "").strip().lower())
    if not text:
        return ""
    base, _, region = text.partition("-")
    base = LANGUAGE_ALIASES.get(base, base)
    if not re.fullmatch(r"[a-z]{2,3}", base):
        return ""
    # `es-419` se conserva (latino ≠ castellano); un sufijo raro se descarta.
    return f"{base}-{region}" if re.fullmatch(r"([a-z]{2}|\d{3})", region) else base


def language_name(value: Any) -> str:
    """`es` → `Español`, `es-419` → `Español (Latino)`, `pt-BR` → `Portugués (Brasil)`."""
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
    """`.../foo.es.srt?token=…` → `srt`; sin extensión conocida, `None`."""
    clean = (url or "").split("#", 1)[0].split("?", 1)[0].lower()
    extension = clean.rsplit(".", 1)[-1] if "." in clean else ""
    return SUBTITLE_FORMATS.get(extension)


def normalize_subtitle(raw: Any, provider: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Una entrada cruda del scraper → subtítulo del protocolo Stremio.

    Devuelve `None` para lo que ExoPlayer no puede abrir: una página de embed,
    una imagen o una URL relativa no son pistas de texto.
    """
    if not isinstance(raw, dict):
        return None
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
    """Deduplica por URL, pone el español primero y corta en [SUBTITLE_LIMIT]."""
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



def normalize_stream(raw: Dict[str, Any], provider: Dict[str, Any], is_live: bool) -> Optional[Dict[str, Any]]:
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
    # Reject malformed header values; retain Cookie/Origin and any other required headers.
    canonical = {"referer": "Referer", "user-agent": "User-Agent", "origin": "Origin", "cookie": "Cookie"}
    headers = {canonical.get(str(key).lower(), str(key)): str(value)
               for key, value in headers.items() if value and re.fullmatch(r"[\w-]+", str(key))
               and "\n" not in str(value) and "\r" not in str(value)}
    hints.update(requestHeaders=headers, isLive=is_live)
    return {"url": url, "name": str(raw.get("name") or provider["label"]),
            "title": str(raw.get("title") or raw.get("name") or provider["label"]),
            "provider": provider["label"], "isLive": is_live, "behaviorHints": hints,
            **({"quality": str(raw["quality"])} if raw.get("quality") else {})}


class Engine:
    def __init__(self, cache_dir: str, registry: Dict[str, Any], live: Dict[str, Any], cinemeta_base: str = CINEMETA):
        self.cache = JsonCache(str(Path(cache_dir) / "responses"))
        self.cinemeta_base = cinemeta_base.rstrip("/")
        self.providers: Dict[str, Dict[str, Any]] = {}
        for item in sorted(registry.get("providers") or [], key=lambda p: p.get("priority", 100)):
            name = item.get("id")
            if name in providers.MODULES and item.get("script") == f"{name}.py" and item.get("enabled", True):
                self.providers[name] = item
        self.live = []
        # La estantería necesita el JSON curado tal cual (cada entrada trae su
        # `category`); `normalize_meta` se queda sólo con lo presentable.
        self.live_raw = [item for item in live.get("items") or [] if isinstance(item, dict)]
        for item in live.get("items") or []:
            meta = normalize_meta({**item, "type": "tv"}, "tv")
            if meta:
                self.live.append(meta)
        self.pool = concurrent.futures.ThreadPoolExecutor(max_workers=3, thread_name_prefix="provider")
        self.lock = threading.RLock()
        self.known: OrderedDict[str, Dict[str, Any]] = OrderedDict()
        self.inflight: Dict[str, concurrent.futures.Future] = {}

    def close(self):
        self.pool.shutdown(wait=True, cancel_futures=True)

    def catalogs(self, metadata_catalogs: bool = False) -> Dict[str, Any]:
        """Filas de la Home: Cinemeta (activado de fábrica, apagable) y canales en vivo.

        Los scrapers de películas/series/anime quedan fuera a propósito: son
        resuelven streams, no son catálogos. Su listado interno
        (`_embedded_catalogs.catalogue`) se usa para buscar por título dentro de
        `streams`, nunca para pintar filas.
        """
        rows: List[Dict[str, Any]] = []
        if metadata_catalogs:
            # Cinemeta va PRIMERO: es la única fuente que trae póster 2:3 real y
            # backdrop 16:9 para todos sus títulos, así que el hero y las
            # primeras filas se pintan con arte de verdad. Debajo sólo quedan
            # los canales en vivo: los scrapers no pintan filas.
            for order, (media_type, catalog_id, title) in enumerate(CINEMETA_CATALOGS):
                rows.append({"id": "cinemeta_" + catalog_id, "type": media_type, "name": title,
                             "posterShape": "poster", "order": order, "source": "cinemeta",
                             "provider": "Cinemeta"})
        rows.append({"id": "iptvorg", "type": "tv", "name": "TV en vivo · Canales abiertos",
                     "posterShape": "landscape", "order": len(rows), "source": "iptv",
                     "provider": "iptv-org"})
        rows.append({"id": "bundled_live", "type": "tv", "name": "TV en vivo · Canales guardados",
                     "posterShape": "landscape", "order": len(rows), "source": "embedded"})
        # El resto de canales de los proveedores de directo, recogidos solos: la
        # lista curada de arriba es el subconjunto fijo, esta es la grande.
        rows.append({"id": CHANNELS_CATALOG_ID, "type": "tv", "name": "TV en vivo · Canales",
                     "posterShape": "landscape", "order": len(rows), "source": "embedded"})
        # Agenda deportiva: partidos de hoy con su liga, equipos, hora y estado.
        # Va al final para que el hero siga saliendo de Cinemeta/canales.
        rows.append({"id": SPORTS_CATALOG_ID, "type": "tv", "name": "Agenda deportiva",
                     "posterShape": "landscape", "order": len(rows), "source": "embedded",
                     "layout": "sports"})
        # Los scrapers NO publican filas: no tienen portada, ni póster 2:3, ni
        # orden curado, y su listado es una sopa de títulos repetidos. Sólo
        # resuelven streams (ver `streams`), igual que los addons del servidor.
        return {"catalogs": rows}

    @staticmethod
    def _shelf_shape(value: Any) -> bool:
        return (isinstance(value, dict) and isinstance(value.get("channels"), list)
                and isinstance(value.get("events"), list))

    def _build_shelf(self) -> Dict[str, Any]:
        """Un scrape de los proveedores de directo, ya normalizado para la UI."""
        live_providers = [provider for name, provider in self.providers.items()
                          if name in providers.LIVE]
        # Sólo los canales se fijan a mano. La agenda es dato del día: si se
        # sembrara con el JSON curado, un partido de la semana pasada seguiría
        # apareciendo como "Hoy" y su Play estaría muerto.
        pinned = [item for item in self.live_raw
                  if "evento" not in normalize(str(item.get("category") or ""))]
        try:
            built = _liveshelf.build(live_providers, curated=pinned)
        except Exception as exc:
            log("embedded live shelf unavailable:", type(exc).__name__)
            return {"channels": [], "events": [], "meta": {}}
        return {
            "channels": [meta for item in built.get("channels") or []
                         if (meta := normalize_meta(item, "tv"))],
            "events": [meta for item in built.get("events") or []
                       if (meta := normalize_meta(item, "tv"))],
            "meta": built.get("meta") or {},
        }

    def live_shelf(self) -> Dict[str, List[Dict[str, Any]]]:
        """Canales y agenda de los proveedores de directo, con caché de 20 min.

        Se recoge en paralelo (`_liveshelf`) y se guarda entero: la Home pide las
        dos filas casi a la vez y no tiene sentido pagar dos veces el scraping.
        Un scrape vacío **no** reutiliza partidos viejos (serían tarjetas
        muertas: la agenda es dato del día), pero sí los canales: una lista de
        canales de ayer sigue sirviendo.
        """
        fresh = self.cache.get("shelf:live")
        if self._shelf_shape(fresh):
            return fresh

        shelf = self._build_shelf()
        if shelf["channels"] or shelf["events"]:
            self.cache.put("shelf:live", shelf, SHELF_TTL_SECONDS)
            self._remember(shelf["channels"] + shelf["events"])
            return shelf

        stale = self.cache.get("shelf:live", stale_seconds=SHELF_STALE_SECONDS)
        channels = stale.get("channels") if self._shelf_shape(stale) else []
        channels = channels or []
        if channels:
            log("embedded live shelf degraded: only stale channels")
        self._remember(channels)
        return {"channels": channels, "events": [],
                "meta": {"degraded": True, "reason": "shelf_unavailable"}}

    def iptv_metas(self) -> List[Dict[str, Any]]:
        """Canales abiertos ya normalizados, con caché de 6 h (y 7 días de respaldo)."""
        cached = self.cache.get("iptv:channels", stale_seconds=7 * 86400)
        if cached:
            self._remember(cached["metas"])
            return cached["metas"]
        metas: List[Dict[str, Any]] = []
        for channel in iptv_channels():
            url = channel.get("url") or ""
            meta = {"id": iptv_encode(url), "type": "tv", "name": channel.get("name") or "Canal abierto",
                    "sourceName": "iptv-org", "genres": ["Abierto"], "live": True}
            if channel.get("logo"):
                meta["poster"] = channel["logo"]
            metas.append(meta)
        bundled = {url for _, url in BUNDLED_IPTV}
        from_playlist = any(
            iptv_decode(meta["id"]) not in bundled for meta in metas
        )
        only_fallback = bool(metas) and not from_playlist
        if metas and not only_fallback:
            # Sólo se guarda lo que vino de la lista pública: si esto era la
            # copia empaquetada, la próxima apertura vuelve a intentar GitHub.
            self.cache.put("iptv:channels", {"metas": metas}, 6 * 3600)
        self._remember(metas)
        return metas

    def sports_metas(self) -> List[Dict[str, Any]]:
        """Partidos de hoy (agenda de los scrapers en vivo) + canales deportivos.

        La agenda cambia durante el día, así que se cachea 10 minutos y **no**
        hay respaldo rancio: enseñar el partido de ayer como si fuera de hoy es
        peor que dejar la fila vacía.
        """
        cached = self.cache.get("sports:today")
        if cached is not None:
            self._remember(cached["metas"])
            return cached["metas"]
        metas: List[Dict[str, Any]] = []
        seen = set()
        # La agenda sale de la estantería: los partidos de todos los proveedores
        # que publican programación (Fútbol Libre, DAMITV…), ya sin repetidos.
        for meta in self.live_shelf().get("events") or []:
            if meta["id"] not in seen:
                seen.add(meta["id"])
                metas.append(meta)
        # Directo primero y, dentro de cada grupo, por hora de comienzo.
        metas.sort(key=lambda meta: (not meta.get("onAir"), meta.get("startTime") or "99:99"))
        channels = [meta for meta in self.iptv_metas()
                    if is_sports_channel(str(meta.get("name") or ""))][:SPORTS_CHANNEL_LIMIT]
        for channel in channels:
            if channel["id"] in seen:
                continue
            seen.add(channel["id"])
            metas.append({**channel, "genres": ["Deportes", "Canal"], "onAir": True,
                          "releaseInfo": "En vivo", "status": "En vivo"})
        if metas:
            self.cache.put("sports:today", {"metas": metas}, 600)
        self._remember(metas)
        return metas

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
        if catalog_id == "iptvorg" and media_type == "tv":
            metas = self.iptv_metas()
        elif catalog_id == "bundled_live" and media_type == "tv":
            metas = self.live
        elif catalog_id == CHANNELS_CATALOG_ID and media_type == "tv":
            metas = self.live_shelf()["channels"]
        elif catalog_id == SPORTS_CATALOG_ID and media_type == "tv":
            metas = self.sports_metas()
        elif catalog_id in self.providers:
            # Contrato: el scraper resuelve streams, no publica catálogo. Si un
            # cliente viejo pide su fila, recibe una lista vacía (sin `degraded`:
            # no hay error, simplemente no hay catálogo que enseñar).
            return {"metas": []}
        else:
            try:
                if catalog_id.startswith("cinemeta_"):
                    upstream_type = "series" if media_type == "anime" else media_type
                    extras = {"search": query, "genre": "Animation" if media_type == "anime" else genre, "skip": skip or ""}
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
                # Stale titles/artwork are safe. Signed streams have NO stale fallback.
                stale = self.cache.get(key, stale_seconds=7 * 86400)
                if stale:
                    self._remember(stale["metas"])
                return stale or {"metas": [], "degraded": True}
        if catalog_id.startswith("cinemeta_"):
            # Cinemeta already applied server-side search/skip; do not paginate twice.
            result = {"metas": metas[:50]}
        else:
            # Provider search results may include translated titles: only filter live rows locally.
            if media_type == "tv" and query:
                metas = [m for m in metas if normalize(query) in normalize(m.get("name", ""))]
            if genre:
                metas = [m for m in metas if genre in m.get("genres", [])]
            result = {"metas": metas[max(0, skip):max(0, skip) + 50]}
        if result["metas"]:
            self._remember(result["metas"])
            self.cache.put(key, result, 600 if media_type == "tv" else 3600)
        return result

    def meta(self, media_type: str, content_id: str) -> Dict[str, Any]:
        base_id, _, _ = providers.split_id(content_id, media_type)
        key = f"meta:{media_type}:{base_id}"
        with self.lock:
            known = self.cache.get(key, stale_seconds=7 * 86400) or self.known.get(key) or {}
        if known.get("_detail") and self.cache.get(key):
            return {"meta": known}
        if media_type == "tv":
            found = next((item for item in self.live if item["id"] == base_id), None)
            return {"meta": found or known or None}
        try:
            if re.fullmatch(r"tt\d+", base_id):
                upstream_type = "series" if media_type == "anime" else media_type
                data = http_get_json(f"{self.cinemeta_base}/meta/{upstream_type}/{base_id}.json",
                                     {"Accept": "application/json", "User-Agent": "Streamflix/embedded"}, 6) or {}
                raw = data.get("meta")
                result = normalize_meta({**raw, "type": media_type, "sourceName": "Cinemeta"}, media_type) if raw else None
            else:
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

        Los subtítulos salen del `meta` del proveedor (hoy sólo novaHD los
        publica). Viajan con las fuentes para que la app no tenga que pedirlos
        aparte: el reproductor los necesita **antes** de crear el `MediaItem`,
        y una segunda petición retrasaría el arranque del vídeo.
        """
        try:
            result = providers.module_for(provider["id"]).scrape(payload)
            meta: Dict[str, Any] = {}
            if isinstance(result, tuple):
                streams, meta = result[0], (result[1] if len(result) > 1 and isinstance(result[1], dict) else {})
            elif isinstance(result, dict):
                streams, meta = result.get("streams", []), (result.get("meta") or {})
            else:
                streams = []
            subtitles = []
            if not payload["isLive"]:
                subtitles = [item for raw in (meta.get("subtitles") or [])
                             if (item := normalize_subtitle(raw, provider))]
            return {
                "streams": [item for raw in streams if isinstance(raw, dict)
                            and (item := normalize_stream(raw, provider, payload["isLive"]))],
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
        is_live = media_type == "tv"

        if base_id.startswith(IPTV_PREFIX):
            url = iptv_decode(base_id)
            if url:
                known = self.known.get(f"meta:{media_type}:{base_id}") or {}
                result = {"streams": [{
                    "url": url,
                    "name": "iptv-org",
                    "title": str(known.get("name") or "Canal abierto"),
                    "provider": "iptv-org",
                    "isLive": True,
                    "behaviorHints": {"isLive": True,
                                      "requestHeaders": {"User-Agent": DEFAULT_USER_AGENT}},
                }], "subtitles": [], "cacheMaxAge": 3600}
                return result
            return {"streams": [], "subtitles": [], "cacheMaxAge": 0}
        parts = base_id.split(":")
        explicit = parts[1] if is_live and len(parts) > 1 and parts[0] == "tv" else parts[0]
        selected = [self.providers[explicit]] if explicit in self.providers else [
            p for name, p in self.providers.items()
            if name in (providers.LIVE if is_live else providers.ANIME if media_type == "anime" else providers.MOVIES)]
        # Never feed an IMDb number to a site's slug parser or require a secret TMDB key.
        title = None
        if re.fullmatch(r"tt\d+", base_id):
            with self.lock:
                title = (self.known.get(f"meta:{media_type}:{base_id}") or {}).get("name")
            title = title or (self.meta(media_type, base_id).get("meta") or {}).get("name")
            if not title:
                return {"streams": [], "subtitles": [], "cacheMaxAge": 0}
        futures = []
        for provider in selected:
            provider_id = provider["id"]
            if is_live != (provider_id in providers.LIVE):
                continue
            request_id = f"{provider_id}:{title}" if title else content_id
            payload = {"type": media_type, "id": request_id, "season": season, "episode": episode,
                       "isLive": is_live, "timeoutMs": 8000, "referer": provider.get("referer"),
                       "userAgent": provider.get("userAgent") or DEFAULT_USER_AGENT, "provider": provider_id}
            flight_key = provider_id + ":" + key
            with self.lock:
                future = self.inflight.get(flight_key)
                if future is None or future.done():
                    future = self.pool.submit(self._scrape, provider, payload)
                    self.inflight[flight_key] = future
                    future.add_done_callback(lambda task, key=flight_key: self._forget_flight(key, task))
            futures.append((flight_key, future))
        if not futures:
            return {"streams": [], "subtitles": [], "cacheMaxAge": 0}
        done, pending = concurrent.futures.wait([future for _, future in futures], timeout=9)
        for future in pending:
            future.cancel()  # Cancels queued work; running calls retain their own socket/deadline bounds.
        streams, seen, subtitles = [], set(), []
        # Iteration order is the immutable priority order, not completion order.
        for flight_key, future in futures:
            if future in done and not future.cancelled():
                try:
                    payload_out = future.result()
                    for item in payload_out["streams"]:
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
        ttl = 60 if is_live else 300
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
        if operation == "catalogs":
            return self.catalogs(bool(request.get("metadataCatalogs", False)))
        if operation == "health":
            # Import every bundled script to catch packaging/stdlib problems in device smoke tests.
            imported = [name for name in self.providers if providers.module_for(name)]
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
            # Misma caché que las fuentes: pedir subtítulos después de los
            # streams no vuelve a raspar los proveedores.
            return {"subtitles": self.streams(media_type, content_id).get("subtitles") or []}
        raise ValueError("Unknown engine operation")


_engine: Optional[Engine] = None


def configure(cache_dir: str, registry_json: str, live_json: str, http_transport=None) -> None:
    global _engine
    # Must happen before importing provider modules which compute cache paths at import time.
    Path(cache_dir).mkdir(parents=True, exist_ok=True)
    tempfile.tempdir = cache_dir
    if http_transport is not None:
        from _android_http import AndroidUrlOpener
        install_url_opener(AndroidUrlOpener(http_transport))
    else:
        install_url_opener(None)
    _engine = Engine(cache_dir, json.loads(registry_json), json.loads(live_json))


def dispatch(operation: str, request_json: str) -> str:
    if _engine is None:
        raise RuntimeError("Embedded engine not configured")
    request = json.loads(request_json)
    if not isinstance(request, dict):
        raise ValueError("Expected a JSON object")
    return json.dumps(_engine.dispatch(operation, request), ensure_ascii=False, allow_nan=False)
