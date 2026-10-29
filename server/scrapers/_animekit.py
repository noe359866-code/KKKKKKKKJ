"""
scrapers/_animekit.py
------------------------------------------------------------------------------
Capa común de los proveedores de anime (AnimeFLV, JkAnime, Monoschinos).

Los tres comparten la misma forma, así que la plomería vive aquí:

    id de Stremio        <proveedor>:<slug>[:<temporada>]:<episodio>
                         animeflv:one-piece:1180
                         jkanime:one-piece:1:1180     (temporada:episodio)
                         monoschinos:one-piece-sub-espanol:3

    página del episodio  cada sitio la arma a su manera, pero todos publican
                         una lista de "servidores" (Streamwish, VOE, Filemoon…)
                         que `_hosters.py` convierte en `.m3u8` / `.mp4`.

Qué aporta este módulo
    - `parse_id()`: id → `{slug, season, episode}`, tolerando los prefijos del
      addon (`anime:`, `tv:`) y las formas sin episodio.
    - `js_literal()` / `entries()`: lectura de los `var videos = [...]` y
      `var servers = [{...}]` que estos sitios escriben en JS (claves sin
      comillas, comillas simples, comas finales) y que `json.loads` rechaza.
    - `decode_maybe_base64()`: muchos servidores vienen en base64.
    - `build_streams()`: formato único de las señales (una por servidor), igual
      que en los proveedores en directo.
    - `search_hits()`: extracción genérica de tarjetas de resultado, para que
      un id escrito a mano (`animeflv:one piece`) se resuelva por búsqueda.
"""

from __future__ import annotations

import base64
import binascii
import json
import re
import urllib.parse
from typing import Any, Dict, Iterable, List, Optional, Tuple

from _base import stream
from _htmlkit import (
    ANCHOR_RE,
    HREF_RE,
    clean_title,
    normalize,
    pretty_name,
    slugify,
    unescape_markup,
    visible_text,
)

#: Prefijos que puede traer el id (los pone el addon o el cliente Stremio).
ID_PREFIXES = ("anime:", "tv:", "stremio:")

#: Capturas de "episodio N" dentro de una URL o texto.
EPISODE_RE = re.compile(
    r"""(?:episodio|episode|cap(?:itulo|ítulo)?|ep)[-_. /]*(\d{1,5})(?!\d)""", re.IGNORECASE
)
TRAILING_NUMBER_RE = re.compile(r"[-_/](\d{1,5})/?$")


# ---------------------------------------------------------------------------
# Identificadores
# ---------------------------------------------------------------------------


def parse_id(content_id: str, provider: str) -> Dict[str, Any]:
    """Convierte el id de Stremio en ``{slug, season, episode, url}``.

    Acepta las formas que puede mandar un cliente:

        animeflv:one-piece:1180          → episodio 1180
        animeflv:one-piece:1:1180        → temporada 1, episodio 1180
        animeflv:one-piece               → episodio 1 (películas/especiales)
        https://www3.animeflv.net/ver/one-piece-1180
        one piece                        → slug normalizado (búsqueda después)
    """
    raw = (content_id or "").strip()
    result: Dict[str, Any] = {
        "raw": raw,
        "slug": "",
        "season": None,
        "episode": None,
        "url": "",
        "explicit_episode": False,
    }

    if not raw:
        return result

    # URL del sitio: se guarda para pedirla directamente (y se saca el slug).
    if raw.startswith(("http://", "https://")):
        result["url"] = raw
        path = urllib.parse.urlparse(raw).path.strip("/")
        candidate = path.split("/")[-1]
        candidate = re.sub(r"-episodio-\d+$", "", candidate, flags=re.IGNORECASE)
        candidate = EPISODE_RE.sub("", candidate)
        candidate = re.sub(r"-\d{1,5}$", "", candidate.strip("-"))
        result["slug"] = slugify(candidate)
        match = EPISODE_RE.search(path) or TRAILING_NUMBER_RE.search(path)
        if match:
            result["episode"] = int(match.group(1))
            result["explicit_episode"] = True
        return result

    text = raw
    lowered = text.lower()
    for prefix in ID_PREFIXES:
        if lowered.startswith(prefix):
            text = text[len(prefix):]
            lowered = text.lower()
    if lowered.startswith(f"{provider.lower()}:"):
        text = text[len(provider) + 1:]
    elif lowered.startswith("animeflv:") or lowered.startswith("jkanime:") or \
            lowered.startswith("monoschinos:"):
        # id de otro proveedor de anime: se queda el slug igualmente
        text = text.split(":", 1)[1]

    parts = [part for part in text.split(":") if part != ""]
    if not parts:
        return result

    # Los dos últimos segmentos numéricos son temporada/episodio (o sólo episodio).
    numbers: List[int] = []
    while len(parts) > 1 and re.fullmatch(r"\d{1,5}", parts[-1]):
        numbers.insert(0, int(parts.pop()))
        if len(numbers) == 2:
            break

    result["slug"] = slugify(":".join(parts))
    if len(numbers) == 1:
        result["episode"] = numbers[0]
        result["explicit_episode"] = True
    elif len(numbers) == 2:
        result["season"], result["episode"] = numbers
        result["explicit_episode"] = True

    if not result["slug"]:
        result["slug"] = slugify(text)
    return result


