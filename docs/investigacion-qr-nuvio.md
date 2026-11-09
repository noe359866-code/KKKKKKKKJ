# Investigación: QR de Nuvio para addons, plugins y administración desde el teléfono

Fecha de revisión: 8 de noviembre de 2026. Se inspeccionó `NuvioMedia/NuvioTV` en el commit `12d96bced9821bdf4bc233039ffb1ccd1795e55b` de `dev`, además de las pantallas y modelos relacionados. La investigación es estática; el estado de implementación y verificación en Streamflix se documenta al final.

## Resumen importante

El QR de Nuvio **no codifica directamente la URL de un addon ni de un plugin**. En modo “Manage from phone”, la TV abre temporalmente un pequeño servidor HTTP local y el QR codifica la dirección LAN de la TV, por ejemplo `http://192.168.x.x:8080`. El teléfono abre una página web servida por la TV, modifica una lista de configuración, envía el cambio y espera que la persona lo confirme en la pantalla del televisor.

El login de la cuenta Nuvio también utiliza un QR, pero es **otro flujo**. Para administrar addons/plugins, el QR conecta el teléfono con el servidor de la TV en la misma red local.

## Flujo del QR de plugins

1. En la pantalla Plugins, el usuario elige **Manage from phone**.
2. `PluginViewModel.startQrMode()` busca una IPv4 activa: primero intenta Wi-Fi y luego recorre las interfaces de red.
3. Detiene un servidor anterior y arranca `RepositoryConfigServer` en el primer puerto libre de 8090–8099.
4. Genera un QR de 512 × 512 con ZXing cuyo contenido es `http://<IP-de-la-TV>:<puerto>`; muestra además la dirección en texto para poder escribirla manualmente.
5. El teléfono carga la página HTML local, lee los repositorios instalados con `GET /api/repositories`, permite añadir/quitar URLs y envía la lista completa con `POST /api/repositories`.
6. El servidor crea una propuesta pendiente. El teléfono muestra “esperando al televisor” y consulta `GET /api/status/<id>` cada 1,5 segundos, hasta dos minutos.
7. La TV muestra un diálogo de cambios; sólo al confirmar se aplican las adiciones/eliminaciones. Al rechazar o cerrar se descarta. Tras confirmar, el servidor se apaga unos segundos después; cerrar el QR también lo apaga y limpia el estado.
8. Añadir el repositorio no necesariamente activa todos sus proveedores: el usuario puede tener que habilitarlos desde Plugins.

