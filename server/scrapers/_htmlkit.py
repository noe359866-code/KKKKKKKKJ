"""
scrapers/_htmlkit.py
------------------------------------------------------------------------------
Helpers compartidos por los proveedores reales (tvenvivo, deportelibre,
futbolibre…). Sólo librería estándar.

Qué resuelve
    - Presupuesto de tiempo (`Deadline`) y de documentos (`Budget`) para que un
      scraper nunca exceda el `timeoutMs` que impone el puente Node.
    - `http_get()` con gzip/deflate (aunque la cabecera falte), detección de la
      codificación (UTF-8 antes que lo que declare el servidor) y un reintento
      acotado para 429/5xx y cortes de conexión.
    - `unescape_markup()` / `unpack_js()`: los sitios esconden las URLs dentro
      de JavaScript escapado (`https:\\/\\/…`, `\\u0026`, `&amp;`) y, a menudo,
      empaquetado con `eval(function(p,a,c,k,e,d)…)`, donde incluso el esquema
      se sustituye por un índice de la tabla (`46://host/…`).
    - `media_urls()` / `player_links()`: extracción genérica de señales
      (`.m3u8` / `.mpd` / `.mp4`) y de reproductores por los que seguir.
    - `sanitize_media()` y `token_expiry()`: normalizan candidatos y leen la
      caducidad del token (`?s=…&e=<epoch>`).
    - `guess_quality()`: `1080p` / `720p` / `4K`… a partir de la etiqueta del
      servidor o de la URL, con el mismo vocabulario que el cliente.

Contrato: este módulo no imprime nada por stdout; los diagnósticos van a
stderr con `_base.log()`.
"""

from __future__ import annotations

import gzip
import html as html_lib
import http.client
import re
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import zlib
from typing import Any, Dict, Iterable, List, Optional, Tuple

from _base import log

# ---------------------------------------------------------------------------
# Constantes
# ---------------------------------------------------------------------------

#: Hosts de publicidad/analítica: nunca son la señal.
BLOCKED_HOST_HINTS = (
    "doubleclick", "googlesyndication", "adservice", "adsystem", "adnxs",
    "popads", "popcash", "adsterra", "propellerads", "onclickads",
    "google-analytics", "googletagmanager", "histats", "sharethis",
)

#: Extensiones que nunca son un reproductor.
NON_PLAYER_SUFFIXES = (
    ".jpg", ".jpeg", ".png", ".gif", ".webp", ".svg", ".ico", ".css", ".js",
    ".woff", ".woff2", ".ttf", ".pdf", ".zip", ".xml", ".txt", ".webmanifest",
)

MEDIA_EXTENSIONS = (".m3u8", ".m3u", ".mpd", ".mp4", ".m4v")

#: Palabras que se dejan en mayúsculas al construir un nombre legible.
ACRONYMS = {
    "espn", "tudn", "tyc", "tnt", "fox", "dazn", "mls", "nba", "nfl", "ufc",
    "wwe", "usa", "uk", "hd", "tv", "nhl", "mlb", "dsports", "vtv", "dsp",
    "canal", "premiere", "op", "opt", "sd", "fhd",
}

#: Palabras de relleno en títulos/nombres.
TITLE_NOISE = (
    "en vivo", "online", "hd", "en directo", "gratis", "ver", "canal", "directo",
)

# ---------------------------------------------------------------------------
# Expresiones regulares
# ---------------------------------------------------------------------------

#: URL de medio con cadena de consulta opcional (`?s=<token>&e=<epoch>`).
#: El esquema puede venir empaquetado (`46://…`, donde 46 -> "https").
MEDIA_RE = re.compile(
    r"""(?:https?://|(?<![\w.])\d{1,3}://)[^\s"'<>\\]+?\.(?:m3u8|mpd|mp4|m4v)(?:\?[^\s"'<>\\]*)?""",
    re.IGNORECASE,
)

#: Cabecera del empaquetador Dean Edwards.
PACKER_RE = re.compile(
    r"function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)", re.IGNORECASE
)
PACKER_ARGS_RE = re.compile(r"',(\d{1,3}),(\d{1,4}),'([^']*)'\.split\('\|'\)", re.IGNORECASE)

