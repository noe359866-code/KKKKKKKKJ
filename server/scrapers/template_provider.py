#!/usr/bin/env python3
"""
scrapers/template_provider.py
------------------------------------------------------------------------------
TEMPLATE for a real provider. Copy it, rename it, and enable it in
config/providers.json. It is NOT enabled by default and does not ship with any
site URL: point `PROVIDER_BASE` at a site you are authorised to use.

It shows the typical flow of a real scraper:
    1. build the search/details URL for the requested content
    2. fetch it with the provider's Referer/User-Agent
    3. extract the .m3u8 / .mp4 URL from the player config or an <iframe>
    4. return one or more stream objects (quality, headers...)

Dependencies: standard library only, so it works on a bare Python 3 image. If
you need JS-rendered players install `requests` + `playwright` and run them in
this process (Node will still kill it on timeout).
"""

from __future__ import annotations

import gzip
import json
import os
import re
import urllib.error
import urllib.parse
import urllib.request

from _base import default_headers, log, run, stream

# Set this to a domain you are allowed to scrape, e.g. inside .env:
#   PROVIDER_BASE=https://example.com
PROVIDER_BASE = os.getenv("PROVIDER_BASE", "").rstrip("/")

# Common patterns used by embedded players.
PLAYER_PATTERNS = [
    r"""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
    r"""source\s*:\s*["']([^"']+\.m3u8[^"']*)["']""",
    r"""<source[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
    r"""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""",
]


def http_get(url: str, headers: dict, timeout: float = 10.0) -> str:
    """GET with a hard timeout. Returns decoded text (gzip aware)."""
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read()
        if response.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
        charset = response.headers.get_content_charset() or "utf-8"
        return raw.decode(charset, errors="replace")


def find_media_url(html: str, base_url: str) -> str | None:
    """Extracts the first playable URL from an HTML/JS blob."""
    for pattern in PLAYER_PATTERNS:
        match = re.search(pattern, html, flags=re.IGNORECASE)
        if match:
            candidate = match.group(1).replace("\\/", "/")
            return urllib.parse.urljoin(base_url, candidate)
    return None


def scrape(payload):
    """
    payload keys provided by the Node bridge:
      type, id, baseId, imdbId, season, episode, isLive, isSports, referer,
      userAgent, timeoutMs  (+ any static `options` from providers.json)
    """
    if not PROVIDER_BASE:
        return [], {"reason": "PROVIDER_BASE not configured"}

    content_id = payload.get("id", "")
    headers = default_headers(payload)

    # 1) Build the URL for this provider. Every site differs; this is the part
    #    you rewrite per provider.
    if payload.get("isLive"):
        page_url = f"{PROVIDER_BASE}/live/{urllib.parse.quote(content_id.replace('tv:', ''))}"
    elif payload.get("season") is not None:
        page_url = f"{PROVIDER_BASE}/series/{payload['baseId']}/{payload['season']}/{payload['episode']}"
    else:
        page_url = f"{PROVIDER_BASE}/movie/{payload.get('imdbId') or content_id}"

    log("GET", page_url)
    try:
        html = http_get(page_url, headers, timeout=min(10.0, payload.get("timeoutMs", 10000) / 1000))
    except urllib.error.HTTPError as exc:
        return [], {"reason": f"http_{exc.code}", "url": page_url}
    except Exception as exc:  # noqa: BLE001 - network errors are expected
        return [], {"reason": "request_failed", "error": str(exc)}

    # 2) Some sites hide the player in a second iframe: follow one level.
    iframe = re.search(r"""<iframe[^>]+src=["']([^"']+)["']""", html, flags=re.IGNORECASE)
    if iframe and not find_media_url(html, page_url):
        embed_url = urllib.parse.urljoin(page_url, iframe.group(1))
        try:
            html = http_get(embed_url, {**headers, "Referer": page_url})
        except Exception as exc:  # noqa: BLE001
            return [], {"reason": "iframe_failed", "error": str(exc)}

    media_url = find_media_url(html, page_url)
    if not media_url:
        return [], {"reason": "no_media_url", "url": page_url}

    return (
        [
            stream(
                url=media_url,
                name="Template · 1080p",
                title=f"Template · {content_id}",
                quality="1080p",
                referer=PROVIDER_BASE + "/",
                user_agent=headers["User-Agent"],
                is_live=bool(payload.get("isLive")),
            )
        ],
        {"page": page_url, "resolved": media_url},
    )


if __name__ == "__main__":
    run(scrape)
