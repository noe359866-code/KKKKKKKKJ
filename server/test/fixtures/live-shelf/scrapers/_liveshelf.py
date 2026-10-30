#!/usr/bin/env python3
"""Doble de pruebas de `scrapers/_liveshelf.py`: lee el payload y responde fijo.

No importa nada del proyecto a propósito: el objetivo es comprobar que el puente
Node ↔ Python le pasa el payload correcto y que el servicio normaliza la
respuesta, no volver a probar el constructor real (eso está en
`app/src/test/python/test_live_shelf.py`).
"""
import json
import os
import sys

payload = json.loads(sys.stdin.read() or "{}")
providers = [str(item.get("id")) for item in payload.get("providers") or []]

if os.getenv("LIVESHELF_FIXTURE") == "empty":
    print(json.dumps({"channels": [], "events": [], "meta": {"providers": providers}}))
    raise SystemExit(0)

channels = [
    {"id": "tv:futbolibre:espn-1", "type": "tv", "name": "ESPN 1", "sourceName": "Fútbol Libre",
     "releaseInfo": "En directo", "genres": ["Deportes", "Canales"], "live": True},
    {"id": "tv:ntv:nfl-network", "type": "tv", "name": "NFL Network", "sourceName": "NTV",
     "releaseInfo": "En directo", "genres": ["Canales"], "live": True},
    {"id": "tv:streamcorner:mlb-network", "type": "tv", "name": "MLB Network",
     "sourceName": "StreamCorner", "releaseInfo": "En directo", "genres": ["Canales"], "live": True},
]
events = [
    {"id": "tv:futbolibre:evento:liga-1-cienciano-vs-atletico-grau", "type": "tv",
     "name": "Cienciano vs Atlético Grau", "sourceName": "Fútbol Libre",
     "description": "Liga 1: Cienciano vs Atlético Grau", "releaseInfo": "En vivo · 19:00",
     "status": "En vivo", "startTime": "19:00", "league": "Liga 1",
     "home": "Cienciano", "away": "Atlético Grau",
     "genres": ["Deportes", "Liga 1"], "onAir": True, "live": True},
    {"id": "tv:damitv:mlb-yankees-vs-red-sox", "type": "tv", "name": "Yankees vs Red Sox",
     "sourceName": "DAMITV", "description": "MLB: Yankees vs Red Sox",
     "releaseInfo": "Hoy · 21:00", "status": "Programado", "startTime": "21:00",
     "league": "MLB", "home": "Yankees", "away": "Red Sox",
     "genres": ["Deportes", "MLB"], "onAir": False, "live": True},
]

print(json.dumps({"channels": channels, "events": events,
                  "meta": {"providers": providers, "timeoutMs": payload.get("timeoutMs")}}))
