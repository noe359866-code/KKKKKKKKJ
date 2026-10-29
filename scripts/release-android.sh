#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build de producción del APK + AAB
# ---------------------------------------------------------------------------
# Uso:
#   export ADDON_BASE_URL=https://addon.tu-dominio.com
#   ./scripts/release-android.sh
#
# Requisitos:
#   - JDK 17 (JAVA_HOME apuntando a él)
#   - Android SDK con la plataforma 36 (ANDROID_HOME o local.properties)
#   - keystore.properties (o variables RELEASE_*) para firmar
#
# Sin ADDON_BASE_URL el script se detiene aquí: el build de Gradle también
# fallaría, pero mejor decirlo antes de arrancar el daemon.
# ---------------------------------------------------------------------------
set -euo pipefail

cd "$(dirname "$0")/.."

if [[ -z "${ADDON_BASE_URL:-}" ]]; then
    echo "ERROR: falta ADDON_BASE_URL." >&2
    echo "       export ADDON_BASE_URL=https://addon.tu-dominio.com" >&2
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: no hay 'java' en el PATH. Instala un JDK 17." >&2
    exit 1
fi

echo "→ Compilando release contra ${ADDON_BASE_URL}"
./gradlew :app:assembleRelease :app:bundleRelease \
    -PreleaseAddonBaseUrl="${ADDON_BASE_URL}" \
    "$@"

echo
echo "Artefactos:"
find app/build/outputs/apk/release app/build/outputs/bundle/release \
    -maxdepth 1 -type f \( -name '*.apk' -o -name '*.aab' \) -print0 2>/dev/null |
    xargs -0 -r ls -lh

echo
echo "Mapping de R8 (guárdalo para desofuscar crash reports):"
ls -lh app/build/outputs/mapping/release/mapping.txt 2>/dev/null || echo "  (sin mapping: revisa que el build haya terminado)"
