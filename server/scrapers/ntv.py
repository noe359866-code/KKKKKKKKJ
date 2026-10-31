#!/usr/bin/env python3
"""
scrapers/ntv.py
------------------------------------------------------------------------------
Proveedor de **deporte en directo y canales 24/7** para **NTV / NTVSTREAM**
(https://ntv.st).

Qué se comprobó del sitio (2026-10-30, ver `RECON.md`)
------------------------------------------------------
NTV no tiene una API pública: el índice es HTML normal y el reproductor se
monta en la página de reproducción.

    /matches/{raptor|falcon|phoenix|titan}     índice por servidor
    /watch/{servidor}/ppv-{id}                 página del evento
        └─ "Source 1 (247CHANNEL)", "Loading stream" y un iframe que pide el
           manifiesto *después* de cargar: por eso el `.m3u8` no se ve en el
           HTML, hay que seguir el iframe/JS.

Los cuatro servidores publican los **mismos ids** (`ppv-18172` está en raptor,
falcon, phoenix y titan). Eso da el failover que hace falta cuando uno se cae:
se prueban en orden y se publica la primera señal que aparezca.

IDs aceptados en `/stream/tv/<id>.json`
---------------------------------------
    ntv:ppv-18172                 se prueban los cuatro servidores
    ntv:raptor/ppv-18172          sólo ese servidor (y luego los demás)
    tv:ntv:ppv-18172
    ntv:NFL Network               nombre → se busca en los índices
    https://ntv.st/watch/raptor/ppv-18172

Uso desde la CLI
----------------
    python3 scrapers/ntv.py --list --query "nfl"
    echo '{"type":"tv","id":"ntv:ppv-18172","isLive":true}' | python3 scrapers/ntv.py

Variables de entorno (todas opcionales)
---------------------------------------
    NTV_BASE       https://ntv.st
    NTV_MIRRORS    https://ntvx.link
    NTV_SERVERS    raptor,falcon,phoenix,titan
    NTV_MAX_STREAMS  6
"""

from __future__ import annotations

import os
import re
import urllib.parse
from typing import Any, Dict, List, Optional, Tuple

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
    strip_live_prefix,
)

LABEL = "NTV"
PROVIDER = "ntv"

BASE_URL = os.getenv("NTV_BASE", "https://ntv.st").rstrip("/")
MAX_STREAMS = int(os.getenv("NTV_MAX_STREAMS", "6"))
SERVERS = [
    value.strip()
    for value in (os.getenv("NTV_SERVERS", "raptor,falcon,phoenix,titan")).split(",")
    if value.strip()
]
#: Páginas que listan eventos/canales. Todas enlazan a `/watch/…`.
INDEX_PATHS = ("/matches/{}", "/channels", "/247")
WATCH_HINTS = ("/watch/",)

#: Categorías que el índice pega al nombre del canal ("NFL NetworkAmerican
#: Football1"). Se cortan del final, de la más larga a la más corta.
CATEGORIES = (
    "american football", "soccer", "football", "basketball", "baseball",
    "ice hockey", "hockey", "tennis", "cricket", "motor sports", "motorsport",
    "formula 1", "boxing", "ufc", "mma", "wrestling", "golf", "rugby", "afl",
    "darts", "snooker", "cycling", "esports", "nfl", "nba", "mlb", "nhl",
)
#: Nombre + categoría + contador, con o sin espacios entre ellos: el índice a
#: veces los pega (`NFL NetworkAmerican Football1`) y a veces los separa
#: (`Sky Sports 1 Football 3`).
_CARD_RE = re.compile(
    r"^(?P<name>.+?)\s*(?P<cat>"
    + "|".join(re.escape(cat) for cat in sorted(CATEGORIES, key=len, reverse=True))
    + r")\s*(?P<count>\d{0,3})$",
    re.IGNORECASE,
)


def _bases() -> List[str]:
    return env_bases(BASE_URL, "NTV_BASE", "NTV_MIRRORS")


def channel_name(text: str) -> str:
    """`LiveNFL NetworkAmerican Football1` → `NFL Network`.

    El índice pega canal + categoría + cuenta atrás sin separadores, así que se
    recorta la categoría del final (y el contador) en lugar de adivinar dónde
    acaba el nombre.
    """
    value = " ".join((text or "").split())
    value = strip_live_prefix(value).strip()
    match = _CARD_RE.match(value)
    if match:
        name = match.group("name").strip(" ·-|")
        if len(name) >= 3:
            return name
    return value or "Canal"


def _headers(payload: Dict[str, str]) -> Dict[str, str]:
    headers = default_headers(payload)
    if not headers.get("Referer"):
        headers["Referer"] = f"{BASE_URL}/"
    return headers


