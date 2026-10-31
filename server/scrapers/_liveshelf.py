#!/usr/bin/env python3
"""
scrapers/_liveshelf.py
------------------------------------------------------------------------------
**TV en vivo y Agenda deportiva**: junta lo que ya publican los proveedores de
directo y lo deja en dos listas que la Home puede pintar tal cual.

Por qué existe
--------------
Cada proveedor de directo sabe enumerar lo suyo (`load_index`, `load_channels`,
`load_agenda`) y el addon sólo tenía 12 canales escritos a mano en
`config/live-catalog.json`. Editar ese JSON a mano para "tener más canales" no
escala: aquí se recoge el listado de todos, se normaliza y se deduplica, y la
fila se llena sola. El JSON curado sigue mandando: sus ids entran **primero** y
bloquean duplicados.

Qué devuelve
------------
```python
{
  "channels": [ {id: "tv:ntv:nfl-network", type: "tv", name: "NFL Network",
                 sourceName: "NTV", releaseInfo: "En directo", live: True,
                 genres: ["Canales"], poster?: "https://…"}, … ],
  "events":   [ {id: "tv:futbolibre:evento:liga-1-x-vs-y", type: "tv",
                 name: "Cienciano vs Atlético Grau", league: "Liga 1",
                 home: "Cienciano", away: "Atlético Grau",
                 startTime: "19:00", status: "En vivo", onAir: True,
                 releaseInfo: "En vivo · 19:00", genres: ["Deportes", "Liga 1"]}, … ],
  "meta": {"providers": {...}, "errors": {...}}
}
```

Los `id` son los mismos que ya resuelve `GET /stream/tv/<id>.json`, así que una
tarjeta sacada de aquí se reproduce sin tocar nada más.

Regla que se respeta: esto **no** convierte a los scrapers en catálogos. Es una
sola fila curada por el addon (canales y agenda), armada con los índices que los
proveedores ya exponen para resolución de streams.

Uso
---
    python3 scrapers/_liveshelf.py --json                # todo
    python3 scrapers/_liveshelf.py --section events      # sólo agenda
    python3 scrapers/_liveshelf.py --section channels --limit 40
    echo '{"section":"events"}' | python3 scrapers/_liveshelf.py

Variables: `LIVESHELF_TIMEOUT_MS` (6000 por proveedor), `LIVESHELF_CHANNELS`
(120), `LIVESHELF_EVENTS` (60).
"""

from __future__ import annotations

import concurrent.futures
import importlib
import json
import os
import re
import sys
import urllib.error
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

from _base import default_headers, log
from _htmlkit import Deadline, normalize

SECTION_CHANNELS = "channels"
SECTION_EVENTS = "events"
SECTIONS = (SECTION_CHANNELS, SECTION_EVENTS)

#: De dónde sale cada lista. Es explícito a propósito: adivinar por la forma del
#: registro acaba metiendo un partido en la fila de canales.
CHANNEL_LOADERS: Dict[str, Tuple[str, ...]] = {
    "futbolibre": ("load_channels",),
    "deportelibre": ("load_index",),
    "tvenvivo": ("load_index",),
    "livelive24": ("load_index",),
    "ntv": ("load_index",),
    "streamcorner": ("load_index",),
}
EVENT_LOADERS: Dict[str, Tuple[str, ...]] = {
    "futbolibre": ("load_agenda",),
    "damitv": ("load_index",),
}

PROVIDER_TIMEOUT_MS = int(os.getenv("LIVESHELF_TIMEOUT_MS", "6000"))
CHANNEL_LIMIT = int(os.getenv("LIVESHELF_CHANNELS", "120"))
EVENT_LIMIT = int(os.getenv("LIVESHELF_EVENTS", "60"))
MAX_WORKERS = 4

