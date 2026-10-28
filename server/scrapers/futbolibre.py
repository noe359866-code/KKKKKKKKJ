#!/usr/bin/env python3
"""
scrapers/futbolibre.py
------------------------------------------------------------------------------
Proveedor real para https://futbolibre.live/ ("Fútbol Libre" / "Pelota Libre TV").
Cubre dos cosas:

    A. Canales 24/7          /en-vivo/<slug>      (ESPN 1, FOX Sports 3, TUDN…)
    B. Agenda de partidos    la18hd.su/eventos/json/agenda123.json

Cómo publica el sitio las señales
---------------------------------
    1. Cada canal vive en  https://futbolibre.live/en-vivo/<slug>/
       y esa página embebe el reproductor del backend:

           https://la18hd.su/vivo/canal.php?stream=<id del canal>

    2. La agenda es un JSON con los partidos del día y **varias señales por
       partido** (la18hd.su, tarjetarojita.xyz…):

           [{"title":"Liga 1: Cienciano vs Atlético Grau","category":"futbol",
             "time":"19:00","status":"En vivo","date":"2026-10-28",
             "link":"https://la18hd.su/vivo/canales.php?stream=liga1max"}, …]

    3. El reproductor sirve un HTML con la URL del CDN en su JavaScript:

           "src":"https://host/hls/…/index.m3u8?s=<token>&e=<caducidad>&ip=<ip>"

       Algunos players son `sw3.html?get=https://…/repro/x.html` (redirección
       intermedia) y otros vienen empaquetados con `eval(function(p,a,c,k,e,d)…)`.

Por eso el scraper: resuelve el canal o el partido, recoge **todas** sus
señales (una entrada por señal para el failover de ExoPlayer), sigue iframes/
redirecciones con un presupuesto acotado y descarta el parámetro `ip` del CDN
(viene ligado a la IP del servidor, no a la del televisor).

IDs aceptados en `/stream/tv/<id>.json`
---------------------------------------
    tv:futbolibre:espn-1                        (canal)
    tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau   (partido)
    futbolibre:tudn-mx
    espn-2 / fox-sports-premium                 (slug de canal)
    evento:liga-1-cienciano-vs-atletico-grau
    https://futbolibre.live/en-vivo/espn-1
    "Cienciano", "TUDN"                         (texto libre)

Uso desde la CLI
----------------
    python3 scrapers/futbolibre.py --list                 # canales + partidos
    python3 scrapers/futbolibre.py --list --channels
    python3 scrapers/futbolibre.py --list --events --query liga
    python3 scrapers/futbolibre.py --list --json
    echo '{"type":"tv","id":"tv:futbolibre:espn-1","isLive":true}' \
        | python3 scrapers/futbolibre.py

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
from typing import Any, Dict, Iterable, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run, stream
from _htmlkit import (
    ANCHOR_RE,
    Budget,
    Deadline,
    HREF_RE,
    H_TAG_RE,
    clean_title,
    follow_for_media,
    host_of,
    http_get,
    http_get_json,
    pretty_name,
    slugify,
    looks_like_player,
    token_expiry,
    unique,
    unescape_markup,
    unwrap_redirect,
    visible_text,
)

# ---------------------------------------------------------------------------
# Configuración
# ---------------------------------------------------------------------------

LABEL = "Fútbol Libre"

#: Sitio que se scrapea (los tests apuntan a un fixture local).
BASE_URL = os.getenv("FUTBOLIBRE_BASE", "https://futbolibre.live").rstrip("/")

#: JSON con la agenda de partidos y sus señales.
AGENDA_URL = os.getenv(
    "FUTBOLIBRE_AGENDA_URL",
    "https://la18hd.su/eventos/json/agenda123.json",
)

#: Prefijo de los ids de partido (`tv:futbolibre:evento:<slug>`).
EVENT_PREFIX = "evento:"

#: Caché en disco del catálogo de canales y de la agenda.
INDEX_CACHE_TTL = int(os.getenv("FUTBOLIBRE_INDEX_TTL_SECONDS", "21600"))
AGENDA_CACHE_TTL = int(os.getenv("FUTBOLIBRE_AGENDA_TTL_SECONDS", "600"))
INDEX_CACHE_PATH = os.getenv(
    "FUTBOLIBRE_INDEX_CACHE",
    os.path.join(tempfile.gettempdir(), "streamflix-futbolibre-index.json"),
)
AGENDA_CACHE_PATH = os.getenv(
    "FUTBOLIBRE_AGENDA_CACHE",
    os.path.join(tempfile.gettempdir(), "streamflix-futbolibre-agenda.json"),
)

#: Topes: señales por contenido, documentos totales y paralelismo.
MAX_SIGNALS = int(os.getenv("FUTBOLIBRE_MAX_SIGNALS", "6"))
MAX_DOCS = int(os.getenv("FUTBOLIBRE_MAX_DOCS", "12"))
MAX_DEPTH = int(os.getenv("FUTBOLIBRE_MAX_DEPTH", "2"))
PARALLEL_SIGNALS = 4

#: Parámetros del CDN que dependen de la IP del cliente y no deben propagarse.
DROP_CDN_PARAMS = ("ip",)

#: Hosts del backend del reproductor (se aceptan como candidatos aunque la ruta
#: no acabe en .php, p. ej. `…/vivo/canales.php?stream=`).
PLAYER_HOST_HINTS = ("la18hd", "la16hd", "la18", "tarjetarojita", "pelotalibre")

#: Texto de relleno de las tarjetas de canal ("Deportes en vivo", "Fútbol premium"…).
CHANNEL_DESCRIPTORS = (
    r"deportes en vivo", r"f[uú]tbol y deportes", r"f[uú]tbol premium",
    r"liga mx en vivo", r"deportes", r"f[uú]tbol", r"ver canal", r"en vivo",
)

CHANNEL_PATH_RE = re.compile(r"/en-vivo/([a-z0-9][a-z0-9._-]{0,80})/?(?:$|[?#])", re.IGNORECASE)
STREAM_PARAM_RE = re.compile(r"[?&]stream=([A-Za-z0-9._-]{1,60})")


# ---------------------------------------------------------------------------
# Catálogo de canales (futbolibre.live)
# ---------------------------------------------------------------------------


def _channel_name(inner_html: str, window: str, slug: str) -> str:
    """Nombre del canal a partir de su tarjeta.

    Prioridad: `alt` del logo ("Logo de ESPN 1") → `<strong>`/`<h*>` del título
    → texto de la tarjeta sin el descriptor ("Deportes en vivo", "Ver Canal").
    """
    alt = re.search(r"""<img[^>]+?alt\s*=\s*["']([^"']+)["']""", inner_html, re.IGNORECASE)
    if alt:
        name = clean_title(re.sub(r"^(?:logo|imagen|foto)\s+(?:de\s+)?", "", alt.group(1).strip(),
                                  flags=re.IGNORECASE))
        if 2 <= len(name) <= 40:
            return name

    for block in H_TAG_RE.findall(inner_html) + re.findall(
        r"<(?:strong|b)[^>]*>(.*?)</(?:strong|b)>", inner_html, re.IGNORECASE | re.DOTALL
    ):
        name = clean_title(block)
        if 2 <= len(name) <= 40:
            return name

    for source in (inner_html, window):
        text = visible_text(source)
        text = re.split(r"Ver [Cc]anal", text)[0]
        for descriptor in CHANNEL_DESCRIPTORS:
            text = re.sub(descriptor, " ", text, flags=re.IGNORECASE)
        name = clean_title(" ".join(text.split()[-4:]))
        if 2 <= len(name) <= 40:
            return name

    return pretty_name(slug)


def parse_channels(html: str, page_url: str) -> List[Dict[str, str]]:
    """Canales a partir de los enlaces `/en-vivo/<slug>` del home o /deportes/."""
    text = unescape_markup(html)
    channels: List[Dict[str, str]] = []
    seen = set()
    site_host = host_of(page_url)

    for match in ANCHOR_RE.finditer(text):
        href_match = HREF_RE.search(match.group(1))
        if not href_match:
            continue
        url = urllib.parse.urljoin(page_url, href_match.group(1).strip())
        path_match = CHANNEL_PATH_RE.search(urllib.parse.urlparse(url).path + "/")
        if not path_match or host_of(url) != site_host:
            continue
        slug = path_match.group(1).lower()
        if slug in seen or slug in ("en-vivo", "deportes", "agenda"):
            continue
        seen.add(slug)

        # La página canónica del canal termina en "/" (`/en-vivo/espn-1/`):
        # pedirla así evita una redirección y respeta el canonical del sitio.
        canonical = url.split("#")[0].split("?")[0]
        if not canonical.endswith("/"):
            canonical += "/"

        window = text[max(0, match.start() - 400):match.start()]
        channels.append({
            "slug": slug,
            "name": _channel_name(match.group(2), window, slug),
            "url": canonical,
        })

    return channels


def read_cache(path: str, ttl: int, key: str) -> Optional[Any]:
    if ttl <= 0 or not path:
        return None
    try:
        with open(path, "r", encoding="utf-8") as handle:
            payload = json.load(handle)
        if time.time() - float(payload.get("fetched_at", 0)) > ttl:
            return None
        value = payload.get(key)
        return value if value else None
    except (OSError, ValueError):
        return None


def write_cache(path: str, key: str, value: Any) -> None:
    if not path:
        return
    try:
        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        tmp_path = f"{path}.{os.getpid()}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump({"fetched_at": time.time(), key: value}, handle, ensure_ascii=False)
        os.replace(tmp_path, path)
    except OSError as exc:
        log("cache not written:", exc)


def load_channels(headers: Dict[str, str], deadline: Deadline,
                  force_refresh: bool = False) -> List[Dict[str, str]]:
    """Catálogo de canales del sitio (caché en disco)."""
    if not force_refresh:
        cached = read_cache(INDEX_CACHE_PATH, INDEX_CACHE_TTL, "channels")
        if cached:
            return cached

    channels: List[Dict[str, str]] = []
    for path in ("/", "/deportes/"):
        try:
            html, final_url, _ = http_get(f"{BASE_URL}{path}", headers, deadline.remaining())
        except (urllib.error.URLError, OSError, ValueError) as exc:
            log("channel index", path, "failed:", exc)
            continue
        channels.extend(parse_channels(html, final_url))
        if channels:
            break

    channels = _dedupe_channels(channels)
    if channels:
        write_cache(INDEX_CACHE_PATH, "channels", channels)
    log(f"index: {len(channels)} canales")
    return channels


def _dedupe_channels(channels: Iterable[Dict[str, str]]) -> List[Dict[str, str]]:
    out: List[Dict[str, str]] = []
    seen = set()
    for channel in channels:
        if channel["slug"] in seen:
            continue
        seen.add(channel["slug"])
        out.append(channel)
    return out


# ---------------------------------------------------------------------------
# Agenda de partidos (backend la18hd.su)
# ---------------------------------------------------------------------------


def _decode_links(raw_link: str) -> List[str]:
    """Enlaces de un evento: el directo y, si lo hay, el player de respaldo."""
    link = unescape_markup(str(raw_link or "")).replace("\\/", "/").strip()
    links = [link] if link.startswith(("http://", "https://")) else []

    # tarjetarojita.xyz/sw3.html?get=<reproductor real>
    parsed = urllib.parse.urlparse(link)
    if parsed.query:
        for key, value in urllib.parse.parse_qsl(parsed.query, keep_blank_values=True):
            if key.lower() in ("get", "url", "u") and value.startswith(("http://", "https://")):
                links.append(value)
    return unique(links)


def parse_agenda(payload: Any) -> List[Dict[str, Any]]:
    """Agrupa las entradas del JSON por partido (un partido = varias señales)."""
    if not isinstance(payload, list):
        return []

    grouped: Dict[str, Dict[str, Any]] = {}
    for entry in payload:
        if not isinstance(entry, dict):
            continue
        title = clean_title(str(entry.get("title") or ""))
        links = _decode_links(entry.get("link") or "")
        if not title or not links:
            continue

        key = slugify(title)
        if not key:
            continue

        event = grouped.get(key)
        if not event:
            grouped[key] = {
                "id": f"{EVENT_PREFIX}{key}",
                "slug": key,
                "title": title,
                "category": str(entry.get("category") or "").strip(),
                "date": str(entry.get("date") or "").strip(),
                "time": str(entry.get("time") or "").strip(),
                "status": str(entry.get("status") or "").strip(),
                "language": str(entry.get("language") or "").strip(),
                "links": list(links),
            }
        else:
            event["links"] = unique(event["links"] + links)
            for field in ("category", "date", "time", "status", "language"):
                if not event[field] and entry.get(field):
                    event[field] = str(entry.get(field)).strip()

    return list(grouped.values())


def load_agenda(headers: Dict[str, str], deadline: Deadline,
                force_refresh: bool = False) -> List[Dict[str, Any]]:
    """Agenda de partidos (caché corta: cambia durante el día)."""
    if not force_refresh:
        cached = read_cache(AGENDA_CACHE_PATH, AGENDA_CACHE_TTL, "events")
        if cached:
            return cached

    try:
        payload = http_get_json(AGENDA_URL, headers, deadline.remaining())
    except (urllib.error.URLError, OSError, ValueError) as exc:
        log("agenda fetch failed:", exc)
        return read_cache(AGENDA_CACHE_PATH, AGENDA_CACHE_TTL * 4, "events") or []

    events = parse_agenda(payload)
    if events:
        write_cache(AGENDA_CACHE_PATH, "events", events)
    log(f"agenda: {len(events)} partidos")
    return events


# ---------------------------------------------------------------------------
# Resolución del id -> canal o partido
# ---------------------------------------------------------------------------


def parse_key(content_id: str) -> str:
    """Quita los prefijos del id de Stremio: ``tv:futbolibre:espn-1`` -> ``espn-1``."""
    key = (content_id or "").strip().strip("/")
    for prefix in ("tv:", "futbolibre:"):
        if key.lower().startswith(prefix):
            key = key[len(prefix):]
    if key.lower().startswith("futbolibre:"):
        key = key[len("futbolibre:"):]
    # "evento:<slug>" conserva su prefijo; el resto se queda con el último
    # segmento (por si llega "tv:algo:slug").
    if not key.lower().startswith(EVENT_PREFIX):
        key = re.sub(r"\.(?:php|html?|json)$", "", key, flags=re.IGNORECASE)
        if ":" in key:
            key = key.split(":")[-1]
    return key.strip()


def channel_slug_from_url(url: str) -> str:
    match = CHANNEL_PATH_RE.search(urllib.parse.urlparse(url).path + "/")
    return match.group(1).lower() if match else ""


def match_channel(query: str, channels: List[Dict[str, str]]) -> Optional[Dict[str, str]]:
    wanted = slugify(query.replace(EVENT_PREFIX, ""))
    if not wanted or not channels:
        return None
    compact = re.sub(r"[^a-z0-9]", "", wanted)

    def score(channel: Dict[str, str]) -> int:
        slug = channel["slug"]
        name = slugify(channel.get("name", ""))
        name_compact = re.sub(r"[^a-z0-9]", "", name)
        slug_compact = re.sub(r"[^a-z0-9]", "", slug)
        if wanted == slug or compact in (slug_compact, name_compact):
            return 100
        if slug.startswith(wanted) or name.startswith(wanted) \
                or slug_compact.startswith(compact) or name_compact.startswith(compact):
            return 70
        if wanted in slug or wanted in name or compact in name_compact:
            return 40
        return 0

    best = max(channels, key=score)
    return best if score(best) > 0 else None


def match_event(query: str, events: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Busca un partido por slug exacto o por texto libre del título."""
    cleaned = query[len(EVENT_PREFIX):] if query.lower().startswith(EVENT_PREFIX) else query
    wanted = slugify(cleaned)
    if not wanted or not events:
        return None

    for event in events:
        if event["slug"] == wanted:
            return event

    compact = re.sub(r"[^a-z0-9]", "", wanted)
    scored: List[Tuple[int, Dict[str, Any]]] = []
    for event in events:
        title_slug = event["slug"]
        title_compact = re.sub(r"[^a-z0-9]", "", slugify(event["title"]))
        if title_slug.startswith(wanted) or title_compact.startswith(compact) and compact:
            scored.append((70, event))
        elif wanted in title_slug or (compact and compact in title_compact):
            scored.append((40, event))
    return max(scored, key=lambda pair: pair[0])[1] if scored else None


