#!/usr/bin/env python3
"""
scrapers/_livekit.py
------------------------------------------------------------------------------
Plomería común de los proveedores de **TV y deporte en directo**.

Los tres proveedores de directo que ya había (`futbolibre`, `deportelibre`,
`tvenvivo`) crecieron cada uno por su lado porque cada sitio tenía su forma.
Los nuevos (`livelive24`, `damitv`, `ntv`, `streamcorner`) se parecen mucho
entre sí: un índice con canales/partidos y una página (o una API) por evento que
esconde un `.m3u8`. Este módulo concentra lo que comparten:

    fetch()               GET tolerante con deadline (HTML o JSON).
    fetch_json()          Lo mismo, decodificando JSON.
    media_candidates()    Todo lo que en una página *parece* un medio: `m3u8`,
                          `mpd` y `mp4` con o sin extensión, fuentes tipadas,
                          `data-*`, literales JS y base64.
    player_candidates()   Páginas intermedias (iframes y reproductores).
    harvest()             BFS corto: descarga la página del evento, sigue como
                          mucho `max_docs` reproductores y devuelve los medios.
    anchor_entries()      Índice HTML → `[{id, name, url, live}]`.
    build_live_streams()  Medios → streams de Stremio (`isLive`, `mediaType`).
    json_walk()           Recorre JSON anidado (APIs como la de DAMITV).
    decode_embedded_url() Tapa el caso `player?url=%2Fc%2F346.m3u8` → `/c/346.m3u8`.

Nada de esto publica canales: los proveedores son **resolvedores**. El catálogo
de la app es `server/config/live-catalog.json` (fila de programación).
"""

from __future__ import annotations

import base64
import json
import os
import re
import urllib.error
import urllib.parse
from typing import Any, Dict, Iterable, List, Optional, Tuple

from _base import log, stream
from _hosters import probe_media_kind, resolve_all
from _htmlkit import (
    Deadline,
    absolute,
    guess_quality,
    host_of,
    http_get,
    is_media_url,
    looks_like_player,
    media_kind_of_url,
    media_urls,
    normalize,
    player_links,
    pretty_name,
    typed_media_urls,
    unique,
    unescape_markup,
    visible_text,
)

#: Tope de señales publicadas por evento (el resto se descarta).
MAX_STREAMS = int(os.getenv("LIVE_MAX_STREAMS", "6"))
#: Documentos que como mucho se siguen por evento (la página + sus reproductores).
MAX_DOCS = int(os.getenv("LIVE_MAX_DOCS", "4"))
#: Profundidad del BFS (0 = sólo la página del evento).
MAX_DEPTH = int(os.getenv("LIVE_MAX_DEPTH", "1"))


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------


def env_bases(default: str, base_env: str, mirrors_env: str) -> List[str]:
    """Dominios a probar, sin repetir y en orden.

    Si `*_BASE` está definido manda **solo** (más `*_MIRRORS`): es lo que usan
    los tests para apuntar a un puerto cerrado y lo que usa quien despliega
    contra un espejo. El dominio por defecto sólo entra cuando no hay override,
    para que un test nunca acabe hablando con el sitio real.
    """
    explicit = (os.getenv(base_env) or "").strip().rstrip("/")
    bases: List[str] = [explicit] if explicit else [default.rstrip("/")]
    for value in (os.getenv(mirrors_env, "") or "").split(","):
        cleaned = value.strip().rstrip("/")
        if cleaned and cleaned not in bases:
            bases.append(cleaned)
    return bases


def fetch(
    url: str, headers: Dict[str, str], deadline: Deadline
) -> Optional[Tuple[str, str, str]]:
    """GET tolerante: ``(cuerpo, url_final, content_type)`` o ``None``."""
    try:
        body, final_url, content_type = http_get(url, headers, deadline.remaining())
        return body, final_url, content_type or ""
    except urllib.error.HTTPError as exc:
        log("fetch", url, "->", exc.code)
        return None
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("fetch", url, "failed:", exc)
        return None


