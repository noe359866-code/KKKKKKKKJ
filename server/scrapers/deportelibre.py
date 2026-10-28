#!/usr/bin/env python3
"""
scrapers/deportelibre.py
------------------------------------------------------------------------------
Proveedor real para https://deportelibre.top/ (agenda deportiva + canales 24/7).

Cómo publica el sitio sus señales
---------------------------------
    1. /stream/  lista los canales 24/7:

           <a href="/total/stream-01.php">▶ Ver canal</a>   … ESPN
           <a href="/total/stream-387.php">▶ Ver canal</a>  … ESPN Premium

    2. La página del canal ofrece varias opciones de reproducción:

           /en-vivo-online/espn/embed2.php   → Opción 1
           /en-vivo-online/espn/embed3.php   → Opción 2
           /en-vivo-online/espn/embed5.php   → Opción 4
           /en-vivo-online/espn/embed.php    → (VPN AR)

    3. Cada embed es un reproductor **Clappr** con la URL del CDN incrustada en
       el JavaScript (a menudo dentro de un empaquetador eval(function(p,a,c,k,
       e,d)…), pero la cadena sigue siendo texto plano en el HTML):

           var src="https://host:8443/hls/<ref>.m3u8?s=<token>&e=<caducidad>"

       y una copia sin P2P para navegadores sin soporte:

           {"stream_url": "...", "stream_url_nop2p": "...", "p2p": true}

Este scraper recorre página del canal → embeds (en paralelo), extrae todas las
URLs reproducibles (.m3u8 / .mpd / .mp4) de cada opción, las desescapa
(`\\/`, `\\u0026`, `&amp;`) y devuelve **una entrada por opción** para que
ExoPlayer haga failover opción 1 → 2 → 3. Las opciones con token caducado
(`e=<epoch>` en el pasado) se degradan al final.

IDs aceptados en `/stream/tv/<id>.json`
---------------------------------------
    tv:deportelibre:stream-01          (id de la lista de canales)
    tv:deportelibre:espn               (slug que usa el sitio)
    deportelibre:stream-387
    stream-387 / espn-premium
    https://deportelibre.top/total/stream-387.php
    ESPN Premium                       (texto libre, se busca en el índice)

Uso desde la CLI
----------------
    python3 scrapers/deportelibre.py --list                # canales + ids
    python3 scrapers/deportelibre.py --list --query espn   # filtrar
    echo '{"type":"tv","id":"tv:deportelibre:stream-01","isLive":true}' \
        | python3 scrapers/deportelibre.py

Dependencias: sólo la librería estándar. La plomería común (HTTP, deadline,
presupuesto, desempaquetado de JS, extracción de URLs) vive en `_htmlkit.py`.
"""

from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run, stream
from _htmlkit import (  # helpers compartidos con el resto de proveedores
    Budget,
    Deadline,
    absolute,
    clean_title,
    http_get,
    media_urls,
    normalize,
    player_links,
    pretty_name,
    slugify,
    token_expiry,
    unescape_markup,
    visible_text as _visible_text,
)

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------

LABEL = "DeporteLibre"

#: Base del sitio. `DEPORTELIBRE_BASE` permite apuntar a un fixture en tests.
BASE_URL = os.getenv("DEPORTELIBRE_BASE", "https://deportelibre.top").rstrip("/")

#: Ruta del catálogo de canales 24/7.
CHANNELS_PATH = os.getenv("DEPORTELIBRE_CHANNELS_PATH", "/stream/")

#: Índice de canales en disco (evita re-descargar el catálogo en cada consulta).
INDEX_CACHE_TTL = int(os.getenv("DEPORTELIBRE_INDEX_TTL_SECONDS", "21600"))
INDEX_CACHE_PATH = os.getenv(
    "DEPORTELIBRE_INDEX_CACHE",
    os.path.join(tempfile.gettempdir(), "streamflix-deportelibre-index.json"),
)

#: Topes: opciones por canal, documentos por opción y documentos totales.
MAX_OPTIONS = int(os.getenv("DEPORTELIBRE_MAX_OPTIONS", "4"))
MAX_DOCS_PER_OPTION = int(os.getenv("DEPORTELIBRE_MAX_DEPTH", "2"))
MAX_DOCS = int(os.getenv("DEPORTELIBRE_MAX_DOCS", "12"))
MAX_STREAMS = 8
PARALLEL_OPTIONS = 4

