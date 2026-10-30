# Android TV autónomo

## Qué cambia

El APK incluye el intérprete **Python 3.11** (Chaquopy 17.1.0), todos los
scrapers reales de `server/scrapers/`, sus helpers, el registro de proveedores
y los identificadores de canales guardados. No basta con copiar los `.py` como
assets: `ChaquopyRuntime` inicia el intérprete y el motor importa cada módulo
y llama a su función `scrape(payload)` dentro del proceso Android.
Las sinopsis, fondos y datos extraídos al abrir una ficha actualizan la interfaz
sin cambiar el identificador del proveedor ni borrar los datos de la tarjeta.

```text
Home / búsqueda / detalles / episodios
              │
       ContentClient (Kotlin)
              │
       modo autónomo [predeterminado]
              │
       EmbeddedContentClient → ChaquopyRuntime
              │
       embedded_engine.py + caché local
              │
       scrapers Python incluidos en el APK
              │
       sitios de origen y servidores de video
              │
       enlaces directos + cabeceras → Media3
```

No se inicia Node.js, un proceso Python externo, un servidor HTTP local,
Termux, Docker ni una shell. Tampoco se descarga el intérprete o código remoto
al arrancar. Los scripts se incluyen directamente desde la carpeta compartida
para evitar mantener dos copias diferentes.

## Cómo usarla

1. Instala el APK en un **Android TV / TV box / Fire TV** con **Android 7.0 /
   API 24 o posterior**. La app es sólo de televisión: declara
   `android.software.leanback` como requisito y no instala en teléfonos.
2. Abre la app: entra en modo autónomo sin pedir IP o dominio.
3. La primera fila es **Canales abiertos** (iptv-org, HLS libre): se reproduce
   con el mando sin claves, sin addon y sin proveedor. Debajo aparece la fila de
   canales guardados. Los scrapers de películas/series/anime **no añaden filas**:
   sólo resuelven el video cuando abres un título.
4. Usa **Buscar** para filtrar las filas de Cinemeta y los canales en vivo, e
   **Inicio** para volver a la portada.
5. Abre una ficha, elige capítulo cuando corresponda y pulsa **Reproducir**.
   No hay que elegir servidor: el reproductor pide las fuentes y salta solo a la
   siguiente si una falla. Si no se pudo extraer una lista real de episodios, se
   permite indicar temporada y episodio con dos contadores; no se fabrica una
   lista.

### Ficha del título (TV)

La ficha es la página de película de Apple TV: backdrop a sangre con dos velos,
tipo y nota en insignias, título grande, sinopsis de tres líneas y **una sola
cápsula blanca** — *Reproducir* (con el capítulo detrás: `T1 E2`) — más *Cerrar*.

- **Sin lista de servidores.** Antes había que elegir fuente antes de ver nada;
  ahora el botón hace todo y el failover decide. La ficha sólo elige *qué*
  capítulo se reproduce.
- **Episodios** como tarjetas 16:9 con la miniatura real del proveedor, etiqueta
  `T1 E2`, nombre y sinopsis corta. La elegida lleva borde blanco y ✓; al cambiar
  de temporada la fila se desplaza al capítulo elegido.
- **Temporadas** en píldoras a la derecha del encabezado, sólo si hay más de una.
- **Sin lista publicada**: contadores `− 1 +` de temporada y episodio y una
  cápsula *Ver este episodio*. Nada de `TextField` con teclado del sistema.

### Reproductor (TV)

Los controles son propios (no el `PlayerView` de Media3): título y fuente en
uso arriba, botones redondos en el centro (título · **−10** · **▶/⏸** · **+10** ·
**Fuentes** · **CC** si hay subtítulos) y barra de progreso con tiempos abajo.
Se ocultan solos a los 4 s y las teclas multimedia del mando funcionan siempre.