#: `e=<epoch>`: caducidad del token del CDN.
EXPIRY_RE = re.compile(r"[?&]e=(\d{9,13})")

IFRAME_RE = re.compile(r"""<iframe[^>]+?src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
SOURCE_TAG_RE = re.compile(r"""<(?:source|video)[^>]+?src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
ANCHOR_RE = re.compile(r"""<a\b([^>]*)>(.*?)</a>""", re.IGNORECASE | re.DOTALL)
HREF_RE = re.compile(r"""href\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
ATTR_RE = re.compile(r"""([a-zA-Z-]+)\s*=\s*["']([^"']*)["']""")
SCRIPT_SRC_RE = re.compile(r"""<script[^>]+?src\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
TAG_RE = re.compile(r"<[^>]+>")
H_TAG_RE = re.compile(r"<h[1-4][^>]*>(.*?)</h[1-4]>", re.IGNORECASE | re.DOTALL)

#: `file: "…"` y su `type` en el mismo objeto (jwplayer y sus clones). La URL
#: puede no tener extensión: el `type` es lo que dice que es HLS o MP4.
TYPED_SOURCE_RE = re.compile(
    r"""(?:file|src|source|url|hls|mp4|video_url|videourl|media)\s*[:=]\s*["']([^"']{4,})["']"""
    r"""[^{}]{0,150}?type\s*[:=]\s*["']([^"']{3,})["']""",
    re.IGNORECASE,
)
#: Lo mismo con el `type` delante de la URL.
TYPE_FIRST_RE = re.compile(
    r"""type\s*[:=]\s*["']([^"']{3,})["']"""
    r"""[^{}]{0,150}?(?:file|src|source|url|hls|mp4)\s*[:=]\s*["']([^"']{4,})["']""",
    re.IGNORECASE,
)

#: Subcadena del MIME/tipo → tipo canónico de medio.
MEDIA_TYPE_HINTS: Tuple[Tuple[str, str], ...] = (
    ("mpegurl", "hls"),
    ("m3u8", "hls"),
    ("m3u", "hls"),
    ("hls", "hls"),
    ("mpeg-dash", "dash"),
    ("mpd", "dash"),
    ("dash", "dash"),
    ("mp4", "mp4"),
    ("m4v", "mp4"),
    ("quicktime", "mp4"),
)

#: `src="/hls/x.m3u8"`: medios con ruta relativa (los CDN del propio sitio).
RELATIVE_MEDIA_RE = re.compile(
    r"""["']((?:/|\.{1,2}/)[^"'\s<>]{2,}?\.(?:m3u8|mpd|mp4|m4v|m3u)(?:\?[^"'\s<>]*)?)["']""",
    re.IGNORECASE,
)

#: `?file=master.m3u8`: manifiestos que viajan en la consulta del reproductor.
MEDIA_QUERY_RE = re.compile(
    r"""[?&][^=&\s]*=[^&\s]*\.(?:m3u8|mpd|mp4|m4v)(?:[&#\s]|$)""", re.IGNORECASE
)

#: Tope de bytes que se leen de cada documento (un reproductor no pesa más).
MAX_BODY_BYTES = 1_500_000

#: Reintentos por petición: uno solo, y sólo si el primer intento falló pronto.
DEFAULT_RETRIES = 1

#: Códigos HTTP que sí merecen otro intento (rate limit y caídas pasajeras).
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504, 520, 522, 524})

#: Etiquetas de calidad que entiende el cliente. Mismo vocabulario que
#: `server/src/lib/quality.js` para que el addon pueda ordenar los streams.
#: El orden importa: primero lo más específico (`2160p` antes que `720p`).
QUALITY_STRONG: Tuple[Tuple[str, str], ...] = (
    (r"(?<![a-z0-9])(?:2160p?|4k|uhd)(?![a-z0-9])", "4K"),
    (r"(?<![a-z0-9])(?:1440p|2k)(?![a-z0-9])", "1440p"),
    (r"(?<![a-z0-9])(?:1080p|1080|fhd|full[\s_.-]?hd)(?![a-z0-9])", "1080p"),
    (r"(?<![a-z0-9])(?:720p|720)(?![a-z0-9])", "720p"),
    (r"(?<![a-z0-9])576p(?![a-z0-9])", "576p"),
    (r"(?<![a-z0-9])480p(?![a-z0-9])", "480p"),
    (r"(?<![a-z0-9])360p(?![a-z0-9])", "360p"),
)

