#!/usr/bin/env python3
"""
scrapers/tvenvivo.py
------------------------------------------------------------------------------
Proveedor real para https://www.tvenvivo.org/ (TV en vivo / deportes, LATAM).

Cómo publica el sitio sus señales
---------------------------------
    1. Cada canal es una página  <slug>-en-vivo-online.php
       (p. ej. /espn-2-en-vivo-online.php). El índice del home enlaza todas.
    2. La página NO trae el .m3u8: define un mapa JavaScript con las opciones
       del reproductor, normalmente

           const SOURCES = {1:"/live/core.php?canal=espn-2", 2:"/live2/..."};

       y luego hace `videoFrame.src = SOURCES[n]`.
    3. Cada `core.php?canal=…` es un wrapper que devuelve un HTML con un
       <iframe> (o un reproductor JW/Clappr) que finalmente apunta al
       `.m3u8`/`.mpd` del CDN.

Por eso este scraper: busca los SOURCES, sigue los wrappers e iframes (con un
tope de documentos y de profundidad) y devuelve **una entrada por opción**
para que ExoPlayer haga failover opción 1 → 2 → 3.

IDs aceptados en `/stream/tv/<id>.json`
---------------------------------------
    tv:tvenvivo:espn-2          (recomendado)
    tvenvivo:espn-2
    espn-2
    espn-2-en-vivo-online       (o ...-en-vivo-online.php)
    https://www.tvenvivo.org/espn-2-en-vivo-online.php
    ESPN 2                      (texto libre: se resuelve contra el índice)

Uso desde la CLI
----------------
    python3 scrapers/tvenvivo.py --list                 # canales disponibles
    python3 scrapers/tvenvivo.py --list --query espn    # filtrar
    echo '{"type":"tv","id":"tv:tvenvivo:espn-2","isLive":true}' \
        | python3 scrapers/tvenvivo.py

Dependencias: sólo la librería estándar (urllib + re), así funciona en la
imagen base de Python sin `pip install`.
"""

from __future__ import annotations

import gzip
import json
import os
import re
import sys
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Iterable, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run, stream

# ---------------------------------------------------------------------------
# Configuración (todo sobreescribible por env para pruebas)
# ---------------------------------------------------------------------------

LABEL = "TV en Vivo"

#: Base del sitio. `TVENVIVO_BASE` permite apuntar a un fixture local en tests.
BASE_URL = os.getenv("TVENVIVO_BASE", "https://www.tvenvivo.org").rstrip("/")

#: Sufijos de página de canal, en orden de preferencia.
CHANNEL_SUFFIXES = ("-en-vivo-online.php", "-en-vivo.php")

#: Índice de canales en disco (evita re-descargar el home en cada consulta).
INDEX_CACHE_TTL = int(os.getenv("TVENVIVO_INDEX_TTL_SECONDS", "21600"))
INDEX_CACHE_PATH = os.getenv(
    "TVENVIVO_INDEX_CACHE",
    os.path.join(tempfile.gettempdir(), "streamflix-tvenvivo-index.json"),
)

#: Topes duros: un reproductor con anuncios no puede encadenar peticiones.
MAX_DOCS = int(os.getenv("TVENVIVO_MAX_DOCS", "8"))
MAX_DEPTH = int(os.getenv("TVENVIVO_MAX_DEPTH", "3"))
MAX_STREAMS = 6

#: Hosts de publicidad/analítica: nunca son la señal.
BLOCKED_HOST_HINTS = (
    "doubleclick",
    "googlesyndication",
    "adservice",
    "adsystem",
    "adnxs",
    "analytics",
    "google-analytics",
    "googletagmanager",
    "facebook.net",
    "sharethis",
)

#: Extensiones que nunca son un reproductor ni una señal.
NON_PLAYER_SUFFIXES = (
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico", ".css",
    ".js", ".woff", ".woff2", ".ttf", ".pdf", ".zip", ".xml", ".txt",
)

MEDIA_EXTENSIONS = (".m3u8", ".m3u", ".mpd", ".mp4")

# ---------------------------------------------------------------------------
# Expresiones regulares
# ---------------------------------------------------------------------------

