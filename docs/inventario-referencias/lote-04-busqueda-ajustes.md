# Lote 4: búsqueda y ajustes

Se consultaron mediante `fetch_page` los 21 archivos completos de este lote: nueve de [Nuvio TV, commit `fe5c314bdca956696155f2b649de7070c2d03ba4`](https://github.com/NuvioMedia/NuvioTV/tree/fe5c314bdca956696155f2b649de7070c2d03ba4) y doce de [SkyStream, commit `f5523ae07d729b579be30976df8783a0b98c4559`](https://github.com/akashdh11/skystream/tree/f5523ae07d729b579be30976df8783a0b98c4559). Las rutas y hashes están en los inventarios TSV. Son lecturas estáticas; no se ejecutaron estas pruebas ni las aplicaciones de referencia.

## Nuvio TV (9 archivos)

Rutas relativas a `app/src/`.

1. `main/java/com/nuvio/tv/ui/screens/search/SearchEvent.kt`: define eventos independientes para editar y confirmar consulta, recordar búsquedas, borrar historial, filtros, paginación y reintento. El comentario distingue pasar del campo a resultados de simplemente teclear; el archivo declara eventos, no demuestra cómo se procesan.
2. `main/java/com/nuvio/tv/ui/screens/search/SearchUiState.kt`: distingue texto actual y consulta enviada, resultados, error, carga, descubrimiento y sugerencias; la consulta confirmada exige dos caracteres tras quitar espacios externos. La visibilidad de descubrir depende tanto del texto como de si hay una búsqueda enviada.
3. `test/java/com/nuvio/tv/ui/screens/search/SearchPresentationRulesTest.kt`: describe pruebas del umbral, visibilidad de descubrir y restauración de catálogo preferido o actual. No prueba la navegación con mando ni la concurrencia de solicitudes; no ejecutada.
4. `main/java/com/nuvio/tv/ui/screens/settings/LayoutSettingsModel.kt`: define secciones de diseño y oculta opciones incompatibles con el modo elegido; en modo esencial muestra sólo la sección de inicio. Aplicación: opciones reales y sin secciones vacías, no añadir configuraciones que Streamflix no soporte.
5. `main/java/com/nuvio/tv/ui/screens/settings/MediaServersViewModel.kt`: evita conexiones y refrescos simultáneos, comunica estados de conexión/errores y propaga cancelación; mantiene contraseñas fuera del estado de pantalla definido aquí. La persistencia y seguridad de credenciales requieren revisar el repositorio correspondiente, todavía pendiente.
6. `test/java/com/nuvio/tv/ui/screens/settings/SettingsCatalogTest.kt`: comprueba orden y agrupación de categorías bajo combinaciones de perfil, modo, depuración y disponibilidad; valida que no aparezca un separador al principio. No se ejecutó.
7. `test/java/com/nuvio/tv/ui/screens/settings/SettingsStructureTest.kt`: verifica orden de secciones, reglas por motor y ocultación de opciones de diseño sin contenido. No verifica la interacción visual o semántica de cada control; no se ejecutó.
8. `main/java/com/nuvio/tv/ui/screens/settings/PlaybackSettingsModel.kt`: agrupa ajustes de reproducción y expone el bloque de búfer/red para motores compatibles con ExoPlayer; `AUTO` se considera compatible también con mpv. Son categorías de Nuvio, no capacidades existentes de Streamflix.
9. `main/java/com/nuvio/tv/ui/screens/settings/SettingsScrollIndicators.kt`: muestra indicadores decorativos cuando se puede desplazar hacia cada borde, con iconos horizontales automirrored. Comprobar contraste, dirección RTL y que no se confundan con botones; `contentDescription = null` los trata como decoración.

## SkyStream (12 archivos)

Rutas relativas a la raíz del repositorio.

1. `lib/features/search/data/search_history_store.dart`: interfaz pequeña de lectura/escritura que aísla el historial del almacenamiento y facilita pruebas sin base de datos real. No describe por sí misma cifrado ni retención.
2. `lib/features/search/presentation/search_history_provider.dart`: conserva hasta 50 consultas, descarta vacías, actualiza recencia sin duplicar por mayúsculas, permite eliminar y borrar, y limita sugerencias visibles. Prioriza coincidencias iniciales sobre las interiores. Actualiza el estado antes de escribir: habría que comprobar fallos y orden de escrituras concurrentes antes de adoptar un comportamiento semejante.
3. `lib/features/search/presentation/widgets/search_source_tag.dart`: identifica visualmente la familia de una fuente mediante icono y texto localizado. Las etiquetas de 10 dp son específicas de la referencia y no acreditan legibilidad a distancia en TV.
4. `test/features/search/search_history_provider_test.dart`: usa almacenamiento falso y comprueba persistencia solicitada, límites, repetidos, mayúsculas, filtrado y eliminación. No prueba persistencia real, fallos de disco ni concurrencia; no ejecutada.
5. `test/features/search/search_result_filter_test.dart`: describe filtrado que contempla árabe, japonés, chino simplificado/tradicional, turco y puntuación, y conserva respuestas en otro sistema de escritura bajo ciertas condiciones. Es una cobertura útil para no perder resultados internacionales; la implementación del proveedor y sus límites aún no se revisaron aquí.
6. `test/features/search/search_source_tag_test.dart`: comprueba la presencia de nombres de sistemas; no mide contraste, tamaño en TV ni conducta de foco. No se ejecutó.
7. `test/features/search/search_suggestion_row_test.dart`: verifica que el fondo de foco visual abarque toda la fila incluso el icono final y que el margen cambie para un póster. No demuestra por sí solo que el mando pueda operar una acción secundaria o que su semántica sea correcta; no se ejecutó.
8. `lib/features/settings/presentation/general_settings_provider.dart`: lee preferencias del repositorio y persiste antes de publicar la mayoría de cambios; la clave TMDB se normaliza y actualiza también en configuración de ejecución. **Observación estática:** `copyWith(downloadDirectory: null)` conserva el valor anterior por `??`, aunque el setter acepte `null`; si se usa para borrar una ruta, es un caso a investigar con prueba. No se comprobaron los consumidores ni se afirma un fallo visible.
9. `lib/features/settings/presentation/full_screen_mode_provider.dart`: recuerda una elección de pantalla completa para escritorio conectado a TV y actualiza el indicador de modo antes de pedir el cambio a la plataforma. El propio comentario contempla una posible negativa del sistema; no extrapolarlo a Android TV ni asumir que confirma la ventana real.
10. `lib/features/settings/presentation/cache_provider.dart`: expone el tamaño calculado de caché de imágenes/vídeo por el repositorio. No implementa aquí eliminación ni define qué archivos incluye.
11. `test/features/settings/settings_extensions_order_test.dart`: prueba el orden de extensiones y la etiqueta beta mediante sustitutos de dependencias en un entorno simulado de escritorio. No implica que esas extensiones existan en Streamflix; no se ejecutó.
12. `test/features/settings/settings_inset_test.dart`: mide el borde pintado de las tarjetas en cuatro pantallas para detectar márgenes duplicados e inconsistentes. Es una prueba de Flutter con dependencias simuladas, no verifica overscan ni TV/Firestick; no se ejecutó.

## Aplicación y verificaciones propuestas para Streamflix

- El buscador ya tiene vista previa con mínimo de dos caracteres e historial: antes de cambiarlo, probar consultas vacías y cortas, historial, borrar, privacidad, títulos internacionales y resultados que llegan después de cambiar la consulta. `HomeViewModel` ya cancela trabajos anteriores; comprobarlo con respuestas que no cooperan con la cancelación, en vez de declarar resuelta toda condición de carrera.
- En ajustes ya hay borrador, validación, guardado y confirmaciones, además de foco explícito. Probar instalación repetida, cambios sin guardar, fallos, teclado IME, Back y retorno del foco; no asumir que las pruebas externas los cubren.
- Medir márgenes y foco con Compose real, pantalla pequeña en dp, texto grande, RTL y zona segura; los dp de la referencia no son una medida de pulgadas ni una receta de overscan.
- Presentar procedencia o estado sólo cuando el modelo de Streamflix los conoce. No agregar categorías, fuentes, opciones de reproducción ni servicios que no existan.

Las referencias declaran GPL-3.0 y Streamflix es EPL-2.0: se analizan conceptos, sin incorporar código ni recursos de las referencias. Una posible incorporación o distribución requiere revisar licencias.