#: Sólo válidas como etiqueta corta ("HD", "Latino HD"): dentro de una URL
#: larga son ruido (`hd.tubeload`, `/ts/`…).
QUALITY_WEAK: Tuple[Tuple[str, str], ...] = (
    (r"(?<![a-z0-9])hd(?![a-z0-9])", "720p"),
    (r"(?<![a-z0-9])sd(?![a-z0-9])", "SD"),
    (r"(?<![a-z0-9])(?:cam|ts)(?![a-z0-9])", "CAM"),
)

#: Longitud máxima de un texto que se considera "etiqueta" y no contenido.
QUALITY_LABEL_MAX = 32

# ---------------------------------------------------------------------------
# Presupuestos
# ---------------------------------------------------------------------------


class Deadline:
    """Presupuesto de tiempo: nunca exceder `timeoutMs` del padre."""

    def __init__(self, timeout_ms: Optional[int]) -> None:
        total = (timeout_ms or 12000) / 1000.0
        # Margen para serializar el JSON y salir antes del SIGKILL del puente.
        self._expires_at = time.monotonic() + max(1.0, total * 0.85)

    def remaining(self, cap: float = 8.0) -> float:
        return max(0.5, min(cap, self._expires_at - time.monotonic()))

    def expired(self) -> bool:
        return time.monotonic() >= self._expires_at


class Budget:
    """Presupuesto compartido de documentos por consulta (hilos incluidos)."""

    def __init__(self, max_docs: int) -> None:
        self.left = max(1, max_docs)
        self._lock = threading.Lock()

    def take(self) -> bool:
        with self._lock:
            if self.left <= 0:
                return False
            self.left -= 1
            return True


# ---------------------------------------------------------------------------
# Texto
# ---------------------------------------------------------------------------


def _js_unescape(text: str) -> str:
    """Desescapa una cadena JavaScript (comillas, barras y saltos)."""
    out: List[str] = []
    index = 0
    mapping = {"n": "\n", "t": "\t", "r": "\r", "'": "'", '"': '"', "\\": "\\", "/": "/"}
    while index < len(text):
        char = text[index]
        if char == "\\" and index + 1 < len(text):
            nxt = text[index + 1]
            out.append(mapping.get(nxt, nxt))
            index += 2
        else:
            out.append(char)
            index += 1
    return "".join(out)


def unescape_markup(text: str) -> str:
    """Deja legibles las URLs que viven dentro de JavaScript/JSON.

    Cubre `https:\\/\\/host\\/x.m3u8`, `\\u0026`, `&amp;` y comillas escapadas.
    Se repite hasta estabilizar porque algunas plantillas escapan dos veces.
    """
    if not text:
        return ""
    if "\\" in text:
        for _ in range(3):
            previous = text
            text = text.replace("\\/", "/").replace("\\'", "'").replace('\\"', '"')
            for escaped, char in (("\\u0026", "&"), ("\\u003d", "="), ("\\u003f", "?"),
                                  ("\\u002f", "/"), ("\\u003a", ":")):
                if escaped.lower() in text.lower():
                    text = re.sub(re.escape(escaped), char, text, flags=re.IGNORECASE)
            if text == previous:
                break
    if "&" in text:
        text = html_lib.unescape(text)
    return text


def unpack_js(text: str, limit: int = 12) -> str:
    """Desempaqueta los bloques `eval(function(p,a,c,k,e,d)…)` de un documento.

    El empaquetador sustituye cada token por su índice en la tabla de palabras,
    así que en el HTML se lee `46://host/…` en lugar de `https://host/…`. Sin
    desempaquetar no hay `.m3u8` reconocible. Devuelve el texto de todos los
    bloques encontrados (cadena vacía si no hay ninguno).
    """
    if not text or "function(p,a,c,k,e" not in text.lower():
        return ""

    decoded: List[str] = []
    for match in list(PACKER_RE.finditer(text))[:limit]:
        start = text.find("('", match.end())
        if start == -1:
            continue
        start += 2
        args = PACKER_ARGS_RE.search(text, start)
        if not args or args.start() - start > 400_000:
            continue
        payload = _js_unescape(text[start:args.start()])
        table = args.group(3).split("|")
        for index in range(len(table) - 1, -1, -1):
            if table[index]:
                payload = re.sub(r"\b%d\b" % index, table[index], payload)
        decoded.append(payload)

    return "\n".join(decoded)


