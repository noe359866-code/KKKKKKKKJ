# Reproductor: implementación del lote 7

Cambios propios para Media3/Compose, inspirados en los problemas analizados en `inventario-referencias/lote-07-reproductor.md`, sin trasladar código de las referencias.

- Los saltos de diez segundos requieren que Media3 informe que la fuente admite seek, que permita el comando y que la duración sea positiva. Duración desconocida no significa automáticamente contenido no saltable, así que el botón sigue deshabilitado hasta que llega la duración.
- Cada instancia del reproductor conserva su última muestra válida de progreso. Se observa cuando Media3 está listo o terminó, con duración conocida, y se guarda con la petición de esa instancia; datos de carga no reemplazan esa muestra. Guardado periódico, parada y liberación siguen usando la posición del motor. Esto reduce el riesgo durante failover, pero no prueba la interacción real entre seek, buffering y distintas fuentes.
- Las listas laterales intentan abrir en la selección, seguir la pista enfocada cuando se mueve y recuperar foco si desaparece. Si la lista queda vacía se cierra el panel. Una opción concreta se valida nuevamente contra las pistas actuales antes de aplicarla. No se borran overrides ni se fuerzan selecciones durante el failover; la intención de subtítulos no se cambia sólo porque una lista esté temporalmente vacía.
- Los paneles apartan su contenido de la zona segura estimada por dimensiones dp. El fondo no es un elemento enfocable; Back cierra el panel. El auto-ocultado se suspende cuando Android informa de un servicio de accesibilidad activo. Un formato de fuente desconocido ya no se presenta como MP4.

## Validación y límites

- Se añadieron pruebas unitarias de disponibilidad de seek, caché de muestras y elección de ancla, más un caso de formato desconocido. Las tres pruebas de reglas Kotlin se ejecutaron con stubs locales, no con Android/Media3 reales.
- `git diff --check` y análisis sintáctico de los Kotlin modificados; 108 pruebas Python y 281 pruebas Node del backend pasaron. Estas pruebas no comprueban el reproductor Android.
- Gradle no pudo descargarse (`SSLHandshakeException`); falta el SDK Android. No se compiló un APK ni se ejecutaron pruebas Compose/instrumentadas ni en Android TV o Firestick.
- Pendiente comprobar en dispositivos reales foco con listas largas o cambiantes, botones de seek al cambiar disponibilidad, guardado al fallar/cambiar fuente durante un salto, estados de audio/subtítulos, TalkBack y zona segura/contraste. La medida en dp no identifica pulgadas físicas; verificar visualmente en televisores de 32 pulgadas o mayores.
- Se mantienen fuera de alcance selector de calidad, DVR, siguiente episodio y agrupación de saltos hasta disponer de datos y pruebas de Media3 que los sustenten.