M3U8_RE = re.compile(r"""https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""", re.IGNORECASE)
MPD_RE = re.compile(r"""https?://[^\s"'<>\\]+?\.mpd[^\s"'<>\\]*""", re.IGNORECASE)
MP4_RE = re.compile(r"""https?://[^\s"'<>\\]+?\.mp4(?:\?[^\s"'<>\\]*)?$""", re.IGNORECASE)
HLS_PATH_RE = re.compile(r"""https?://[^\s"'<>\\]+/(?:hls|live)/[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""", re.IGNORECASE)

IFRAME_RE = re.compile(r"""<iframe[^>]+?src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
SOURCE_TAG_RE = re.compile(r"""<(?:source|video)[^>]+?src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)

#: `SOURCES = {1:"/live/core.php?canal=x", 2:"…"}`  (puede ser const/let/var).
SOURCES_BLOCK_RE = re.compile(r"""\bSOURCES\s*=\s*\{(.*?)\}""", re.IGNORECASE | re.DOTALL)
SOURCE_PAIR_RE = re.compile(r"""["']?([\w-]{1,12})["']?\s*:\s*["']([^"']{1,500})["']""")

#: Configuración de reproductores (JW Player, Clappr, hls.js, Video.js…).
PLAYER_KEY_RE = re.compile(
    r"""(?:file|src|source|url|hls|playlist)\s*[:=]\s*["']([^"']{4,600})["']""",
    re.IGNORECASE,
)

#: Título del canal en el HTML.
H1_RE = re.compile(r"""<h1[^>]*>(.*?)</h1>""", re.IGNORECASE | re.DOTALL)
TITLE_RE = re.compile(r"""<title[^>]*>(.*?)</title>""", re.IGNORECASE | re.DOTALL)
TAG_RE = re.compile(r"""<[^>]+>""")

#: Palabras que se dejan en mayúsculas al construir un nombre legible.
ACRONYMS = {
    "espn", "tudn", "tyc", "tnt", "fox", "dazn", "mls", "rcn", "atv", "fx",
    "axn", "amc", "hbo", "nba", "nfl", "ufc", "wwe", "usa", "hd", "tv", "cw",
    "tdt", "rtve", "rpp", "ntn24", "c5n", "tn", "ln", "eltrece", "univision",
}

#: Palabras que se eliminan del título de la página al derivar el nombre.
TITLE_NOISE = ("en vivo", "online", "hd", "en directo", "gratis", "ver")


# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------


class Deadline:
    """Presupuesto de tiempo: nunca exceder `timeoutMs` del padre."""

    def __init__(self, timeout_ms: Optional[int]) -> None:
        total = (timeout_ms or 12000) / 1000.0
        # Margen para serializar el JSON y salir antes del SIGKILL del bridge.
        self._expires_at = time.monotonic() + max(1.0, total * 0.85)

    def remaining(self, cap: float = 8.0) -> float:
        return max(0.5, min(cap, self._expires_at - time.monotonic()))

    def expired(self) -> bool:
        return time.monotonic() >= self._expires_at


def html_unescape(text: str) -> str:
    """Deja legibles las URLs que viven dentro de JavaScript/JSON.

    Los wrappers escriben ``https:\\/\\/host\\/x.m3u8`` o ``\\u0026``; sin
    desescapar no se pueden reconstruir los enlaces.
    """
    if not text:
        return ""
    if "\\" in text:
        text = text.replace("\\/", "/")
        for escaped, char in (("\\u0026", "&"), ("\\u003d", "="), ("\\u003f", "?"),
                              ("\\u002f", "/"), ("\\u003a", ":")):
            if escaped.lower() in text.lower():
                text = re.sub(re.escape(escaped), char, text, flags=re.IGNORECASE)
        text = text.replace("\\'", "'").replace('\\"', '"')
    if "&" in text:
        import html as _html

        text = _html.unescape(text)
    return text


def normalize(text: str) -> str:
    """Minúsculas sin acentos, para comparar nombres de canal."""
    decomposed = unicodedata.normalize("NFKD", text or "")
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch)).lower()


def slugify(text: str) -> str:
    value = re.sub(r"[^a-z0-9]+", "-", normalize(text)).strip("-")
    return value


