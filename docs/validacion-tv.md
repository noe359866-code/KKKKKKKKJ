# Validación de Android TV y Fire TV — fase 6

## Estado de esta revisión

**Parcial; no hay compilación Android ni pruebas en emulador o dispositivos físicos.** Los resultados siguientes corresponden a esta revisión, no a un APK nuevo:

| Comprobación | Resultado | Alcance y límites |
| --- | --- | --- |
| Lógica Kotlin seleccionada | 31 pruebas pasan | Compiladas con Kotlin 2.1.21 y ejecutadas mediante un runner temporal y sustitutos mínimos de Android, Compose, JUnit, serialización y Media3. No es Gradle/JUnit real ni comprueba las API o integración Android. |
| Ficha y episodios | 4 escenarios pasan | Código del `DetailsViewModel` ejecutado con corutinas y sustitutos de lifecycle, cliente y ajustes: carga y elección de episodio, película, selección manual y reintento. No verifica Compose ni proveedores reales. |
| Python | 108 pruebas pasan | `python3 -m unittest discover -s app/src/test/python -q`. |
| Servidor Node | 281 pruebas pasan | En `server/`: `npm ci --no-audit --no-fund` y `npm test -- --test-concurrency=2`. |
| Compilación y pruebas Android | Bloqueadas | Con JDK 17, el wrapper falló al descargar Gradle desde `services.gradle.org` con `SSLHandshakeException`. Este entorno tampoco tiene SDK Android ni acceso a `dl.google.com`. No se ejecutaron lint, instrumentación ni compilación del APK. |
| Emulador y equipos físicos | No ejecutados | Ninguna evidencia de foco, legibilidad, overscan, reproducción o rendimiento en pantalla. |

Durante la revisión se corrigió la etiqueta «Continuar» para entradas de TV programadas y se hizo que el perfil de dimensiones que usa Compose se recalcule al cambiar la configuración. Ambos cambios necesitan verificación Android real.

Los SHA-256 de los archivos existentes en `dist/` coinciden con su listado y el APK anterior contiene el motor y proveedores integrados; **son artefactos anteriores y no incluyen el rediseño no compilado**. No sirven para validar estos cambios.

## Compilar en un entorno Android preparado

Requisitos: JDK 17, SDK Android (plataforma 36 y build-tools 35.0.0), acceso a los repositorios de Gradle/Google y licencia del SDK aceptada. Desde la raíz:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest \
  --no-daemon --stacktrace
./gradlew :app:connectedDebugAndroidTest --no-daemon --stacktrace
```

El segundo comando requiere un dispositivo o emulador conectado. El debug resultante se espera en `app/build/outputs/apk/debug/app-debug.apk`; sólo existe si la compilación tiene éxito. Se puede instalar con `adb install -r app/build/outputs/apk/debug/app-debug.apk` tras seleccionar el dispositivo correcto en `adb devices` (con más de uno, usar `adb -s SERIAL ...`). Un release sin firmar no es instalable directamente.

El flujo `.github/workflows/autonomous-checks.yml` ya configura JDK/SDK, pruebas Kotlin, lint, compilación y pruebas de instrumentación en un emulador TV; **no se ha ejecutado para estos cambios**. Sus pruebas automatizadas tampoco sustituyen la comprobación visual y manual con mando.

## Matriz pendiente

Registrar para cada ensayo: fecha, commit o identificador de compilación, modelo, versión de Android/Fire OS, resolución, densidad, tamaño físico del televisor, distancia aproximada, escalado de texto, proveedor/contenido usado, resultado y evidencia sin datos personales ni URLs privadas.

| Entorno | Estado | Objetivo |
| --- | --- | --- |
| Emulador Android TV 1080p | Pendiente | Compilación, instrumentación y recorrido inicial con D-pad. |
| Android TV / Google TV en pantalla de 32 pulgadas | Pendiente | Texto y foco legibles a distancia habitual, zona segura y rendimiento. |
| Android TV / Google TV en pantalla mayor | Pendiente | Misma comprobación, con otra resolución o densidad si es posible. |
| Fire TV Stick con Fire OS basado en Android, pantalla de 32 pulgadas o mayor | Pendiente | Instalación, mando real, foco, reproducción y memoria del dispositivo. |

Android no informa de manera fiable las pulgadas físicas: los tamaños de interfaz se basan en dimensiones lógicas `dp`. El margen inicial es 5 % por borde (48 × 27 dp a 960 × 540 dp), que debe comprobarse con el overscan real de cada pantalla; no garantiza ausencia de recortes en todos los televisores.

## Lista manual por dispositivo

No marcar una casilla sin haberla comprobado en el dispositivo correspondiente. Anotar incidencias, pasos, resultado esperado y resultado observado.

- [ ] Instalar el APK recién compilado, abrir desde el lanzador TV y completar el acceso/perfil disponible; repetir tras reiniciar la app.
- [ ] Comprobar que títulos, botones, indicaciones y foco no se cortan en los cuatro bordes; probar con los ajustes de imagen/overscan del televisor.
- [ ] Desde la distancia habitual, leer textos, metadatos, indicadores, errores y controles en una pantalla de 32 pulgadas y otra mayor; probar también escalado de texto cuando el sistema lo permita.
- [ ] Con el mando, recorrer inicio, categorías y carruseles en las cuatro direcciones; confirmar que siempre hay foco visible, no queda atrapado y el scroll revela el elemento enfocado.
- [ ] Entrar y salir de búsqueda; usar el teclado D-pad propio y la entrada física, probar sin resultados y devolver el foco al elemento esperado.
- [ ] En los campos de servidor y addons, usar el teclado del sistema; probar flechas, cierre y reapertura del teclado, y acceso a Guardar tanto en Android TV como en Fire OS.
- [ ] Abrir película, serie y directo; comprobar ficha, metadatos ausentes, selección de temporada/episodio y retorno del foco a la tarjeta de origen. Probar series sin lista y reintento.
- [ ] Recorrer ajustes en dos columnas, abrir y cerrar diálogos con Back, guardar y descartar cambios; comprobar conexión, addons y actualización con servicios reales sin afirmar éxito si hay error.
- [ ] Reproducir contenido disponible; probar pausa, reanudación, avance/retroceso, Back, regreso al catálogo y progreso después de reabrir. Revisar también los eventos de TV todavía no emitidos.
- [ ] Probar reproducción en segundo plano y regreso, interrupción de red, fuentes fallidas y reintento; no cambiar de fuente por una pausa intencional.
- [ ] Probar únicamente pistas de audio y subtítulos realmente presentes, activar/desactivar, cambio de fuente y zapeo con una cola de canales disponible.
- [ ] Navegar listas largas, volver varias veces a inicio y reproducir durante un periodo prolongado; observar fluidez, tiempos de carga, bloqueos y memoria (`adb shell dumpsys meminfo com.tanasi.streamflix.debug`). Registrar mediciones antes de sacar conclusiones.
- [ ] Si el dispositivo permite cambiar resolución o densidad sin reiniciar, verificar que márgenes, tarjetas y foco se actualizan correctamente en catálogo y reproductor.

Si se detectan defectos, corregirlos y repetir tanto las pruebas automatizadas como los casos afectados en la matriz antes de considerar terminada la fase.
