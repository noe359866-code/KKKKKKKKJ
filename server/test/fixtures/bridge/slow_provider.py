#!/usr/bin/env python3
"""Sleeps far longer than the test budget: must be SIGKILLed by the bridge."""
import json
import time

json.loads(__import__("sys").stdin.read() or "{}")
time.sleep(30)
print(json.dumps({"streams": [{"name": "Late", "url": "https://cdn.example/late.m3u8"}]}))