def apply_payload_episode(ref: Dict[str, Any], payload: Dict[str, Any]) -> Dict[str, Any]:
    """Completa episodio y, si hace falta, el slug desde el título del cliente.

    La APK autónoma puede mandar un id IMDb de Cinemeta más `title`; en ese
    caso el slug útil para buscar en el sitio sale del título, no del `tt…`.
    """
    payload = payload or {}
    title = str(payload.get("title") or "").strip()
    slug = str(ref.get("slug") or "")
    if title and (not slug or re.fullmatch(r"tt\d+", slug, flags=re.IGNORECASE)):
        ref["slug"] = slugify(title)
    if ref.get("explicit_episode"):
        return ref
    episode = payload.get("episode")
    season = payload.get("season")
    try:
        if episode is not None and str(episode).strip() != "":
            ref["episode"] = int(episode)
            ref["explicit_episode"] = True
        if season is not None and str(season).strip() != "":
            ref["season"] = int(season)
    except (TypeError, ValueError):
        pass
    return ref


def with_episode(ref: Dict[str, Any], episode: int) -> Dict[str, Any]:
    """Copia del ref con otro episodio (para intentos de respaldo)."""
    return {**ref, "episode": int(episode), "explicit_episode": True}


# ---------------------------------------------------------------------------
# Literales JS → JSON
# ---------------------------------------------------------------------------


def _balanced(text: str, start: int, open_ch: str, close_ch: str) -> Optional[str]:
    """Devuelve el trozo equilibrado `[...]`/`{...}` que empieza en `start`."""
    depth = 0
    quote = ""
    index = start
    while index < len(text):
        char = text[index]
        if quote:
            if char == "\\":
                index += 2
                continue
            if char == quote:
                quote = ""
        elif char in "\"'":
            quote = char
        elif char == open_ch:
            depth += 1
        elif char == close_ch:
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
        index += 1
    return None


def _quote_keys(text: str) -> Optional[str]:
    """Normaliza un literal JS a JSON: claves entre comillas y comillas dobles."""
    out: List[str] = []
    index = 0
    quote = ""
    length = len(text)
    while index < length:
        char = text[index]
        if quote:
            if char == "\\":
                out.append(text[index:index + 2])
                index += 2
                continue
            if char == quote:
                out.append('"')
                quote = ""
                index += 1
                continue
            if char == '"':
                out.append('\\"')
            elif char == "\n":
                out.append("\\n")
            elif char == "\r":
                out.append("\\r")
            elif char == "\t":
                out.append("\\t")
            else:
                out.append(char)
            index += 1
            continue

        if char in "\"'":
            quote = char
            out.append('"')
            index += 1
            continue

        if char.isalpha() or char in "_$":
            end = index
            while end < length and (text[end].isalnum() or text[end] in "_$"):
                end += 1
            word = text[index:end]
            probe = end
            while probe < length and text[probe].isspace():
                probe += 1
            if probe < length and text[probe] == ":" and not text[probe + 1:probe + 2] == ":":
                out.append(f'"{word}"')
                index = end
                continue
            out.append(word)
            index = end
            continue

        out.append(char)
        index += 1

    if quote:
        return None
    return "".join(out)