El **selector de fuentes** es el panel lateral del reproductor de tvOS: entra por
la derecha, el vídeo sigue viéndose a la izquierda y cada fuente es una tarjeta
enfocable con su número de failover, el proveedor, `HLS · 1080p` y una marca ✓
en la que está en uso. El foco arranca en esa misma, así que ATRÁS sale sin
mover nada; OK cambia de fuente sin salir del vídeo.

**Cambiar de canal** es lo propio de la TV en vivo y no pasa por el Home:
`CH+`/`CH−` (o `MEDIA_NEXT`/`MEDIA_PREVIOUS` en mandos sin esas teclas) saltan
al canal siguiente/anterior de la misma fila, con vuelta en los extremos. Cada
salto pinta un rótulo arriba con el nombre y `3 de 12` que desaparece solo, y la
imagen del canal anterior se mantiene hasta que resuelve la señal nueva. `▲`
abre la guía (franja inferior, vídeo visible) donde `◀ ▶` sintonizan al instante,
`OK` cierra y `ATRÁS` vuelve al canal desde el que se abrió. La tarjeta de un
canal del Home entra al reproductor en una sola pulsación: la ficha no tiene
nada que elegir.

### Perfiles

Al arrancar, la app pregunta **¿Quién está viendo?** con avatares redondos (hasta
seis perfiles). Cada perfil guarda su propio **Seguir viendo**: la posición se
apunta al salir del reproductor y la fila aparece la primera en el Home de esa
persona. El avatar de la barra superior cambia de perfil sin cerrar la app y el
último usado se recuerda para el siguiente encendido.

En **Ajustes** se puede activar **Añadir catálogos de Cinemeta**. Es opcional:
las filas de TV en vivo no dependen de ese servicio.
Los títulos IMDb de Cinemeta se traducen a nombres antes de invocar los
scrapers, sin necesitar una clave privada de TMDB.

### Deportes (área propia)

La última fila del Home es **Agenda deportiva**, con el dibujo del Sports de
Apple TV: no son pósters 2:3 sino marcadores. Justo encima, **TV en vivo ·
Canales** recoge los canales de todos los proveedores de directo.

- **Datos reales, no inventados**: se leen de la agenda del día que publican los
  scrapers en vivo (`futbolibre` y `damitv`, vía `_liveshelf.py`), con título,
  competición, hora y estado. `"Liga 1: Cienciano vs Atlético Grau"` se parte en
  liga y equipos, y un registro sin equipos ni hora se trata como canal.
- Cada tarjeta enseña la competición en mayúsculas, los dos equipos con sus
  iniciales, la hora y, si el partido ya empezó, el chip **EN VIVO**.
- Los partidos programados **no** se marcan como directos: la meta viaja con
  `onAir: false` y la app no los pinta como emisión en curso.
- También entran los canales deportivos de la lista pública (ESPN, TUDN, MLB,
  DAZN…) cuando aparecen. El partido se reproduce con su id de siempre
  (`tv:futbolibre:evento:<slug>`), así que el failover no cambia.
- Sin agenda no hay fila: se prefiere un Home sin deportes a enseñar el partido
  de ayer. La caché es de 10 minutos y no tiene respaldo rancio.

### Scrapers: sólo resuelven streams

Los scrapers de `server/scrapers/` (películas, series, anime y directo) no publican
filas de catálogo. Sus listados son de mejor esfuerzo —sin póster 2:3, sin
sinopsis, con títulos repetidos— y ensuciaban la portada. Ahora sólo se usan
para **resolver el stream**:

- `GET /stream/movie/tt0944947.json` → el motor traduce el id IMDb a título y
  cada scraper busca esa ficha en su propio buscador.
- `GET /catalog/:type/<id-de-scraper>.json` devuelve `{"metas": []}`: no es un
  error, es el contrato. Si un cliente viejo pide esa fila, se le responde vacío.
