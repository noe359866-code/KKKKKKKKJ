#!/usr/bin/env python3
"""
scrapers/example_provider.py
------------------------------------------------------------------------------
Reference scraper. It does NOT touch any real site: it returns public test
streams (Mux / Google sample bucket) so the whole pipeline (cache, priority
ordering, ExoPlayer failover) can be validated end to end.

It is intentionally verbose: copy this file, replace `scrape()` internals with
your real provider logic (requests + BeautifulSoup/Playwright), keep the rest.

How the Node side calls it:

    echo '{"type":"tv","id":"demo-live","isLive":true,"provider":"demo-live"}' \
      | python3 scrapers/example_provider.py
"""

from __future__ import annotations

import os
import time

# `_base.py` lives next to this file, which is also sys.path[0] when Node
# spawns `python3 /abs/path/example_provider.py`.
from _base import log, run, stream

# ---------------------------------------------------------------------------
# Public test streams (safe, no copyrighted content).
# ---------------------------------------------------------------------------
HLS_TEST_STREAMS = {
    "demo-live": [
        {
            "url": "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
            "name": "Demo TV · 1080p",
            "quality": "1080p",
        },
        {
            "url": "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
            "name": "Demo TV · 720p",
            "quality": "720p",
        },
    ],
    "demo-vod": [
        {
            "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            "name": "Demo VOD · 1080p",
            "quality": "1080p",
        },
        {
            "url": "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ElephantsDream.mp4",
            "name": "Demo VOD · 720p",
            "quality": "720p",
        },
    ],
    "demo-fallback": [
        {
            "url": "https://test-streams.mux.dev/pts_shift/master.m3u8",
            "name": "Backup · 480p",
            "quality": "480p",
        }
    ],
}


# Subtítulos de demostración: el contrato del protocolo Stremio
# (`{url, lang, label}`) tal como lo deja novaHD en `meta.subtitles`. Sirven
# para ejercitar `lib/subtitles.js` y el recurso `/subtitles/...` sin red.
DEMO_SUBTITLES = {
    "demo-vod": [
        {"url": "https://demo.local/subs/bigbuckbunny.en.vtt", "lang": "en", "label": "English"},
        {"url": "https://demo.local/subs/bigbuckbunny.es.srt", "lang": "SPA", "label": "Español"},
    ],
    "demo-fallback": [
        {"url": "https://demo.local/subs/bigbuckbunny.es.srt", "lang": "es-419"},
        {"url": "https://demo.local/embed/player.html", "lang": "es"},
    ],
}


def scrape(payload):
    """
    Real scrapers follow this shape:

        1. validate payload (imdbId / season / episode / channel slug)
        2. GET the site search page with default_headers(payload)
        3. parse HTML, build candidate URLs
        4. resolve the .m3u8 (some sites hide it behind a JS player)
        5. return (streams, meta)

    Here we just simulate a slow network call so the TTL cache is observable.
    """
    provider = payload.get("provider", "demo-live")
    is_live = bool(payload.get("isLive"))
    content_id = payload.get("id", "?")

    # Simulated scrape latency. Cache hits must be MUCH faster than this.
    delay_ms = int(os.getenv("DEMO_DELAY_MS", "350"))
    time.sleep(delay_ms / 1000)

    candidates = HLS_TEST_STREAMS.get(provider, HLS_TEST_STREAMS["demo-live"])

    # A "live" provider shouldn't answer VOD requests and vice versa, except
    # for the fallback provider which serves both.
    if provider == "demo-live" and not is_live:
        log("demo-live skipping non-live id", content_id)
        return [], {"reason": "type_mismatch", "provider": provider}
    if provider == "demo-vod" and is_live:
        log("demo-vod skipping live id", content_id)
        return [], {"reason": "type_mismatch", "provider": provider}

    streams = []
    for candidate in candidates:
        streams.append(
            stream(
                url=candidate["url"],
                name=candidate["name"],
                title=f"{candidate['name']} · {provider} · demo",
                quality=candidate["quality"],
                # Most HLS providers reject requests without their Referer.
                referer=payload.get("referer") or "https://demo.local/",
                user_agent=payload.get("userAgent"),
                not_web_ready=False,
                is_live=is_live,
            )
        )

    meta = {"provider": provider, "delay_ms": delay_ms, "live": is_live}
    subtitles = DEMO_SUBTITLES.get(provider)
    if subtitles and not is_live:
        meta["subtitles"] = subtitles

    log(f"provider={provider} id={content_id} streams={len(streams)} delay_ms={delay_ms}")
    return streams, meta


if __name__ == "__main__":
    run(scrape)