#: Enlaces y etiquetas: el catálogo se lee como HTML, no como texto plano.
ANCHOR_RE = re.compile(r"""<a\b([^>]*)>(.*?)</a>""", re.IGNORECASE | re.DOTALL)
HREF_RE = re.compile(r"""href\s*=\s*["']([^"']+)["']""", re.IGNORECASE)
ATTR_RE = re.compile(r"""([a-zA-Z-]+)\s*=\s*["']([^"']*)["']""")
TAG_RE = re.compile(r"<[^>]+>")
H_TAG_RE = re.compile(r"<h[1-4][^>]*>(.*?)</h[1-4]>", re.IGNORECASE | re.DOTALL)

#: Enlaces a la página de canal: /total/stream-01.php
CHANNEL_PAGE_RE = re.compile(r"/total/stream-(\d{1,5})\.php", re.IGNORECASE)
#: Enlaces a una opción de reproducción: /en-vivo-online/<slug>/embed<N>.php
OPTION_RE = re.compile(
    r"""/(?:en-vivo-online|embed)/([a-z0-9._-]{1,80})/(?:embed|mpd)(\d*)\.php""",
    re.IGNORECASE,
)
#: Variante del mismo reproductor: /mpd2.php?id=espn-op2
MPD_ID_RE = re.compile(r"""/mpd\d*\.php\?(?:[^#]*&)?id=([a-z0-9._-]{1,80})""", re.IGNORECASE)


# ---------------------------------------------------------------------------
# Utilidades
# ---------------------------------------------------------------------------


# ---------------------------------------------------------------------------
# Índice de canales (/stream/)
# ---------------------------------------------------------------------------


def _card_name(html: str, anchor_start: int, inner: str) -> str:
    """Nombre del canal en una tarjeta del catálogo.

    El texto del enlace suele ser "▶ Ver canal", así que el nombre se toma del
    texto visible inmediatamente anterior (la tarjeta es: bandera → nombre →
    enlace) y, si no hubiera, del `alt`/`title` o del encabezado de la tarjeta.
    """
    name = clean_title(inner)
    if name and normalize(name) not in ("ver canal", "ver", "play", "canal"):
        return name

    window = html[max(0, anchor_start - 900):anchor_start]

    # Texto visible justo antes del enlace: se corta en el "Ver canal" anterior
    # para no arrastrar el nombre de la tarjeta vecina.
    visible = _visible_text(window)
    visible = re.split(r"(?:Ver canal|Ver Canal|▶)", visible)[-1].strip() if visible else ""
    if visible:
        words = visible.split()
        candidate = clean_title(" ".join(words[-6:]))
        if candidate and normalize(candidate) not in ("ver canal", "canal"):
            return candidate

    for pattern in (r"""alt\s*=\s*["']([^"']{2,60})["']""",
                    r"""title\s*=\s*["']([^"']{2,60})["']"""):
        found = re.findall(pattern, window, re.IGNORECASE)
        if found:
            candidate = clean_title(found[-1])
            if candidate:
                return candidate

    headings = H_TAG_RE.findall(window)
    if headings:
        candidate = clean_title(headings[-1])
        if candidate:
            return candidate

    return ""


def parse_channels(html: str, page_url: str) -> List[Dict[str, str]]:
    """Catálogo: ``[{id, name, url}]`` a partir de los enlaces del /stream/."""
    text = unescape_markup(html)
    channels: List[Dict[str, str]] = []
    seen = set()

    for match in ANCHOR_RE.finditer(text):
        href_match = HREF_RE.search(match.group(1))
        if not href_match:
            continue
        url = absolute(href_match.group(1), page_url)
        page_match = CHANNEL_PAGE_RE.search(urllib.parse.urlparse(url).path)
        if not page_match:
            continue
        channel_id = f"stream-{page_match.group(1)}"
        if channel_id in seen:
            continue
        seen.add(channel_id)
        channels.append({
            "id": channel_id,
            "name": _card_name(text, match.start(), match.group(2)) or pretty_name(channel_id),
            "url": url,
        })

    return channels


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
            json.dump({"fetched_at": time.time(), "channels": channels}, handle,
                      ensure_ascii=False)
        os.replace(tmp_path, INDEX_CACHE_PATH)
    except OSError as exc:
        log("index cache not written:", exc)