def searchable(html: str) -> str:
    """Documento listo para buscar: desescapado y desempaquetado."""
    text = unescape_markup(html)
    unpacked = unpack_js(text)
    return f"{text}\n{unpacked}" if unpacked else text


def normalize(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text or "")
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch)).lower()


def slugify(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", normalize(text)).strip("-")


def pretty_name(value: str) -> str:
    """'espn-premium' -> 'ESPN Premium', 'dsports_op2' -> 'Dsports Op2'."""
    words = []
    for word in re.split(r"[-_\s]+", value or ""):
        if not word:
            continue
        if word.isdigit():
            words.append(word)
        elif word.lower() in ACRONYMS:
            words.append(word.upper())
        else:
            words.append(word[:1].upper() + word[1:])
    return " ".join(words) or value


def clean_title(value: str) -> str:
    """Limpia el nombre de una tarjeta o el `<title>` del sitio."""
    text = TAG_RE.sub(" ", html_lib.unescape(value or ""))
    text = re.split(r"\s*[|·]\s*", text)[0]
    text = text.replace("▷", " ").replace("»", " ")
    noise = {word for phrase in TITLE_NOISE for word in phrase.split()}
    kept = [
        word for word in re.findall(r"[^\s]+", text)
        if normalize(word).strip(" .·-,¿?¡!") not in noise
    ]
    cleaned = " ".join(kept).strip(" .·-|,»")
    return cleaned if len(cleaned) >= 2 else ""


def visible_text(chunk: str) -> str:
    """Texto visible de un trozo de HTML (sin etiquetas)."""
    return " ".join(TAG_RE.sub(" ", html_lib.unescape(chunk or "")).split())


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


# Android injects an OkHttp-backed opener with an overall call timeout. Node and
# desktop CLI retain urllib unchanged. This is installed once before any scraping.
_url_opener = None


def install_url_opener(opener) -> None:
    global _url_opener
    _url_opener = opener


def open_url(request, timeout: float):
    opener = _url_opener or urllib.request.urlopen
    return opener(request, timeout=timeout)


def _maybe_decompress(raw: bytes, encoding: str) -> bytes:
    """Descomprime gzip/deflate aunque la cabecera no lo diga (o mienta).

    Muchos CDN de los sitios de cine mandan gzip con `Content-Encoding` vacío en
    las respuestas de error; sin esto el HTML llega binario y no hay señales.
    """
    if raw[:2] == b"\x1f\x8b" or "gzip" in encoding:
        try:
            return gzip.decompress(raw)
        except (OSError, EOFError, zlib.error):
            return raw
    if "deflate" in encoding or raw[:1] == b"\x78":
        for wbits in (zlib.MAX_WBITS, -zlib.MAX_WBITS):
            try:
                return zlib.decompress(raw, wbits)
            except zlib.error:
                continue
    return raw


def _decode_text(raw: bytes, charset: str = "") -> str:
    """Bytes → texto, tolerante a sitios que declaran mal su codificación.

    Orden: BOM → UTF-8 estricto → `charset` declarado → `<meta charset>` del
    propio documento → cp1252. El UTF-8 va antes que la cabecera a propósito:
    media web latina declara `iso-8859-1` y sirve UTF-8, y eso rompía los
    títulos ("PokÃ©mon") justo antes de compararlos con la búsqueda.
    """
    if isinstance(raw, str):
        return raw
    if raw[:3] == b"\xef\xbb\xbf":
        return raw[3:].decode("utf-8", errors="replace")
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        return raw.decode("utf-16", errors="replace")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        pass
    for candidate in (charset, _sniff_charset(raw)):
        if not candidate:
            continue
        try:
            return raw.decode(candidate, errors="strict")
        except (UnicodeDecodeError, LookupError):
            continue
    try:
        return raw.decode("cp1252")
    except UnicodeDecodeError:
        return raw.decode("utf-8", errors="replace")


def _sniff_charset(raw: bytes) -> str:
    """`<meta charset>` / `<meta http-equiv>` de las primeras líneas."""
    head = raw[:2048].decode("latin-1", errors="replace")
    match = re.search(r"""charset\s*=\s*["']?\s*([\w-]+)""", head, re.IGNORECASE)
    return match.group(1) if match else ""


def _decode_body(raw: bytes, headers) -> str:
    """Cuerpo crudo → texto, descomprimiendo y resolviendo la codificación."""
    encoding = ""
    charset = ""
    if headers is not None:
        try:
            encoding = (headers.get("Content-Encoding") or "").lower()
        except (AttributeError, KeyError):
            encoding = ""
        try:
            charset = headers.get_content_charset() or ""
        except AttributeError:
            charset = ""
    return _decode_text(_maybe_decompress(raw, encoding), charset)


def _read_with_retries(request_factory, timeout: float, retries: int) -> Tuple[bytes, str, str]:
    """Ejecuta la petición con un reintento acotado por el propio `timeout`.

    El presupuesto total no crece: si el primer intento se comió más de la mitad
    del tiempo, no se reintenta. Así un host lento no empuja al scraper fuera
    del `timeoutMs` del puente, pero un 503 instantáneo sí se recupera.
    """
    started = time.monotonic()
    attempt = 0
    while True:
        attempt += 1
        try:
            with open_url(request_factory(), timeout=timeout) as response:
                raw = response.read(MAX_BODY_BYTES)
                headers = response.headers
                final_url = response.geturl() or ""
                content_type = ""
                if headers is not None:
                    content_type = headers.get("Content-Type") or ""
                return _decode_body(raw, headers), final_url, content_type
        except urllib.error.HTTPError as exc:
            if not _should_retry(attempt, retries, started, timeout, exc.code):
                raise
        except (urllib.error.URLError, http.client.HTTPException, OSError):
            if not _should_retry(attempt, retries, started, timeout, None):
                raise
        time.sleep(0.15)
        timeout = max(0.8, timeout - (time.monotonic() - started) - 0.15)


def _should_retry(attempt: int, retries: int, started: float, timeout: float,
                  status: Optional[int]) -> bool:
    """¿Merece otro intento? Sólo si es transitorio y quedó tiempo."""
    if attempt > retries:
        return False
    if status is not None and status not in RETRYABLE_STATUS:
        return False
    elapsed = time.monotonic() - started
    # Un timeout ya gastó el presupuesto: reintentar sólo alargaría la espera.
    return elapsed < timeout * 0.5 and timeout - elapsed > 0.6


def http_get(url: str, headers: Dict[str, str], timeout: float,
             retries: int = DEFAULT_RETRIES) -> Tuple[str, str, str]:
    """GET con timeout duro. Devuelve ``(texto, url_final, content_type)``.

    Un reintento extra para 429/5xx y cortes de conexión (ver `_should_retry`).
    """
    text, final_url, content_type = _read_with_retries(
        lambda: urllib.request.Request(url, headers=headers), timeout, retries
    )
    return text, final_url or url, content_type


def http_peek(url: str, headers: Dict[str, str], timeout: float, size: int = 2048) -> bytes:
    """Primeros bytes de una URL, para saber **qué** es (HLS, MP4, HTML…).

    Pide `Range: bytes=0-<size>` y lee como mucho `size`, así que no descarga el
    vídeo. Devuelve ``b""`` ante cualquier error: quien llama decide.
    """
    if size <= 0:
        return b""
    request_headers = {"Range": f"bytes=0-{max_size(size)}", **headers}
    try:
        with open_url(urllib.request.Request(url, headers=request_headers),
                      timeout=max(0.5, float(timeout))) as response:
            return response.read(size)
    except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError):
        return b""