def pretty_name(slug: str) -> str:
    """'espn-2' -> 'ESPN 2', 'liga-1-max' -> 'Liga 1 Max'."""
    words = []
    for word in slug.split("-"):
        if not word:
            continue
        if word.isdigit():
            words.append(word)
        elif word in ACRONYMS:
            words.append(word.upper())
        else:
            words.append(word[:1].upper() + word[1:])
    return " ".join(words) or slug


def clean_title(title: str) -> str:
    """Limpia el <title>/<h1> del sitio: '▷ ESPN 2 EN VIVO HD | TV EN VIVO'."""
    value = TAG_RE.sub(" ", html_unescape(title or ""))
    value = re.split(r"\s*\|\s*", value)[0]
    value = value.replace("▷", " ").replace("»", " ")

    # Se descartan las palabras de relleno ("en", "vivo", "hd", "online"…)
    # conservando las mayúsculas originales de las que sí identifican el canal.
    noise = {word for phrase in TITLE_NOISE for word in phrase.split()}
    kept = [
        word for word in re.findall(r"[^\s]+", value)
        if normalize(word).strip(" .·-,¿?¡!") not in noise
    ]
    cleaned = " ".join(kept).strip(" .·-|,»")
    return cleaned if len(cleaned) >= 2 else ""


def http_get(url: str, headers: Dict[str, str], timeout: float) -> Tuple[str, str, str]:
    """GET con timeout duro. Devuelve ``(texto, url_final, content_type)``.

    Lanza la excepción original de urllib para que el llamador decida: aquí no
    se ocultan los errores, sólo se normaliza la descompresión.
    """
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read(1_500_000)
        encoding = (response.headers.get("Content-Encoding") or "").lower()
        if "gzip" in encoding:
            raw = gzip.decompress(raw)
        charset = response.headers.get_content_charset() or "utf-8"
        text = raw.decode(charset, errors="replace")
        return text, response.geturl() or url, response.headers.get("Content-Type") or ""


def absolute(url: str, base: str) -> str:
    """Resuelve una URL relativa y normaliza esquemas."""
    value = html_unescape(url or "").strip()
    if not value:
        return ""
    if value.startswith("//"):
        value = "https:" + value
    resolved = urllib.parse.urljoin(base, value)
    return resolved if resolved.startswith(("http://", "https://")) else ""


def host_of(url: str) -> str:
    try:
        return urllib.parse.urlparse(url).netloc.lower()
    except ValueError:
        return ""


def is_blocked(url: str) -> bool:
    host = host_of(url)
    return any(hint in host for hint in BLOCKED_HOST_HINTS)


def is_media_url(url: str) -> bool:
    """¿Es una URL que un reproductor puede abrir directamente?"""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return False
    if is_blocked(url):
        return False
    path = parsed.path.lower()
    return path.endswith(MEDIA_EXTENSIONS)


#: Trozos de ruta que delatan un reproductor.
PLAYER_PATH_HINTS = (
    "embed", "player", "reproductor", "core.php", "/live", "/hls", "/iframe", "repro",
)


def looks_like_player(url: str) -> bool:
    """Heurística de "aquí hay un reproductor", no un recurso estático.

    Se excluyen las páginas de canal (`…-en-vivo-online.php`): son enlaces de
    navegación ("ver más canales"), no reproductores, y seguirlos multiplicaría
    las peticiones.
    """
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return False
    if is_blocked(url):
        return False

    path = parsed.path.lower()
    if path.endswith(NON_PLAYER_SUFFIXES) or path.endswith(CHANNEL_SUFFIXES):
        return False
    if is_media_url(url):
        return True
    if not path or path == "/":
        return True
    if any(hint in path for hint in PLAYER_PATH_HINTS):
        return True
    return path.endswith((".php", ".html", ".htm"))


def unique(values: Iterable[str]) -> List[str]:
    out: List[str] = []
    seen = set()
    for value in values:
        if not value or value in seen:
            continue
        seen.add(value)
        out.append(value)
    return out


# ---------------------------------------------------------------------------
# Índice de canales (home -> slug/nombre)
# ---------------------------------------------------------------------------