def load_index(headers: Dict[str, str], deadline: Deadline,
               force_refresh: bool = False) -> List[Dict[str, str]]:
    """Catálogo de canales del sitio (caché en disco con TTL)."""
    if not force_refresh:
        cached = read_index_cache()
        if cached:
            return cached

    try:
        html, final_url, _ = http_get(f"{BASE_URL}{CHANNELS_PATH}", headers,
                                      deadline.remaining())
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("index fetch failed:", exc)
        return read_index_cache() or []

    channels = parse_channels(html, final_url)
    if channels:
        write_index_cache(channels)
    log(f"index: {len(channels)} canales")
    return channels


def find_in_index(query: str, channels: List[Dict[str, str]]) -> Optional[Dict[str, str]]:
    """Búsqueda tolerante por id, nombre o slug (sin acentos ni separadores)."""
    wanted = slugify(query)
    if not wanted or not channels:
        return None
    compact = re.sub(r"[^a-z0-9]", "", wanted)

    def score(channel: Dict[str, str]) -> int:
        name = slugify(channel.get("name", ""))
        cid = channel.get("id", "")
        name_compact = re.sub(r"[^a-z0-9]", "", name)
        if wanted in (name, cid) or compact in (name_compact, cid):
            return 100
        if name.startswith(wanted) or name_compact.startswith(compact):
            return 70
        if wanted in name or (compact and compact in name_compact):
            return 40
        return 0

    best = max(channels, key=score)
    return best if score(best) > 0 else None


# ---------------------------------------------------------------------------
# Página del canal -> opciones de reproducción
# ---------------------------------------------------------------------------


def parse_options(html: str, page_url: str) -> List[Dict[str, Any]]:
    """Opciones de reproducción de la página del canal.

    Devuelve ``[{url, label, vpn}]`` en el orden del sitio (Opción 1, 2, 3…).
    """
    text = unescape_markup(html)
    options: List[Dict[str, Any]] = []
    seen = set()

    for match in ANCHOR_RE.finditer(text):
        href_match = HREF_RE.search(match.group(1))
        if not href_match:
            continue
        url = absolute(href_match.group(1), page_url)
        parsed = urllib.parse.urlparse(url)
        if not OPTION_RE.search(parsed.path) and not MPD_ID_RE.search(
            f"{parsed.path}?{parsed.query}"
        ):
            continue
        if url in seen:
            continue
        seen.add(url)

        label = clean_title(match.group(2))
        attrs = {key.lower(): value for key, value in ATTR_RE.findall(match.group(1))}
        if not label:
            label = clean_title(attrs.get("title") or attrs.get("aria-label") or "")
        vpn = "vpn" in normalize(label)

        options.append({"url": url, "label": label or f"Opción {len(options) + 1}", "vpn": vpn})

    return options


def _page_name(html: str) -> str:
    """Nombre del canal según el <title>/<h1> de su página."""
    for pattern in (r"<title[^>]*>(.*?)</title>", r"<h1[^>]*>(.*?)</h1>"):
        match = re.search(pattern, html, re.IGNORECASE | re.DOTALL)
        if match:
            candidate = clean_title(match.group(1))
            if candidate:
                return candidate
    return ""


def synth_options(slug: str) -> List[Dict[str, Any]]:
    """Opciones por convención cuando no hay página de canal disponible."""
    options = [
        {"url": f"{BASE_URL}/en-vivo-online/{slug}/embed{n}.php",
         "label": f"Opción {n - 1}", "vpn": False}
        for n in range(2, 2 + MAX_OPTIONS)
    ]
    options.append({"url": f"{BASE_URL}/en-vivo-online/{slug}/embed.php",
                    "label": "VPN AR", "vpn": True})
    return options


# ---------------------------------------------------------------------------
# Embed -> URL del CDN
# ---------------------------------------------------------------------------