def fetch_many(
    urls: Iterable[str],
    headers: Dict[str, str],
    deadline: Deadline,
    stop_after: int = 2,
    cap: int = 8,
) -> List[Tuple[str, str, str]]:
    """Descarga varias URLs cortando cuando el sitio no responde.

    Un dominio muerto falla rápido pero no gratis (cada intento paga el
    reintento de `_htmlkit`), y estos índices multiplican bases por rutas: sin
    este corte, un sitio caído cuesta segundos. Tras `stop_after` fallos
    seguidos se asume que el sitio no está y se devuelve lo que haya.
    """
    results: List[Tuple[str, str, str]] = []
    failures = 0
    for url in list(urls)[:cap]:
        if deadline.expired():
            break
        found = fetch(url, headers, deadline)
        if not found:
            failures += 1
            if failures >= stop_after:
                break
            continue
        failures = 0
        results.append(found)
    return results


def fetch_json(url: str, headers: Dict[str, str], deadline: Deadline) -> Any:
    """GET + JSON; `None` si lo que llega no es JSON."""
    found = fetch(url, headers, deadline)
    if not found:
        return None
    body, final_url, _ = found
    try:
        return json.loads(body)
    except (TypeError, ValueError):
        log("fetch", url, "no es JSON")
        return None


# ---------------------------------------------------------------------------
# Candidatos dentro de una página
# ---------------------------------------------------------------------------

#: `data-*` que los reproductores usan para la fuente (DAMITV, StreamCorner…).
_DATA_ATTR_RE = re.compile(
    r"""data-(?:src|source|url|file|hls|stream|video|player|link|iframe|embed)\s*=\s*["']([^"']+)["']""",
    re.IGNORECASE,
)
#: Literales JS típicos: `file: "…"`, `sources:[{src:"…"}]`, `hls="…"`.
_JS_SOURCE_RE = re.compile(
    r"""(?:file|source|src|url|link|hls|hls_url|playlist|manifest|stream)\s*[:=]\s*["']([^"']{12,})["']""",
    re.IGNORECASE,
)
#: Texto en base64 que podría decodificar a una URL (`atob("aHR0cHM6…")`).
_B64_RE = re.compile(r"""["']([A-Za-z0-9+/]{24,}={0,2})["']""")


def _looks_media(value: str) -> bool:
    lowered = (value or "").lower()
    if not lowered.startswith(("http://", "https://", "/")):
        return False
    if is_media_url(value):
        return True
    return any(hint in lowered for hint in (".m3u8", ".mpd", ".mp4", "m3u8?", "format=m3u8"))


def _decode_b64(value: str) -> str:
    padded = value + "=" * (-len(value) % 4)
    try:
        decoded = base64.b64decode(padded).decode("utf-8", "strict")
    except (ValueError, UnicodeDecodeError):
        return ""
    if decoded.startswith(("http://", "https://", "/")) and len(decoded) < 800:
        return decoded.strip()
    return ""


def media_candidates(html: str, page_url: str = "") -> List[str]:
    """URLs de medio en una página, en el orden en que aparecen.

    Cubre lo que ya sabemos que usan estos sitios: `m3u8`/`mpd`/`mp4` en texto,
    fuentes tipadas sin extensión (`sources:[{file, type}]`), atributos
    `data-*`, literales JS y blobs base64 (los `atob` de algunos clones).
    """
    found: List[str] = []
    found.extend(media_urls(html, page_url))
    found.extend(url for url, _kind in typed_media_urls(html, page_url))

    body = html or ""
    for match in _DATA_ATTR_RE.finditer(body):
        found.append(match.group(1))
    for match in _JS_SOURCE_RE.finditer(body):
        found.append(unescape_markup(match.group(1)))
    for match in _B64_RE.finditer(body):
        decoded = _decode_b64(match.group(1))
        if decoded:
            found.append(decoded)

    resolved: List[str] = []
    for value in found:
        candidate = absolute(unescape_markup(value.strip()), page_url)
        if candidate and _looks_media(candidate) and candidate not in resolved:
            resolved.append(candidate)
    return resolved


def player_candidates(
    html: str, page_url: str = "", extra_hints: Iterable[str] = ()
) -> List[str]:
    """Iframes y páginas de reproductor (no medios) a las que vale la pena ir."""
    found: List[str] = []
    for value in player_links(html, page_url, extra_hints):
        candidate = absolute(unescape_markup(value.strip()), page_url)
        if candidate and candidate not in found:
            found.append(candidate)
    for match in _DATA_ATTR_RE.finditer(html or ""):
        candidate = absolute(unescape_markup(match.group(1).strip()), page_url)
        if candidate and candidate not in found and looks_like_player(candidate):
            found.append(candidate)
    return [url for url in found if not _looks_media(url)]