# ---------------------------------------------------------------------------
# De canal / partido a señales
# ---------------------------------------------------------------------------


def channel_player_links(channel_html: str, page_url: str) -> List[str]:
    """URLs del reproductor embebido en la página del canal.

    El sitio enlaza al backend (`la18hd.su/vivo/canal.php?stream=…`) tanto en un
    `<iframe>` como en un enlace de respaldo, y a veces mediante un salto
    intermedio (`sw3.html?get=<reproductor>`). Se aceptan los tres casos.
    """
    text = unescape_markup(channel_html)
    site_host = host_of(page_url)
    from_anchors: List[str] = []
    from_frames: List[str] = []

    for match in ANCHOR_RE.finditer(text):
        href_match = HREF_RE.search(match.group(1))
        if href_match:
            from_anchors.append(urllib.parse.urljoin(page_url, href_match.group(1).strip()))

    for match in re.finditer(r"""<iframe[^>]+?src\s*=\s*["']([^"']+)["']""", text, re.IGNORECASE):
        from_frames.append(urllib.parse.urljoin(page_url, match.group(1).strip()))

    candidates = unique(from_frames + from_anchors)
    out: List[str] = []
    for url in candidates:
        if not url.startswith(("http://", "https://")):
            continue
        parsed = urllib.parse.urlparse(url)
        host = host_of(url)
        if host != site_host and not any(hint in host for hint in PLAYER_HOST_HINTS):
            continue
        if not (STREAM_PARAM_RE.search(parsed.query or "")
                or unwrap_redirect(url)
                or looks_like_player(url)):
            continue
        out.append(url)
    return unique(out)


