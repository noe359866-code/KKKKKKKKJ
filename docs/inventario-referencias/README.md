# Revisión priorizada de Nuvio TV y SkyStream

**Estado: iniciada, no terminada.** Este inventario registra todas las rutas del árbol Git de dos commits fijos. Inventariar una ruta no significa haber leído o comprendido su contenido.

## Alcance y progreso

| Repositorio y commit | Archivos Git | Leídos completos mediante fetch hasta ahora | Archivos pendientes de lectura completa |
| --- | ---: | ---: | ---: |
| Nuvio TV [`fe5c314`](https://github.com/NuvioMedia/NuvioTV/tree/fe5c314bdca956696155f2b649de7070c2d03ba4) | 1.684 | 68 | 1.616 |
| SkyStream [`f5523ae`](https://github.com/akashdh11/skystream/tree/f5523ae07d729b579be30976df8783a0b98c4559) | 4.783 | 66 | 4.717 |
| **Total** | **6.467** | **134** | **6.333** |

Adicionalmente, Nuvio tiene una referencia de submódulo `libmpv-android` al commit `f48aa82b2677428f756a689a44c2477c3f37be51`: el contenido de ese repositorio no forma parte de los 1.684 archivos ni se ha inventariado. Los árboles recursivos de GitHub respondieron `truncated: false`.

> **Licencia (comprobada el 2026-11-07).** Nuvio TV es **GPL-3.0**; Streamflix es
> **EPL-2.0** (lo dice el archivo `LICENSE`; el README llegó a decir Apache-2.0,
> y era falso). Copiar código de Nuvio —aunque sea un archivo suelto— metería
> código GPL-3.0 en un programa EPL-2.0, que son incompatibles entre sí. Por eso
> de aquí sólo se toman
> **ideas**: la lógica y los umbrales no son copyrightables, la expresión
> concreta sí. Todo lo implementado a partir de este inventario se escribió de
> cero, con decisiones y pruebas propias. Mantener esa línea es una condición
> del proyecto, no una preferencia de estilo.
>
> `dev` seguía en `fe5c314` al hacer esta revisión, así que el inventario ya
> cubría la versión vigente.

- [nuvio.tsv](nuvio.tsv) y [sky.tsv](sky.tsv): ruta, tipo Git, tamaño cuando lo da GitHub, SHA de objeto y estado. `fetch_completo` significa que se abrieron todos los fragmentos devueltos por fetch de ese archivo hasta ahora; `pendiente` incluye archivos no examinados y los que sólo se habían consultado parcialmente en la investigación anterior. No indica pruebas funcionales.
- Hay código, pruebas, traducciones, configuraciones, imágenes y binarios; por ejemplo, Nuvio contiene 1.375 archivos Kotlin y SkyStream 915 Dart y 1.949 Go. También hay recursos y dependencias multiplataforma: no todos son pantallas de TV. Los archivos binarios y recursos visuales requieren inspección apropiada, no lectura como texto.
- El archivo [referencias-nuvio-skystream.md](../referencias-nuvio-skystream.md) recoge la revisión anterior de ejemplos adicionales, principalmente mediante API y capturas; no supone lectura completa del repositorio.

## Hallazgos del primer lote (13 archivos)

### Nuvio TV

1. [README.md](https://github.com/NuvioMedia/NuvioTV/blob/fe5c314bdca956696155f2b649de7070c2d03ba4/README.md): describe compilación con Gradle y una base Kotlin/Compose/TV Material/Media3. Es información del proyecto, no una prueba de Streamflix.
2. [ACCESSIBILITY_HANDOVER.md](https://github.com/NuvioMedia/NuvioTV/blob/fe5c314bdca956696155f2b649de7070c2d03ba4/ACCESSIBILITY_HANDOVER.md): los autores registran inspecciones de accesibilidad en un dispositivo propio, problemas de controles sin nombre y límites del árbol de accesibilidad. Indican que algunos roles y anuncios seguían necesitando verificación escuchando TalkBack. Aplicación: comprobar nombres, roles, estados y anuncios reales en Streamflix, no confiar sólo en capturas o en el árbol exportado.
3. [HomeScreenFocusState.kt](https://github.com/NuvioMedia/NuvioTV/blob/fe5c314bdca956696155f2b649de7070c2d03ba4/app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreenFocusState.kt): guarda identidad de fila y elemento, anclas de scroll y desplazamiento vertical. Prueba propuesta: abrir una ficha desde una fila larga, alterar el orden y volver; comprobar que el foco reaparece en el contenido correcto o en una alternativa válida.
4. [FocusRestoreUtils.kt](https://github.com/NuvioMedia/NuvioTV/blob/fe5c314bdca956696155f2b649de7070c2d03ba4/app/src/main/java/com/nuvio/tv/ui/screens/detail/FocusRestoreUtils.kt): espera fotogramas y limita los intentos de restauración. No demuestra que un reintento aislado sirva para todas las listas virtualizadas; hay que probar cuándo el destino existe y qué hacer si desapareció.
5. [SettingsCatalog.kt](https://github.com/NuvioMedia/NuvioTV/blob/fe5c314bdca956696155f2b649de7070c2d03ba4/app/src/main/java/com/nuvio/tv/ui/screens/settings/SettingsCatalog.kt): agrupa categorías y filtra algunas según perfil, modo o disponibilidad. Aplicación: conservar sólo acciones funcionales y evitar controles que no correspondan al estado actual.
6. `LivePlaybackUiPolicy.kt` de NuvioTV: un archivo que su proyecto usa para separar señal de directo y tipo de catálogo. **Retirado del inventario**: Streamflix ya no publica contenido en directo (§26 de `docs/pendientes.md`), así que su convención no aplica. Lo único que sigue vigente es que la extensión HLS por sí sola no distingue directo de VOD, y eso aquí deja de importar: el progreso se guarda sólo con duración conocida.
7. [ContinueWatchingCachePolicy.kt](https://github.com/NuvioMedia/NuvioTV/blob/fe5c314bdca956696155f2b649de7070c2d03ba4/app/src/main/java/com/nuvio/tv/ui/screens/home/ContinueWatchingCachePolicy.kt): restaura caché bajo condiciones explícitas para no sustituir datos remotos ya cargados. Es pertinente sólo si Streamflix incorpora sincronización remota real.

### SkyStream

1. [README.md](https://github.com/akashdh11/skystream/blob/f5523ae07d729b579be30976df8783a0b98c4559/README.md): declara arquitectura Flutter y soporte de varias plataformas y extensiones. Sus afirmaciones de prestaciones no se han probado.
2. [row_traversal.dart](https://github.com/akashdh11/skystream/blob/f5523ae07d729b579be30976df8783a0b98c4559/lib/shared/focus/row_traversal.dart): la política vertical busca la fila más cercana y después el control más cercano en horizontal. Prueba propuesta: recorrer filas desiguales de addons y ajustes sin saltarse controles. La API Flutter no se puede copiar como solución Compose.
3. [home_section.dart](https://github.com/akashdh11/skystream/blob/f5523ae07d729b579be30976df8783a0b98c4559/lib/features/home/presentation/widgets/home_section.dart): oculta filas vacías y usa lista horizontal construida bajo demanda con claves por contenido. En Streamflix, validar filas vacías y elementos duplicados o reordenados; ya existe una función propia de claves con ocurrencia.
4. [player_panel.dart](https://github.com/akashdh11/skystream/blob/f5523ae07d729b579be30976df8783a0b98c4559/lib/features/player/presentation/vlc/panel/player_panel.dart): decide pestañas por capacidades, conserva el foco inicial aunque cambien datos en segundo plano, mantiene los controles visibles bajo el panel y trata Back por niveles. Mide los rótulos para ajustar pestañas a idioma y escala. Pruebas propuestas: selección activa fuera de pantalla, cambio tardío de pistas, Back único, textos grandes y regreso al control de origen. Utiliza libVLC: Streamflix usa Media3 y debe validar sus propias capacidades.
5. [live_window.dart](https://github.com/akashdh11/skystream/blob/f5523ae07d729b579be30976df8783a0b98c4559/lib/features/player/domain/live_window.dart): modela una ventana deslizante para libVLC y limita posiciones a segmentos reproducibles. Los márgenes son específicos de ese motor; no deben trasladarse a Media3 ni presentarse como soporte DVR ya disponible.
6. [playback_progress.dart](https://github.com/akashdh11/skystream/blob/f5523ae07d729b579be30976df8783a0b98c4559/lib/features/player/domain/playback_progress.dart): identifica muestras por sesión, evita guardar datos de otra reproducción y preserva el progreso existente del episodio siguiente. Streamflix ya conserva una petición asociada al reproductor y limita cuándo guarda progreso; hay que verificar con cambios de fuente y reproducción real. Los umbrales del otro proyecto son decisiones de producto, no reglas universales.

## Cómo continuar sin confundir inventario con revisión

Según la última indicación del usuario, priorizar pantallas, navegación y lógica realmente importantes para Streamflix en lugar de recorrer todos los archivos. Mantener los commits fijos y registrar en el TSV sólo archivos de texto abiertos completos, con hallazgos y límites. El resto sigue pendiente, sin pretensión de revisión exhaustiva. Si se revisan recursos visuales o binarios, registrar el método y sus límites; no ejecutar scripts desconocidos.

Ambos proyectos indican GPL-3.0; Streamflix es EPL-2.0. El inventario contiene rutas y hashes, no código o imágenes externos. No se han ejecutado las apps ni se han verificado estos comportamientos en un TV o Firestick.

## Segundo lote: foco, accesibilidad y teclado (25 archivos)

El análisis individual y las pruebas propuestas están en [lote-02-foco.md](lote-02-foco.md).

## Tercer lote: inicio, fichas y episodios (27 archivos)

El análisis individual, una posible discrepancia en selección de progreso y las verificaciones propuestas están en [lote-03-inicio-fichas.md](lote-03-inicio-fichas.md).

## Cuarto lote: búsqueda y ajustes (21 archivos)

El análisis individual y las verificaciones propuestas están en [lote-04-busqueda-ajustes.md](lote-04-busqueda-ajustes.md).

## Quinto lote: reproductor, mando y fuentes (26 archivos)

El análisis individual y las verificaciones propuestas están en [lote-05-reproductor.md](lote-05-reproductor.md).

## Sexto lote: pantallas principales (6 archivos)

La revisión priorizada de inicio, fichas y ajustes está en [lote-06-pantallas-principales.md](lote-06-pantallas-principales.md).

## Séptimo lote: reproductor y pruebas (16 archivos)

El análisis individual está en [lote-07-reproductor.md](lote-07-reproductor.md).

## Alder, NeawStreamTV y VortX

El inventario y la comparación independiente del primer lote de estas tres referencias están en [lote 8](lote-08-alder-neaw-vortx.md), con rutas y estado en [alder.tsv](alder.tsv), [neaw.tsv](neaw.tsv) y [vortx.tsv](vortx.tsv). Sus cifras no están incluidas en la tabla Nuvio/SkyStream de arriba.
