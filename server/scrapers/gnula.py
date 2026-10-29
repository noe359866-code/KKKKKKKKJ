#!/usr/bin/env python3
"""
scrapers/gnula.py
------------------------------------------------------------------------------
Proveedor real para **Gnula** (https://ww3.gnulahd.nu/): películas, series y
anime en español latino.

Cómo publica el sitio la señal
------------------------------
    1. Cada título tiene su ficha:

           /ver/street-fighter/            (película)
           /ver/visionquest/               (serie: los capítulos van aquí dentro)

    2. La ficha trae un objeto JavaScript con el reproductor:

           window.GNRD={pid:1234, tok:"…", k:"…", …}

    3. Con ese `pid`/`tok` se pide el reproductor a la API del propio sitio:

           GET /wp-json/gnrd/v1/player?id=1234&t=<tok>
           X-Requested-With: gnrd

       que responde `{"p": "<carga ofuscada>"}`.

    4. `p` se desofusca en cadena — ROT13 → sustituir los separadores por `_` →
       quitar `_` → base64 → desplazar cada carácter −3 → invertir → base64 — y
       queda el JSON de idiomas y servidores:

           [{"label":"Latino","servers":[{"src":"https://streamwish.to/e/…"}]}]

    5. Cada `src` es un servidor conocido: de resolverlo se encarga `_hosters.py`
       (Streamwish, VOE, Filemoon, Streamtape, Mixdrop, Mp4upload…).

En las series cada capítulo es un `<a class="gnrd-epc" data-s data-e data-id
data-t>`: se elige el de la temporada/episodio pedidos y se llama a la misma API
con su `id`/`t`, sin abrir otra página.

IDs aceptados en `/stream/movie/<id>.json`
------------------------------------------
    gnula:street-fighter
    gnula:visionquest:1:2              (temporada:episodio, como manda Stremio)
    movie:gnula:street-fighter         (prefijo de tipo del addon)
    gnula:tt0145487                    (IMDb: se resuelve con TMDB si hay clave)
    gnula:street fighter               (texto libre: se busca en el sitio)
    https://ww3.gnulahd.nu/ver/street-fighter/

Uso desde la CLI
----------------
    python3 scrapers/gnula.py --list
    python3 scrapers/gnula.py --list --query "street fighter" --json
    echo '{"type":"movie","id":"gnula:street-fighter"}' | python3 scrapers/gnula.py

Variables de entorno
--------------------
    GNULA_BASE     https://ww3.gnulahd.nu
    GNULA_MIRRORS  otros dominios separados por comas (el sitio rota a menudo)
    TMDB_API_KEY   opcional: ids IMDb (`gnula:tt0145487`) → título/año → slug

Dependencias: sólo la librería estándar (vía `_htmlkit`, `_peliskit`, `_hosters`).
"""

from __future__ import annotations

import base64
import binascii
import json
import re
import urllib.parse
from typing import Any, Dict, List, Optional

from _animekit import server_entry
from _base import log, run
from _htmlkit import Deadline
from _peliskit import (
    apply_payload,
    cli,
    env_bases,
    fetch_html,
    join,
    parse_ref,
    scrape_site,
    tmdb_prepare,
)

BASE = "https://ww3.gnulahd.nu"
PLAYER_API = "/wp-json/gnrd/v1/player"

