#!/usr/bin/env python3
"""Check that an Android client APK contains no bundled site providers."""
import io
import json
import os
from pathlib import Path
import re
import sys
import zipfile

# Historical resolver modules: none of these may be present in the Android APK.
SITE_PROVIDER_MODULES = {
    "animeflv", "jkanime", "monoschinos", "gnula", "cuevana", "pelisplushd",
    "repelis", "peliculasflix", "pelisjuanita", "novahd", "seriesturcas",
    "fanpelis", "flixlatam", "sololatino", "tioanime", "latanime",
}
HELPERS = {"embedded_engine", "_embedded_catalogs", "_base", "_htmlkit", "_hosters", "_animekit", "_peliskit", "_tmdb"}
NATIVE_HELPERS = {
    "_embedded_cache": b"Lcom/tanasi/streamflix/data/local/NativeJsonCache;",
    "_android_http": b"Lcom/tanasi/streamflix/data/local/NativeHttpAdapter;",
}
NATIVE_ENGINE_CLASSES = {
    "engine tools": b"Lcom/tanasi/streamflix/data/local/NativeEngineTools;",
    "provider scheduler": b"Lcom/tanasi/streamflix/data/local/NativeProviderScheduler;",
    "provider ticket": b"Lcom/tanasi/streamflix/data/local/NativeProviderTicket;",
    "catalog document factory": b"Lcom/tanasi/streamflix/data/local/NativeCatalogDocumentFactory;",
    "catalog document": b"Lcom/tanasi/streamflix/data/local/NativeCatalogDocument;",
}
ABIS = {"armeabi-v7a", "arm64-v8a", "x86_64"}


def verify(path: Path):
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        for asset in ("assets/providers.json", "assets/chaquopy/app.imy"):
            if asset not in names:
                raise ValueError(f"Missing APK asset: {asset}")
        with zipfile.ZipFile(io.BytesIO(apk.read("assets/chaquopy/app.imy"))) as app:
            python_names = app.namelist()

            def bundled(module):
                return any(re.search(rf"(^|/){re.escape(module)}(?:\.cpython-\d+)?\.pyc?$", name) for name in python_names)

            missing = sorted(name for name in HELPERS if not bundled(name))
            if missing:
                raise ValueError(f"Python catalog/runtime modules missing from APK: {missing}")
            if bundled("example_provider") or bundled("template_provider"):
                raise ValueError("Demo/template providers must not ship in the Android APK")
            shipped_providers = sorted(name for name in SITE_PROVIDER_MODULES if bundled(name))
            if shipped_providers:
                raise ValueError(f"Website scraper modules must not ship in the Android APK: {shipped_providers}")
            if any(bundled(name) for name in NATIVE_HELPERS):
                raise ValueError("Native helpers must not also ship their Python module")

        dex = [apk.read(name) for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        for helper, descriptor in NATIVE_HELPERS.items():
            if not any(descriptor in content for content in dex):
                raise ValueError(f"Kotlin helper missing from APK: {helper}")
        for helper, descriptor in NATIVE_ENGINE_CLASSES.items():
            if not any(descriptor in content for content in dex):
                raise ValueError(f"Kotlin engine component missing from APK: {helper}")
        for abi in sorted(ABIS):
            for library in ("libpython3.11.so", "libchaquopy_java.so"):
                if f"lib/{abi}/{library}" not in names:
                    raise ValueError(f"Missing Python native library: {abi}/{library}")
        if "assets/catalogs.json" in names:
            raise ValueError("Server-only third-party addon configuration must not be bundled")
        config = json.loads(apk.read("assets/providers.json").decode("utf-8"))
        if config.get("providers", []) != []:
            raise ValueError("The Android client provider registry must be empty; install Stremio addons instead")
    return {"apk": path.name, "bytes": path.stat().st_size, "MiB": round(path.stat().st_size / 1024**2, 2),
            "embeddedProviders": [], "source": "Stremio addons", "abis": sorted(ABIS)}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 scripts/verify-embedded-apk.py path/to/app.apk")
    try:
        print(json.dumps(verify(Path(sys.argv[1])), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        message = f"Invalid Android client APK: {error}"
        if os.environ.get("GITHUB_ACTIONS"):
            escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::error title=Android APK verification::{escaped}")
        sys.exit(message)
