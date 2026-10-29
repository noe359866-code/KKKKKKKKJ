#!/usr/bin/env python3
"""Verifica que la APK lleve el modo autónomo completo dentro del paquete."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path

REQUIRED_ASSETS = {
    "assets/embedded/catalogs.json",
    "assets/embedded/live-catalog.json",
    "assets/embedded/providers.json",
}


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def verify(apk: Path) -> None:
    with zipfile.ZipFile(apk) as zf:
        names = set(zf.namelist())
        missing = REQUIRED_ASSETS - names
        if missing:
            fail(f"faltan assets embebidos: {sorted(missing)}")

        python_libs = [n for n in names if n.startswith("lib/") and n.endswith(".so") and "libpython" in n]
        if not python_libs:
            fail("la APK no incluye libpython; Chaquopy no empaquetó el intérprete")

        chaquopy_assets = [n for n in names if n.startswith("assets/chaquopy/")]
        if not chaquopy_assets:
            fail("la APK no incluye assets de Chaquopy")

        providers = json.loads(zf.read("assets/embedded/providers.json"))
        enabled = [p for p in providers.get("providers", []) if p.get("enabled", True)]
        if not enabled:
            fail("no hay scrapers habilitados en embedded/providers.json")
        if any(p.get("script") == "example_provider.py" for p in enabled):
            fail("el proveedor de demo no debe estar habilitado en la APK autónoma")

    print(f"APK autónoma verificada: {len(enabled)} scrapers, {len(python_libs)} libpython, assets Chaquopy presentes.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    if not args.apk.is_file():
        fail(f"no existe la APK: {args.apk}")
    verify(args.apk)


if __name__ == "__main__":
    main()