def _index_from_html(html: str, page_url: str) -> List[Dict[str, str]]:
    """Extrae ``[{slug, name, url}]`` de los enlaces ``*-en-vivo(-online).php``."""
    text = html_unescape(html)
    channels: List[Dict[str, str]] = []
    seen = set()

    # Recorre cada ancla junto con su etiqueta (texto o alt/title de la imagen).
    for match in re.finditer(r"""<a\b([^>]*)>(.*?)</a>""", text, re.IGNORECASE | re.DOTALL):
        attrs, inner = match.group(1), match.group(2)
        href_match = re.search(r"""href\s*=\s*["']([^"']+)["']""", attrs, re.IGNORECASE)
        if not href_match:
            continue
        url = absolute(href_match.group(1), page_url)
        if not url.lower().endswith(CHANNEL_SUFFIXES):
            continue
        if host_of(url) != host_of(page_url):
            continue
        name = clean_title(inner)
        if not name:
            alt = re.search(r"""alt\s*=\s*["']([^"']+)["']""", inner, re.IGNORECASE)
            title_attr = re.search(r"""title\s*=\s*["']([^"']+)["']""", attrs, re.IGNORECASE)
            name = clean_title((alt or title_attr).group(1)) if (alt or title_attr) else ""
        slug = channel_slug_from_url(url)
        if not slug or slug in seen:
            continue
        seen.add(slug)
        channels.append({"slug": slug, "name": name or pretty_name(slug), "url": url})

    return channels


def channel_slug_from_url(url: str) -> str:
    """``…/espn-2-en-vivo-online.php`` -> ``espn-2``."""
    path = urllib.parse.urlparse(url).path
    name = os.path.basename(path)
    if name.endswith(".php"):
        name = name[:-4]
    for suffix in ("-en-vivo-online", "-en-vivo"):
        if name.endswith(suffix):
            return name[: -len(suffix)]
    return name


def read_index_cache() -> Optional[List[Dict[str, str]]]:
    if INDEX_CACHE_TTL <= 0 or not INDEX_CACHE_PATH:
        return None
    try:
        with open(INDEX_CACHE_PATH, "r", encoding="utf-8") as handle:
            payload = json.load(handle)
        if time.time() - float(payload.get("fetched_at", 0)) > INDEX_CACHE_TTL:
            return None
        channels = payload.get("channels") or []
        return channels if isinstance(channels, list) and channels else None
    except (OSError, ValueError):
        return None


def write_index_cache(channels: List[Dict[str, str]]) -> None:
    if INDEX_CACHE_TTL <= 0 or not INDEX_CACHE_PATH:
        return
    try:
        directory = os.path.dirname(INDEX_CACHE_PATH)
        if directory:
            os.makedirs(directory, exist_ok=True)
        tmp_path = f"{INDEX_CACHE_PATH}.{os.getpid()}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump({"fetched_at": time.time(), "channels": channels},
                      handle, ensure_ascii=False)
        os.replace(tmp_path, INDEX_CACHE_PATH)
    except OSError as exc:  # caché opcional: nunca rompe el scraping
        log("index cache not written:", exc)


def load_index(headers: Dict[str, str], deadline: Deadline,
               force_refresh: bool = False) -> List[Dict[str, str]]:
    """Lista de canales del home (caché en disco con TTL)."""
    if not force_refresh:
        cached = read_index_cache()
        if cached:
            return cached

    try:
        html, final_url, _ = http_get(f"{BASE_URL}/", headers, deadline.remaining())
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("index fetch failed:", exc)
        return read_index_cache() or []

    channels = _index_from_html(html, final_url)
    if channels:
        write_index_cache(channels)
    log(f"index: {len(channels)} canales")
    return channels


# ---------------------------------------------------------------------------
# Resolución del id -> página del canal
# ---------------------------------------------------------------------------


