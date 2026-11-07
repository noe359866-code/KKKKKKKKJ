# Aplicación inicial de las referencias del lote 8

En lugar de seguir revisando repositorios completos, se comenzó a aplicar lo viable en Streamflix. Esta entrega es parcial; no implica que todos los puntos del [análisis](inventario-referencias/lote-08-alder-neaw-vortx.md) estén implementados ni validados.

## Cambios

- El guardado de progreso sigue asociado a la instancia y petición de reproducción. Una fuente con vídeo seleccionado necesita al menos un aviso de primer fotograma de Media3; una fuente con sólo audio seleccionado necesita estar reproduciéndose. Al cambiar de fuente se reinicia esa evidencia antes de cargarla. Si la fuente aún no tiene evidencia, se conserva la última muestra válida de la sesión. Se aplica la misma captura al guardado periódico, segundo plano y liberación.
- La ficha ya no afirma que se haya encontrado «la mejor fuente»: indica que se prueban en el orden disponible y que se intenta otra cuando es posible.
- La restauración de foco ante una acción nueva del mando se implementó en el lote anterior; falta validarla en Compose real.

## Límites y validación

Se añadieron y ejecutaron cinco pruebas de reglas del reproductor con sustitutos mínimos, no con Android/Media3. El código se analizó sintácticamente y `git diff --check` pasó. Se consultaron puntualmente las firmas de Media3 1.7.1 de `onRenderedFirstFrame` y `Tracks.Group.isSelected`.

No hubo compilación Gradle, APK, pruebas instrumentadas ni ejecución en Android TV/Firestick. La evidencia de primer fotograma no lleva identidad de fuente en el callback usado y no confirma por sí sola que un seek haya terminado: probar eventos tardíos, fuentes de sólo audio, pistas cambiantes, buffering, rotación, segundo plano y failover antes de considerar resuelto el problema. El error TLS al descargar Gradle y la ausencia del SDK siguen bloqueando la validación Android.

Quedan pendientes la revisión práctica de foco y carga de fichas, Back en ajustes, accesibilidad, privacidad de diagnósticos, interrupciones de audio sin duplicar la gestión de Media3 y otras propuestas del lote 8. No se han añadido Cast, DVR, IPTV, selector de calidad ni servicios nuevos.