- La portada queda con Cinemeta (si está activo), la TV en vivo y el área de
  deportes; el catálogo del servidor (`server/config/catalogs.json`) sigue
  igual: sin filas de proveedores.

### Addons de Stremio (v3)

En la pestaña **Addons de Stremio** de Ajustes es posible instalar cualquier
addon externo compatible con el protocolo Stremio v3 indicando la URL de su
manifest (`https://.../manifest.json` o `stremio://...`):

- **Catálogos integrados:** las filas de los addons habilitados aparecen
  automáticamente en la pantalla de inicio con su nombre y el proveedor de origen.
- **Streams combinados:** al abrir cualquier título o episodio, la app consulta
  en paralelo los scrapers locales y los addons instalados que soporten streams,
  combinando y deduplicando las fuentes de video (HTTP/HLS/MPD) respetando
  sus cabeceras (`Referer`, `User-Agent`, etc.) para Media3.
- **Gestión:** cada addon puede activarse, desactivarse o eliminarse
  individualmente. La lista se conserva de forma persistente en el dispositivo.
- **Addons sugeridos:** incluye accesos de instalación en un solo clic para
  catálogos populares como *Streaming Catalogs* (Netflix, Disney+, Prime Video...)
  y *Cinemeta*.

### Actualizaciones automáticas y funcionamiento 100% autónomo

La app integra un gestor de actualizaciones (`AppUpdateManager`) que opera de
forma completamente autónoma:
- **Comprobación directa:** consulta periódicamente las nuevas versiones
  publicadas en las releases oficiales de GitHub (`https://api.github.com/repos/.../releases/latest`),
  sin pasar por Google Play ni servidores intermedios.
- **Descarga e instalación integrada:** al detectar una versión más reciente,
  ofrece el changelog y permite descargar el APK directamente en el dispositivo
  con barra de progreso, abriendo el instalador del paquete Android mediante
  `FileProvider` al finalizar.
- **Control en Ajustes:** permite buscar actualizaciones manualmente en
  cualquier momento y activar o desactivar la comprobación automática al inicio.

La opción **Servidor externo** conserva compatibilidad con el addon anterior.
Sólo ese modo utiliza la URL guardada. Un fallo local no cambia automáticamente
al servidor ni envía consultas a un backend configurado anteriormente.

## Lo que significa «autónoma» y lo que no

- **Sí:** catálogo, búsqueda, selección de episodios, scraping, resolución de
  enlaces y reproducción se coordinan en el dispositivo; no hace falta alojar
  `server/` en una computadora o contratar un backend.
- **No:** no contiene películas ni puede reproducirlas sin Internet. Sigue
  consultando las páginas del proveedor y el CDN que entrega cada video.
- Los dominios, las estructuras HTML, los enlaces firmados y la disponibilidad
  de las fuentes pueden cambiar. Un bloqueo geográfico, CAPTCHA, contenido DRM
  o una fuente caída no se elimina por integrar Python.
- Los parsers de los scrapers son de mejor esfuerzo: buscan la ficha por
  título y no todos los sitios publican pósters, sinopsis o listas completas de
  episodios en HTML. No fabrican filas de catálogo: sólo resuelven streams.
- Los scripts incluidos son estáticos; actualizar un scraper o un dominio
  predeterminado requiere una nueva compilación del APK. No hay actualización
  de código ejecutable descargado de terceros.
- Usa únicamente fuentes y contenido para los que tengas autorización.

## Recursos, caché y concurrencia

- Compatibilidad nativa: **ARM 32 bits (`armeabi-v7a`), ARM 64 bits
  (`arm64-v8a`) y emuladores/TV x86 de 64 bits (`x86_64`)**.
  El mínimo anterior API 23 pasa a API 24 por el intérprete embebido.
- El APK universal incluye las tres arquitecturas; ahora pesará más que el
  cliente anterior. El tamaño real se obtiene después de compilar, no se fija
  un peso mínimo artificial. El AAB permite a Play entregar sólo la ABI necesaria.