#: `A vs B`, `A v B`, `A - B`, `A contra B` (el separador de los títulos reales).
TEAMS_RE = re.compile(r"\s+(?:vs\.?|v\.?|contra|–|—|-)\s+", re.IGNORECASE)
ON_AIR_RE = re.compile(r"\b(?:en\s*vivo|en\s*directo|live|directo)\b", re.IGNORECASE)
#: Sufijos que el sitio pega al nombre del equipo: "(Final)", "· EN VIVO"…
SUFFIX_RE = re.compile(r"\s*(?:\([^)]*\)|\[[^\]]*\])\s*$")
TAG_RE = re.compile(r"\s*[·|]\s*(?:en\s*vivo|live|directo|hd|fhd)\s*$", re.IGNORECASE)
#: Categorías del sitio que dicen el deporte, no la competición.
GENERIC_SPORTS = frozenset((
    "futbol", "fútbol", "soccer", "beisbol", "béisbol", "baseball", "baloncesto",
    "basketball", "tenis", "tennis", "boxeo", "deportes", "otros", "general", "destacados",
))


# ---------------------------------------------------------------------------
# Normalización
# ---------------------------------------------------------------------------


def clean_text(value: Any) -> str:
    """Espacios colapsados y sin marcas de "en vivo" al final."""
    text = " ".join(str(value or "").split())
    previous = None
    while text and text != previous:
        previous = text
        text = TAG_RE.sub("", SUFFIX_RE.sub("", text)).strip(" ·-|")
    return text


def split_teams(text: str) -> Tuple[str, str]:
    """`"Cienciano vs Atlético Grau"` → `("Cienciano", "Atlético Grau")`."""
    halves = TEAMS_RE.split(clean_text(text), maxsplit=1)
    if len(halves) != 2:
        return "", ""
    home, away = clean_text(halves[0]), clean_text(halves[1])
    return (home, away) if home and away else ("", "")


def _entry_id(entry: Dict[str, Any]) -> str:
    return str(entry.get("id") or entry.get("slug") or entry.get("key") or "").strip()


def _entry_name(entry: Dict[str, Any]) -> str:
    return clean_text(entry.get("name") or entry.get("title") or entry.get("label") or "")


def _entry_logo(entry: Dict[str, Any]) -> str:
    for key in ("logo", "poster", "image", "thumbnail", "icon"):
        value = entry.get(key)
        if isinstance(value, str) and value.strip().startswith(("http://", "https://")):
            return value.strip()
    return ""


def _genres(base: str, league: str) -> List[str]:
    """Géneros sin repetir: `Deportes` + `Deportes` no es una lista."""
    genres = [base]
    if league and normalize(league) != normalize(base):
        genres.append(league)
    return genres