def resolve_option(option: Dict[str, Any], headers: Dict[str, str], deadline: Deadline,
                   budget: Optional[Budget] = None) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Descarga una opción (y sus iframes) y devuelve las señales encontradas."""
    results: List[Dict[str, Any]] = []
    queue: List[Tuple[str, int, str]] = [(option["url"], 0, option["url"])]
    visited = set()
    fetched = 0
    errors: List[str] = []

    while queue and fetched < MAX_DOCS_PER_OPTION and not deadline.expired():
        url, depth, referer = queue.pop(0)
        if url in visited:
            continue
        visited.add(url)
        if budget is not None and not budget.take():
            errors.append("budget_exhausted")
            break

        try:
            body, final_url, content_type = http_get(
                url, {**headers, "Referer": referer}, deadline.remaining()
            )
            fetched += 1
        except urllib.error.HTTPError as exc:
            errors.append(f"http_{exc.code}")
            log("option", url, "->", exc.code)
            continue
        except (urllib.error.URLError, OSError, ValueError) as exc:
            errors.append("request_failed")
            log("option", url, "failed:", exc)
            continue

        media = media_urls(body, final_url)
        for item in media:
            results.append({"url": item, "referer": final_url, "option": option})

        if not media and "mpegurl" in (content_type or "").lower():
            results.append({"url": final_url, "referer": referer, "option": option})

        if not media and depth + 1 < MAX_DOCS_PER_OPTION:
            for child in player_links(body, final_url)[:2]:
                queue.append((child, depth + 1, final_url))

    return results, {"options": option["label"], "fetched": fetched, "errors": errors}


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def parse_content_id(content_id: str) -> Tuple[str, str]:
    """Normaliza el id y devuelve ``(clave, url_explicita)``.

    Acepta ``tv:deportelibre:stream-01``, ``deportelibre:espn``, ``stream-01``,
    un slug (``espn-premium``) o una URL completa del sitio.
    """
    raw = (content_id or "").strip()
    if not raw:
        return "", ""

    if raw.startswith(("http://", "https://")):
        page_match = CHANNEL_PAGE_RE.search(urllib.parse.urlparse(raw).path)
        return (f"stream-{page_match.group(1)}" if page_match else ""), raw

    candidate = raw.strip().strip("/")
    if ":" in candidate:
        candidate = candidate.split(":")[-1]
    candidate = re.sub(r"\.(?:php|html?|json)$", "", candidate, flags=re.IGNORECASE)

    if "/" in candidate:
        page_match = CHANNEL_PAGE_RE.search(candidate)
        if page_match:
            return f"stream-{page_match.group(1)}", ""
        option_match = OPTION_RE.search(candidate)
        if option_match:
            return option_match.group(1), ""
        candidate = candidate.split("/")[-1]

    if re.fullmatch(r"(?i)stream[-_]?(\d{1,5})", candidate):
        number = re.fullmatch(r"(?i)stream[-_]?(\d{1,5})", candidate).group(1)
        return f"stream-{number}", ""

    if re.fullmatch(r"[a-z0-9][a-z0-9._-]{0,80}", candidate):
        return candidate, ""

    return "", ""


def channel_page_for(key: str) -> Optional[str]:
    """URL de la página del canal si la clave es un id de la lista."""
    match = re.fullmatch(r"(?i)stream-(\d{1,5})", key)
    return f"{BASE_URL}/total/stream-{match.group(1)}.php" if match else None


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    content_id = str(payload.get("id") or payload.get("canal") or "").strip()

    if payload.get("isLive") is False:
        # Sólo sirve señal en directo (canales 24/7 y eventos).
        return [], {"reason": "not_live", "id": content_id}

    headers = default_headers(payload)
    headers.setdefault("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
    headers.setdefault("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT

    deadline = Deadline(payload.get("timeoutMs"))

    key, explicit_url = parse_content_id(content_id)
    name = pretty_name(key) if key else ""
    options: List[Dict[str, Any]] = []
    meta: Dict[str, Any] = {"id": content_id, "errors": []}

    def open_channel_page(url: str) -> Tuple[str, str]:
        """Descarga la página del canal; devuelve (html, url_final)."""
        try:
            html, final_url, _ = http_get(url, headers, deadline.remaining())
            return html, final_url
        except urllib.error.HTTPError as exc:
            meta["errors"].append(f"channel_http_{exc.code}")
            log("channel page", url, "->", exc.code)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            meta["errors"].append("channel_request_failed")
            log("channel page", url, "failed:", exc)
        return "", ""

    # 1) Página del canal: id de la lista (stream-NN) o URL completa.
    page_url = explicit_url or channel_page_for(key) or ""
    if page_url:
        channel_html, final_url = open_channel_page(page_url)
        if channel_html:
            page_url = final_url
            meta["channel_page"] = final_url
            options = parse_options(channel_html, final_url)
            name = _page_name(channel_html) or name

    # 2) Sin página (slug o nombre libre): resolver contra el catálogo /stream/.
    if not options:
        lookup = content_id if not key.startswith("stream-") else key
        match = find_in_index(lookup, load_index(headers, deadline))
        if match:
            if not name or key.startswith("stream-"):
                name = match.get("name") or name
            if match.get("url") and match["url"] != page_url:
                channel_html, final_url = open_channel_page(match["url"])
                if channel_html:
                    meta["channel_page"] = final_url
                    options = parse_options(channel_html, final_url)
                    name = _page_name(channel_html) or name

    # 3) Último recurso: componer las opciones con el slug (convención del sitio).
    if not options and key and not re.fullmatch(r"(?i)stream-\d{1,5}", key):
        options = synth_options(key)
        meta["synthesized"] = True

    if not options:
        return [], {**meta, "reason": "channel_unavailable", "channel": name}

    # 4) Descarga de las opciones en paralelo (cada una con su presupuesto).
    selected = options[:MAX_OPTIONS]
    meta["options"] = [option["label"] for option in selected]

    collected: List[Tuple[int, Dict[str, Any]]] = []
    lock = threading.Lock()
    budget = Budget(MAX_DOCS)

    def worker(position: int, option: Dict[str, Any]) -> None:
        if deadline.expired():
            return
        results, info = resolve_option(option, headers, deadline, budget)
        with lock:
            meta["errors"].extend(info.get("errors") or [])
            for item in results:
                collected.append((position, item))

    threads = [
        threading.Thread(target=worker, args=(position, option), daemon=True)
        for position, option in enumerate(selected)
    ]
    for start in range(0, len(threads), PARALLEL_OPTIONS):
        batch = threads[start:start + PARALLEL_OPTIONS]
        for thread in batch:
            thread.start()
        for thread in batch:
            thread.join(timeout=max(1.0, deadline.remaining()))
        if deadline.expired():
            break

    # 5) Deduplicar: se respeta el orden de las opciones y los tokens vivos
    #    van primero (un token caducado sólo sirve como último recurso).
    now = int(time.time())

    def sort_key(pair: Tuple[int, Dict[str, Any]]) -> Tuple[int, int]:
        position, item = pair
        expiry = token_expiry(item["url"])
        expired = 1 if (expiry is not None and expiry <= now) else 0
        return position, expired

    streams: List[Dict[str, Any]] = []
    seen = set()
    for position, item in sorted(collected, key=sort_key):
        url = item["url"]
        if url in seen:
            continue
        seen.add(url)

        option: Dict[str, Any] = item.get("option") or {}
        label = option.get("label") or f"Opción {position + 1}"
        expiry = token_expiry(url)
        suffix = f" · {label}"
        if expiry is not None and expiry <= now:
            suffix += " · token caducado"

        quality_match = re.search(r"(\d{3,4})p", url, re.IGNORECASE)
        display = name or LABEL
        streams.append(
            stream(
                url=url,
                name=f"{display}{suffix}",
                title=f"{LABEL} · {display}{suffix}",
                quality=(f"{quality_match.group(1)}p" if quality_match else None),
                referer=item.get("referer") or option.get("url") or f"{BASE_URL}/",
                user_agent=user_agent,
                not_web_ready=False,
                is_live=True,
            )
        )
        if len(streams) >= MAX_STREAMS:
            break

    meta["errors"] = meta["errors"][:12]
    meta.update({"channel": name, "streams": len(streams)})
    if not streams:
        meta["reason"] = "no_streams"

    log(f"id={content_id} channel={name!r} streams={len(streams)} options={len(selected)}")
    return streams, meta


# ---------------------------------------------------------------------------
# CLI: catálogo de canales (`--list`)
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
            if wanted in slugify(channel.get("name", "")) or wanted in channel.get("id", "")
        ]

    if as_json:
        print(json.dumps(
            [{"stremioId": f"tv:deportelibre:{channel['id']}", **channel} for channel in channels],
            ensure_ascii=False, indent=2,
        ))
        return

    if not channels:
        print("No se encontraron canales (¿el sitio cambió o no hay red?).")
        return

    print(f"{len(channels)} canales en {BASE_URL}\n")
    width = max(len(channel.get("name", "")) for channel in channels)
    for channel in channels:
        print(f"  {channel.get('name', ''):<{width}}  tv:deportelibre:{channel['id']}")
    print("\nÚsalo en Stremio:  /stream/tv/tv:deportelibre:<id>.json")


def main() -> None:
    args = sys.argv[1:]
    if args and args[0] in ("-h", "--help"):
        print(__doc__)
        return
    if args and args[0] == "--list":
        query = ""
        if "--query" in args:
            index = args.index("--query")
            query = args[index + 1] if len(args) > index + 1 else ""
        _print_list(query, "--json" in args, "--refresh" in args)
        return

    # Modo bridge (Node): JSON por stdin, JSON por stdout.
    run(scrape)


if __name__ == "__main__":
    main()