- En Android, urllib pasa por un adaptador OkHttp con tiempo máximo para la
  llamada completa y un tope de bytes por documento. TLS usa la confianza de
  Android; no se desactiva la validación de certificados. El servidor/CLI
  conservan urllib estándar.
- El arranque de Python y las consultas se ejecutan fuera del hilo principal,
  con un pool/cola acotados. El pool de proveedores mantiene prioridad y aísla
  errores. Los resolutores internos conservan sus propios límites.
- Cancelar una pantalla descarta el trabajo que no haya empezado y sus
  resultados. Una llamada Python en curso finaliza según sus límites de red;
  no se intenta matar el intérprete compartido ni interrumpir JNI a la fuerza.
- La caché JSON es atómica, acotada y vive en `cacheDir/embedded-engine`.
  Guarda catálogos una hora (directo: diez minutos), detalles seis horas y
  enlaces un máximo de cinco minutos (directo: un minuto), reducido por la
  caducidad declarada (`e`, `exp`, `expires`, `expire`, `expiry` o `exp` en `hdnea`).
- Sólo los metadatos pueden usar un respaldo caducado de hasta siete días.
  Los enlaces de reproducción no usan ese respaldo. Los resultados vacíos no
  se guardan para no impedir un reintento.
- Se conservan Referer, User-Agent, Origin y Cookie de cada fuente para Media3.
  HLS y DASH están incluidos. Las fuentes duplicadas/no reproducibles se filtran.

## Compilar sin servidor

En una máquina con **JDK 17, Python 3.11 y Android SDK 36**:

```bash
./gradlew :app:assembleDebug
python3 scripts/verify-embedded-apk.py app/build/outputs/apk/debug/app-debug.apk

# Producción, con la firma configurada en keystore.properties / RELEASE_*:
./scripts/release-android.sh
```

Sólo se empaquetan `providers.json` y `live-catalog.json` de `server/config/`,
generados con lista blanca durante el build; no se incluyen sus addons externos.
No se necesita `ADDON_BASE_URL`, `allowEmptyAddonBaseUrl` ni iniciar Node.
La URL de backend, si se especifica al compilar, sólo sirve de valor por defecto
al elegir manualmente el modo servidor. Sin keystore, el release queda sin
firmar y no será instalable hasta firmarlo; el debug se firma automáticamente.

El verificador inspecciona el APK real: comprueba el archivo de módulos Python,
todos los scrapers y sus helpers, las bibliotecas nativas de las tres ABIs y la
exclusión de los scrapers de demostración. No considera «completo» un APK
únicamente porque su ZIP contenga archivos `.py`.

## Pruebas

```bash
# Motor local con fixtures HTTP sintéticos (sin servicios externos):
python3 -m unittest discover -s app/src/test/python -v

# Contrato Kotlin, ambos modos y cancelación:
./gradlew :app:testDebugUnitTest :app:lintDebug

# Intérprete, 12 imports y HTTP nativo (GET, POST/formulario y timeout), sin backend:
./gradlew :app:connectedDebugAndroidTest

# Regresiones del servidor opcional y sus scrapers existentes:
(cd server && npm ci && npm test -- --test-concurrency=2)
```

Los fixtures comprueban navegación de catálogo a reproducción usando el scraper
real de PelisPlus, selección de capítulos, parsers de anime, orden de prioridad,
cabeceras, normalización, caché, expiración de tokens y aislamiento de fallos.
No prueban que todos los dominios reales estén disponibles en una ubicación o
fecha determinada. La prueba en TV real debe verificar el mando, los códecs y
una fuente autorizada disponible en su red.

`autonomous-checks.yml` añade compilación, inspección del APK y smoke test de
Android TV en emulador. `android-release.yml` aplica también las pruebas offline
y la inspección del contenido al generar los APK/AAB de producción.