def channel_meta(provider: Dict[str, Any], entry: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Un canal del índice → tarjeta de la fila de TV."""
    slug = _entry_id(entry)
    name = _entry_name(entry)
    if not slug or not name or slug.startswith(("http://", "https://")):
        return None
    if split_teams(name)[0]:  # un partido no es un canal
        return None
    league = clean_text(entry.get("league") or entry.get("category") or "")
    genres = _genres("Canales", league)
    meta: Dict[str, Any] = {
        "id": f"tv:{provider['id']}:{slug}",
        "type": "tv",
        "name": name,
        "sourceName": provider.get("label") or provider["id"],
        "releaseInfo": "En directo",
        "genres": genres,
        "live": True,
    }
    logo = _entry_logo(entry)
    if logo:
        meta["poster"] = logo
    return meta


def event_meta(provider: Dict[str, Any], event: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Un partido de la agenda → tarjeta de marcador (liga, equipos, hora, estado).

    Acepta las dos formas que publican los proveedores:

    * `{"title": "Liga 1: Cienciano vs Atlético Grau", "time": "19:00"}`
      (la agenda de Fútbol Libre),
    * `{"name": "Cienciano vs Atlético Grau", "league": "Liga 1",
      "status": "En vivo"}` (el índice de DAMITV).
    """
    event_id = _entry_id(event)
    title = clean_text(event.get("title") or event.get("name") or "")
    if not event_id or not title:
        return None

    # "Liga 1: A vs B" → la liga del título manda; si no viene ahí, la declara
    # el propio registro (`league`) y, en último caso, la categoría —salvo que
    # sólo diga el deporte ("futbol"), que no es una competición.
    league = clean_text(event.get("league") or "")
    teams = title
    head, _, tail = title.partition(":")
    if tail and TEAMS_RE.search(tail):
        league = league or clean_text(head)
        teams = clean_text(tail)
    if not league:
        category = clean_text(event.get("category") or "")
        if normalize(category) not in GENERIC_SPORTS:
            league = category

    home, away = split_teams(teams)
    status = clean_text(event.get("status") or "")
    start = clean_text(event.get("time") or event.get("startTime") or "")
    on_air = bool(ON_AIR_RE.search(status)) or bool(event.get("onAir"))
    if on_air:
        when = f"En vivo · {start}" if start else "En vivo"
    else:
        when = f"Hoy · {start}" if start else "Hoy"

    name = f"{home} vs {away}" if home and away else teams
    genres = _genres("Deportes", league)
    return {
        "id": f"tv:{provider['id']}:{event_id}",
        "type": "tv",
        "name": name,
        "sourceName": provider.get("label") or provider["id"],
        "description": title,
        "releaseInfo": when,
        "status": status or when,
        "startTime": start,
        "league": league,
        "home": home,
        "away": away,
        "genres": genres,
        "onAir": on_air,
        "live": True,
    }


# ---------------------------------------------------------------------------
# Carga por proveedor
# ---------------------------------------------------------------------------


def _call(module: Any, names: Sequence[str], headers: Dict[str, str],
          deadline: Deadline) -> List[Dict[str, Any]]:
    """Prueba los cargadores en orden; un fallo no tumba la fila entera."""
    for name in names:
        loader = getattr(module, name, None)
        if not callable(loader):
            continue
        if deadline.expired():
            return []
        try:
            data = loader(headers, deadline)
        except (urllib.error.URLError, urllib.error.HTTPError, OSError, ValueError, KeyError,
                TypeError, AttributeError) as exc:
            log(f"_liveshelf: {getattr(module, '__name__', '?')}.{name} failed:", exc)
            return []
        except Exception as exc:  # un parser roto no puede dejar la Home vacía
            log(f"_liveshelf: {getattr(module, '__name__', '?')}.{name} crashed:", type(exc).__name__)
            return []
        # Algunos índices devuelven `(entradas, base)`.
        if isinstance(data, tuple):
            data = data[0] if data else []
        if isinstance(data, list):
            return [item for item in data if isinstance(item, dict)]
        return []
    return []


def provider_block(provider: Dict[str, Any], sections: Sequence[str],
                   timeout_ms: int = PROVIDER_TIMEOUT_MS) -> Dict[str, List[Dict[str, Any]]]:
    """Lo que aporta un proveedor: canales y/o partidos de su agenda."""
    pid = str(provider.get("id") or "").strip()
    out: Dict[str, List[Dict[str, Any]]] = {"provider": pid, SECTION_CHANNELS: [], SECTION_EVENTS: []}
    if not pid or (pid not in CHANNEL_LOADERS and pid not in EVENT_LOADERS):
        return out
    if SECTION_CHANNELS not in sections and SECTION_EVENTS not in sections:
        return out
    try:
        module = importlib.import_module(pid)
    except Exception as exc:
        log("_liveshelf: cannot import provider", pid, type(exc).__name__)
        return out

    headers = default_headers(provider)
    if SECTION_CHANNELS in sections and pid in CHANNEL_LOADERS:
        deadline = Deadline(timeout_ms)
        for entry in _call(module, CHANNEL_LOADERS[pid], headers, deadline):
            meta = channel_meta(provider, entry)
            if meta:
                out[SECTION_CHANNELS].append(meta)
    if SECTION_EVENTS in sections and pid in EVENT_LOADERS:
        deadline = Deadline(timeout_ms)
        for entry in _call(module, EVENT_LOADERS[pid], headers, deadline):
            meta = event_meta(provider, entry)
            if not meta:
                continue
            # El índice de DAMITV mezcla partidos y canales 24 h: un registro sin
            # equipos ni hora de comienzo es un canal, no un marcador.
            if not meta.get("home") and not meta.get("startTime"):
                if SECTION_CHANNELS in sections:
                    channel = channel_meta(provider, {
                        **entry,
                        "name": meta["name"],
                        "league": meta.get("league") or "",
                    })
                    if channel:
                        out[SECTION_CHANNELS].append(channel)
                continue
            out[SECTION_EVENTS].append(meta)
    return out


# ---------------------------------------------------------------------------
# Estantería
# ---------------------------------------------------------------------------


def _curated_sections(curated: Iterable[Dict[str, Any]],
                      labels: Optional[Dict[str, str]] = None) -> Dict[str, List[Dict[str, Any]]]:
    """El JSON curado se reparte por `category`: eventos explícitos, resto canales."""
    labels = labels or {}
    out: Dict[str, List[Dict[str, Any]]] = {SECTION_CHANNELS: [], SECTION_EVENTS: []}
    for item in curated or []:
        if not isinstance(item, dict) or not item.get("id"):
            continue
        category = normalize(str(item.get("category") or ""))
        target = SECTION_EVENTS if "evento" in category else SECTION_CHANNELS
        item = dict(item)
        if not item.get("genres"):
            item["genres"] = ["Eventos"] if target == SECTION_EVENTS else ["Canales"]
        if not item.get("sourceName"):
            # El id ya dice el proveedor (`tv:futbolibre:espn-1`).
            parts = str(item["id"]).split(":")
            if len(parts) > 1 and parts[1] in labels:
                item["sourceName"] = labels[parts[1]]
        out[target].append({**item, "type": "tv"})
    return out


def build(providers: Iterable[Dict[str, Any]],
          sections: Sequence[str] = SECTIONS,
          curated: Iterable[Dict[str, Any]] = (),
          channel_limit: int = CHANNEL_LIMIT,
          event_limit: int = EVENT_LIMIT,
          timeout_ms: int = PROVIDER_TIMEOUT_MS) -> Dict[str, Any]:
    """Recoge todos los proveedores en paralelo y devuelve las dos listas.

    El orden de `providers` es la prioridad: el primero que publica un canal se
    queda con su tarjeta y los demás no lo repiten. Los curados van delante.
    """
    wanted = [section for section in sections if section in SECTIONS]
    providers = [provider for provider in providers if isinstance(provider, dict)]
    rows: Dict[str, List[Dict[str, Any]]] = {SECTION_CHANNELS: [], SECTION_EVENTS: []}
    errors: Dict[str, str] = {}

    labels = {str(provider["id"]): str(provider.get("label") or provider["id"])
              for provider in providers if provider.get("id")}
    curated_rows = _curated_sections(curated, labels)
    seen_ids = set()
    seen_names: Dict[str, set] = {SECTION_CHANNELS: set(), SECTION_EVENTS: set()}
    for section in wanted:
        for item in curated_rows[section]:
            if item["id"] in seen_ids:
                continue
            seen_ids.add(item["id"])
            name = normalize(str(item.get("name") or ""))
            if name:
                seen_names[section].add(name)
            rows[section].append(item)

    queue = [provider for provider in providers if provider.get("id")]
    workers = min(MAX_WORKERS, max(1, len(queue)))
    results: List[Dict[str, Any]] = []
    if queue:
        with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(provider_block, provider, wanted, timeout_ms): provider
                       for provider in queue}
            for future in concurrent.futures.as_completed(futures):
                provider = futures[future]
                try:
                    results.append(future.result())
                except Exception as exc:  # pragma: no cover - defensivo
                    errors[provider["id"]] = type(exc).__name__
        # `as_completed` no respeta prioridades: se reordena por la lista original.
        order = {str(provider["id"]): index for index, provider in enumerate(queue)}
        results.sort(key=lambda block: order.get(str(block.get("provider") or ""), 99))

    for block in results:
        pid = str(block.get("provider") or "")
        for section in wanted:
            for item in block.get(section) or []:
                if item["id"] in seen_ids:
                    continue
                name = normalize(str(item.get("name") or ""))
                if name and name in seen_names[section]:
                    continue
                seen_ids.add(item["id"])
                if name:
                    seen_names[section].add(name)
                rows[section].append(item)

    pinned = len(curated_rows[SECTION_CHANNELS])
    channels = _pin_first(rows[SECTION_CHANNELS], pinned, _sort_channels)[:max(0, channel_limit)]
    pinned_events = len(curated_rows[SECTION_EVENTS])
    events = _pin_first(rows[SECTION_EVENTS], pinned_events, _sort_events)[:max(0, event_limit)]
    return {
        SECTION_CHANNELS: channels,
        SECTION_EVENTS: events,
        "meta": {
            "providers": [str(provider["id"]) for provider in queue],
            "sections": wanted,
            "errors": errors,
            "counts": {SECTION_CHANNELS: len(channels), SECTION_EVENTS: len(events)},
        },
    }


