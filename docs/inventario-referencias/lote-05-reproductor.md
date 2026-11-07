# Lote 5: reproductor, mando, fuentes y subtítulos

Se abrieron completos mediante `fetch_page` 26 archivos: 14 de [Nuvio TV, commit `fe5c314bdca956696155f2b649de7070c2d03ba4`](https://github.com/NuvioMedia/NuvioTV/tree/fe5c314bdca956696155f2b649de7070c2d03ba4) y 12 de [SkyStream, commit `f5523ae07d729b579be30976df8783a0b98c4559`](https://github.com/akashdh11/skystream/tree/f5523ae07d729b579be30976df8783a0b98c4559). Las rutas y hashes figuran en los TSV. Es revisión estática: no se ejecutaron las pruebas ni los reproductores de las referencias.

## Nuvio TV (14 archivos)

Rutas relativas a `app/src/`.

1. `main/java/com/nuvio/tv/core/player/PlayerSessionTracker.kt`: cuenta sesiones abiertas incluso en pausa, búfer o búsqueda; cada sesión cierra una sola vez. La superposición de dos sesiones no permite que cerrar la anterior declare cerrado el reproductor nuevo. El paquete declarado no coincide con la carpeta `core/player`; se registra la ruta tal como está en Git.
2. `test/java/com/nuvio/tv/core/player/PlayerSessionTrackerTest.kt`: prueba sesiones superpuestas y cierre idempotente. No mide ahorro de recursos ni prueba el ciclo de vida Android; no ejecutada.
3. `main/java/com/nuvio/tv/ui/screens/player/PlayerScrubRates.kt`: acelera el salto al mantener una tecla mediante su contador de repetición, preservando un paso corto para una pulsación. Las cantidades concretas son decisiones de la referencia; no se trasladan como recomendación universal.
4. `test/java/com/nuvio/tv/ui/screens/player/PlayerScrubRatesTest.kt`: comprueba umbrales, dirección y contador negativo; no prueba eventos reales de mandos o límites del contenido. No ejecutada.
5. `test/java/com/nuvio/tv/ui/screens/player/PlayerBackPressTest.kt`: describe decisiones de Back después de cambiar de episodio según reproducción manual o automática. No es una prueba general de cierre de paneles y depende de reglas no leídas en este lote; no ejecutada.
6. `main/java/com/nuvio/tv/ui/screens/player/PlayerStallWatchdogPolicy.kt`: ante detención prolongada decide esperar, no intervenir con duración desconocida/búfer insuficiente o buscar una posición posterior al borde del búfer, limitada por la duración. Saltar contenido automáticamente puede perjudicar la experiencia; no asumir que es apropiado para Streamflix ni para directos.
7. `main/java/com/nuvio/tv/ui/screens/player/PlayerStartupLoadingPolicy.kt`: separa carga previa al primer fotograma, error conocido y actualización del mensaje durante búfer. Un estado de carga no prueba por sí mismo qué falló.
8. `main/java/com/nuvio/tv/ui/screens/player/PlayerStartupPlaybackPolicy.kt`: modela transiciones READY según primera imagen, reproducción tunelizada y pausa manual; conserva la decisión de pausa. Es una política específica de su controlador Media3, no una receta para copiar sin pruebas.
9. `test/java/com/nuvio/tv/ui/screens/player/PlayerStartupPlaybackPolicyTest.kt`: cubre arranque normal/tunelizado, pausa y posteriores transiciones READY. Trabaja sobre una política pura; no confirma comportamiento de decodificadores o sincronización audiovisual en TV. No ejecutada.
10. `main/java/com/nuvio/tv/ui/screens/player/SkipIntroVisibilityRules.kt`: determina segmentos activos con límite final exclusivo, controla visibilidad y foco al abrir subtítulos y contempla escenas tras créditos explícitas o inferidas. La inferencia no garantiza que exista escena; los intervalos dependen de proveedores y versión de contenido.
11. `test/java/com/nuvio/tv/ui/screens/player/SkipIntroVisibilityRulesTest.kt`: describe límites del segmento, visibilidad y exclusión de foco bajo el panel de subtítulos, además del temporizador. No valida la precisión de los segmentos ni el mando físico; no ejecutada.
12. `main/java/com/nuvio/tv/ui/screens/player/PlayerFirstFrameWatchdogPolicy.kt`: considera forzar `playWhenReady` sólo antes del primer fotograma con pista de vídeo, estado READY y sin pausa manual. La decisión aislada no prueba que debamos forzar reproducción en otra aplicación.
13. `main/java/com/nuvio/tv/ui/screens/player/PlayerFirstFrameCodecRecoveryPolicy.kt`: propone un reintento limitado de modo Dolby Vision o un fallo VC-1 según señales específicas, siempre que se intente reproducir. No demuestra compatibilidad de códecs para Streamflix ni distintos dispositivos.
14. `test/java/com/nuvio/tv/ui/screens/player/PlayerFirstFrameCodecRecoveryPolicyTest.kt`: cubre pausa y clasificación VC-1, no todos los caminos de Dolby Vision ni prueba hardware real. No ejecutada.

## SkyStream (12 archivos)

Rutas relativas a la raíz del repositorio.

1. `lib/features/player/presentation/vlc/chrome_visibility_controller.dart`: mantiene controles visibles mientras no reproduce o hay paneles/gestos activos; soporta retenciones anidadas y evita autoocultar mientras hay navegación accesible. El estado se consulta dinámicamente; las particularidades del árbol semántico descritas corresponden a Flutter.
2. `lib/features/player/domain/source_row_status.dart`: separa resultado de sondeo de conectividad y estado del intento de reproducción. Un archivo o torrent no comprobable figura como no comprobado; haber mostrado imagen acredita acceso, pero no necesariamente capacidad de búsqueda temporal.
3. `test/features/player/source_row_status_test.dart`: cubre no comprobado, chequeo, falta de búsqueda, respuesta que no es vídeo, intentos fallidos y reintento de la fuente actual. Son reglas simuladas, no pruebas contra redes reales; no ejecutada.
4. `lib/features/player/domain/skip_segments.dart`: consulta servicios opcionales y sanitiza intervalos; un resultado vacío o fallo no detiene la reproducción. Para identificadores de episodio no positivos usa temporada/episodio 1: esa suposición no debería reproducirse sin datos fiables en Streamflix.
5. `test/features/player/skip_segments_test.dart`: describe límites inclusivo/exclusivo de intervalos y casos vacíos o subsegundo; no prueba los servicios remotos. No ejecutada.
6. `lib/features/player/domain/subtitle_search_target.dart`: agrupa título, identificadores y episodio para una búsqueda de subtítulos; deja datos desconocidos como nulos y trata una película de un episodio de forma distinta a una serie. Su normalización de IMDb y heurística de tipo son locales a SkyStream y requieren validación antes de reutilizar la idea.
7. `test/features/player/subtitle_search_target_test.dart`: prueba prioridad y normalización de IDs, episodio ausente, película de una entrada y serie mal clasificada. No prueba la exactitud de subtítulos obtenidos ni los proveedores remotos; no ejecutada.
8. `test/features/player/player_icon_button_focus_test.dart`: comprueba un realce además del estado activo y un anillo para TV; en tema claro mide contraste sobre un fondo fijo simulado. No garantiza contraste frente a todas las escenas ni valida una TV física; no ejecutada.
9. `test/features/player/player_panel_labels_test.dart`: prueba extracción de información del texto de fuentes y etiquetas alternativas de pistas libVLC cuando falta nombre o idioma. Reconocer etiquetas no demuestra calidad, idioma o capacidad reales de la fuente; no ejecutada.
10. `test/features/player/chrome_header_test.dart`: describe un encabezado con título, episodio cuando existe y fuente conocida; omite numeración desconocida. Usa un reproductor simulado y variantes de plataforma, no reproducción real; no ejecutada.
11. `lib/features/player/domain/hls_quality.dart`: deduplica alturas presentes en variantes HLS y calcula una altura elegida bajo una política de límite propia de libVLC. La capacidad y selección efectiva de Media3 pueden diferir; no presentar alturas sin señal real ni trasladar la regla de libVLC directamente.
12. `lib/features/player/presentation/vlc/player_value_selector.dart`: reconstruye un widget sólo cuando cambia la parte seleccionada del estado de libVLC y administra el listener al sustituir controlador. La observación de coste se refiere a una vista nativa Flutter; medir por separado recomposiciones y superficies de Compose/Media3.

## Qué verificar en Streamflix

- `PlayerScreen` ya evita autoocultar mientras no reproduce o se muestran fuentes, pistas o guía, e intenta devolver foco al control de origen. Falta comprobar esos caminos con mando, cambios tardíos de pistas y TalkBack; no se ha establecido que la exploración accesible mantenga visibles los controles.
- `SeekPolicy` usa pasos fijos y límites propios. Probar pulsación y repetición, inicio/fin, duración desconocida, directos y mandos Fire TV antes de considerar aceleración. No habilitar búsqueda temporal en directos sólo por una extensión HLS.
- Distinguir fuente no comprobada de fuente fallida y no mostrar una calidad, disponibilidad o idioma que el reproductor/proveedor no haya demostrado. Revisar qué datos reales exponen nuestras fuentes antes de añadir estados.
- Verificar el progreso ante cambio de fuente o sesión y la pausa manual durante búfer/recuperación. No incorporar watchdogs, salto de introducción ni búsquedas externas de subtítulos sin datos reales, consentimiento cuando corresponda y pruebas de su efecto.
- Comprobar etiquetas de pistas reales, foco sobre imágenes claras/oscuras, carga previa al primer fotograma y Back por niveles en Android TV y Firestick. Son verificaciones pendientes, no resultados.

Las referencias declaran GPL-3.0; Streamflix es EPL-2.0. Se documentan ideas y riesgos sin copiar código ni recursos de las referencias; revisar licencias antes de incorporar o distribuir contenido externo.
