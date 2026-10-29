#!/usr/bin/env python3
"""Exits non-zero after writing diagnostics to stderr."""
import sys

sys.stdin.read()
print("Traceback (most recent call last): ...", file=sys.stderr)
sys.exit(3)