def host_label(url: str) -> str:
    """Host corto de una señal ("la18hd.su", "tarjetarojita.xyz")."""
    return host_of(url).replace("www.", "")


def collect_signals(links: List[str], headers: Dict[str, str], deadline: Deadline) -> List[Dict[str, Any]]:
    """Sigue cada enlace (en paralelo y con presupuesto) y junta las señales."""
    budget = Budget(MAX_DOCS)
    collected: List[Tuple[int, str, Dict[str, Any]]] = []
    lock = threading.Lock()

    def worker(position: int, link: str) -> None:
        if deadline.expired():
            return
        found, info = follow_for_media(
            link, headers, deadline, budget,
            max_depth=MAX_DEPTH, drop_params=DROP_CDN_PARAMS,
        )
        with lock:
            for item in found:
                collected.append((position, link, item))
            if info.get("errors"):
                log("signal", position, link, "errors:", ",".join(info["errors"]))

    threads = [
        threading.Thread(target=worker, args=(position, link), daemon=True)
        for position, link in enumerate(links[:MAX_SIGNALS])
    ]
    for start in range(0, len(threads), PARALLEL_SIGNALS):
        batch = threads[start:start + PARALLEL_SIGNALS]
        for thread in batch:
            thread.start()
        for thread in batch:
            thread.join(timeout=max(1.0, deadline.remaining()))
        if deadline.expired():
            break

    # Orden: por enlace de origen (failover) y tokens vivos antes que caducados.
    now = int(time.time())

    def sort_key(pair: Tuple[int, str, Dict[str, Any]]) -> Tuple[int, int]:
        position, _, item = pair
        expiry = token_expiry(item["url"])
        expired = 1 if expiry is not None and expiry <= now else 0
        return expired, position

    ordered = sorted(collected, key=sort_key)

    signals: List[Dict[str, Any]] = []
    seen = set()
    for position, link, item in ordered:
        url = item["url"]
        if url in seen:
            continue
        seen.add(url)
        expiry = token_expiry(url)
        signals.append({
            "url": url,
            "referer": item.get("referer") or link,
            "label": host_label(item["url"]),
            "expired": bool(expiry is not None and expiry <= now),
        })
    return signals


