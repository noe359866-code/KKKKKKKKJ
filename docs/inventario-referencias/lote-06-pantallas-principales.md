# Lote 6: pantallas principales de inicio, fichas y ajustes

Siguiendo la priorización solicitada, se consultaron con `fetch_page` **seis archivos completos** de pantallas y componentes centrales, en vez de recorrer todas las rutas. Commits fijos: [Nuvio TV `fe5c314bdca956696155f2b649de7070c2d03ba4`](https://github.com/NuvioMedia/NuvioTV/tree/fe5c314bdca956696155f2b649de7070c2d03ba4) y [SkyStream `f5523ae07d729b579be30976df8783a0b98c4559`](https://github.com/akashdh11/skystream/tree/f5523ae07d729b579be30976df8783a0b98c4559). Se leyeron todos los fragmentos de las versiones raw; los hashes de Git figuran en los inventarios. Es análisis estático: los componentes importados por estos archivos no quedan automáticamente revisados ni se ha ejecutado ninguna aplicación.

## Nuvio TV

### 1. `app/src/main/java/com/nuvio/tv/ui/screens/home/HomeScreen.kt`

- Coordina tres presentaciones de portada (clásica, cuadrícula y moderna), refresco al reanudar, carga, errores, continuar viendo y diálogos de opciones. La implementación real de cada presentación está en archivos separados que no quedan cubiertos sólo por esta lectura.
- Usa una barrera de inicio para esperar datos estables y una salida de seguridad de cinco segundos. Una vez liberada, evita volver a bloquear el inicio; también comunica que la pantalla ha terminado de dibujarse. Esto reduce cambios bruscos, pero una espera fija puede retrasar contenido disponible: no copiar el tiempo ni ocultar errores útiles.
- Envía y guarda claves de fila y elemento para restaurar foco; usa referencias estables y pasa algunos estados derivados sin leerlos en el nivel superior para reducir recomposiciones. Los diálogos buscan el foco inicial y evitan operaciones duplicadas mientras están pendientes.
- **Streamflix:** ya carga catálogo por partes y tiene un intento de restauración por identidad. Comprobar visualmente que la carga parcial no haga saltar filas o foco, que errores tengan reintento y que Back vuelva a una tarjeta válida; no convertir la barrera de Nuvio en un requisito propio sin medir tiempos.

### 2. `app/src/main/java/com/nuvio/tv/ui/screens/home/ModernHomeHero.kt`

- Separa imagen/vídeo, degradados y texto; limita el tamaño solicitado de imagen, conserva una imagen anterior durante el enriquecimiento y hace transición al tráiler sólo después de su primer fotograma. El degradado horizontal se invierte en RTL y el inferior funde la imagen con el fondo.
- Muestra el título cuando el logo falta o falla; presenta metadatos según datos disponibles, soporta límites de texto y permite desplazamiento automático de sinopsis si está habilitado. Oculta semántica del indicador IMDb cuando es invisible.
- **Streamflix:** `HeroBanner` ya tiene fondo ligado al foco, transiciones y degradados; `DetailsPanel` ya dispone de respaldo de logo. Prioridad: comprobar imagen fallida, cambios rápidos de foco, textos largos, RTL y legibilidad, y medir memoria en dispositivos modestos. Tráilers automáticos no son imprescindibles y exigirían datos, gestión de sonido, accesibilidad y rendimiento.

## SkyStream

### 3. `lib/features/details/presentation/details_screen.dart`

- Usa un hero inmersivo para ventanas grandes y una variante distinta con `SliverAppBar` para móviles; delega el hero grande a otro componente. Debajo presenta temporadas y episodios, y sólo añade reparto, tráilers o recomendaciones cuando existen.
- Muestra carga y error de metadatos, y cambia los controles cuando el contenido se marca como próximo estreno. Mantiene selección de episodios y su barra de acciones; los botones de retroceso y marcadores de la variante grande se declaran accesibles al D-pad, pero aquí no se ha verificado el recorrido real.
- **Streamflix:** ya distingue estados de episodios y muestra temporadas sólo cuando corresponden. Probar película, serie sin episodios, datos incompletos, carga fallida, recuperación y retorno de foco. No añadir reparto, próximos estrenos o recomendaciones sin datos reales.

### 4. `lib/features/details/presentation/widgets/details_desktop_hero.dart`

- El hero para escritorio/TV usa imagen con fallback, capas de degradado, logo con respaldo textual, metadatos y sinopsis ampliable; condiciona las acciones de reproducción a `comingSoon` y continúa con contenido inferior.
- El archivo declara anchos y márgenes fijos relativos a la ventana y un tamaño de decodificación basado en densidad de `MediaQuery`. La prueba de decodificación leída en el lote 3 era para **móvil**, no valida este hero; comprobar densidad real, recorte y memoria en TV antes de extrapolar.
- **Streamflix:** ya tiene imagen con degradados y logo/texto alternativo en la ficha. Verificar que sinopsis y acciones sean legibles en una ventana de 960×540 dp, con fuente grande y overscan; no adoptar los tamaños de Flutter como medidas universales.

### 5. `lib/features/details/presentation/widgets/tmdb_details_desktop_hero.dart`

- Otra variante de ficha presenta datos TMDB/AniList, progreso local si tiene posición y duración, metadatos y secciones de búsqueda de fuentes como pares; el espacio entre grupos se centraliza.
- Revisión estática: en esta variante se declara un placeholder textual para el logo, pero no se ve un `errorWidget` textual equivalente al del hero anterior; además, el formato de duración construye texto incluso cuando el valor numérico es cero. No se inspeccionaron aquí el modelo ni todos los caminos de datos, por lo que no se afirma un fallo observado.
- **Streamflix:** mantener la jerarquía de metadatos y botones sin inventar valoración o duración; considerar respaldo al fallar imagen y mostrar progreso sólo cuando se corresponda con la obra/episodio real. No copiar servicios o recursos gráficos.

### 6. `lib/features/settings/presentation/settings_screen.dart`

- Organiza ajustes en grupos con título, descripción y valor actual, utilizando recorrido de foco por orden de lectura y diálogos para opciones. Diferencia operaciones de caché, reinicio y borrado total y enlaza un apartado de licencias.
- Oculta el ajuste de ventana completa fuera de escritorio. Varios títulos y descripciones son propios de sus integraciones: su presencia no implica que Streamflix las soporte. La pantalla delega en diálogos y widgets no leídos en este lote; sus confirmaciones o accesibilidad no se consideran verificadas.
- **Streamflix:** ya tiene categorías, borrador y confirmaciones para cambios. Comprobar orden del D-pad, valores actuales, Guardar/Cancelar, acciones destructivas y semántica en TalkBack, manteniendo sólo opciones que realmente funcionan.

## Próximos focos de revisión priorizada

Pantalla de ficha principal de Nuvio y sus componentes de episodios; búsqueda y ajustes principales; controles y navegación del reproductor; pruebas directamente relacionadas con esas pantallas. Los archivos no leídos siguen identificados como pendientes en los inventarios, pero ya no se propone recorrerlos todos indiscriminadamente.

Ambas referencias declaran GPL-3.0 y Streamflix es EPL-2.0. Se documentan ideas y pruebas posibles; no se ha incorporado código ni recursos de las referencias. No se ha compilado Streamflix ni se ha probado en Android TV/Firestick durante este lote.
