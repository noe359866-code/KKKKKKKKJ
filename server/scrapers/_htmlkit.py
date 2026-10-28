"""
scrapers/_htmlkit.py
------------------------------------------------------------------------------
Helpers compartidos por los proveedores reales (tvenvivo, deportelibre,
futbolibre…). Sólo librería estándar.

Qué resuelve
    - Presupuesto de tiempo (`Deadline`) y de documentos (`Budget`) para que un
      scraper nunca exceda el `timeoutMs` que impone el puente Node.
    - `http_get()` con gzip y timeout duro.
    - `unescape_markup()` / `unpack_js()`: los sitios esconden las URLs dentro
      de JavaScript escapado (`https:\\/\\/…`, `\\u0026`, `&amp;`) y, a menudo,
      empaquetado con `eval(function(p,a,c,k,e,d)…)`, donde incluso el esquema
      se sustituye por un índice de la tabla (`46://host/…`).
    - `media_urls()` / `player_links()`: extracción genérica de señales
      (`.m3u8` / `.mpd` / `.mp4`) y de reproductores por los que seguir.
    - `sanitize_media()` y `token_expiry()`: normalizan candidatos y leen la
      caducidad del token (`?s=…&e=<epoch>`).

Contrato: este módulo no imprime nada por stdout; los diagnósticos van a
stderr con `_base.log()`.
"""

from __future__ import annotations

import gzip
import html as html_lib
import re
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
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

#: Tope de bytes que se leen de cada documento (un reproductor no pesa más).
MAX_BODY_BYTES = 1_500_000

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


def http_get(url: str, headers: Dict[str, str], timeout: float) -> Tuple[str, str, str]:
    """GET con timeout duro. Devuelve ``(texto, url_final, content_type)``."""
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read(MAX_BODY_BYTES)
        if "gzip" in (response.headers.get("Content-Encoding") or "").lower():
            raw = gzip.decompress(raw)
        charset = response.headers.get_content_charset() or "utf-8"
        text = raw.decode(charset, errors="replace")
        return text, response.geturl() or url, response.headers.get("Content-Type") or ""


def http_get_json(url: str, headers: Dict[str, str], timeout: float) -> Any:
    """GET + JSON. Devuelve ``None`` si el cuerpo no es JSON válido."""
    import json

    text, _, _ = http_get(url, headers, timeout)
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


def is_media_url(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return False
    return parsed.path.lower().endswith(MEDIA_EXTENSIONS) and not is_blocked(url)


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