Fuentes de código: [PluginViewModel](https://github.com/NuvioMedia/NuvioTV/blob/12d96bced9821bdf4bc233039ffb1ccd1795e55b/app/src/main/java/com/nuvio/tv/ui/screens/plugin/PluginViewModel.kt), [RepositoryConfigServer](https://github.com/NuvioMedia/NuvioTV/blob/12d96bced9821bdf4bc233039ffb1ccd1795e55b/app/src/main/java/com/nuvio/tv/core/server/RepositoryConfigServer.kt), [página web de repositorios](https://github.com/NuvioMedia/NuvioTV/blob/12d96bced9821bdf4bc233039ffb1ccd1795e55b/app/src/main/java/com/nuvio/tv/core/server/RepositoryWebPage.kt), [generador QR](https://github.com/NuvioMedia/NuvioTV/blob/12d96bced9821bdf4bc233039ffb1ccd1795e55b/app/src/main/java/com/nuvio/tv/core/qr/QrCodeGenerator.kt).

## Flujo del QR de addons y “más”

Los addons Stremio usan otro servicio, `AddonConfigServer`, normalmente en el rango 8080–8089. El servidor recibe un modo de trabajo, crea la página web correspondiente y sirve el estado actual. Según el modo puede permitir:

- añadir o quitar manifest URLs de addons;
- ordenar o habilitar/deshabilitar catálogos;
- administrar colecciones y su visibilidad;
- usar búsquedas/metadatos auxiliares para configurar las colecciones.

Hay modos separados (`FULL`, `ADDONS_ONLY`, `COLLECTIONS_ONLY`) para no exponer controles que no se necesitan en cada flujo. Al guardar, la TV recibe una propuesta y presenta al usuario el resumen antes de aplicar cambios. El formato de addons no es el mismo que los repositorios de plugins Nuvio: tienen pantallas y endpoints separados.

Fuentes de código: [AddonManagerViewModel](https://github.com/NuvioMedia/NuvioTV/blob/12d96bced9821bdf4bc233039ffb1ccd1795e55b/app/src/main/java/com/nuvio/tv/ui/screens/addon/AddonManagerViewModel.kt), [AddonConfigServer](https://github.com/NuvioMedia/NuvioTV/blob/12d96bced9821bdf4bc233039ffb1ccd1795e55b/app/src/main/java/com/nuvio/tv/core/server/AddonConfigServer.kt), [modelos/modos de configuración](https://github.com/NuvioMedia/NuvioTV/blob/12d96bced9821bdf4bc233039ffb1ccd1795e55b/app/src/main/java/com/nuvio/tv/core/server/AddonConfigServerModels.kt).

## Lecciones de producto que sí conviene adoptar

- **No teclear URLs largas con el mando.** Mostrar “Administrar desde el teléfono” al lado del formulario normal.
- **QR de sesión local y temporal**, no un QR estático ni uno que contenga directamente la URL del manifest.
- **Mostrar la dirección legible bajo el QR** y explicar “teléfono y TV deben estar en la misma red”.
- En el navegador del teléfono, mostrar nombre, URL y estado actual; permitir añadir/quitar y corregir errores antes de guardar.
- En la TV, presentar una confirmación explícita con cambios concretos —repositorios agregados/eliminados—; no aplicar a ciegas el estado enviado desde el teléfono.
- Mientras se edita, bloquear/serializar cambios concurrentes; al cancelar, expirar o confirmar, apagar el servidor y limpiar bitmap, token y estado pendiente.
- Mantener vías alternativas: URL en el mando o archivo/manifest importado cuando el QR no pueda usarse.
- Mantener separados los tres conceptos: login de cuenta, repositorio de plugin Nuvio y manifest de addon Stremio.

## Riesgos y diferencias que no copiaría sin mejorar

- El QR revisado es una URL `http://` a una IPv4 local. La dirección puede fallar con aislamiento de clientes Wi-Fi, redes de invitados, VLAN distintas, varias interfaces o cambios de IP.
- En los endpoints inspeccionados no aparece una autenticación por sesión/token; la confirmación del televisor sí protege la aplicación de cambios, pero el servidor LAN todavía expone datos y endpoints mientras está abierto. Para Streamflix conviene un token aleatorio de un solo uso/vida corta y validar su posesión para leer estado y proponer cambios.
- El servidor local debe detenerse siempre: cancelar, confirmar, timeout, salir de pantalla y `onCleared`/ciclo de vida. Probar además cierres por Back, suspensión y terminación de proceso.
- El navegador puede visitar cualquier página que la LAN anuncie como QR; la pantalla debe identificar claramente la IP/nombre de Streamflix y no inducir a escanear un QR de otra TV.
- Validar esquema, tamaño y cantidad de URLs en servidor; normalizar/deduplicar; no confiar en la validación JavaScript del teléfono. En caso de instalar plugin ejecutable, pedir consentimiento específico y dejar claro qué se ejecutará.
- El `GET`/`POST` local y las búsquedas auxiliares amplían superficie de ataque. Limitar rutas, datos y tiempo de vida; no devolver secretos; manejar errores y solicitudes simultáneas.

## Diseño recomendado para Streamflix

Implementar una pieza reutilizable `TvPhoneSetupSession` para administrar *plugins y addons*, con adaptadores específicos de validación/aplicación por formato, en vez de copiar los dos servidores de Nuvio. La sesión debería:

1. iniciar sólo tras acción explícita del usuario, resolver IP alcanzable y abrir puerto efímero disponible;
2. generar token criptográficamente aleatorio, asociarlo al puerto y poner expiración corta; codificar una URL local de uso único;
3. servir una página local pequeña y autocontenida (sin depender de Google Fonts/Internet), con formulario para la URL que el usuario ya eligió y estado claro;
4. validar todo en Android, proponer el cambio sin aplicarlo, y mostrar en TV tipo de integración, URL/nombre y operación solicitada;
5. aplicar sólo tras confirmar con el mando y cerrar inmediatamente el listener; cancelar/rechazar/expirar también lo cierra;
6. mostrar URL manual, estado de conexión, error de red y botón de renovar QR.

El QR administra repositorios/manifests. La instalación de paquetes, activación y ejecución se realiza desde la UI de Streamflix; Nuvio usa QuickJS y Cloudstream requiere confirmación antes de cargar DEX in-process.

## Implementación en Streamflix (estado del código)

La primera versión está implementada en `data/phone/PhoneSetupSession.kt` y conectada a `ServerSettingsDialog.kt`:

- El botón «Gestionar desde el teléfono · QR» inicia un NanoHTTPD ligado a la IPv4 de la TV, en un puerto libre del rango 8765–8774. El QR apunta a una ruta de sesión con token de 192 bits aleatorio; la sesión expira a los 10 minutos y se cierra al salir de la pantalla QR.
- La página local no depende de recursos externos. Presenta la lista instalada, permite añadir/quitar addons Stremio e índices Nuvio/Cloudstream, y expone modo autónomo/servidor externo, URL del backend, catálogos Cinemeta y comprobación de actualizaciones.
- El teléfono sólo envía una propuesta. El servidor comprueba token, tamaño, esquemas, límites y URLs; la TV muestra los elementos añadidos/eliminados y preferencias que cambiarán. Sólo después de aprobar con el mando se llama a los gestores existentes.
- Stremio usa `StremioAddonManager.installAddon/removeAddon`. Nuvio/Cloudstream usan `ExternalExtensionRepositoryManager` para índices y `ExternalExtensionPackageManager` para instalar/quitar paquetes; Nuvio y Cloudstream tienen engines de playback conectados.
- Si falla alguna operación remota, la TV reporta aplicación parcial en vez de ocultar el error. No se promete atomicidad entre varias instalaciones.

Dependencias: `NanoHTTPD 2.3.1` y `ZXing core 3.5.3`, como la referencia investigada. Se añadió `ACCESS_WIFI_STATE` para preferir la IP del adaptador Wi-Fi cuando Android la expone; se recorre la interfaz IPv4 como fallback.

**Verificación pendiente:** la sesión actual no tiene Java, por lo que no se pudo compilar Kotlin/Compose ni iniciar la app en un dispositivo/emulador Android TV. Sí pasaron `git diff --check`, los 119 tests Python existentes, parseo XML del manifest y comprobación sintáctica Node del JavaScript embebido. La validez de permisos/conectividad, foco D-pad, lectura del QR, acceso entre redes/aislamiento Wi-Fi, expiración y confirmación real en TV siguen sin comprobarse en hardware.