# ---------------------------------------------------------------------------
# Descenso desde la página del evento
# ---------------------------------------------------------------------------


def harvest(
    page_url: str,
    headers: Dict[str, str],
    deadline: Deadline,
    max_docs: int = MAX_DOCS,
    max_depth: int = MAX_DEPTH,
    player_hints: Iterable[str] = (),
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Sigue la página del evento y sus reproductores hasta encontrar medios.

    Devuelve ``([{url, referer, source}], meta)``. El orden es el de aparición:
    el primero es el que el sitio considera principal (y es el que ExoPlayer
    intentará antes).
    """
    media: List[Dict[str, Any]] = []
    errors: List[str] = []
    info: Dict[str, Any] = {"title": ""}
    queue: List[Tuple[str, int, str]] = [(page_url, 0, page_url)]
    visited: set = set()
    fetched = 0
    seen_media: set = set()

    while queue and fetched < max_docs and not deadline.expired():
        url, depth, referer = queue.pop(0)
        if url in visited:
            continue
        visited.add(url)

        found = fetch(url, {**headers, "Referer": referer}, deadline)
        fetched += 1
        if not found:
            errors.append("fetch_failed")
            continue
        body, final_url, content_type = found
        if depth == 0 and not info["title"]:
            info["title"] = page_title(body)

        if not body.strip():
            errors.append("empty")

        # Un `.m3u8` servido en crudo (algunos CDN lo devuelven como texto).
        if "mpegurl" in (content_type or "").lower() and final_url not in seen_media:
            seen_media.add(final_url)
            media.append({"url": final_url, "referer": referer, "source": ""})

        for candidate in media_candidates(body, final_url):
            if candidate in seen_media:
                continue
            seen_media.add(candidate)
            media.append({"url": candidate, "referer": final_url, "source": ""})

        if media or depth + 1 > max_depth:
            continue

        # Sin medios todavía: probar los reproductores de la página.
        children = player_candidates(body, final_url, player_hints)[:3]
        for child in children:
            # Algunos reproductores llevan la ruta del manifiesto en su propia
            # consulta (`/player/jw/?url=%2Fc%2F346.m3u8`): eso ya es la señal.
            direct = decode_embedded_url(child, final_url)
            if direct and direct not in seen_media:
                seen_media.add(direct)
                media.append({"url": direct, "referer": final_url, "source": ""})
                continue
            queue.append((child, depth + 1, final_url))

        # Y los "servidores" conocidos (Streamwish, VOE…), que sí sabemos abrir.
        hosters = resolve_all(
            [{"name": host_of(child) or "servidor", "url": child} for child in children],
            headers,
            deadline,
            episode_url=final_url,
            parallel=2,
            second_chance=0,
        )[0] if children else []
        for signal in hosters:
            signal_url = signal.get("url") or ""
            if signal_url and signal_url not in seen_media:
                seen_media.add(signal_url)
                media.append({
                    "url": signal_url,
                    "referer": signal.get("referer") or final_url,
                    "source": signal.get("name") or "",
                })

    return media, {
        "fetched": fetched,
        "errors": errors,
        "media": len(media),
        "title": info["title"],
    }


def quality_of(entry: Dict[str, Any], name: str = "") -> Optional[str]:
    """Calidad a partir de las pistas del propio sitio (`hd`, `sd`, etiqueta)."""
    if entry.get("hd") and not entry.get("sd"):
        return "720p"
    if entry.get("sd") and not entry.get("hd"):
        return "SD"
    return guess_quality(name or entry.get("name") or "", entry.get("url") or "")


def page_title(html: str, brand: str = "") -> str:
    """Nombre del canal/evento a partir del `<title>` de su ficha.

    Los tres sitios titulan igual ("NFL Network - Watch Live Stream | NTV…",
    "NFL NETWORK - StreamCorner", "ESPN 2 en vivo | Live24"), así que se corta
    por el separador y se quita la coletilla de marca.
    """
    match = re.search(r"<title[^>]*>(.*?)</title>", html or "", re.IGNORECASE | re.DOTALL)
    if not match:
        return ""
    value = " ".join(re.sub(r"<[^>]+>", " ", match.group(1)).split())
    value = re.split(r"\s+[|\-–—]\s+", value)[0]
    value = re.sub(r"(?i)\b(?:watch|stream(?:ing)?|en vivo|live)\b.*$", "", value)
    if brand:
        value = re.sub(re.escape(brand) + r"$", "", value, flags=re.IGNORECASE)
    return value.strip(" -|:")

def build_live_streams(
    label: str,
    name: str,
    media: Iterable[Dict[str, Any]],
    user_agent: str = "",
    referer: Optional[str] = None,
    max_streams: int = MAX_STREAMS,
) -> List[Dict[str, Any]]:
    """Medios → streams de Stremio, deduplicados y con `mediaType`."""
    streams: List[Dict[str, Any]] = []
    seen: set = set()
    for item in media:
        url = (item.get("url") or "").strip()
        if not url or url in seen:
            continue
        seen.add(url)
        kind = media_kind_of_url(url)
        source = (item.get("source") or "").strip()
        title = f"{label} · {name}"
        if source:
            title += f" · {source}"
        streams.append(
            stream(
                url,
                name=name,
                title=title,
                quality=quality_of(item, name),
                referer=item.get("referer") or referer,
                user_agent=user_agent or None,
                is_live=True,
                media_type=kind or None,
            )
        )
        if len(streams) >= max_streams:
            break
    return streams


def probe_streams(
    streams: List[Dict[str, Any]],
    headers: Dict[str, str],
    deadline: Deadline,
    budget: int = 2,
) -> List[Dict[str, Any]]:
    """Rellena `mediaType` leyendo 2 KB de las señales que no lo declaran."""
    probed = 0
    for item in streams:
        if probed >= budget or deadline.expired():
            break
        hints = item.get("behaviorHints") or {}
        if hints.get("mediaType"):
            continue
        probed += 1
        kind = probe_media_kind(item["url"], {**headers, "Referer": item.get("referer") or ""}, deadline)
        if kind:
            item.setdefault("behaviorHints", {})["mediaType"] = kind
    return streams


# ---------------------------------------------------------------------------
# Índices HTML
# ---------------------------------------------------------------------------

_ANCHOR_RE = re.compile(
    r"""<a\b[^>]*href\s*=\s*["']([^"']+)["'][^>]*>(.*?)</a>""",
    re.IGNORECASE | re.DOTALL,
)
_TAG_RE = re.compile(r"<[^>]+>")
#: Elemento interno que sólo muestra la **fuente** del canal (`Football24`,
#: `HotHD🔥`): se quita antes de leer el nombre de la tarjeta.
_SOURCE_TAG_RE = re.compile(
    r"<(span|div|em|b|i|small)\b[^>]*class\s*=\s*[\"'][^\"']*\b(?:src|source|badge|chip)\b[^\"']*[\"'][^>]*>.*?</\1>",
    re.IGNORECASE | re.DOTALL,
)
def strip_live_prefix(text: str) -> str:
    """Quita la etiqueta "Live"/"En vivo" inicial sin comerse el nombre.

    El lookahead es sensible a mayúsculas a propósito: `LiveNFL Network` sí
    empieza por la etiqueta, `Liverpool TV` no.
    """
    value = text or ""
    match = re.match(r"(?i:live|en vivo|directo)(?=[A-Z]|[^A-Za-z0-9]|$)", value)
    if match:
        value = value[match.end():]
    return value.lstrip(" ·:-–")


#: Marcas de "está en directo" en las tarjetas.
LIVE_MARKERS = ("live", "en vivo", "directo", "🔴")


def anchor_entries(
    html: str,
    page_url: str,
    hints: Iterable[str] = (),
    skip: Iterable[str] = (),
    extra_id: Optional[Any] = None,
) -> List[Dict[str, Any]]:
    """Enlaces del índice que apuntan a un canal/partido.

    ``hints`` son trozos de ruta que identifican un evento (`/watch/`,
    `/stream/`, `ppv-`…). El nombre sale del texto visible del enlace; si viene
    vacío se usa el último trozo de la URL, porque estos sitios meten la
    portada y el escudo dentro del propio `<a>`.
    """
    hints = tuple(hint.lower() for hint in hints)
    skip = tuple(value.lower() for value in skip)
    entries: List[Dict[str, Any]] = []
    seen: set = set()

    for match in _ANCHOR_RE.finditer(html or ""):
        href = unescape_markup(match.group(1).strip())
        if not href or href.startswith(("#", "javascript:", "mailto:")):
            continue
        lowered = href.lower()
        if hints and not any(hint in lowered for hint in hints):
            continue
        if any(value in lowered for value in skip):
            continue
        url = absolute(href, page_url)
        if not url or url in seen:
            continue
        seen.add(url)

        inner = _SOURCE_TAG_RE.sub(" ", match.group(2))
        raw_text = visible_text(inner)
        name = " ".join(raw_text.split())
        live = any(marker in raw_text.lower() for marker in LIVE_MARKERS)
        name = strip_live_prefix(name).strip()
        if extra_id is not None:
            name = extra_id(name, url) or name
        entries.append({
            "id": slug_from_url(url, page_url),
            "name": name or pretty_name(slug_from_url(url, page_url)),
            "url": url,
            "live": live,
        })
    return entries


def slug_from_url(url: str, page_url: str = "") -> str:
    """Identificador estable de un evento: la ruta relativa, sin barras sobrantes."""
    parsed = urllib.parse.urlsplit(url)
    path = parsed.path.strip("/")
    if parsed.query:
        path = f"{path}?{parsed.query}" if path else parsed.query
    if not path:
        return host_of(url) or "home"
    return path


def find_entry(entries: Iterable[Dict[str, Any]], key: str) -> Optional[Dict[str, Any]]:
    """Busca por id, por sufijo o por parecido del nombre."""
    key = (key or "").strip().strip("/")
    if not key:
        return None
    items = list(entries)
    lowered = key.lower()
    for item in items:
        if item["id"].lower() == lowered or item["url"].lower() == lowered:
            return item
    for item in items:
        if item["id"].lower().endswith(lowered) or lowered in item["id"].lower():
            return item
    wanted = normalize(key)
    for item in items:
        if normalize(item["name"]) == wanted:
            return item
    for item in items:
        if wanted and wanted in normalize(item["name"]):
            return item
    return None


# ---------------------------------------------------------------------------
# JSON (APIs tipo DAMITV)
# ---------------------------------------------------------------------------


def json_walk(node: Any, keys: Iterable[str] = ()) -> Iterable[Any]:
    """Recorre un JSON anidado devolviendo los objetos que tienen alguna `keys`."""
    wanted = tuple(keys)
    stack: List[Any] = [node]
    while stack:
        current = stack.pop(0)
        if isinstance(current, dict):
            if not wanted or any(key in current for key in wanted):
                yield current
            stack.extend(value for value in current.values() if isinstance(value, (dict, list)))
        elif isinstance(current, list):
            stack.extend(item for item in current if isinstance(item, (dict, list)))


def decode_embedded_url(embed: str, base: str = "") -> str:
    """Saca el medio de una URL de reproductor.

    DAMITV publica `…/player/jw/?url=%2Fc%2F346.m3u8&name=…`, donde el `.m3u8`
    real es una ruta del propio sitio. Se devuelve absoluta.
    """
    value = (embed or "").strip()
    if not value:
        return ""
    parsed = urllib.parse.urlsplit(value)
    query = urllib.parse.parse_qs(parsed.query)
    for field in ("url", "file", "src", "source", "stream", "m3u8"):
        for raw in query.get(field, []):
            if _looks_media(raw):
                return absolute(raw, base or f"{parsed.scheme}://{parsed.netloc}")
    if _looks_media(value):
        return absolute(value, base)
    return ""


# ---------------------------------------------------------------------------
# Ids de contenido
# ---------------------------------------------------------------------------


def key_from_url(url: str) -> str:
    """Id de evento a partir de una URL: `?id=`/`?event=` o el último tramo."""
    parsed = urllib.parse.urlsplit(url)
    params = urllib.parse.parse_qs(parsed.query)
    for field in ("id", "event", "match", "channel", "watch"):
        values = params.get(field)
        if values and values[0].strip("/"):
            return values[0].strip("/")
    parts = [part for part in parsed.path.split("/") if part]
    return parts[-1] if parts else ""


def live_key(content_id: str, provider: str) -> str:
    """`tv:damitv:cr-x`, `damitv:cr-x`, `cr-x` o una URL → clave del evento."""
    value = (content_id or "").strip()
    for prefix in (f"tv:{provider}:", f"{provider}:", "tv:"):
        if value.lower().startswith(prefix):
            value = value[len(prefix):]
            break
    return value.strip()
