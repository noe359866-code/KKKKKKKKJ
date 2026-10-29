#!/usr/bin/env python3
"""Echoes the payload back as one stream: verifies the stdin JSON contract."""
import json
import sys

payload = json.loads(sys.stdin.read() or "{}")
print(json.dumps({
    "streams": [{
        "name": "Echo",
        "title": f"eco · {payload.get('type')} · {payload.get('id')}",
        "url": "https://cdn.example/echo/index.m3u8",
    }],
    "meta": {"id": payload.get("id"), "type": payload.get("type")},
}, ensure_ascii=False))