def max_size(value: int) -> int:
    return max(1, int(value)) - 1


def http_get_json(url: str, headers: Dict[str, str], timeout: float) -> Any:
    """GET + JSON. Devuelve ``None`` si el cuerpo no es JSON válido."""
    import json

    text, _, _ = http_get(url, headers, timeout)
    try:
        return json.loads(text)
    except ValueError:
        return None


def http_post_json(url: str, data: Any, headers: Dict[str, str], timeout: float,
                   retries: int = 0) -> Any:
    """POST JSON + respuesta JSON (APIs GraphQL de los sitios de cine).

    Sin reintentos por defecto: un POST se repite sólo si el proveedor lo pide.
    """
    import json

    body = data if isinstance(data, (bytes, str)) else json.dumps(data)
    if isinstance(body, str):
        body = body.encode("utf-8")
    request_headers = {"Content-Type": "application/json", **headers}
    text, _, _ = _read_with_retries(
        lambda: urllib.request.Request(url, data=body, headers=request_headers, method="POST"),
        timeout,
        retries,
    )
    try:
        return json.loads(text)
    except ValueError:
        return None


# ---------------------------------------------------------------------------
# URLs
# ---------------------------------------------------------------------------


def absolute(url: str, base: str) -> str:
    value = unescape_markup(url or "").strip()
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


