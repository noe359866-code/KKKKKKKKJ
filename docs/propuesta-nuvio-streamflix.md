# Propuesta aplicada a Streamflix: inspiración Nuvio TV

## Estado

La propuesta comienza por **foco de catálogo y reproductor**, porque determina si se puede navegar con mando. Nuvio TV y SkyStream sirvieron de referencia conceptual; no se incorporó código ni recursos de esos repositorios. Esta implementación todavía no tiene compilación ni validación Android real.

### Trabajo realizado

- Al abrir una ficha desde una tarjeta del catálogo, se guarda la identidad de la fila, la clave de la tarjeta y su posición. Al cerrar la ficha o volver del reproductor se intenta desplazar la lista vertical, desplazar la fila y recuperar el foco. Si la tarjeta desapareció, se usa una posición válida de la misma fila; si desapareció la fila, la primera fila con contenido. Una lista vacía no recibe una petición de foco.
- Se usa un indicador de retorno para no restaurar la tarjeta en el momento de abrir la ficha; una petición ya intentada se consume para no robar foco posteriormente. Aperturas desde hero y búsqueda no se identifican como tarjeta de fila y mantienen la navegación existente.
- Al cerrar las opciones de fuentes o pistas, se intenta devolver el foco al botón que abrió el panel cuando sigue disponible. Desde el error de reproducción, o si las pistas desaparecen, se conserva el retorno a la superficie de vídeo. Los controles no deben ocultarse automáticamente mientras esté abierto un panel.
- Las categorías de ajustes exponen además el rol semántico de pestaña. Ya existían la semántica de selección y los roles radio/switch para las opciones correspondientes.

### Verificaciones de esta iteración

- Cinco pruebas de la lógica de resolución de foco pasaron con Kotlin 2.1.21 y sustitutos mínimos para serialización y JUnit. Cubren reordenación, duplicados, tarjeta y fila desaparecidas, y listas vacías. No prueban Compose ni Android.
- 108 pruebas Python pasaron. Un análisis sintáctico de ocho archivos Kotlin no encontró errores; no comprueba tipos ni integración.
- Se intentó `./gradlew :app:testDebugUnitTest --no-daemon` con JDK 17; falló al descargar Gradle por `SSLHandshakeException`. El SDK Android no está disponible. No se construyó APK ni se ejecutaron pruebas de instrumentación.

### Pendiente antes de darlo por terminado

1. Compilar y ejecutar pruebas Kotlin, lint y Compose reales en un entorno Android preparado.
2. Probar con D-pad la ida y vuelta desde tarjetas lejanas, títulos duplicados, filas reordenadas/eliminadas y resultados de búsqueda; verificar que el foco no se pierde ni se mueve de forma inesperada.
3. Probar con fuentes y pistas reales el cierre de paneles, el cambio de opciones durante la reproducción y el retorno desde errores. Comprobar Back y que los controles no se oculten bajo un panel.
4. Verificar semántica de ajustes y anuncios con TalkBack, además de fuente grande y overscan en pantallas de 32 pulgadas y mayores.
5. Tras validar foco, revisar visualmente portada, catálogo, fichas y episodios, manteniendo exclusivamente funciones respaldadas por datos reales. No se han rediseñado de nuevo en esta iteración.
6. Medir fluidez y memoria en Android TV y Fire OS. La matriz de prueba completa está en [validacion-tv.md](validacion-tv.md).

Los artefactos antiguos de `dist/` no incluyen estos cambios. La compatibilidad con dispositivos y la restauración de foco no pueden darse por confirmadas sin una compilación y pruebas en emulador y televisor.
