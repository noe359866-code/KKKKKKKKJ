# Streamflix Reborn · cliente para TV

Continuación comunitaria de Streamflix, centrada exclusivamente en televisores: Android TV, Google TV y dispositivos Fire TV compatibles con Android. La navegación está pensada para mando D-Pad; no hay una interfaz de teléfono ni se exige pantalla táctil.

## Estado de las fuentes

La app Android **no empaqueta providers web propios**. Las fuentes de reproducción pueden venir de addons Stremio remotos o de plugins Nuvio/Cloudstream que el usuario instala y activa desde Ajustes. No se incluye contenido audiovisual ni se distribuyen repositorios de terceros.

> Los addons de terceros pueden acceder a contenido protegido. Añade únicamente servicios que tengas derecho a usar y respeta las leyes y condiciones aplicables.

## Integraciones

| Formato | Qué hace Streamflix | Estado |
| --- | --- | --- |
| Addon Stremio v3 | Lee `manifest.json`; consulta catálogos, metadatos y streams HTTP directos compatibles con Media3 | **Integración funcional**; no incluye transporte torrent/P2P |
| Addon Stremio añadido desde Nuvio | Usa el mismo protocolo Stremio; se instala pegando el manifest URL | **Integración funcional** |
| Plugin Nuvio | Descarga `.js`, ejecuta QuickJS con bridges HTTP/HTML y combina streams/subtítulos | **Integrado**; activación explícita, ID TMDB necesario |
| Repo Cloudstream | Descarga y valida `.cs3`, carga `MainAPI` con Cloudstream v4.7.0 y ejecuta `search`/`load`/`loadLinks` | **Integrado**; activación explícita y confirmación de riesgo DEX in-process |

Stremio, Nuvio y Cloudstream son ecosistemas separados: Stremio usa protocolo HTTP remoto; Nuvio plugins son JavaScript; Cloudstream extensiones son DEX. **Ajustes → Índices externos** permite añadir repositorios, instalar, activar/desactivar y quitar paquetes. Los addons Stremio que ya uses en Nuvio también pueden añadirse a Streamflix. Sólo se ejecutan paquetes que el usuario instale y active.

La especificación pública de Stremio describe un manifest y peticiones HTTP `/{resource}/{type}/{id}.json`; Cloudstream distribuye extensiones compiladas `.cs3`; los índices de plugins Nuvio describen providers JavaScript locales. Son formatos distintos, no intercambiables.

## Para TV y Fire TV

- `LEANBACK_LAUNCHER`, banner de TV y requisito de `android.software.leanback`.
- Pantallas Compose navegables con D-Pad, foco visible y reproductor Media3.
- `minSdk 24`; ABI incluidos: `armeabi-v7a`, `arm64-v8a` y `x86_64`, útiles para la mayoría de TV boxes y Fire TV. La compatibilidad final depende de la versión de Fire OS y del dispositivo.
- La versión Android no depende de Google Play Services para las funciones de reproducción.

## Compilar y probar

Requisitos: JDK 17 y Android SDK 36.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

El test instrumentado requiere un emulador o dispositivo Android TV. Este entorno de trabajo no tiene `java` disponible, así que la compilación y las pruebas Android deben ejecutarse en un entorno preparado.

## Estructura

- `app/`: cliente Android TV, UI, Media3, cliente Stremio e índices de repositorios externos.
- `server/`: backend Node.js opcional de catálogos y metadatos. **No incluye scrapers ni fuentes de reproducción** y no se empaqueta en el APK. La reproducción se obtiene de los addons Stremio configurados en el cliente. Consulta [`server/README.md`](server/README.md).
- `docs/`: diseño y validación de TV, además del análisis de compatibilidad de extensiones.

## Investigación y límites

Consulta [`docs/compatibilidad-plugins.md`](docs/compatibilidad-plugins.md) para referencias de formatos y la separación de capacidades. Listar un índice no verifica que sus enlaces funcionen, no implica aprobación de su contenido y no convierte código externo en código seguro. Ningún repositorio externo se instala automáticamente.

## Créditos y licencia

Este repositorio es una continuación independiente del proyecto original de [Lory-Stan TANASI](https://github.com/stantanasi). Consulta [`LICENSE`](LICENSE) para la licencia aplicable al código de este proyecto y las licencias de sus dependencias.