def to_json(raw: str) -> Any:
    """Parsea un literal JS (JSON estricto o con la sintaxis suelta de siempre)."""
    if not raw:
        return None
    attempts = [raw]
    normalized = _quote_keys(raw)
    if normalized:
        attempts.append(normalized)
    attempts.extend(
        re.sub(r",(\s*[}\]])", r"\1", candidate) for candidate in list(attempts)
    )
    for candidate in attempts:
        try:
            return json.loads(candidate)
        except (ValueError, TypeError):
            continue
    return None


def js_literal(html: str, name: str) -> Any:
    """Valor de ``var <name> = [...] / {...}`` (o ``<name> = [...]``)."""
    if not html or not name:
        return None
    pattern = re.compile(
        rf"""(?:var\s+|let\s+|const\s+|window\.)?\b{re.escape(name)}\s*=\s*""",
        re.IGNORECASE,
    )
    for match in pattern.finditer(html):
        rest = html[match.end():]
        starts = [position for position in (rest.find("["), rest.find("{")) if position >= 0]
        if not starts:
            continue
        start = min(starts)
        open_ch = rest[start]
        close_ch = "]" if open_ch == "[" else "}"
        raw = _balanced(rest, start, open_ch, close_ch)
        if not raw:
            continue
        parsed = to_json(raw)
        if parsed is not None:
            return parsed
    return None


def js_string(html: str, name: str) -> str:
    """Valor de ``var <name> = "…"``."""
    match = re.search(
        rf"""(?:var\s+|let\s+|const\s+)?\b{re.escape(name)}\s*=\s*['"]([^'"]+)['"]""",
        html or "",
        re.IGNORECASE,
    )
    return match.group(1) if match else ""


def objects(html: str, name: str) -> List[Dict[str, Any]]:
    """Lista de objetos de un literal JS (tolera que venga suelto o en array)."""
    value = js_literal(html, name)
    if isinstance(value, list):
        return [item for item in value if isinstance(item, dict)]
    if isinstance(value, dict):
        return [value]
    return []


# ---------------------------------------------------------------------------
# URLs y servidores
# ---------------------------------------------------------------------------


def decode_maybe_base64(value: str) -> str:
    """Muchos sitios publican el embed en base64 (`code`, `remote`)."""
    if not value or not isinstance(value, str):
        return ""
    text = value.strip()
    if text.startswith(("http://", "https://", "//")):
        return text
    padded = text + "=" * (-len(text) % 4)
    try:
        decoded = base64.b64decode(padded, validate=False).decode("utf-8", "replace").strip()
    except (binascii.Error, ValueError):
        return text
    if decoded.startswith(("http://", "https://", "//")):
        return decoded
    return text


