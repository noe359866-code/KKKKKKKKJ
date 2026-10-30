#!/usr/bin/env python3
"""
scrapers/damitv.py
------------------------------------------------------------------------------
Proveedor de **deporte en directo y canales 24/7** para **DAMITV**
(https://damitv.st).

Qué se comprobó del sitio (2026-10-30, ver `RECON.md`)
------------------------------------------------------
DAMITV no esconde los reproductores en el HTML: publica una **API pública** en
`/papi/` que la propia web consume. Es lo más estable que tiene el sitio:

    GET /papi/api/streams          → partidos y eventos, agrupados por deporte
    GET /papi/matches/all          → canales 24/7 (NFL Network, Sky Sports Golf…)

Cada evento trae `sources[]` con el nombre del canal y un `embed` que ya lleva
**la ruta del manifiesto** dentro de la consulta:

    "embed": "/player/jw/?url=%2Fc%2F346.m3u8&name=Willow%20Cricket"
                                      └── el HLS real: /c/346.m3u8

Así que la señal se puede construir sin abrir ningún reproductor: se decodifica
ese `url=` y se publica `https://damitv.st/c/346.m3u8` con el `Referer` del
sitio. Sólo cuando un `source` no trae ruta (`/embed/?id=…`) se abre su página y
se cosecha el medio de dentro.

IDs aceptados en `/stream/tv/<id>.json`
---------------------------------------
    damitv:ev-f1-2026-mexico-fp1          id del evento (o `uri_name`)
    tv:damitv:cr-new-zealand-india
    damitv:formula 1                      texto libre → se busca en el feed
    https://damitv.st/watch/…

Uso desde la CLI
----------------
    python3 scrapers/damitv.py --list                    # ids listos para pegar
    python3 scrapers/damitv.py --list --query "nba" --json
    echo '{"type":"tv","id":"damitv:ev-f1-2026-mexico-fp1","isLive":true}' \\
        | python3 scrapers/damitv.py

Variables de entorno (todas opcionales)
---------------------------------------
    DAMITV_BASE      https://damitv.st
    DAMITV_MIRRORS   https://ondemand.st,https://90minutes.pro
    DAMITV_MAX_STREAMS  6

Dependencias: sólo la librería estándar (vía `_htmlkit.py` y `_livekit.py`).
"""

from __future__ import annotations

import os
from typing import Any, Dict, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _htmlkit import Deadline, normalize
from _livekit import (
    build_live_streams,
    decode_embedded_url,
    env_bases,
    fetch_json,
    harvest,
    json_walk,
    key_from_url,
    live_key,
    probe_streams,
)

LABEL = "DAMITV"
PROVIDER = "damitv"

BASE_URL = os.getenv("DAMITV_BASE", "https://damitv.st").rstrip("/")
STREAMS_PATH = os.getenv("DAMITV_STREAMS_PATH", "/papi/api/streams")
MATCHES_PATH = os.getenv("DAMITV_MATCHES_PATH", "/papi/matches/all")

MAX_STREAMS = int(os.getenv("DAMITV_MAX_STREAMS", "6"))
PARALLEL_SERVERS = 4


#: Espejos del mismo feed (rotan cada pocas semanas). Sólo entran cuando no hay
#: `DAMITV_BASE`/`_MIRRORS` en el entorno.
DEFAULT_MIRRORS = ("https://ondemand.st", "https://90minutes.pro")


def _bases() -> List[str]:
    bases = env_bases(BASE_URL, "DAMITV_BASE", "DAMITV_MIRRORS")
    if not (os.getenv("DAMITV_BASE") or os.getenv("DAMITV_MIRRORS")):
        for mirror in DEFAULT_MIRRORS:
            if mirror not in bases:
                bases.append(mirror)
    return bases


# ---------------------------------------------------------------------------
# Feed
# ---------------------------------------------------------------------------


