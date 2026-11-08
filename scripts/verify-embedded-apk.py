#!/usr/bin/env python3
"""Fail a build if the APK contains assets but not a runnable embedded engine."""
import io
import json
import os
from pathlib import Path
import re
import sys
import zipfile

PROVIDERS = {
    "animeflv", "jkanime", "monoschinos",
    "gnula", "cuevana", "pelisplushd", "repelis", "peliculasflix", "pelisjuanita",
    "novahd",
}
NATIVE_PROVIDERS = {"novahd": b"Lcom/tanasi/streamflix/data/local/scrapers/NovaScraper;"}
PYTHON_PROVIDERS = PROVIDERS - NATIVE_PROVIDERS.keys()
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
            missing = sorted(name for name in PYTHON_PROVIDERS | HELPERS if not bundled(name))
            if missing:
                raise ValueError(f"Python modules missing from APK: {missing}")
            if bundled("example_provider") or bundled("template_provider"):
                raise ValueError("Demo/template providers must not ship in the autonomous APK")
            if any(bundled(name) for name in NATIVE_PROVIDERS):
                raise ValueError("Native providers must not also ship their Python scraper")
            if any(bundled(name) for name in NATIVE_HELPERS):
                raise ValueError("Native helpers must not also ship their Python module")
        dex = [apk.read(name) for name in names if re.fullmatch(r"classes(?:\d+)?\.dex", name)]
        for provider, descriptor in NATIVE_PROVIDERS.items():
            if not any(descriptor in content for content in dex):
                raise ValueError(f"Kotlin provider missing from APK: {provider}")
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
        # Un APK "completo" pero con la configuración vacía arranca y no
        # reproduce nada: eso es exactamente el fallo que se reportó. Se
        # comprueba el contenido, no sólo que el archivo exista.
        providers = json.loads(apk.read("assets/providers.json").decode("utf-8"))
        enabled = [item for item in providers.get("providers", [])
                   if item.get("enabled", True) and item.get("id") in PROVIDERS]
        if len(enabled) != len(PROVIDERS):
            raise ValueError(f"providers.json has {len(enabled)} enabled scrapers, expected {len(PROVIDERS)}")
        demos = [item.get("id") for item in providers.get("providers", [])
                 if str(item.get("id", "")).startswith("demo-")]
        if demos:
            raise ValueError(f"Demo providers must not ship in the APK: {demos}")
    return {"apk": path.name, "bytes": path.stat().st_size, "MiB": round(path.stat().st_size / 1024**2, 2),
            "embeddedProviders": sorted(PROVIDERS), "abis": sorted(ABIS)}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 scripts/verify-embedded-apk.py path/to/app.apk")
    try:
        print(json.dumps(verify(Path(sys.argv[1])), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        message = f"Invalid autonomous APK: {error}"
        if os.environ.get("GITHUB_ACTIONS"):
            escaped = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
            print(f"::error title=Autonomous APK verification::{escaped}")
        sys.exit(message)
