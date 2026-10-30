"""
scrapers/_base.py
------------------------------------------------------------------------------
Shared helpers for every scraper.

Contract (Node <-> Python)
    stdin : one JSON object, e.g.
            {"type":"movie","id":"tt1254207","imdbId":"tt1254207",
             "season":null,"episode":null,"isLive":false,
             "referer":"https://site/","userAgent":"Mozilla/5.0...",
             "timeoutMs":12000}
    stdout: one JSON object:
            {"streams":[{"name":"...","title":"...","url":"https://.../x.m3u8",
                         "quality":"1080p","referer":"https://site/",
                         "userAgent":"Mozilla/5.0..."}],
             "meta":{"reason":"optional debug info"}}

Rules
    - Never print anything but the final JSON to stdout. Use log() (stderr).
    - Never hang: Node kills the process after `timeoutMs`, but implement your
      own socket/request timeout too.
    - Return [] instead of raising when a title is simply not available.
"""

from __future__ import annotations

import json
import os
import sys
import time
import traceback
from typing import Any, Dict, List, Optional

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)


def log(*args: Any) -> None:
    """Diagnostics to stderr: captured by the Node bridge, never parsed."""
    print("[scraper]", *args, file=sys.stderr, flush=True)


def read_payload() -> Dict[str, Any]:
    """Reads the JSON payload from stdin, tolerating empty input."""
    raw = sys.stdin.read()
    if not raw or not raw.strip():
        return {}
    try:
        payload = json.loads(raw)
        return payload if isinstance(payload, dict) else {}
    except json.JSONDecodeError as exc:
        log("invalid payload on stdin:", exc)
        return {}


def emit(streams: List[Dict[str, Any]], meta: Optional[Dict[str, Any]] = None) -> None:
    """Writes the final JSON response. Only call this once per process."""
    payload = {"streams": [s for s in streams if s and s.get("url")], "meta": meta or {}}
    sys.stdout.write(json.dumps(payload, ensure_ascii=False))
    sys.stdout.flush()


def stream(
    url: str,
    name: str,
    title: Optional[str] = None,
    quality: Optional[str] = None,
    referer: Optional[str] = None,
    user_agent: Optional[str] = None,
    not_web_ready: bool = False,
    is_live: bool = False,
    media_type: Optional[str] = None,
    **extra: Any,
) -> Dict[str, Any]:
    """Builds one stream object with the shape the Node side expects."""
    item: Dict[str, Any] = {
        "name": name,
        "title": title or name,
        "url": url.strip(),
        "behaviorHints": {"notWebReady": not_web_ready},
        "isLive": is_live,
    }
    if quality:
        # Se normaliza al vocabulario del cliente ("FullHD" → "1080p") porque es
        # la etiqueta con la que el addon ordena y muestra los streams.
        from _htmlkit import guess_quality  # import diferido: _htmlkit importa _base

        item["quality"] = guess_quality(quality) or str(quality).strip()
    if media_type:
        # `hls` / `dash` / `mp4`: el cliente lo necesita cuando la URL no tiene
        # extensión (ExoPlayer, sin pista, intentaría abrir un HLS como MP4).
        item["behaviorHints"]["mediaType"] = str(media_type).lower()
    if referer:
        item["referer"] = referer
    if user_agent:
        item["userAgent"] = user_agent
    item.update(extra)
    return item


def run(scraper_fn) -> None:
    """
    Entrypoint wrapper: reads payload, calls `scraper_fn(payload)`, emits JSON.
    Any exception becomes an empty result + diagnostic on stderr, so a broken
    scraper never breaks the addon response.
    """
    started = time.time()
    try:
        payload = read_payload()
        streams, meta = scraper_fn(payload) or ([], {})
        meta = {**meta, "elapsed_ms": int((time.time() - started) * 1000)}
        emit(streams, meta)
    except Exception as exc:  # noqa: BLE001 - last-resort guard
        log("fatal:", exc)
        traceback.print_exc(file=sys.stderr)
        emit([], {"error": str(exc)})


def default_headers(payload: Dict[str, Any]) -> Dict[str, str]:
    """Headers every real scraper should send to the provider site."""
    return {
        "User-Agent": payload.get("userAgent") or os.getenv("STREAM_USER_AGENT", DEFAULT_USER_AGENT),
        "Referer": payload.get("referer") or "",
        "Accept-Language": "es-ES,es;q=0.9,en;q=0.8",
    }
