"""Small, bounded, atomic disk cache. Never caches Python code or video files."""
from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from pathlib import Path
from typing import Any, Optional


class JsonCache:
    def __init__(self, directory: str, max_entries: int = 160, clock=time.time):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.max_entries = max_entries
        self.clock = clock
        self.lock = threading.RLock()

    def _path(self, key: str) -> Path:
        return self.directory / (hashlib.sha256(key.encode()).hexdigest() + ".json")

    def get(self, key: str, stale_seconds: int = 0) -> Optional[Any]:
        with self.lock:
            try:
                path = self._path(key)
                if path.stat().st_size > 2_000_000:
                    return None
                entry = json.loads(path.read_text(encoding="utf-8"))
                if entry["expires"] + stale_seconds <= self.clock():
                    return None
                return entry["value"]
            except (OSError, ValueError, KeyError, TypeError):
                return None

    def put(self, key: str, value: Any, ttl: int) -> None:
        if ttl <= 0:
            return
        with self.lock:
            path = self._path(key)
            temp = path.with_suffix(".tmp")
            try:
                temp.write_text(json.dumps({"expires": self.clock() + ttl, "value": value},
                                           ensure_ascii=False), encoding="utf-8")
                os.replace(temp, path)
                files = sorted(self.directory.glob("*.json"), key=lambda p: p.stat().st_mtime)
                for old in files[:max(0, len(files) - self.max_entries)]:
                    old.unlink(missing_ok=True)
            except OSError:
                # Android may evict cacheDir or storage may be full: playback still works.
                try:
                    temp.unlink(missing_ok=True)
                except OSError:
                    pass

    def clear(self) -> None:
        with self.lock:
            for path in self.directory.glob("*.json"):
                try:
                    path.unlink()
                except OSError:
                    pass