def parse_content_id(content_id: str) -> Tuple[str, str]:
    """Normaliza el id recibido y devuelve ``(slug, url_explicita)``.

    Acepta ``tv:tvenvivo:espn-2``, ``tvenvivo:espn-2``, ``espn-2``,
    ``espn-2-en-vivo-online.php`` o una URL completa. El slug es ``""`` cuando
    el id parece un nombre ("ESPN 2"), caso que se resuelve con el índice.
    """
    raw = (content_id or "").strip()
    if not raw:
        return "", ""

    # URL completa del propio sitio: se respeta la ruta tal cual.
    if raw.startswith(("http://", "https://")):
        return channel_slug_from_url(raw), raw

    candidate = raw.strip().strip("/")
    # Id de Stremio: el último segmento es el canal.
    if ":" in candidate:
        candidate = candidate.split(":")[-1]
    candidate = re.sub(r"\.(?:json|php)$", "", candidate, flags=re.IGNORECASE)

    slug = channel_slug_from_url(candidate) if "/" in candidate else candidate
    for suffix in ("-en-vivo-online", "-en-vivo"):
        if slug.endswith(suffix):
            slug = slug[: -len(suffix)]

    if re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,80}", slug):
        return slug, ""
    return "", ""


def find_in_index(query: str, channels: List[Dict[str, str]]) -> Optional[Dict[str, str]]:
    """Búsqueda tolerante (sin acentos, por slug o por nombre)."""
    wanted = slugify(query)
    if not wanted or not channels:
        return None

    # Comparación también sin separadores: "espn2" debe encontrar "espn-2".
    compact = re.sub(r"[^a-z0-9]", "", wanted)

    def compact_of(value: str) -> str:
        return re.sub(r"[^a-z0-9]", "", value or "")

    def score(channel: Dict[str, str]) -> int:
        slug = channel.get("slug", "")
        name = slugify(channel.get("name", ""))
        slug_compact, name_compact = compact_of(slug), compact_of(name)
        if not compact:
            return 0
        if wanted in (slug, name) or compact in (slug_compact, name_compact):
            return 100
        if slug.startswith(wanted) or name.startswith(wanted) \
                or slug_compact.startswith(compact) or name_compact.startswith(compact):
            return 70
        if wanted in slug or wanted in name \
                or compact in slug_compact or compact in name_compact:
            return 40
        return 0

    best = max(channels, key=score)
    return best if score(best) > 0 else None


def fetch_channel_page(target: str, headers: Dict[str, str],
                       deadline: Deadline) -> Tuple[Optional[str], str, str]:
    """Descarga la página del canal probando los sufijos conocidos del sitio.

    ``target`` puede ser un slug (``espn-2``) o una URL completa. Devuelve
    ``(url_final, html, error)``; ``html`` vacío si ninguna variante respondió.
    """
    if "/" in target:
        candidates = [target]
    else:
        candidates = [f"{BASE_URL}/{target}{suffix}" for suffix in CHANNEL_SUFFIXES]

    last_error = "not_found"
    for url in candidates:
        if deadline.expired():
            return None, "", "deadline"
        try:
            html, final_url, _ = http_get(url, headers, deadline.remaining())
            return final_url, html, ""
        except urllib.error.HTTPError as exc:
            last_error = f"http_{exc.code}"
            log("channel page", url, "->", exc.code)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            last_error = "request_failed"
            log("channel page", url, "failed:", exc)
    return None, "", last_error


# ---------------------------------------------------------------------------
# Extracción del reproductor
# ---------------------------------------------------------------------------


def extract_sources(html: str, page_url: str) -> List[str]:
    """URLs de los wrappers declarados en el mapa ``SOURCES`` de la página."""
    text = html_unescape(html)
    out: List[str] = []
    for block in SOURCES_BLOCK_RE.finditer(text):
        for _, value in SOURCE_PAIR_RE.findall(block.group(1)):
            url = absolute(value, page_url)
            if url and looks_like_player(url):
                out.append(url)
    return unique(out)


def extract_media(html: str, page_url: str) -> List[str]:
    """URLs reproducibles (HLS/DASH/MP4) presentes en el HTML o su JS."""
    text = html_unescape(html)
    found: List[str] = []
    for regex in (M3U8_RE, MPD_RE, HLS_PATH_RE, MP4_RE):
        found.extend(regex.findall(text))
    for value in PLAYER_KEY_RE.findall(text):
        url = absolute(value, page_url)
        if is_media_url(url):
            found.append(url)

    out: List[str] = []
    for value in found:
        url = absolute(value, page_url)
        if is_media_url(url) and not is_blocked(url):
            out.append(url)
    return unique(out)


