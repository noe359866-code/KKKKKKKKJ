#!/usr/bin/env python3
"""
scrapers/livelive24.py
------------------------------------------------------------------------------
Proveedor de **TV y deporte en directo** para **Live24 · TV & Sport**
(https://livelive24.com).

Qué se comprobó del sitio (2026-10-30, ver `RECON.md`)
------------------------------------------------------
La portada es un índice de dos bloques —**Live Sport** y **Live TV**— con
buscador y filtro por país:

    Live Sport 654      canales/eventos de deporte
    Live TV 434         canales de televisión

Cada tarjeta trae su origen (`Football24`, `Tennis24`, `Basketball`, `HotHD🔥`,
`PeleZidaneTV`…) y un enlace de reproducción. El nombre del canal está en la
tarjeta y el reproductor se abre en su propia página, así que el trabajo es:
índice → ficha del canal → `.m3u8` dentro (iframe, literal JS o `data-*`).

IDs aceptados en `/stream/tv/<id>.json`
---------------------------------------
    livelive24:<slug>          se prueba en las rutas conocidas del sitio
    tv:livelive24:<slug>
    livelive24:ESPN            nombre → se busca en el índice
    https://livelive24.com/<ruta>

Uso desde la CLI
----------------
    python3 scrapers/livelive24.py --list --query "espn"
    echo '{"type":"tv","id":"livelive24:espn","isLive":true}' | python3 scrapers/livelive24.py

Variables de entorno (todas opcionales)
---------------------------------------
    LIVELIVE24_BASE      https://livelive24.com
    LIVELIVE24_MIRRORS   (ninguno conocido; acepta una lista por comas)
    LIVELIVE24_MAX_STREAMS  6
"""

from __future__ import annotations

import os
import re
from typing import Any, Dict, List, Tuple

from _base import DEFAULT_USER_AGENT, default_headers, log, run
from _htmlkit import Deadline, normalize, pretty_name
from _livekit import (
    anchor_entries,
    build_live_streams,
    env_bases,
    fetch_many,
    find_entry,
    harvest,
    live_key,
    probe_streams,
    slug_from_url,
)

LABEL = "Live24"
PROVIDER = "livelive24"

BASE_URL = os.getenv("LIVELIVE24_BASE", "https://livelive24.com").rstrip("/")
MAX_STREAMS = int(os.getenv("LIVELIVE24_MAX_STREAMS", "6"))

#: Índices del sitio (portada + los dos bloques que anuncia).
INDEX_PATHS = ("/", "/live", "/tv", "/sport")
#: Rutas que usan estas webs para la ficha de un canal/evento.
CARD_PATHS = ("/watch/{}", "/channel/{}", "/live/{}", "/match/{}/", "/{}")
#: Trozos de URL que identifican la ficha de un canal.
CARD_HINTS = ("/watch/", "/channel/", "/live/", "/stream/", "/match/", "/tv/", "?id=", "?watch=")


def _bases() -> List[str]:
    return env_bases(BASE_URL, "LIVELIVE24_BASE", "LIVELIVE24_MIRRORS")


def _headers(payload: Dict[str, str]) -> Dict[str, str]:
    headers = default_headers(payload)
    if not headers.get("Referer"):
        headers["Referer"] = f"{BASE_URL}/"
    return headers


#: Etiquetas de origen que la tarjeta pega delante del nombre del canal.
_SOURCE_LABEL_RE = re.compile(
    r"^(?:[A-Za-z]{3,}\d{2}🔥?|HotHD🔥?|PeleZidaneTV|Basketball|Cricket)\s+", re.IGNORECASE
)


def clean_name(name: str) -> str:
    """`Football24 ESPN` → `ESPN` (la etiqueta de origen no es el canal)."""
    value = " ".join((name or "").split())
    for _ in range(3):
        stripped = _SOURCE_LABEL_RE.sub("", value).strip()
        if stripped == value or not stripped:
            break
        value = stripped
    return value


def parse_index(html: str, page_url: str) -> List[Dict[str, Any]]:
    entries = anchor_entries(html, page_url, hints=CARD_HINTS)
    for entry in entries:
        entry["id"] = slug_from_url(entry["url"], page_url)
        entry["name"] = clean_name(entry["name"])
        if not entry["name"] or entry["name"].startswith("http"):
            entry["name"] = pretty_name(entry["id"].split("/")[-1])
    return entries


def load_index(headers: Dict[str, str], deadline: Deadline) -> Tuple[List[Dict[str, Any]], str]:
    entries: List[Dict[str, Any]] = []
    seen: set = set()
    base_used = ""

    urls = [f"{base}{path}" for base in _bases() for path in INDEX_PATHS]
    for body, final_url, _content_type in fetch_many(urls, headers, deadline):
        page_entries = parse_index(body, final_url)
        if page_entries and not base_used:
            base_used = final_url.split("//", 1)[0] + "//" + final_url.split("//", 1)[1].split("/")[0]
        for entry in page_entries:
            if entry["id"] in seen:
                continue
            seen.add(entry["id"])
            entries.append(entry)
    return entries, base_used


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    if payload.get("isLive") is False:
        return [], {"reason": "not_live", "provider": PROVIDER}

    headers = _headers(payload)
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT
    deadline = Deadline(payload.get("timeoutMs"))

    raw_id = str(payload.get("id") or payload.get("canal") or "")
    key = live_key(raw_id, PROVIDER)
    meta: Dict[str, Any] = {"provider": PROVIDER, "id": key}

    candidates: List[str] = []
    name = ""

    if raw_id.startswith("http"):
        candidates.append(raw_id)
    elif key.startswith(("watch/", "channel/", "live/", "stream/", "match/")):
        candidates.extend(f"{base}/{key.lstrip('/')}" for base in _bases())

    entries: List[Dict[str, Any]] = []
    if not candidates:
        entries, _base = load_index(headers, deadline)
        meta["index"] = len(entries)
        entry = find_entry(entries, key) if entries else None
        if entry:
            name = entry["name"]
            meta["id"] = entry["id"]
            candidates.append(entry["url"])
        else:
            # La clave como slug: probar las rutas típicas de ficha.
            slug = key.strip("/")
            candidates.extend(f"{base}{template.format(slug)}" for base in _bases() for template in CARD_PATHS)

    for url in candidates:
        if deadline.expired():
            break
        media, page_meta = harvest(url, headers, deadline, max_docs=3, max_depth=1,
                                   player_hints=("embed", "/player", "source"))
        meta["fetched"] = meta.get("fetched", 0) + page_meta.get("fetched", 0)
        if not media:
            log("livelive24", url, "sin señal")
            continue
        display = name or page_meta.get("title") or pretty_name(key)
        streams = build_live_streams(
            LABEL, display, media, user_agent, referer=url, max_streams=MAX_STREAMS,
        )
        probe_streams(streams, headers, deadline)
        meta["page"] = url
        meta["streams"] = len(streams)
        return streams, meta

    return [], {**meta, "reason": "no_streams"}



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