GNRD_RE = re.compile(r"window\.GNRD\s*=\s*\{([^}]*)\}", re.IGNORECASE)
PID_RE = re.compile(r"\bpid\s*:\s*['\"]?(\d+)")
TOK_RE = re.compile(r"\btok\s*:\s*['\"]([^'\"]+)")
EPISODE_ANCHOR_RE = re.compile(r"<a\b[^>]*data-e\s*=[^>]*>", re.IGNORECASE)
ANCHOR_ATTR_RE = re.compile(r"""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

#: Sustituciones con las que el sitio disfraza los `_` del base64.
OBFUSCATION_PATTERNS = ("@$", "^^", "~@", "%?", "*~", "!!", "#&")
#: Relleno que el sitio sirve cuando no hay señal.
PLACEHOLDER_MARKERS = ("aviso.mp4",)


# ---------------------------------------------------------------------------
# Desofuscado del reproductor
# ---------------------------------------------------------------------------


def _rot13(text: str) -> str:
    out = []
    for char in text:
        if "A" <= char <= "Z":
            out.append(chr((ord(char) - ord("A") + 13) % 26 + ord("A")))
        elif "a" <= char <= "z":
            out.append(chr((ord(char) - ord("a") + 13) % 26 + ord("a")))
        else:
            out.append(char)
    return "".join(out)


def _b64(text: str) -> str:
    body = (text or "").strip()
    if not body:
        return ""
    padded = body + "=" * (-len(body) % 4)
    try:
        return base64.b64decode(padded, validate=False).decode("utf-8", "replace")
    except (binascii.Error, ValueError):
        return ""


def _shift(text: str, amount: int = -3) -> str:
    return "".join(chr(max(0, ord(char) + amount)) for char in text)


def _parse_json(text: str) -> Optional[Any]:
    if not text:
        return None
    try:
        return json.loads(text)
    except (ValueError, TypeError):
        return None


def decrypt_player(blob: str) -> Optional[Any]:
    """Desofusca `p` y devuelve el JSON de idiomas/servidores.

    Se prueban las variantes vistas en el sitio y en sus clones; la primera que
    produzca JSON válido gana. Nunca lanza: devuelve `None` si no hay forma.
    """
    if not blob or not isinstance(blob, str):
        return None
    value = blob.strip()

    chain = _rot13(value)
    for pattern in OBFUSCATION_PATTERNS:
        chain = chain.replace(pattern, "_")
    chain = chain.replace("_", "")
    first = _b64(chain)
    if first:
        for candidate in (
            _b64(_shift(first[::-1], -3)),
            _b64(first[::-1]),
            _b64(_shift(first, -3))[::-1],
        ):
            parsed = _parse_json(candidate)
            if parsed is not None:
                return parsed
        parsed = _parse_json(first)
        if parsed is not None:
            return parsed

    direct = _b64(value)
    if direct:
        parsed = _parse_json(direct) or _parse_json(_b64(_shift(direct[::-1], -3)))
        if parsed is not None:
            return parsed
    return None


# ---------------------------------------------------------------------------
# Servidores
# ---------------------------------------------------------------------------


def _walk_servers(node: Any, inherited: str, out: List[Dict[str, Any]]) -> None:
    """Recorre el JSON del reproductor y recoge `{label, src}`.

    Las etiquetas se componen de idioma + servidor ("Latino · Streamwish"),
    que es lo útil para elegir en Stremio: el idioma viene heredado del nodo
    `label` y el servidor del propio objeto.
    """
    if isinstance(node, list):
        for item in node:
            _walk_servers(item, inherited, out)
        return
    if not isinstance(node, dict):
        return

    language = inherited
    for key in ("label", "lang", "language", "idioma"):
        value = node.get(key)
        if isinstance(value, str) and value.strip():
            language = value.strip()
            break

    server_name = ""
    for key in ("server", "name", "title", "host"):
        value = node.get(key)
        if isinstance(value, str) and value.strip() and value.strip() != language:
            server_name = value.strip()
            break

    src = ""
    for key in ("src", "url", "file", "link", "code", "embed"):
        value = node.get(key)
        if isinstance(value, str) and value.strip():
            src = value.strip().replace("\\/", "/")
            break
    if src:
        if src.startswith("//"):
            src = "https:" + src
        if src.lower().startswith(("http://", "https://")) and not any(
            marker in src for marker in PLACEHOLDER_MARKERS
        ):
            # `server_entry` corta por "·", así que se separa con " - ".
            label = " - ".join(part for part in (language, server_name) if part)
            out.append(server_entry(label, src))

    for value in node.values():
        if isinstance(value, (dict, list)):
            _walk_servers(value, language, out)


def episode_anchor(html: str, season: int, episode: int) -> Optional[Dict[str, str]]:
    """`<a class="gnrd-epc" data-s data-e data-id data-t>` del capítulo pedido."""
    for match in EPISODE_ANCHOR_RE.finditer(html or ""):
        attrs: Dict[str, str] = {}
        for name, double, single in ANCHOR_ATTR_RE.findall(match.group(0)):
            attrs[name.lower()] = double if double is not None else (single or "")
        try:
            episode_value = int(attrs.get("data-e", ""))
            season_value = int(attrs.get("data-s", "1") or 1)
        except ValueError:
            continue
        if episode_value == episode and season_value in (season, 1):
            return attrs
    return None


def player_servers(
    html: str,
    page_url: str,
    headers: Dict[str, str],
    deadline: Deadline,
    season: Optional[int] = None,
    episode: Optional[int] = None,
) -> List[Dict[str, Any]]:
    """Pide el reproductor a la API de GNRD y devuelve sus servidores."""
    pid = tok = ""

    anchor = None
    if season is not None and episode is not None:
        anchor = episode_anchor(html, season, episode)
    if anchor and anchor.get("data-id") and anchor.get("data-t"):
        pid, tok = anchor["data-id"], anchor["data-t"]
    else:
        gnrd = GNRD_RE.search(html or "")
        scope = gnrd.group(1) if gnrd else (html or "")
        pid_match = PID_RE.search(scope)
        tok_match = TOK_RE.search(scope)
        pid = pid_match.group(1) if pid_match else ""
        tok = tok_match.group(1) if tok_match else ""

    if not pid or not tok:
        return []

    parsed_page = urllib.parse.urlparse(page_url)
    origin = f"{parsed_page.scheme}://{parsed_page.netloc}" if parsed_page.netloc else _bases()[0]
    api_url = join(origin, f"{PLAYER_API}?{urllib.parse.urlencode({'id': pid, 't': tok})}")
    request_headers = {
        **headers,
        "Referer": page_url,
        "X-Requested-With": "gnrd",
        "Accept": "application/json, text/plain, */*",
    }
    body, _final = fetch_html(api_url, request_headers, deadline, accept="application/json")
    if not body:
        log("gnula: player API unreachable")
        return []

    data = _parse_json(body)
    blob = ""
    if isinstance(data, dict):
        for key in ("p", "data", "html", "player"):
            value = data.get(key)
            if isinstance(value, str) and len(value) > 20:
                blob = value
                break
    elif isinstance(data, str):
        blob = data
    if not blob:
        log("gnula: player API without payload")
        return []

    decoded = decrypt_player(blob)
    if decoded is None:
        log("gnula: could not decode the player payload")
        return []

    servers: List[Dict[str, Any]] = []
    _walk_servers(decoded, "", servers)
    return servers


def _bases() -> List[str]:
    return env_bases(BASE, "GNULA_BASE", "GNULA_MIRRORS")


def site_config(ref: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Configuración del sitio; con `ref` se añaden título y episodio pedidos."""
    site: Dict[str, Any] = {
        "id": "gnula",
        "label": "Gnula",
        "base": _bases()[0],
        "bases": _bases(),
        "home_path": "/",
        "movie_paths": ["/ver/{slug}/"],
        "series_paths": ["/ver/{slug}/"],
        "search_paths": ["/wp-json/gnrd/v1/search?q={query}", "/?s={query}"],
        "search_hints": ["/ver/"],
        "player_hints": ("embed", "player", "repro", "gnrd"),
        "not_found_markers": ("página no encontrada", "404 not found"),
        "max_servers": 6,
        "parallel": 4,
        "tmdb": True,
        "ref": ref,
    }
    if ref:
        def extras(html: str, page_url: str, headers: Dict[str, str], deadline: Deadline) -> List[Dict[str, Any]]:
            return player_servers(
                html, page_url, headers, deadline,
                season=ref.get("season"), episode=ref.get("episode"),
            )

        site["extras"] = extras
    return site


def scrape(payload: Dict[str, Any]) -> Any:
    """Flujo genérico + la API del reproductor de GNRD."""
    ref = apply_payload(parse_ref(payload.get("id") or "", "gnula"), payload)
    ref = tmdb_prepare(ref, payload, "tv" if ref.get("season") is not None else "movie")
    return scrape_site(payload, site_config(ref))


def main() -> None:
    if cli(site_config()):
        return
    run(scrape)


if __name__ == "__main__":
    main()
