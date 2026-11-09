#!/usr/bin/env bash
# APK + AAB cliente para TV. No empaqueta providers web ni requiere backend propio.
# Requisitos: JDK 17, Python 3.11 y Android SDK 36.
# Configura keystore.properties / RELEASE_* para firmar el APK de producción.
set -euo pipefail
cd "$(dirname "$0")/.."

if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: no hay 'java' en el PATH. Instala un JDK 17." >&2
    exit 1
fi

echo "→ Compilando APK + AAB TV client (sin scrapers; fuentes por addons Stremio)"
./gradlew :app:assembleRelease :app:bundleRelease "$@"

APK=$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' -print -quit)
python3 scripts/verify-embedded-apk.py "$APK"

echo
echo "Artefactos (sin keystore, el APK seguirá sin firmar):"
find app/build/outputs/apk/release app/build/outputs/bundle/release \
    -maxdepth 1 -type f \( -name '*.apk' -o -name '*.aab' \) -print0 2>/dev/null |
    xargs -0 -r ls -lh

echo
echo "Mapping de R8:"
ls -lh app/build/outputs/mapping/release/mapping.txt