def media_kind(value: str) -> str:
    """`"application/x-mpegURL"`, `"video/mp4"`, `"hls"` → `"hls"`/`"dash"`/`"mp4"`."""
    text = (value or "").strip().lower()
    if not text or len(text) > 60:
        return ""
    for hint, kind in MEDIA_TYPE_HINTS:
        if hint in text:
            return kind
    return ""


def media_kind_of_url(url: str) -> str:
    """Tipo deducido de la extensión de la URL (``""`` si no hay ninguna)."""
    path = urllib.parse.urlparse(url or "").path.lower()
    if path.endswith((".m3u8", ".m3u")):
        return "hls"
    if path.endswith(".mpd"):
        return "dash"
    if path.endswith((".mp4", ".m4v")):
        return "mp4"
    return ""


def typed_media_urls(html: str, page_url: str = "") -> List[Tuple[str, str]]:
    """``[(url, tipo)]`` de las fuentes que declaran su `type`.

    Es la mitad de los reproductores (jwplayer y sus clones:
    `sources: [{file: "…", type: "application/x-mpegURL"}]`) y la URL no
    siempre acaba en `.m3u8`/`.mp4`; antes todo eso se perdía y el resultado era
    "sin fuentes" aunque el vídeo estuviera ahí.
    """
    if not html:
        return []
    text = searchable(html)
    pairs: List[Tuple[str, str]] = []
    for match in TYPED_SOURCE_RE.finditer(text):
        pairs.append((match.group(1), match.group(2)))
    for match in TYPE_FIRST_RE.finditer(text):
        pairs.append((match.group(2), match.group(1)))
    out: List[Tuple[str, str]] = []
    seen = set()
    for raw_url, raw_type in pairs:
        kind = media_kind(raw_type)
        if not kind:
            continue
        url = absolute(raw_url.strip(), page_url)
        if not url or is_blocked(url) or url in seen:
            continue
        seen.add(url)
        out.append((url, kind))
    return out


def relative_media_urls(html: str, page_url: str = "") -> List[str]:
    """Manifiestos con ruta relativa (`src="/hls/master.m3u8"`)."""
    if not html:
        return []
    out: List[str] = []
    for match in RELATIVE_MEDIA_RE.finditer(searchable(html)):
        url = absolute(match.group(1), page_url)
        if url and is_media_url(url):
            out.append(url)
    return unique(out)


def is_media_url(url: str) -> bool:
    """¿Es una señal reproducible?

    Acepta también los manifiestos que viajan en la consulta
    (`/player.php?id=1&file=master.m3u8`), que son la mitad de los reproductores
    de los sitios de deportes y antes se descartaban por la extensión.
    """
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return False
    if is_blocked(url):
        return False
    if parsed.path.lower().endswith(MEDIA_EXTENSIONS):
        return True
    if not parsed.query:
        return False
    if MEDIA_QUERY_RE.search("?" + parsed.query):
        return True
    # `?url=https://cdn/x.m3u8` (proxy de reproductor).
    return bool(re.search(r"""\.(?:m3u8|mpd|mp4|m4v)(?:[&#]|$)""", parsed.query, re.IGNORECASE))


