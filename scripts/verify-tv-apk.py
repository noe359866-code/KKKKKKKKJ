#!/usr/bin/env python3
"""Verifica que una APK empaquetada sea exclusiva de Android TV.

Usa `aapt dump xmltree` sobre el manifiesto fusionado de la APK, no sobre el
XML fuente, para detectar también cambios introducidos por el merger.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def feature_blocks(tree: str) -> dict[str, bool]:
    """Devuelve {feature: required}. Si falta required, Android lo trata como true."""
    features: dict[str, bool] = {}
    pattern = re.compile(r"^\s*E: uses-feature\b.*?(?=^\s*E: |\Z)", re.M | re.S)
    for block in pattern.findall(tree):
        name = re.search(r'android:name\(0x[0-9a-fA-F]+\)="([^"]+)"', block)
        if not name:
            continue
        required = re.search(
            r"android:required\(0x[0-9a-fA-F]+\)=\(type 0x12\)0x([0-9a-fA-F]+)",
            block,
        )
        features[name.group(1)] = required is None or int(required.group(1), 16) != 0
    return features


def verify(tree: str) -> None:
    features = feature_blocks(tree)
    if features.get("android.software.leanback") is not True:
        fail("android.software.leanback debe estar declarado como required=true")
    if features.get("android.hardware.touchscreen") is not False:
        fail("android.hardware.touchscreen debe estar declarado como required=false")

    if not re.search(r'Raw: "android\.intent\.category\.LEANBACK_LAUNCHER"', tree):
        fail("falta la entrada LEANBACK_LAUNCHER")
    if re.search(r'Raw: "android\.intent\.category\.LAUNCHER"', tree):
        fail("la APK todavía registra LAUNCHER para móviles")

    banner = re.search(r"android:banner\(0x[0-9a-fA-F]+\)=(.+)", tree)
    if not banner or banner.group(1).strip() in {"(null)", "0x0", "@null"}:
        fail("falta el banner de Android TV")

    print("APK exclusiva de Android TV verificada: Leanback obligatorio, sin LAUNCHER móvil y con banner.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt", default=os.environ.get("AAPT") or shutil.which("aapt"))
    args = parser.parse_args()

    if not args.apk.is_file():
        fail(f"no existe la APK: {args.apk}")
    if not args.aapt:
        fail("no se encontró aapt; usa --aapt o define AAPT")

    result = subprocess.run(
        [args.aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml"],
        check=True,
        capture_output=True,
        text=True,
    )
    verify(result.stdout)


if __name__ == "__main__":
    main()
