#!/usr/bin/env python3
"""
scrapers/streamcorner.py
------------------------------------------------------------------------------
Proveedor de **TV y deporte en directo** para **StreamCorner**
(https://streamcorner.fun).

Qué se comprobó del sitio (2026-10-30, ver `RECON.md`)
------------------------------------------------------
StreamCorner es una SPA (React Router) con entrada por liga:

    /stream/{liga}/{slug|hex}      p. ej. `/stream/nfl/nfl-network`,
                                   `/stream/admin/family-guy`,
                                   `/stream/admin/49a4d2df66c13cddcf60bae785e4bec9`
    /extra                         listado extra de canales
    /                              portada con los destacados

La ficha monta un reproductor **Bitmovin** y, debajo, una lista "AVAILABLE
SOURCES" con **varias señales por evento** (el mismo partido en 2–5 canales).
Como los reproductores se montan desde JS, el manifiesto puede no estar en el
HTML: se busca en `data-*`, literales JS, iframes y, si la señal es de un
hosting conocido (`_hosters`), en el propio hosting. Cuando ninguna de esas vías
da un `.m3u8`, el evento se informa sin señal en vez de mandar al usuario a la
web: la regla del proyecto es que el reproductor de la app reproduzca.

IDs aceptados en `/stream/tv/<id>.json`
---------------------------------------
    streamcorner:nfl/nfl-network      ruta tal cual tras `/stream/`
    streamcorner:nfl-network          se busca en el índice
    tv:streamcorner:admin/49a4d2df66c13cddcf60bae785e4bec9
    https://streamcorner.fun/stream/nfl/nfl-network

Uso desde la CLI
----------------
    python3 scrapers/streamcorner.py --list --query "nfl"
    echo '{"type":"tv","id":"streamcorner:nfl/nfl-network","isLive":true}' \\
        | python3 scrapers/streamcorner.py

Variables de entorno (todas opcionales)
---------------------------------------
    STREAMCORNER_BASE      https://streamcorner.fun
    STREAMCORNER_MIRRORS   https://streamcorner.st,https://streamcorner.cloud
    STREAMCORNER_MAX_STREAMS  6
"""

from __future__ import annotations

import os
import re
from typing import Any, Dict, List, Optional, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _htmlkit import Deadline, normalize, pretty_name, slugify
from _livekit import (
    anchor_entries,
    build_live_streams,
    env_bases,
    fetch_many,
    find_entry,
    harvest,
    live_key,
    probe_streams,
)

LABEL = "StreamCorner"
PROVIDER = "streamcorner"

BASE_URL = os.getenv("STREAMCORNER_BASE", "https://streamcorner.fun").rstrip("/")
MAX_STREAMS = int(os.getenv("STREAMCORNER_MAX_STREAMS", "6"))
INDEX_PATHS = ("/", "/extra")
#: Espejos del mismo sitio (rotan cada pocas semanas). Sólo se usan cuando no
#: hay `STREAMCORNER_BASE`/`_MIRRORS` en el entorno, para no romper los tests.
DEFAULT_MIRRORS = (
    "https://streamcorner.st",
    "https://streamcorner.foo",
    "https://streamcorner.cloud",
)
STREAM_HINTS = ("/stream/",)
PLAYER_HINTS = ("embed", "/player", "source", "bitmovin")


def _bases() -> List[str]:
    bases = env_bases(BASE_URL, "STREAMCORNER_BASE", "STREAMCORNER_MIRRORS")
    if not (os.getenv("STREAMCORNER_BASE") or os.getenv("STREAMCORNER_MIRRORS")):
        for mirror in DEFAULT_MIRRORS:
            if mirror not in bases:
                bases.append(mirror)
    return bases


def _headers(payload: Dict[str, str]) -> Dict[str, str]:
    headers = default_headers(payload)
    if not headers.get("Referer"):
        headers["Referer"] = f"{BASE_URL}/"
    return headers