def feed_events(feed: Any) -> List[Dict[str, Any]]:
    """`/papi/api/streams` → lista plana de eventos.

    El JSON viene agrupado por deporte (`streams: [{category, streams: [...]}]`),
    pero se acepta cualquier anidamiento: basta con que el objeto tenga `id`.
    """
    events: List[Dict[str, Any]] = []
    for node in json_walk(feed, keys=("id",)):
        if not isinstance(node.get("id"), (str, int)):
            continue
        if "sources" not in node and "embed" not in node:
            continue
        events.append(node)
    return events


def load_events(headers: Dict[str, str], deadline: Deadline) -> Tuple[List[Dict[str, Any]], str]:
    """Feed de eventos + canales 24/7 (el segundo es opcional)."""
    failures = 0
    for base in _bases():
        data = fetch_json(f"{base}{STREAMS_PATH}", headers, deadline)
        if not data:
            failures += 1
            if failures >= 2:
                break
            continue
        events = feed_events(data)
        extra = fetch_json(f"{base}{MATCHES_PATH}", headers, deadline)
        if extra:
            known = {str(item.get("id")) for item in events}
            for item in feed_events(extra):
                if str(item.get("id")) not in known:
                    events.append(item)
        return events, base
    return [], ""


def event_name(event: Dict[str, Any]) -> str:
    """Nombre legible: el título del sitio, con los equipos si vienen."""
    raw = str(event.get("name") or event.get("title") or "").strip()
    teams = event.get("teams") or {}
    home = str(((teams.get("home") or {}).get("name")) or "").strip()
    away = str(((teams.get("away") or {}).get("name")) or "").strip()
    if home and away:
        return f"{home} vs {away}"
    if raw:
        return raw
    return str(event.get("uri_name") or event.get("id") or "Evento")


def _haystack(event: Dict[str, Any]) -> str:
    """Texto por el que se puede encontrar un evento: nombre, liga, equipos…"""
    parts = [
        event_name(event),
        str(event.get("league") or ""),
        str(event.get("tag") or ""),
        str(event.get("category_name") or event.get("category") or ""),
    ]
    return " ".join(part for part in parts if part).lower()


def load_index(headers: Dict[str, str], deadline: Deadline) -> List[Dict[str, Any]]:
    """Listado plano para las herramientas internas (`_embedded_catalogs`).

    No se publica como catálogo del addon: los scrapers sólo resuelven streams.
    """
    events, _base = load_events(headers, deadline)
    return [
        {
            "id": str(event.get("id") or ""),
            "name": event_name(event),
            "league": str(event.get("league") or ""),
            "status": str(event.get("status") or ""),
        }
        for event in events
        if event.get("id")
    ]


def pick_event(events: List[Dict[str, Any]], key: str) -> Optional[Dict[str, Any]]:
    """Busca por id/`uri_name`, y luego por nombre, liga o parecido.

    La liga cuenta porque es lo que pega el usuario: `formula 1` no aparece en
    el nombre del evento, sí en `league`.
    """
    wanted = (key or "").strip().strip("/").lower()
    if not wanted:
        return None
    for event in events:
        if wanted in {str(event.get("id") or "").lower(), str(event.get("uri_name") or "").lower()}:
            return event
    for event in events:
        if wanted in _haystack(event):
            return event
    target = normalize(key)
    if not target:
        return None
    # 1) coincidencia exacta con el nombre del evento
    for event in events:
        if normalize(event_name(event)) == target:
            return event
    # 2) coincidencia exacta con la liga (el primero de esa liga)
    for event in events:
        if normalize(str(event.get("league") or "")) == target:
            return event
    # 3) parecido por nombre
    for event in events:
        if target in normalize(event_name(event)):
            return event
    return None


