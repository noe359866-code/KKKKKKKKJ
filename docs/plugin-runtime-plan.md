# Estado de ejecución de plugins

## Decisión y alcance

El objetivo es consumir addons Stremio por su protocolo remoto y ofrecer soporte para providers Nuvio y extensiones Cloudstream. La app integra Stremio, gestiona repositorios y paquetes, ejecuta providers Nuvio habilitados con QuickJS y ha integrado el API Cloudstream v4.7.0 con carga DEX, ejecución de `search`/`load`/`loadLinks` y conversión de enlaces/subtítulos. La licencia del proyecto es GPL-3.0; revisar compatibilidad de distribución de cada dependencia/código adaptado.

## Referencias técnicas

- Nuvio: índice `scrapers[]` con scripts `.js`; el host de Nuvio ejecuta `getStreams(tmdbId, mediaType, season, episode)` y brinda APIs de red/HTML/crypto/settings. El runtime adaptado está en `app/src/main/java/com/tanasi/streamflix/data/extension/nuvio/NuvioPluginRuntime.kt`.
- Cloudstream: `repo.json`/`plugins.json` publica `.cs3` con `fileHash` (`sha256-<hex>`) y tamaño. `.cs3` contiene código DEX; el loader es parte del host Cloudstream y requiere API/runtime compatible, no basta el artefacto `library` aislado.
- Stremio: el host consume los recursos HTTP declarados por cada manifest; no carga código nativo del addon.

Referencias primarias: [NuvioTV](https://github.com/NuvioMedia/NuvioTV), [Cloudstream v4.7.0](https://github.com/recloudstream/cloudstream/tree/v4.7.0), [QuickJS Kotlin](https://github.com/dokar3/quickjs-kt), [Stremio Addon Protocol](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md).

## Implementado

1. **Stremio:** instalación de manifests, control de recursos declarados e integración de catálogos, metadatos, streams y subtítulos.
2. **Índices/paquetes:** UI para añadir repositorios, explorar las entradas, descargar `.js`/`.cs3` por HTTPS con redirecciones revalidadas, límites de tamaño, almacenamiento privado y SHA-256 obligatorio para Cloudstream; `.cs3` además se valida como ZIP con encabezado DEX y `manifest.json` antes de persistirse. El estado instalado y habilitación se persisten. Quitar un paquete elimina el archivo dentro del directorio privado gestionado.
3. **Nuvio:** activación explícita y aviso antes de ejecutar. QuickJS tiene límites de memoria/stack y APIs de host HTTP/HTML; los providers corren con concurrencia acotada, resultados/errores se aíslan por provider y streams/subtítulos se convierten a DTOs Streamflix. La integración usa un TMDB ID directo (`tmdb:<id>` o numérico) o busca `tmdb_id` en los metadatos del contenido; los IDs IMDb sin TMDB ID disponible no se envían incorrectamente como si fueran TMDB.
4. **Cloudstream:** dependencia de API fijada a v4.7.0, stubs mínimos de host, carga de `.cs3` verificados con `DexClassLoader`, registro de `MainAPI`, búsqueda por título o carga TMDB directa, resolución de episodios y conversión de links/subtítulos. La UI advierte que DEX de terceros corre in-process y requiere activación deliberada.

## Pendiente antes de considerar completo

- Verificar compatibilidad de los stubs de host con repositorios Cloudstream reales; los plugins que dependen de Activities/servicios específicos de la app Cloudstream pueden no funcionar. Añadir pruebas instrumentadas con paquetes de prueba construidos desde Cloudstream v4.7.0.
- Verificar compilación real, resolución/API/ABI de QuickJS y Cloudstream 4.7.0, pruebas JVM/Android y reproducción en dispositivos TV. En esta sesión no hay Java instalado, por lo que no se ha validado el build Android.
- Revisar atribución/licencia GPL del runtime adaptado antes de distribuir el APK.

No volver a habilitar scrapers web embebidos antiguos como sustituto de estos ecosistemas: la activación debe ser explícita, con procedencia, gestión y fallos identificables por provider.
