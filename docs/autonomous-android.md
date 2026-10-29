# Android TV autónomo

## Qué cambia

El APK incluye el intérprete **Python 3.11** (Chaquopy 17.1.0), los doce
scrapers reales de `server/scrapers/`, sus helpers, el registro de proveedores
y los identificadores de canales guardados. No basta con copiar los `.py` como
assets: `ChaquopyRuntime` inicia el intérprete y el motor importa cada módulo
y llama a su función `scrape(payload)` dentro del proceso Android.

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

1. Instala el APK en Android TV o móvil con **Android 7.0 / API 24 o posterior**.
2. Abre la app: entra en modo autónomo sin pedir IP o dominio.
3. Aparece la fila de canales guardados; las filas consultadas directamente a
   los proveedores se añaden conforme responden.
4. Usa **Buscar** para consultar los proveedores por título/canal y **Inicio**
   para volver a la portada.
5. Abre una ficha, elige capítulo cuando corresponda, selecciona una fuente
   y reproduce. Si no se pudo extraer una lista real de episodios, se permite
   indicar temporada y episodio manualmente; no se fabrica una lista.

En **Ajustes** se puede activar **Añadir catálogos de Cinemeta**. Es opcional:
las filas de los proveedores y la TV guardada no dependen de ese servicio.
Los títulos IMDb de Cinemeta se traducen a nombres antes de invocar los
scrapers, sin necesitar una clave privada de TMDB.

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
- Los parsers de catálogo son de mejor esfuerzo. No todos los sitios publican
  pósters, sinopsis o listas completas de episodios en HTML.
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

No se necesita `ADDON_BASE_URL`, `allowEmptyAddonBaseUrl` ni iniciar Node.
La URL de backend, si se especifica al compilar, sólo sirve de valor por defecto
al elegir manualmente el modo servidor. Sin keystore, el release queda sin
firmar y no será instalable hasta firmarlo; el debug se firma automáticamente.

El verificador inspecciona el APK real: comprueba el archivo de módulos Python,
los doce scrapers y sus helpers, las bibliotecas nativas de las tres ABIs y la
exclusión de los scrapers de demostración. No considera «completo» un APK
únicamente porque su ZIP contenga archivos `.py`.

## Pruebas

```bash
# Motor local con fixtures HTTP sintéticos (sin servicios externos):
python3 -m unittest discover -s app/src/test/python -v

# Contrato Kotlin, ambos modos y cancelación:
./gradlew :app:testDebugUnitTest :app:lintDebug

# Intérprete, 12 imports y puente HTTP nativo con timeout, sin backend:
./gradlew :app:connectedDebugAndroidTest

# Regresiones del servidor opcional y sus scrapers existentes:
(cd server && npm ci && npm test)
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