def event_media(
    event: Dict[str, Any],
    base: str,
    headers: Dict[str, str],
    deadline: Deadline,
) -> Tuple[List[Dict[str, Any]], List[str]]:
    """`sources[]` → medios; si un `source` no trae ruta, se abre su página."""
    media: List[Dict[str, Any]] = []
    opened: List[str] = []

    for source in event.get("sources") or []:
        if not isinstance(source, dict):
            continue
        embed = str(source.get("embed") or source.get("iframe") or "")
        label = str(source.get("name") or source.get("id") or "").strip()
        direct = decode_embedded_url(embed, base)
        if direct:
            media.append({
                "url": direct,
                "referer": f"{base}/",
                "source": label,
                "hd": source.get("hd"),
                "sd": source.get("sd"),
            })
            continue
        if embed and not deadline.expired():
            page = embed if embed.startswith("http") else f"{base}{embed}"
            opened.append(page)
            found, _meta = harvest(page, headers, deadline, max_docs=2, max_depth=0)
            for item in found:
                media.append({**item, "source": label})

    if not media and not deadline.expired():
        # Último recurso: la página `/embed/?id=…` del propio evento.
        embed = str(event.get("embed") or event.get("iframe") or "")
        if embed:
            page = embed if embed.startswith("http") else f"{base}{embed}"
            opened.append(page)
            found, _meta = harvest(page, headers, deadline, max_docs=2, max_depth=1)
            media.extend(found)

    return media, opened


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    if payload.get("isLive") is False:
        return [], {"reason": "not_live", "provider": PROVIDER}

    headers = default_headers(payload)
    headers.setdefault("Accept", "application/json, text/plain, */*")
    if not headers.get("Referer"):
        headers["Referer"] = f"{BASE_URL}/"
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT
    deadline = Deadline(payload.get("timeoutMs"))

    key = live_key(str(payload.get("id") or payload.get("canal") or ""), PROVIDER)
    if key.lower().startswith("http"):
        key = key_from_url(key)
    meta: Dict[str, Any] = {"provider": PROVIDER, "id": key}

    events, base = load_events(headers, deadline)
    meta["feed"] = len(events)
    if not events:
        return [], {**meta, "reason": "site_unreachable"}

    event = pick_event(events, key)
    if not event:
        return [], {**meta, "reason": "not_found"}

    name = event_name(event)
    meta.update({
        "event": str(event.get("id") or ""),
        "title": name,
        "league": event.get("league") or event.get("tag") or "",
        "status": event.get("status") or "",
        "always_live": bool(event.get("always_live")),
    })

    media, opened = event_media(event, base, headers, deadline)
    meta["opened"] = opened
    meta["sources"] = [item.get("source") for item in media if item.get("source")]
    if not media:
        return [], {**meta, "reason": "no_streams"}

    streams = build_live_streams(
        LABEL, name, media, user_agent, referer=f"{base}/", max_streams=MAX_STREAMS
    )
    probe_streams(streams, headers, deadline)
    meta["streams"] = len(streams)
    return streams, meta


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def list_items(query: str = "", limit: int = 30) -> List[Dict[str, str]]:
    headers = {"User-Agent": DEFAULT_USER_AGENT, "Referer": f"{BASE_URL}/"}
    deadline = Deadline(15000)
    events, _base = load_events(headers, deadline)
    items: List[Dict[str, str]] = []
    wanted = normalize(query)
    for event in events:
        name = event_name(event)
        if wanted and wanted not in normalize(name):
            continue
        items.append({
            "id": f"tv:{PROVIDER}:{event.get('id')}",
            "name": name,
            "league": str(event.get("league") or ""),
            "status": str(event.get("status") or ""),
        })
        if len(items) >= limit:
            break
    return items


def main() -> None:
    import json as jsonlib
    import sys

    argv = sys.argv[1:]
    if "--list" in argv:
        query = ""
        if "--query" in argv:
            index = argv.index("--query")
            query = argv[index + 1] if index + 1 < len(argv) else ""
        items = list_items(query)
        if "--json" in argv:
            print(jsonlib.dumps({"items": items}, ensure_ascii=False, indent=2))
        else:
            for item in items:
                print(f"{item['id']}\t{item['name']}" + (f"  [{item['league']}]" if item["league"] else ""))
        return
    run(scrape)


if __name__ == "__main__":
    main()