# ---------------------------------------------------------------------------
# Índice
# ---------------------------------------------------------------------------


def parse_index(html: str, page_url: str) -> List[Dict[str, Any]]:
    """Enlaces `/watch/…` del índice, con el nombre ya limpio de categoría."""
    entries = anchor_entries(html, page_url, hints=WATCH_HINTS)
    for entry in entries:
        entry["name"] = channel_name(entry["name"])
    return entries


def index_urls() -> List[str]:
    """Todas las combinaciones base × índice que hay que mirar."""
    urls: List[str] = []
    for base in _bases():
        for template in INDEX_PATHS:
            paths = (
                [template.format(server) for server in SERVERS]
                if "{}" in template
                else [template]
            )
            urls.extend(f"{base}{path}" for path in paths)
    return urls


def load_index(
    headers: Dict[str, str], deadline: Deadline
) -> Tuple[List[Dict[str, Any]], str]:
    """Índice de los cuatro servidores, deduplicado por id de evento."""
    entries: List[Dict[str, Any]] = []
    seen: set = set()
    base_used = ""

    for body, final_url, _content_type in fetch_many(index_urls(), headers, deadline):
        page_entries = parse_index(body, final_url)
        if page_entries and not base_used:
            base_used = urllib.parse.urlsplit(final_url).scheme + "://" + urllib.parse.urlsplit(final_url).netloc
        for entry in page_entries:
            # El mismo evento está en los cuatro servidores: vale una entrada.
            key = event_key(entry["id"])
            if key in seen:
                continue
            seen.add(key)
            entry["id"] = key
            entries.append(entry)
        if len(entries) >= 240:
            break
    return entries, base_used


def event_key(value: str) -> str:
    """`watch/raptor/ppv-18172` → `ppv-18172`; lo demás se deja igual."""
    text = (value or "").strip().strip("/")
    match = re.search(r"(ppv-\d+)", text, re.IGNORECASE)
    if match:
        return match.group(1).lower()
    parts = text.split("/")
    if len(parts) >= 3 and parts[0].lower() == "watch":
        return parts[-1]
    return text


def watch_urls(key: str) -> List[str]:
    """URLs de `/watch/…` a probar para una clave."""
    value = (key or "").strip().strip("/")
    match = re.match(r"^(?P<server>[a-z]+)[/:](?P<event>ppv-\d+)$", value, re.IGNORECASE)
    servers = SERVERS
    event = value
    if match:
        event = match.group("event")
        preferred = match.group("server").lower()
        servers = [preferred] + [server for server in SERVERS if server != preferred]
    if event.lower().startswith("ppv-"):
        return [f"{base}/watch/{server}/{event}" for base in _bases() for server in servers]
    return []


# ---------------------------------------------------------------------------
# Scraper
# ---------------------------------------------------------------------------


def resolve_page(
    url: str, headers: Dict[str, str], deadline: Deadline
) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Cosecha los medios de una página de reproducción."""
    media, meta = harvest(url, headers, deadline, max_docs=3, max_depth=1,
                          player_hints=("embed", "/player", "source"))
    return media, meta


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    if payload.get("isLive") is False:
        return [], {"reason": "not_live", "provider": PROVIDER}

    headers = _headers(payload)
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT
    deadline = Deadline(payload.get("timeoutMs"))

    raw_id = str(payload.get("id") or payload.get("canal") or "")
    key = event_key(live_key(raw_id, PROVIDER))
    meta: Dict[str, Any] = {"provider": PROVIDER, "id": key}

    # 1) URL directa pegada como id.
    candidates: List[str] = []
    if raw_id.startswith("http"):
        candidates.append(raw_id)
    candidates.extend(watch_urls(key))

    # 2) Nombre libre: hace falta el índice para traducirlo a un id.
    name = ""
    if not candidates or not key.lower().startswith("ppv-"):
        entries, _base = load_index(headers, deadline)
        meta["index"] = len(entries)
        entry = find_entry(entries, key)
        if entry:
            name = entry["name"]
            meta["event"] = entry["id"]
            candidates.extend(watch_urls(entry["id"]))
            if entry["url"] not in candidates:
                candidates.append(entry["url"])
        elif not candidates:
            return [], {**meta, "reason": "not_found"}

    for url in candidates:
        if deadline.expired():
            break
        media, page_meta = resolve_page(url, headers, deadline)
        meta["fetched"] = meta.get("fetched", 0) + page_meta.get("fetched", 0)
        if not media:
            log("ntv", url, "sin señal")
            continue
        page_name = name or page_meta.get("title") or pretty_name(key)
        streams = build_live_streams(
            LABEL, page_name, media, user_agent,
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
        if wanted and wanted not in normalize(entry["name"]):
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