def build_streams(name: str, signals: List[Dict[str, Any]],
                  user_agent: str) -> List[Dict[str, Any]]:
    """Convierte las señales en streams de Stremio (una por señal, en orden).

    La etiqueta es `Señal N`; si las señales vienen de backends distintos se
    añade el host de las que no son mayoritarias, para que el failover sea
    legible ("ESPN 1 · Señal 2 · tarjetarojita.xyz").
    """
    hosts = [signal.get("label") or "" for signal in signals]
    primary_host = ""
    if hosts:
        counts: Dict[str, int] = {}
        for host in hosts:
            counts[host] = counts.get(host, 0) + 1
        # Empate -> el primero que apareció (mantiene el orden del failover).
        primary_host = max(counts, key=lambda host: (counts[host], -hosts.index(host)))

    streams: List[Dict[str, Any]] = []
    for position, signal in enumerate(signals, start=1):
        suffix = f" · Señal {position}"
        if signal.get("label") and signal["label"] != primary_host:
            suffix += f" · {signal['label']}"
        if signal.get("expired"):
            suffix += " · token caducado"
        streams.append(
            stream(
                url=signal["url"],
                name=f"{name}{suffix}",
                title=f"{LABEL} · {name}{suffix}",
                referer=signal["referer"],
                user_agent=user_agent,
                not_web_ready=False,
                is_live=True,
            )
        )
    return streams


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    content_id = str(payload.get("id") or payload.get("canal") or "").strip()

    if payload.get("isLive") is False:
        # Este proveedor sólo sirve señal en directo.
        return [], {"reason": "not_live", "id": content_id}

    headers = default_headers(payload)
    headers.setdefault("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
    headers.setdefault("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT

    deadline = Deadline(payload.get("timeoutMs"))
    key = parse_key(content_id)
    meta: Dict[str, Any] = {"id": content_id, "key": key}

    # ---------------------------------------------------------------- canal
    explicit_channel_url = ""
    if content_id.startswith(("http://", "https://")):
        if "futbolibre" in host_of(content_id):
            explicit_channel_url = content_id
        else:
            # URL del backend: se usa como señal directa.
            signals = collect_signals([content_id], headers, deadline)
            if not signals:
                return [], {**meta, "reason": "no_streams"}
            name = pretty_name(host_label(content_id).split(".")[0] or "Canal")
            return build_streams(name, signals, user_agent), {
                **meta, "channel": name, "streams": len(signals), "source": "url",
            }

    channels = load_channels(headers, deadline)
    channel = None
    if explicit_channel_url:
        slug = channel_slug_from_url(explicit_channel_url)
        channel = next((item for item in channels if item["slug"] == slug), None) or {
            "slug": slug, "name": pretty_name(slug), "url": explicit_channel_url,
        }
    else:
        channel = match_channel(key, channels)

    wants_event = key.lower().startswith(EVENT_PREFIX)

    if channel and not wants_event:
        page_url = channel["url"]
        try:
            html, final_url, _ = http_get(page_url, headers, deadline.remaining())
        except urllib.error.HTTPError as exc:
            return [], {**meta, "reason": "channel_unavailable", "error": f"http_{exc.code}"}
        except (urllib.error.URLError, OSError, ValueError) as exc:
            return [], {**meta, "reason": "channel_unavailable", "error": str(exc)}

        links = channel_player_links(html, final_url)
        meta["player_links"] = len(links)
        if not links:
            return [], {**meta, "reason": "player_not_found", "channel": channel["name"]}

        signals = collect_signals(links, headers, deadline)
        if not signals:
            return [], {**meta, "reason": "no_streams", "channel": channel["name"]}

        name = channel.get("name") or pretty_name(channel["slug"])
        return build_streams(name, signals, user_agent), {
            **meta, "channel": name, "streams": len(signals), "source": "canal",
        }

    # --------------------------------------------------------------- partido
    events = load_agenda(headers, deadline)
    event = match_event(key, events)
    if not event and not wants_event:
        # Un id de canal desconocido no debe convertirse en un partido al azar.
        if not match_channel(key, channels):
            event = match_event(content_id, events)
    if not event:
        return [], {**meta, "reason": "not_found", "channels": len(channels), "events": len(events)}

    signals = collect_signals(event["links"], headers, deadline)
    if not signals:
        return [], {**meta, "reason": "no_streams", "event": event["title"]}

    name = event["title"]
    return build_streams(name, signals, user_agent), {
        **meta,
        "event": name,
        "channel": name,
        "streams": len(signals),
        "category": event.get("category"),
        "time": event.get("time"),
        "status": event.get("status"),
        "source": "evento",
    }


# ---------------------------------------------------------------------------
# CLI: catálogo (`--list`)
# ---------------------------------------------------------------------------


def _print_list(query: str, as_json: bool, refresh: bool,
                only_channels: bool, only_events: bool) -> None:
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/json;q=0.9,*/*;q=0.8",
        "Accept-Language": "es-ES,es;q=0.9",
    }
    deadline = Deadline(20000)

    channels = [] if only_events else load_channels(headers, deadline, force_refresh=refresh)
    events = [] if only_channels else load_agenda(headers, deadline, force_refresh=refresh)

    if query:
        wanted = slugify(query)
        channels = [
            channel for channel in channels
            if wanted in channel["slug"] or wanted in slugify(channel.get("name", ""))
        ]
        events = [
            event for event in events
            if wanted in event["slug"] or wanted in slugify(event["title"])
        ]

    if as_json:
        print(json.dumps({
            "channels": [
                {"stremioId": f"tv:futbolibre:{channel['slug']}", **channel}
                for channel in channels
            ],
            "events": [
                {"stremioId": f"tv:futbolibre:{event['id']}",
                 "signals": len(event["links"]), **event}
                for event in events
            ],
        }, ensure_ascii=False, indent=2))
        return

    if channels:
        width = max(len(channel.get("name", "")) for channel in channels)
        print(f"{len(channels)} canales en {BASE_URL}\n")
        for channel in channels:
            print(f"  {channel.get('name', ''):<{width}}  tv:futbolibre:{channel['slug']}")

    if events:
        print(f"\n{len(events)} partidos en la agenda\n")
        for event in events:
            when = " ".join(filter(None, [event.get("date", ""), event.get("time", "")]))
            status = event.get("status") or ""
            print(f"  {event['title'][:58]:<58} {when}  {status}")
            print(f"  {'':<58} tv:futbolibre:{event['id']}  "
                  f"({len(event['links'])} señal(es))")

    if not channels and not events:
        print("No se encontró nada (¿el sitio cambió o no hay red?).")
        return

    print("\nÚsalo en Stremio:  /stream/tv/<id>.json")


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
        _print_list(
            query,
            as_json="--json" in args,
            refresh="--refresh" in args,
            only_channels="--channels" in args,
            only_events="--events" in args,
        )
        return

    # Modo bridge (Node): JSON por stdin, JSON por stdout.
    run(scrape)


if __name__ == "__main__":
    main()
