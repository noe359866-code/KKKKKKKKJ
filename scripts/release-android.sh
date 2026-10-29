#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build de producción del APK + AAB exclusivo para Android TV / Google TV
# ---------------------------------------------------------------------------
# Uso:
#   ./scripts/release-android.sh
#
# La APK es autónoma: scrapers Python embebidos, catálogo y reproductor.
# ADDON_BASE_URL es opcional y sólo preselecciona un servidor externo avanzado.
#
# Requisitos:
#   - JDK 17 (JAVA_HOME apuntando a él)
#   - Python 3 (buildPython de Chaquopy)
#   - Android SDK con la plataforma 36 (ANDROID_HOME o local.properties)
#   - keystore.properties (o variables RELEASE_*) para firmar
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: no hay 'java' en el PATH. Instala un JDK 17." >&2
    exit 1
fi

if ! command -v python3 >/dev/null 2>&1; then
    echo "ERROR: no hay 'python3' en el PATH. Chaquopy lo necesita para empaquetar los scrapers." >&2
    exit 1
fi

GRADLE_ARGS=()
if [[ -n "${ADDON_BASE_URL:-}" ]]; then
    echo "→ Release con servidor externo opcional: ${ADDON_BASE_URL}"
    GRADLE_ARGS+=("-PreleaseAddonBaseUrl=${ADDON_BASE_URL}")
else
    echo "→ Release autónomo: scrapers embebidos, sin servidor externo."
fi

./gradlew :app:assembleRelease :app:bundleRelease "${GRADLE_ARGS[@]}" "$@"

echo
echo "Artefactos:"
find app/build/outputs/apk/release app/build/outputs/bundle/release \
    -maxdepth 1 -type f \( -name '*.apk' -o -name '*.aab' \) -print0 2>/dev/null |
    xargs -0 -r ls -lh

echo
echo "Mapping de R8 (guárdalo para desofuscar crash reports):"
ls -lh app/build/outputs/mapping/release/mapping.txt 2>/dev/null || echo "  (sin mapping: revisa que el build haya terminado)"
