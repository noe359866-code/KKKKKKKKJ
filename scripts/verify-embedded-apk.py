#!/usr/bin/env python3
"""Fail a build if the APK contains assets but not a runnable embedded engine."""
import io
import json
from pathlib import Path
import re
import sys
import zipfile

PROVIDERS = {
    "futbolibre", "deportelibre", "tvenvivo", "animeflv", "jkanime", "monoschinos",
    "gnula", "cuevana", "pelisplushd", "repelis", "peliculasflix", "pelisjuanita",
}
HELPERS = {"embedded_engine", "_embedded_cache", "_embedded_catalogs", "_android_http", "_base", "_htmlkit", "_hosters", "_animekit", "_peliskit", "_tmdb"}
ABIS = {"armeabi-v7a", "arm64-v8a", "x86_64"}


def verify(path: Path):
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        for asset in ("assets/providers.json", "assets/live-catalog.json", "assets/chaquopy/app.imy"):
            if asset not in names:
                raise ValueError(f"Missing APK asset: {asset}")
        with zipfile.ZipFile(io.BytesIO(apk.read("assets/chaquopy/app.imy"))) as app:
            python_names = app.namelist()
            def bundled(module):
                return any(re.search(rf"(^|/){re.escape(module)}(?:\.cpython-\d+)?\.pyc?$", name) for name in python_names)
            missing = sorted(name for name in PROVIDERS | HELPERS if not bundled(name))
            if missing:
                raise ValueError(f"Python modules missing from APK: {missing}")
            if bundled("example_provider") or bundled("template_provider"):
                raise ValueError("Demo/template providers must not ship in the autonomous APK")
        for abi in sorted(ABIS):
            for library in ("libpython3.11.so", "libchaquopy_java.so"):
                if f"lib/{abi}/{library}" not in names:
                    raise ValueError(f"Missing Python native library: {abi}/{library}")
        if "assets/catalogs.json" in names:
            raise ValueError("Server-only third-party addon configuration must not be bundled")
    return {"apk": path.name, "bytes": path.stat().st_size, "MiB": round(path.stat().st_size / 1024**2, 2),
            "embeddedProviders": sorted(PROVIDERS), "abis": sorted(ABIS)}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 scripts/verify-embedded-apk.py path/to/app.apk")
    try:
        print(json.dumps(verify(Path(sys.argv[1])), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        sys.exit(f"Invalid autonomous APK: {error}")