def stream_key(value: str, page_url: str = "") -> str:
    """`…/stream/nfl/nfl-network` → `nfl/nfl-network`."""
    text = (value or "").strip()
    match = re.search(r"/stream/(.+)$", text)
    if match:
        text = match.group(1)
    return text.strip("/").split("?")[0].split("#")[0]


# ---------------------------------------------------------------------------
# Índice
# ---------------------------------------------------------------------------


def parse_index(html: str, page_url: str) -> List[Dict[str, Any]]:
    entries = anchor_entries(html, page_url, hints=STREAM_HINTS)
    for entry in entries:
        entry["id"] = stream_key(entry["url"])
        if not entry["name"] or entry["name"] == entry["id"]:
            entry["name"] = pretty_name(entry["id"].split("/")[-1])
    return entries


def load_index(headers: Dict[str, str], deadline: Deadline) -> Tuple[List[Dict[str, Any]], str]:
    entries: List[Dict[str, Any]] = []
    seen: set = set()
    base_used = ""

    urls = [f"{base}{path}" for base in _bases()[:2] for path in INDEX_PATHS]
    for body, final_url, _content_type in fetch_many(urls, headers, deadline):
        page_entries = parse_index(body, final_url)
        if page_entries and not base_used:
            base_used = final_url.split("/stream/")[0]
        for entry in page_entries:
            if entry["id"] in seen:
                continue
            seen.add(entry["id"])
            entries.append(entry)
    return entries, base_used


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    if payload.get("isLive") is False:
        return [], {"reason": "not_live", "provider": PROVIDER}

    headers = _headers(payload)
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT
    deadline = Deadline(payload.get("timeoutMs"))

    raw_id = str(payload.get("id") or payload.get("canal") or "")
    key = live_key(raw_id, PROVIDER)
    if raw_id.startswith("http"):
        key = stream_key(raw_id)
    meta: Dict[str, Any] = {"provider": PROVIDER, "id": key}

    candidates: List[str] = []
    name = ""
    has_path = "/" in key

    if raw_id.startswith("http"):
        candidates.append(raw_id)
    if has_path:
        candidates.extend(f"{base}/stream/{key}" for base in _bases())
    if not candidates or not has_path:
        entries, _base = load_index(headers, deadline)
        meta["index"] = len(entries)
        entry = find_entry(entries, key) if entries else None
        if entry:
            name = entry["name"]
            key = entry["id"]
            meta["id"] = key
            candidates.extend(f"{base}/stream/{key}" for base in _bases())
        elif not candidates:
            # Último intento: la clave como ruta directa (liga incluida o no).
            candidates.extend(f"{base}/stream/{key}" for base in _bases())

    for url in candidates:
        if deadline.expired():
            break
        media, page_meta = harvest(url, headers, deadline, max_docs=3, max_depth=1,
                                   player_hints=PLAYER_HINTS)
        meta["fetched"] = meta.get("fetched", 0) + page_meta.get("fetched", 0)
        if not media:
            log("streamcorner", url, "sin señal")
            continue
        display = name or page_meta.get("title") or pretty_name(key.split("/")[-1])
        streams = build_live_streams(
            LABEL, display, media, user_agent,
            referer=url, max_streams=MAX_STREAMS,
        )
        probe_streams(streams, headers, deadline)
        meta["page"] = url
        meta["streams"] = len(streams)
        return streams, meta

    return [], {**meta, "reason": "no_streams"}


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def list_items(query: str = "", limit: int = 40) -> List[Dict[str, str]]:
    headers = {"User-Agent": DEFAULT_USER_AGENT, "Referer": f"{BASE_URL}/"}
    deadline = Deadline(20000)
    entries, _base = load_index(headers, deadline)
    wanted = normalize(query)
    items: List[Dict[str, str]] = []
    for entry in entries:
        if wanted and wanted not in normalize(entry["name"]) and wanted not in entry["id"].lower():
            continue
        items.append({"id": f"tv:{PROVIDER}:{entry['id']}", "name": entry["name"]})
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
                print(f"{item['id']}\t{item['name']}")
        return
    run(scrape)


if __name__ == "__main__":
    main()
