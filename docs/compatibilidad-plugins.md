# Integraciones de extensiones: compatibilidad y seguridad

Actualizado: 8 de noviembre de 2026.

## Resultado de la investigación

Se revisaron los formatos de índices publicados por los proyectos y la especificación HTTP de Stremio. No se descargaron ni ejecutaron paquetes de terceros.

| Ecosistema | Lo que publica | Modelo de ejecución | Implementación aquí |
| --- | --- | --- | --- |
| Stremio Addon Protocol | `manifest.json` y respuestas HTTP de `catalog`, `meta`, `stream`, `subtitles` y otros recursos opcionales | Lógica en el servicio HTTP remoto del autor | Streamflix instala el manifest, limita las peticiones a los recursos declarados y combina catálogos, streams y subtítulos de addons habilitados |
| Nuvio plugins | `manifest.json` con `scrapers[]`; cada descriptor declara un fichero `.js`, formatos, idiomas y tipos | JavaScript en QuickJS con APIs de host de Nuvio | Descarga gestionada, activación explícita y ejecución QuickJS conectada a reproducción; TMDB ID requerido |
| Cloudstream | `repo.json` con `pluginLists[]`, entradas con `fileHash` SHA-256 y URL `.cs3` | Extensiones DEX cargadas con API Cloudstream v4.7.0 | Instalación, verificación estructural/integridad, activación explícita, `DexClassLoader` y ejecución de `MainAPI` conectadas a playback |

Referencias primarias:

- [Stremio Addon Protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md): manifest, recursos HTTP y rutas.
- [Guía/API de Stremio Addons](https://github.com/Stremio/stremio-addon-sdk/tree/master/docs): catálogo, metadatos, streams y filtros de capacidades.
- [Índice Cloudstream de ejemplo](https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json) y [lista de extensiones](https://raw.githubusercontent.com/recloudstream/extensions/builds/plugins.json): el repositorio enlaza otras listas; los elementos apuntan a paquetes `.cs3`/`.jar` e incluyen hash/versión.
- [Código de Nuvio TV](https://github.com/NuvioMedia/NuvioTV): Nuvio documenta su app Android TV y publica su propio sistema de plugins. Un plugin Nuvio no es por ello un addon Stremio.
- [Índice de ejemplo Nuvio](https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/main/manifest.json): objeto `scrapers[]` con ruta y metadatos de providers JavaScript.

Los repositorios comunitarios pueden cambiar, desaparecer, alojar código hostil o incluir enlaces que infringen derechos. Un manifest válido no constituye garantía de seguridad ni de disponibilidad.

## Qué hace la implementación

`ExternalExtensionRepositoryClient`:

- admite únicamente URLs HTTPS públicas, sin credenciales embebidas ni hosts locales/IP literales;
- limita el tamaño de cada índice, el número de listas Cloudstream consultadas y el número de entradas retenidas;
- sigue redirecciones HTTPS, pero no permite que OkHttp siga un cambio a HTTP;
- valida la forma de Nuvio `scrapers[]` o Cloudstream `pluginLists[]`, y conserva URLs, hash y tamaño declarados;
- `ExternalExtensionPackageInstaller` descarga `.js` y `.cs3` bajo HTTPS, con redirecciones HTTPS revalidadas, límites de tamaño y checksum SHA-256 obligatorio en Cloudstream; además, valida que `.cs3` sea ZIP con `classes.dex` y `manifest.json`, limitando entradas y tamaño expandido;
- los paquetes se guardan en almacenamiento privado y la UI permite instalarlos, activar/desactivar y quitar; Nuvio usa QuickJS y Cloudstream carga DEX con `DexClassLoader` sólo tras activación explícita. El SHA-256 se vuelve a calcular antes de cada carga.

La preferencia de repositorios es local al dispositivo. `Ajustes → Repositorios` permite seleccionar el formato, instalar/quitar paquetes y activar providers Nuvio; el acceso está restringido a repositorios de confianza por decisión del usuario. La pestaña `Stremio` es el flujo activo de catálogo y reproducción. En playback se aceptan URLs HTTP(S) que Media3 pueda abrir (p. ej. HLS, DASH o archivos multimedia); no se implementa transporte torrent/P2P ni recursos de protocolo personalizados.

## Seguridad y límites pendientes

- **Nuvio:** QuickJS no expone clases Java directamente y limita memoria/stack, pero los bridges del host dan acceso de red/HTML. Las llamadas externas pueden tener efectos de privacidad; ejecutar sólo providers de repositorios confiables. El runtime QuickJS público y la API/ABI Android usada aquí aún necesitan verificación de compilación/dispositivo.
- **Cloudstream:** `.cs3` es código DEX de terceros y se ejecuta dentro del proceso con los permisos de Streamflix; Android no ofrece un sandbox seguro para este formato. La UI exige una confirmación de riesgo antes de habilitarlo. El host de compatibilidad cubre API v4.7.0 pero no reemplaza servicios/UI exclusivos de la app Cloudstream, por lo que hay plugins que pueden no ser compatibles.
- **Stremio:** su protocolo separa la lógica (servidor remoto de addon) del cliente, por lo que Streamflix consume recursos HTTP declarados sin cargar código del addon.

Nuvio aporta fuentes cuando hay TMDB ID válido; si el contenido sólo tiene ID IMDb y no expone `tmdb_id` en metadatos, no se fuerza una llamada incorrecta. Cloudstream ejecuta providers habilitados mediante la API fijada, con compatibilidad condicionada por las APIs de host disponibles. La API QuickJS y la dependencia Cloudstream aún requieren compilación e instrumentación real.

## Android TV y Fire TV

El APK usa Leanback, mando D-Pad y Media3. `armeabi-v7a` y `arm64-v8a` cubren arquitecturas comunes de Fire TV, pero no certifican toda versión de Fire OS, perfiles de DRM, codecs ni las reglas de publicación de Amazon. La validación final requiere instalarlo en modelos reales de Fire TV y Android TV, probar foco/Back/teclado y reproducir fuentes autorizadas de diferentes tipos.
