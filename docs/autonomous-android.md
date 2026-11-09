# Cliente Android TV sin providers web empaquetados

## Arquitectura actual

La app de `app/` es cliente Android TV / Google TV / Fire TV compatible. No empaqueta ni activa providers web propios: Gradle genera `assets/providers.json` con `providers: []`. Los plugins Nuvio/Cloudstream son paquetes externos instalados y activados deliberadamente por el usuario. El backend `server/` sigue siendo catalog-only; el comprobador del APK falla si detecta providers web históricos embebidos.

- Los catálogos, metadatos y streams se consultan mediante addons Stremio v3 configurados por el usuario.
- La app todavía incluye su runtime local de catálogo/normalización para las funciones de metadatos; no contiene providers de sitios que resuelvan streams.
- El backend `server/` es legado y opcional, queda fuera del APK y no es necesario para usar addons Stremio. Su código fuente mantiene sus propias pruebas y proveedores; no se confunde con el cliente Android.
- Los índices Nuvio (`manifest.json`) y Cloudstream (`repo.json`) se administran desde Ajustes. Nuvio ejecuta JS en QuickJS y Cloudstream habilita `.cs3` verificados bajo API v4.7.0 tras confirmación de riesgo. Ver [compatibilidad de plugins](compatibilidad-plugins.md).

## Seguridad de índices

Los índices deben ser HTTPS. Se rechazan hosts locales/IP directas, se limitan tamaños/cantidades y se siguen redirecciones HTTPS sin permitir downgrade a HTTP. Los paquetes Nuvio/Cloudstream se guardan en privado; `.cs3` exige SHA-256, formato ZIP/DEX y manifest válidos. La ejecución DEX comparte el proceso y los permisos de Streamflix; la activación pide consentimiento.

El protocolo Stremio permite delegar la lógica a un servicio remoto por HTTP. Para reproducción se aceptan URLs HTTP(S) que Media3 pueda abrir; no se incluye soporte torrent/P2P.

## Construcción y comprobación

Requisitos: JDK 17, Android SDK 36 y Python 3.11 para la integración de catálogo Chaquopy que permanece en el APK. Las versiones de API/ABI están en `app/build.gradle`.

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s app/src/test/python -v
python3 scripts/verify-embedded-apk.py app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:connectedDebugAndroidTest
```

`verify-embedded-apk.py` comprueba que el registro empaquetado está vacío, que no hay módulos de proveedores web antiguos y que siguen presentes los componentes de catálogo requeridos. Las pruebas instrumentadas necesitan un emulador o un televisor real.

En el entorno del agente no había `java` en PATH, por lo que no se pudo ejecutar Gradle, lint ni Android tests durante la implementación.