def server_entry(name: str, url: str, extra: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Entrada normalizada de servidor: ``{name, url, lang?}``."""
    label = clean_title(name) or ""
    if not label:
        label = pretty_name(url.split("//")[-1].split("/")[0]) or "Servidor"
    elif label.islower():
        # Los sitios etiquetan los botones en minúsculas ("voe", "doodstream").
        label = pretty_name(label)
    entry: Dict[str, Any] = {"name": label, "url": url.strip()}
    if extra:
        for key, value in extra.items():
            if value not in (None, ""):
                entry[key] = value
    return entry


def dedupe_servers(servers: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Quita servidores repetidos (misma URL) conservando el orden del sitio."""
    out: List[Dict[str, Any]] = []
    seen = set()
    for server in servers:
        url = (server.get("url") or "").strip()
        if not url.startswith(("http://", "https://")) or url in seen:
            continue
        seen.add(url)
        out.append({**server, "url": url})
    return out


def iframe_servers(html: str, page_url: str, skip_hosts: Iterable[str] = ()) -> List[Dict[str, Any]]:
    """Servidores a partir de los `<iframe>` de la página (respaldo genérico)."""
    servers: List[Dict[str, Any]] = []
    for match in re.finditer(
        r"""<iframe[^>]+?src\s*=\s*["']([^"']+)["']""", unescape_markup(html or ""), re.IGNORECASE
    ):
        url = urllib.parse.urljoin(page_url, match.group(1).strip())
        if not url.startswith(("http://", "https://")):
            continue
        if any(hint in url for hint in skip_hosts):
            continue
        servers.append(server_entry("", url))
    return dedupe_servers(servers)


def search_hits(html: str, page_url: str, path_hint: str) -> List[Dict[str, str]]:
    """Tarjetas de resultado: ``[{slug, title, url}]``.

    Se apoya en los enlaces cuyo `path` contiene `path_hint` (por ejemplo
    `/anime/` en Monoschinos o `/ver/` en AnimeFLV) y toma el texto del enlace
    como título cuando no hay encabezado.
    """
    hits: List[Dict[str, str]] = []
    seen = set()
    text = unescape_markup(html or "")
    for match in ANCHOR_RE.finditer(text):
        href_match = HREF_RE.search(match.group(1))
        if not href_match:
            continue
        url = urllib.parse.urljoin(page_url, href_match.group(1).strip())
        path = urllib.parse.urlparse(url).path
        if path_hint not in path:
            continue
        slug = slugify(path.rstrip("/").split("/")[-1])
        slug = re.sub(r"-episodio-\d+$", "", slug)
        slug = EPISODE_RE.sub("", slug).strip("-")
        if not slug or slug in seen:
            continue
        title = clean_title(visible_text(match.group(2)))
        if not title:
            heading = re.search(r"<h[1-4][^>]*>(.*?)</h[1-4]>", match.group(2), re.IGNORECASE | re.DOTALL)
            title = clean_title(heading.group(1)) if heading else ""
        if not title:
            continue
        seen.add(slug)
        hits.append({"slug": slug, "title": title, "url": url})
    return hits


def best_hit(query: str, hits: Iterable[Dict[str, str]]) -> Optional[Dict[str, str]]:
    """Mejor coincidencia de una búsqueda por nombre."""
    wanted = slugify(query)
    if not wanted or not hits:
        return None
    compact = re.sub(r"[^a-z0-9]", "", wanted)
    scored: List[Tuple[int, Dict[str, str]]] = []
    for hit in hits:
        slug = hit["slug"]
        title = slugify(hit.get("title", ""))
        compact_title = re.sub(r"[^a-z0-9]", "", title)
        if slug == wanted or compact_title == compact:
            score = 100
        elif slug.startswith(wanted) or title.startswith(wanted) \
                or compact_title.startswith(compact):
            score = 70
        elif wanted in slug or (compact and compact in compact_title):
            score = 40
        else:
            score = 0
        if score:
            scored.append((score, hit))
    if not scored:
        return None
    return max(scored, key=lambda pair: pair[0])[1]


def normalize_query(value: str) -> str:
    """Texto libre → consulta de búsqueda (sin prefijos ni numeración)."""
    text = re.sub(r":\d{1,5}$", "", (value or "").strip())
    text = EPISODE_RE.sub("", text)
    return " ".join(normalize(text).replace("-", " ").split()).strip()


# ---------------------------------------------------------------------------
# Señales
# ---------------------------------------------------------------------------


def build_streams(
    provider_label: str,
    title: str,
    episode: Optional[int],
    signals: List[Dict[str, Any]],
    user_agent: str,
) -> List[Dict[str, Any]]:
    """Un stream por servidor resuelto, en el orden del sitio.

    ``name``  "One Piece · Ep. 1180"      (lo que se ve en la lista)
    ``title`` "AnimeFLV · One Piece · Ep. 1180 · Streamwish"
    """
    episode_suffix = f" · Ep. {episode}" if episode else ""
    streams: List[Dict[str, Any]] = []
    for signal in signals:
        server = signal.get("server") or signal.get("label") or "Servidor"
        streams.append(
            stream(
                url=signal["url"],
                name=f"{title}{episode_suffix}",
                title=f"{provider_label} · {title}{episode_suffix} · {server}",
                referer=signal.get("referer") or "",
                user_agent=user_agent,
                not_web_ready=False,
                is_live=False,
                # Extra: el addon lo usa como clave de agrupación de binge-watching.
                quality=None,
            )
        )
    return streams