def looks_like_player(url: str) -> bool:
    """Heurística de "aquí hay un reproductor", no un recurso estático."""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return False
    path = parsed.path.lower()
    if is_blocked(url) or path.endswith(NON_PLAYER_SUFFIXES):
        return False
    if is_media_url(url):
        return True
    return path.endswith((".php", ".html", ".htm")) or any(
        hint in path for hint in ("embed", "player", "repro", "mpd", "canal", "/vivo", "/hls")
    )


def unique(values: Iterable[str]) -> List[str]:
    out: List[str] = []
    seen = set()
    for value in values:
        if value and value not in seen:
            seen.add(value)
            out.append(value)
    return out


def sanitize_media(value: str) -> str:
    """Normaliza un candidato a URL de medio.

    - se queda con el primer esquema que produzca una URL válida (el texto
      empaquetado puede traer basura delante);
    - traduce el esquema empaquetado (`46://`) a `https://`;
    - descarta artefactos (host inválido o `:/` dentro de la ruta).
    """
    if not value:
        return ""
    for match in re.finditer(r"""(?:https?://|(?<![\w.])\d{1,3}://)""", value, re.IGNORECASE):
        url = re.sub(r"^\d{1,3}://", "https://", value[match.start():].strip())
        parsed = urllib.parse.urlparse(url)
        host = parsed.netloc or ""
        if not re.fullmatch(r"[a-z0-9.-]+(?::\d{1,5})?", host, re.IGNORECASE) or "." not in host:
            continue
        if ":/" in parsed.path:
            continue
        return url
    return ""


def guess_quality(*values: Any) -> Optional[str]:
    """Deduce la calidad (`"1080p"`, `"720p"`, `"4K"`…) de etiquetas y URLs.

    Se prueban los textos en el orden recibido: lo normal es pasar primero la
    etiqueta del sitio ("Latino HD", "1080p") y después la URL del manifiesto.
    Las palabras sueltas (`HD`, `SD`, `CAM`) sólo cuentan en textos cortos,
    para no etiquetar como 720p un host llamado `hd.tubeload`.

    Devuelve ``None`` si no hay ninguna pista: preferimos "sin dato" a mentir,
    porque el cliente ordena los streams con esta etiqueta.
    """
    texts = [normalize(str(value or "")).strip() for value in values]
    texts = [text for text in texts if text]
    # 1. Pistas explícitas ("1080p", "4K") en cualquier texto: mandan siempre.
    for text in texts:
        for pattern, label in QUALITY_STRONG:
            if re.search(pattern, text, re.IGNORECASE):
                return label
    # 2. Etiquetas cortas ("HD", "Latino HD"): sólo si no hay nada mejor. En una
    #    URL las palabras sueltas no son etiquetas (`hd.tubeload` no es 720p).
    for text in texts:
        if "://" in text or len(text) > QUALITY_LABEL_MAX:
            continue
        for pattern, label in QUALITY_WEAK:
            if re.search(pattern, text, re.IGNORECASE):
                return label
    return None


def token_expiry(url: str) -> Optional[int]:
    """Epoch de caducidad del token (`?s=…&e=…`), o ``None`` si no lo declara."""
    match = EXPIRY_RE.search(url or "")
    if not match:
        return None
    value = int(match.group(1))
    return value // 1000 if value > 10**11 else value


def strip_query_params(url: str, names: Iterable[str]) -> str:
    """Elimina parámetros concretos de la query (p. ej. `ip`, ligado al cliente)."""
    parsed = urllib.parse.urlparse(url)
    if not parsed.query:
        return url
    drop = {name.lower() for name in names}
    pairs = [
        (key, value) for key, value in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True)
        if key.lower() not in drop
    ]
    return urllib.parse.urlunparse(parsed._replace(query=urllib.parse.urlencode(pairs)))


# ---------------------------------------------------------------------------
# Extracción de señales
# ---------------------------------------------------------------------------


def media_urls(html: str, page_url: str = "", drop_params: Iterable[str] = ()) -> List[str]:
    """URLs reproducibles (.m3u8 / .mpd / .mp4) del documento o su JavaScript."""
    if not html:
        return []
    text = searchable(html)
    out: List[str] = []
    for value in MEDIA_RE.findall(text):
        url = sanitize_media(value.rstrip('"\',);'))
        if url:
            url = absolute(url, page_url)
        if not url or not is_media_url(url):
            continue
        if drop_params:
            url = strip_query_params(url, drop_params)
        out.append(url)
    for url in relative_media_urls(html, page_url):
        out.append(strip_query_params(url, drop_params) if drop_params else url)
    return unique(out)


