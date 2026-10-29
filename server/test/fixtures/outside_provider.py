#!/usr/bin/env python3
"""Fuera de SCRAPER_DIR a propósito: el puente NUNCA debe ejecutarlo."""
import json

print(json.dumps({"streams": [{"name": "Escaped", "url": "https://cdn.example/escaped.m3u8"}]}))
