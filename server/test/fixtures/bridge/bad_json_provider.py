#!/usr/bin/env python3
"""Prints something that is not JSON (a traceback, an HTML challenge...)."""
import sys

sys.stdin.read()
print("<html><body>Just a moment...</body></html>")