def extract_players(html: str, page_url: str) -> List[str]:
    """iframes y `<source>` que pueden llevar al reproductor final.

    Los iframes son embeds explícitos y se aceptan aunque sean de otro dominio;
    las URLs sueltas dentro del JavaScript sólo se siguen si su ruta delata un
    reproductor (``/embed/``, ``core.php``, ``/player``…).
    """
    text = html_unescape(html)
    candidates: List[str] = []

    for value in IFRAME_RE.findall(text) + SOURCE_TAG_RE.findall(text):
        url = absolute(value, page_url)
        if url and looks_like_player(url):
            candidates.append(url)

    for value in PLAYER_KEY_RE.findall(text):
        url = absolute(value, page_url)
        if not url or is_media_url(url):
            continue
        path = urllib.parse.urlparse(url).path.lower()
        if any(hint in path for hint in PLAYER_PATH_HINTS):
            candidates.append(url)

    return unique(candidates)


def looks_like_hls(body: str, content_type: str) -> bool:
    if body.lstrip().startswith("#EXTM3U"):
        return True
    return "mpegurl" in (content_type or "").lower()


def resolve_streams(page_url: str, html: str, headers: Dict[str, str],
                    deadline: Deadline) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Recorre wrappers/iframes y devuelve las señales encontradas."""
    resolved: List[Dict[str, Any]] = []
    seen_media = set()
    queue: List[Tuple[str, int, str]] = []  # (url, profundidad, referer)
    meta: Dict[str, Any] = {"page": page_url, "options": 0, "fetched": 0, "errors": []}

    # 1) ¿La propia página ya trae el .m3u8?
    for media in extract_media(html, page_url):
        if media not in seen_media:
            seen_media.add(media)
            resolved.append({"url": media, "referer": page_url})

    # 2) Si no, seguir SOURCES + iframes.
    if not resolved:
        for option, url in enumerate(extract_sources(html, page_url), start=1):
            queue.append((url, 0, page_url))
        meta["options"] = len(queue)
        for url in extract_players(html, page_url):
            queue.append((url, 0, page_url))

    visited = set()
    while queue and len(resolved) < MAX_STREAMS and meta["fetched"] < MAX_DOCS:
        if deadline.expired():
            meta["errors"].append("deadline")
            break
        url, depth, referer = queue.pop(0)
        if url in visited or depth > MAX_DEPTH or not looks_like_player(url):
            continue
        visited.add(url)

        try:
            body, final_url, content_type = http_get(
                url, {**headers, "Referer": referer}, deadline.remaining()
            )
            meta["fetched"] += 1
        except urllib.error.HTTPError as exc:
            meta["errors"].append(f"http_{exc.code}")
            log("player", url, "->", exc.code)
            continue
        except (urllib.error.URLError, OSError, ValueError) as exc:
            meta["errors"].append("request_failed")
            log("player", url, "failed:", exc)
            continue

        # El wrapper *es* el playlist (algunos devuelven el .m3u8 directo).
        if looks_like_hls(body, content_type):
            if final_url not in seen_media:
                seen_media.add(final_url)
                resolved.append({"url": final_url, "referer": referer})
            continue

        media = extract_media(body, final_url)
        for item in media:
            if item in seen_media or len(resolved) >= MAX_STREAMS:
                continue
            seen_media.add(item)
            resolved.append({"url": item, "referer": final_url})

        if not media:
            for child in extract_players(body, final_url)[:4]:
                queue.append((child, depth + 1, final_url))

    return resolved, meta


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    content_id = str(payload.get("id") or payload.get("canal") or "").strip()

    if payload.get("isLive") is False:
        # Este proveedor sólo sirve señal en directo: no responde a VOD.
        return [], {"reason": "not_live", "id": content_id}

    headers = default_headers(payload)
    headers.setdefault("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    headers.setdefault("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT

    deadline = Deadline(payload.get("timeoutMs"))

    slug, explicit_url = parse_content_id(content_id)
    page_url: Optional[str] = explicit_url or None
    name = pretty_name(slug) if slug else ""
    index: List[Dict[str, str]] = []

    # 1) Sin slug determinista ("ESPN 2"): resolver contra el índice del home.
    if not slug and not explicit_url:
        index = load_index(headers, deadline)
        match = find_in_index(content_id, index)
        if not match:
            return [], {"reason": "channel_not_found", "id": content_id,
                        "known": len(index)}
        slug = match["slug"]
        name = match.get("name") or pretty_name(slug)
        log("resolved", content_id, "->", slug)

    # 2) Página del canal (probando -en-vivo-online.php / -en-vivo.php).
    html, error = "", ""
    if page_url:
        page_url, html, error = fetch_channel_page(page_url, headers, deadline)
    if not html and slug:
        page_url, html, error = fetch_channel_page(slug, headers, deadline)

    # 3) El slug construido no existe (p. ej. "espn2" vs "espn-2"):
    #    último intento buscándolo en el índice por nombre.
    if not html and not explicit_url:
        index = index or load_index(headers, deadline)
        match = find_in_index(slug or content_id, index)
        if match and match["slug"] != slug:
            page_url, html, error = fetch_channel_page(match["slug"], headers, deadline)
            if html:
                name = match.get("name") or name

    if not page_url or not html:
        return [], {"reason": "channel_unavailable", "id": content_id,
                    "error": error or "not_found"}

    # 4) Nombre visible: <h1> del canal > <title> > slug legible.
    h1_match = H1_RE.search(html)
    page_title = clean_title(h1_match.group(1)) if h1_match else ""
    if not page_title:
        title_match = TITLE_RE.search(html)
        page_title = clean_title(title_match.group(1)) if title_match else ""
    name = page_title or name or pretty_name(channel_slug_from_url(page_url))

    # 5) Reproductor -> señales, una entrada por opción (failover 1 -> 2 -> 3).
    resolved, meta = resolve_streams(page_url, html, headers, deadline)

    streams: List[Dict[str, Any]] = []
    for position, item in enumerate(resolved, start=1):
        media_url = item["url"]
        quality_match = re.search(r"(\d{3,4})p", media_url, re.IGNORECASE)
        option = f"Opción {position}"
        streams.append(
            stream(
                url=media_url,
                name=f"{name} · {option}",
                title=f"{LABEL} · {name} · {option}",
                quality=(f"{quality_match.group(1)}p" if quality_match else None),
                referer=item.get("referer") or page_url,
                user_agent=user_agent,
                is_live=True,
            )
        )

    meta.update({"channel": name, "streams": len(streams), "page": page_url})
    log(f"id={content_id} channel={name!r} streams={len(streams)}")
    return streams, meta


# ---------------------------------------------------------------------------
# CLI: lista de canales (`--list`)
# ---------------------------------------------------------------------------


def _print_list(query: str, as_json: bool, refresh: bool) -> None:
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,*/*;q=0.8",
        "Accept-Language": "es-ES,es;q=0.9",
    }
    deadline = Deadline(20000)
    channels = load_index(headers, deadline, force_refresh=refresh)
    if query:
        wanted = slugify(query)
        channels = [
            channel for channel in channels
            if wanted in channel.get("slug", "") or wanted in slugify(channel.get("name", ""))
        ]

    if as_json:
        print(json.dumps(
            [{"id": f"tv:tvenvivo:{channel['slug']}", **channel} for channel in channels],
            ensure_ascii=False, indent=2,
        ))
        return

    if not channels:
        print("No se encontraron canales (¿el sitio cambió o no hay red?).")
        return

    print(f"{len(channels)} canales en {BASE_URL}\n")
    width = max(len(channel.get("name", "")) for channel in channels)
    for channel in channels:
        print(f"  {channel.get('name', ''):<{width}}  tv:tvenvivo:{channel['slug']}")
    print("\nÚsalo en Stremio:  /stream/tv/tv:tvenvivo:<slug>.json")


def main() -> None:
    args = sys.argv[1:]
    if args and args[0] in ("-h", "--help"):
        print(__doc__)
        return
    if args and args[0] == "--list":
        query = ""
        as_json = "--json" in args
        refresh = "--refresh" in args
        if "--query" in args:
            query = args[args.index("--query") + 1] if len(args) > args.index("--query") + 1 else ""
        _print_list(query, as_json, refresh)
        return

    # Modo bridge (Node): JSON por stdin, JSON por stdout.
    run(scrape)


if __name__ == "__main__":
    main()