def _pin_first(items: List[Dict[str, Any]], pinned: int,
               sorter: Any) -> List[Dict[str, Any]]:
    """Los curados conservan su sitio; el resto se ordena aparte."""
    head, tail = items[:pinned], items[pinned:]
    return head + sorter(tail)


def _sort_channels(items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Deportes primero (son los que busca el usuario) y luego por nombre."""
    def key(item: Dict[str, Any]) -> Tuple[int, str]:
        genres = [normalize(str(genre)) for genre in item.get("genres") or []]
        sports = 0 if "deportes" in genres else 1
        return (sports, normalize(str(item.get("name") or "")))
    return sorted(items, key=key)


def _sort_events(items: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """En directo primero y, dentro de cada grupo, por hora de comienzo."""
    def key(item: Dict[str, Any]) -> Tuple[int, str, str]:
        return (0 if item.get("onAir") else 1,
                str(item.get("startTime") or "99:99"),
                normalize(str(item.get("name") or "")))
    return sorted(items, key=key)


# ---------------------------------------------------------------------------
# CLI / contrato con el servidor Node
# ---------------------------------------------------------------------------


def _providers_from_payload(payload: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Los descriptores llegan del servidor; sin ellos se leen del registro."""
    given = payload.get("providers")
    if isinstance(given, list) and given:
        return [item for item in given if isinstance(item, dict)]
    try:
        from pathlib import Path
        registry = json.loads((Path(__file__).resolve().parent.parent / "config" / "providers.json").read_text())
        return [item for item in registry.get("providers") or []
                if item.get("kind") == "live" and item.get("enabled", True)]
    except (OSError, ValueError):
        return [{"id": pid, "label": pid} for pid in CHANNEL_LOADERS]


def scrape(payload: Dict[str, Any]) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Contrato del servidor: `{"streams": [...], "meta": {...}}` con las tarjetas."""
    section = str(payload.get("section") or "").strip()
    sections = (section,) if section in SECTIONS else SECTIONS
    curated = payload.get("curated") if isinstance(payload.get("curated"), list) else []
    shelf = build(
        _providers_from_payload(payload),
        sections=sections,
        curated=curated,
        channel_limit=int(payload.get("channelLimit") or CHANNEL_LIMIT),
        event_limit=int(payload.get("eventLimit") or EVENT_LIMIT),
        timeout_ms=int(payload.get("timeoutMs") or PROVIDER_TIMEOUT_MS),
    )
    items: List[Dict[str, Any]] = []
    for name in sections:
        items.extend(shelf[name])
    return items, {**shelf["meta"], "section": section or "all"}


def main(argv: Optional[Sequence[str]] = None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)

    def option(name: str, default: str = "") -> str:
        if name in argv:
            index = argv.index(name)
            return argv[index + 1] if index + 1 < len(argv) else default
        return default

    if "--json" in argv or "--section" in argv:
        payload = {
            "section": option("--section"),
            "channelLimit": int(option("--limit", CHANNEL_LIMIT)),
            "eventLimit": int(option("--limit", EVENT_LIMIT)),
        }
    else:
        raw = sys.stdin.read().strip()
        payload = json.loads(raw) if raw else {}

    items, meta = scrape(payload)
    print(json.dumps({"items": items, "meta": meta}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