def player_links(html: str, page_url: str = "", extra_hints: Iterable[str] = ()) -> List[str]:
    """iframes, `<source>` y URLs de reproductor por las que seguir.

    Los iframes son embeds explícitos y se aceptan aunque sean de otro dominio;
    las URLs sueltas sólo si su ruta delata un reproductor.
    """
    if not html:
        return []
    text = searchable(html)
    hints = tuple(extra_hints) or ("embed", "player", "repro", "mpd", "canal", "/vivo", "/hls")

    candidates: List[str] = []
    for value in IFRAME_RE.findall(text) + SOURCE_TAG_RE.findall(text):
        url = absolute(value, page_url)
        if url and looks_like_player(url) and not is_media_url(url):
            candidates.append(url)

    for value in re.findall(r"""["']((?:https?:)?//[^"'\\\s<>]{8,400})["']""", text):
        url = absolute(value, page_url)
        if not url or is_media_url(url) or is_blocked(url):
            continue
        path = urllib.parse.urlparse(url).path.lower()
        if any(hint in path for hint in hints):
            candidates.append(url)

    return unique(candidates)


def unwrap_redirect(url: str) -> List[str]:
    """Desenvuelve players del tipo `sw3.html?get=https://…/repro/x.html`.

    Devuelve la(s) URL(s) internas (decodificadas) y, si procede, la original.
    """
    parsed = urllib.parse.urlparse(url)
    if not parsed.query:
        return []
    inner: List[str] = []
    for key, value in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True):
        if key.lower() in ("get", "url", "u", "src") and value.startswith(("http://", "https://")):
            inner.append(value)
    return unique(inner)


def load_document(url: str, headers: Dict[str, str], deadline: Deadline,
                  referer: str = "") -> Tuple[str, str, str]:
    """GET con el `Referer` del padre. Propaga las excepciones de red."""
    request_headers = dict(headers)
    if referer:
        request_headers["Referer"] = referer
    return http_get(url, request_headers, deadline.remaining())


def follow_for_media(
    url: str,
    headers: Dict[str, str],
    deadline: Deadline,
    budget: Budget,
    max_depth: int = 2,
    drop_params: Iterable[str] = (),
    referer: str = "",
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Sigue un reproductor (y sus iframes) hasta encontrar las señales.

    Devuelve ``([{url, referer}], meta)``. Un reproductor que responde con el
    propio `.m3u8` (Content-Type `mpegurl`) también cuenta como señal.
    """
    found: List[Dict[str, Any]] = []
    errors: List[str] = []
    fetched = 0
    seen_media = set()

    queue: List[Tuple[str, int, str]] = [(url, 0, referer or url)]
    visited = set()

    # Un enlace "sw3.html?get=…" apunta al reproductor real: se encola primero.
    for inner in unwrap_redirect(url):
        queue.insert(0, (inner, 0, url))

    while queue and fetched < max_depth + 1 and not deadline.expired():
        current, depth, parent = queue.pop(0)
        if current in visited or not looks_like_player(current):
            continue
        visited.add(current)
        if not budget.take():
            errors.append("budget_exhausted")
            break

        try:
            body, final_url, content_type = load_document(current, headers, deadline, parent)
            fetched += 1
        except urllib.error.HTTPError as exc:
            errors.append(f"http_{exc.code}")
            log("player", current, "->", exc.code)
            continue
        except (urllib.error.URLError, OSError, ValueError) as exc:
            errors.append("request_failed")
            log("player", current, "failed:", exc)
            continue

        media = media_urls(body, final_url, drop_params)
        for item in media:
            if item not in seen_media:
                seen_media.add(item)
                found.append({"url": item, "referer": final_url})

        if not media and "mpegurl" in (content_type or "").lower() and is_media_url(final_url):
            if final_url not in seen_media:
                seen_media.add(final_url)
                found.append({"url": final_url, "referer": parent})

        if not media and depth + 1 <= max_depth:
            for child in player_links(body, final_url)[:3]:
                queue.append((child, depth + 1, final_url))

    return found, {"errors": errors, "fetched": fetched}
