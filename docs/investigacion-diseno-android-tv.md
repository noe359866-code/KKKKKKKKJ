# Investigación y propuesta de diseño: Streamflix para TV

Investigación: 7 de noviembre de 2026. Estado: **propuesta y auditoría estática**;
no es una certificación ni una prueba visual en un televisor.

## 1. Qué se investigó y qué no

Se revisó el contenido textual de las portadas y fichas de Cinejoy, Willow y
CinemaCity, incluida una película y una serie; los enlaces concretos y límites
están en [diseño TV](diseno-tv.md#análisis-ampliado-de-las-referencias). Cinejoy
aporta portada, ficha con metadatos y episodios enlazados; Willow, secciones de
catálogo y lista; CinemaCity, ficha, reproductor y etiquetas visibles de calidad,
audio, subtítulos y velocidad. Es **inspiración de jerarquía**, no una autorización
para copiar sus marcas, imágenes, fuentes de reproducción ni servicios.

No se operaron los menús interactivos de ajustes de esas webs ni se probó su
reproducción: la herramienta devolvió contenido textual y, en dos reproductores,
una pantalla de carga. Por ello **no afirmamos conocer su apariencia dinámica**.
Para cubrir la traducción a TV se consultaron guías oficiales de Google y Amazon,
incluyendo el ejemplo de diseño JetStream, con secciones para ficha,
reproductor, ajustes de subtítulos y configuración [8](https://developer.android.com/design/ui/tv/samples/jet-stream).

## 2. La diferencia entre una web y una TV

| En la web | Propuesta en Android TV / Fire OS |
|---|---|
| Hover, clic y scroll libre | Una única posición de foco, flechas, OK y Atrás; cada acción debe ser alcanzable. |
| Mucha información y enlaces | Jerarquía a distancia: título, metadatos útiles, acción principal y detalle progresivo. |
| Botón o menú en cualquier borde | Fondo a sangre; controles, textos y foco dentro de la zona segura. |
| Selector de calidad con ratón | Panel navegable con el mando, sólo con opciones reales de Media3/fuente. |
| Resolución del navegador | `dp`/`sp`, medidas disponibles y preferencias de accesibilidad; nunca inferir pulgadas por resolución. |

Google recomienda diseñar a distancia de salón, con contraste y una trayectoria
predecible de foco [3](https://developer.android.com/training/tv/get-started/navigation).
Amazon recomienda reducir densidad de información y priorizar el acceso al
contenido [1](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html).

### Tamaño de TV, resolución y zona segura

Como referencia, Google diseña en 960 × 540 dp y recomienda proteger los
controles con aproximadamente 5 % por lado: **48 dp horizontales y 27 dp
verticales en ese lienzo**. El fondo puede extenderse hasta los bordes
[1](https://developer.android.com/training/tv/playback/compose/layouts). Amazon
propone también proteger el 5 % exterior y mantener el elemento enfocado
completo dentro de la zona segura [1](https://developer.amazon.com/docs/fire-tv/design-and-user-experience-guidelines.html).
Son referencias, no garantía universal: validar con overscan real y tamaño de
fuente aumentado.

Un 32″ y un 65″ pueden comunicar el mismo ancho y alto lógico, aunque la
experiencia física y la distancia de visión sean distintas. No se puede diseñar
una detección fiable de pulgadas a partir de `screenWidthDp`. La adaptación debe
basarse en **ancho, alto, densidad, escala de fuente y espacio real**, más una
posible preferencia manual de legibilidad/área segura. La resolución del vídeo
puede ser distinta de la resolución de la interfaz
[2](https://developer.android.com/training/tv/playback/memory).

## 3. Diseño propuesto por pantalla

Las siguientes medidas son **puntos de partida de diseño propios**, no valores
certificados ni obligaciones de Google. Deben ajustarse al probarlas con textos
largos, pantallas de distinta altura y mando real.

### Inicio y catálogo

- Portada de gran formato: fondo 16:9 con degradado lateral/inferior, título o
  logo real con texto de respaldo, tipo/año/duración/nota sólo si existen,
  sinopsis breve y acciones «Reproducir» y «Más información».
- Filas horizontales con encabezados legibles. Póster 2:3 para películas/series;
  16:9 para progreso y filas de arte ancho. El arte no debe quedar cortado por el foco.
- Menú con Inicio, Películas, Series, Buscar, Perfil y Ajustes. Elegir
  entre barra superior y lateral como decisión de diseño; no introducir ambos
  simultáneamente sin justificar el espacio y probar el foco.
- Mantener el foco durante navegación entre filas y al regresar de la ficha o
  del vídeo; distinguir visualmente **enfocado**, **seleccionado**, **pulsado** y
  **deshabilitado**. El foco cambia el destacado sólo si la interacción sigue
  siendo fluida. El sistema de Google describe escala, borde, color y elevación
  como indicadores combinables [2](https://developer.android.com/design/ui/tv/guides/styles/focus-system).
- No inventar una «Mi lista», recomendaciones, plataformas o insignias si no hay
  datos y almacenamiento; tampoco presentar filtros de los elementos cargados
  como si fueran todo el catálogo.

### Ficha de película y serie

1. Primera pantalla: imagen, logo opcional o título textual, sinopsis breve y
   botón principal. Mostrar nota, duración, año y géneros únicamente si existen.
2. Series: temporadas claramente separadas; miniatura, número y descripción de
   episodio cuando el proveedor los entregue. OK en episodio inicia ese
   episodio; elegir temporada no inicia reproducción por sí solo.
3. Debajo: información adicional o recomendaciones sólo cuando la fuente lo
   permita; ocultar secciones vacías. No prometer reparto o tráileres que el
   modelo de datos no provee.
4. Sin listado: explicar que no hay episodios y ofrecer selección manual con
   mando; no fabricar resultados. En alturas reducidas el contenido debe poder
   desplazarse y el foco mantenerse visible.
5. Atrás cierra la ficha y devuelve el foco a la tarjeta de origen sin reiniciar
   toda la exploración. Fallo de imagen mantiene texto identificable.

JetStream utiliza ficha cinematográfica con una acción principal y secciones
adicionales para información disponible [8](https://developer.android.com/design/ui/tv/samples/jet-stream).

### Reproductor y sus ajustes

La información del vídeo debe dejar el contenido visible. Sobre un degradado,
mostrar título, reproducción/pausa, progreso y tiempos. En directo no presentar
una barra VOD si no hay ventana de reproducción disponible. Controles de fuente,
audio y subtítulos deben estar accesibles con mando, sin exigir una tecla Menu.

**Propuesta de panel «Audio y subtítulos»:** lista de audio y lista de subtítulos,
marcar la pista activa, incluir «Automático» y «Desactivado» sólo cuando
corresponda. Explicar una pista no disponible sin ofrecer opciones falsas. El
reproductor Media3 expone pistas y parámetros de selección
[1](https://developer.android.com/reference/kotlin/androidx/media3/common/Player).

**Calidad:** hay que diferenciar las fuentes del catálogo de las pistas de vídeo
adaptativas; cambiar «fuente» no equivale necesariamente a cambiar de 1080p a
720p. Una futura opción «Automática / resolución» debe basarse en pistas reales,
soporte del flujo y pruebas de reproducción. **Velocidad:** incluirla sólo si
se implementa y valida; no se añade por figurar en una web. No prometer idiomas,
clasificaciones, tráilers o calidades que el proveedor no devuelve.

Comportamiento previsto: OK pausa/reanuda en el vídeo; izquierda/derecha buscan
en VOD cuando la superficie tiene foco y navegan cuando el foco está en un
control; botones multimedia físicos funcionan independientemente. Atrás cierra
primero la capa superior y luego vuelve a la ficha/catálogo. Google
exige soporte de controles D-pad y pausa/reanudación [1](https://developer.android.com/docs/quality-guidelines/tv-app-quality);
Amazon documenta las teclas del mando [1](https://developer.amazon.com/docs/fire-tv/remote-input.html).

### Ajustes generales y búsqueda

- **Ajustes:** pantalla de dos zonas: categorías (Conexión, Addons,
  Reproducción, Apariencia/Accesibilidad, Acerca de) y panel de opciones. Sólo
  implementar y mostrar categorías que tengan acciones reales. Mantener
  Guardar/Cancelar coherente y evitar pérdida silenciosa de cambios. JetStream
  propone configuración de dos columnas y una lista de opciones de subtítulos
  [8](https://developer.android.com/design/ui/tv/samples/jet-stream).
- **Apariencia propuesta, no implementada:** tamaño de texto / escala, zona segura
  ajustable y movimiento reducido; persistir preferencias, prever valores por
  defecto y comprobar reflujo sin recortar textos ni controles.
- **Búsqueda:** teclado de TV navegable, resultados y recientes; en anchuras o
  alturas pequeñas reorganizar zonas en lugar de comprimir controles. La
  búsqueda por voz es una mejora posterior y necesita integración real, no un
  icono decorativo [2](https://developer.android.com/develop/adaptive-apps/quality-guidelines/tv-app-quality).

## 4. Diagnóstico de este repositorio

Hallazgos de lectura de código, **sin ejecución en Android**:

| Zona | Existe | Brecha / riesgo a comprobar |
|---|---|---|
| Plataforma | `AndroidManifest.xml`: launcher Leanback, táctil no requerido, minSdk 24. | La compatibilidad real depende del dispositivo, versión, codecs y distribución. |
| Inicio | `HomeScreen.kt`: portada, categorías locales, filas, progreso y foco inicial. | Menú superior y portada fija deben probarse con desplazamiento, recarga parcial, listas vacías y retorno desde otras pantallas; categorías son filtros locales. |
| Geometría | `DeviceProfile.kt`: ancho lógico, margen horizontal 48 dp y dos anchos de tarjeta. | No usa altura ni escala de fuente para todas las decisiones; margen fijo no equivale automáticamente al 5 % en todos los tamaños; barra superior empieza a 18 dp. |
| Foco | `MediaRow.kt` y tarjetas usan `FocusRequester` y efectos propios. | No se observó `focusRestorer`; comprobar retorno al elemento y fila, también cuando el elemento desaparece. Los destinos explícitos a elementos de listas perezosas pueden quedar sin montar. |
| Ficha | `DetailsPanel.kt`: portada, acciones, episodios, selección manual y scroll. | Probar scroll/foco en altura reducida, logo fallido, selección y vuelta desde vídeo; no hay reparto ni recomendaciones de la ficha. |
| Reproductor | `PlayerScreen.kt` / `PlayerControls.kt`: Media3, teclas, fuentes, pistas y directo. | Probar estados reales, contraste y retorno de foco; verificar parada/pausa al pasar a segundo plano: no se observó un manejador explícito de ciclo de vida en `PlayerActivity`. No asumir conformidad sin prueba. |
| Ajustes | `ServerSettingsDialog.kt`: conexión, addons y actualizaciones; pestañas con foco. | Es un `AlertDialog` con controles Material generales, no una pantalla de dos zonas de TV; comprobar desplazamiento, teclado, escala de texto y restauración de foco. |
| Búsqueda | `CatalogSearchDialog.kt`: teclado propio y vista previa. | Teclado de diez teclas de 48 dp y separaciones fijas (534 dp antes de márgenes/panel): verificar distribución en anchos menores y alto limitado. |
| Rendimiento | Coil, listas perezosas, fondo estático y respaldo generado. | Medir descodificación, caché, imágenes de portada, memoria del motor Python + vídeo y rendimiento en dispositivos de baja RAM; no se ha medido. |

Google recomienda Compose para TV con componentes específicos para foco
[2](https://developer.android.com/training/tv/playback/compose). La app usa hoy
`androidx.compose.material3` y componentes propios: se puede evaluar una
migración gradual a `androidx.tv.material3`, **sin mezclar temas a ciegas** y
sin sustituir todos los componentes antes de pruebas. Para filas y listas se
pueden mantener `LazyRow`/`LazyColumn` de Foundation; valorar `focusRestorer`
con pruebas de listas que cambian y verificar la versión compatible con el BOM
existente [3](https://developer.android.com/agents/skills/tv/leanback-to-compose-tv-migration/skill).

## 5. Rendimiento y plataforma

Google recomienda dimensionar imágenes al tamaño útil y no superar la
resolución de la UI, usar listas perezosas y perfilar los dispositivos con baja
RAM [2](https://developer.android.com/training/tv/playback/memory). En un equipo
de 1 GB, su guía define un máximo de 280 MB para la suma de las categorías
indicadas allí; es un criterio de medición específico, no un número que pueda
asegurarse sin perfilado. Para esta app importa medir también el motor Python y
los buffers de vídeo. Usar imágenes ajustadas, cancelar peticiones obsoletas,
limitar transiciones simultáneas y reducir efectos costosos según resultados.
No habilitar avance automático de tráilers por defecto sin valorar accesibilidad,
red y carga.

**Fire TV no es una sola plataforma:** el APK Android está orientado a Android TV
y dispositivos Fire TV con Fire OS compatibles. Amazon indica que Vega OS no es
Android y no ejecuta APK; requiere una aplicación/paquete independiente
[2](https://developer.amazon.com/apps-and-games/blogs/2026/10/port-your-tv-app-to-react-native-with-adbt).
No debe prometerse compatibilidad con todos los Fire TV Stick. Vega requiere un
proyecto de adaptación, reproductor e integración específicos y pruebas propias.

## 6. Orden de implementación propuesto

1. **Base verificable:** configurar JDK/SDK, compilar y ejecutar las pruebas
   existentes; crear datos de demostración locales para capturas reproducibles.
2. **Sistema visual y geometría:** tokens de texto, márgenes por tamaño, foco y
   estados; probar pantallas 960×540 dp y más reducidas, con texto ampliado.
3. **Navegación:** recorrido y restauración de foco por fila, ficha, búsqueda y
   ajustes; corregir destinos hacia componentes desmontados y Atrás.
4. **Inicio y fichas:** jerarquía visual, estados de carga/error/vacío, acción
   directa de episodio y arte de respaldo; conservar los datos reales.
5. **Reproductor y ajustes:** audio/subtítulos reales, fuentes diferenciadas de
   calidad, manejo de segundo plano y controles remotos; después implementar
   las preferencias que se decidan.
6. **Validación y entrega:** perfilado y pruebas en Android TV y Fire OS reales;
   si se requiere Vega, tratarlo como adaptación independiente.

## 7. Matriz de aceptación antes de llamar al diseño terminado

- Emulador y hardware real: Android TV/Google TV y al menos un Fire OS de gama
  baja; registrar modelo, OS, resolución de UI y configuración de pantalla.
- Dos tamaños físicos y distancias de lectura; 720p/1080p de UI cuando sea
  posible, escalado de fuente y overscan activo. Ningún texto, CTA o anillo
  esencial recortado. Vega se prueba por separado si llega a implementarse.
- Sólo mando: abrir perfil, navegar menú y filas, abrir y cerrar ficha, escoger
  episodio, reproducir, pausas y saltos, pistas, fuentes, búsqueda, ajustes y
  errores. Repetir con filas vacías, red lenta, imagen rota y carga parcial.
- Restituir foco tras Atrás, reproductor y diálogos; sin foco invisible, trampas,
  selección accidental ni dependencias de ratón/pantalla táctil.
- Verificar reproducción en segundo plano, reanudación y fin de vídeo, pista no
  disponible y número de fuentes variable.
- Medir arranque, memoria, fotogramas perdidos y consumo de red en dispositivos
  representativos; documentar cifras obtenidas, no prometer objetivos sin medir.

**Estado actual:** estas comprobaciones no se han ejecutado aquí. En este
entorno no se dispone de Java/Android SDK ni de un dispositivo Android TV, por lo
que no se han compilado ni validado visualmente los cambios de Kotlin.

## 8. Revisión de los cuatro repositorios propuestos

Consulta realizada el 7 de noviembre de 2026. Se revisaron README, estructura y
archivos concretos; además se examinaron capturas de Alder y la captura de
ajustes de VortX. No se instalaron ni ejecutaron las cuatro aplicaciones.
Los enlaces fijados por commit permiten volver a los archivos revisados.

### Alder — referencia principal para los ajustes de TV

[Alder](https://github.com/aradhyas8/Alder) es un cliente Android TV en Kotlin,
orientado a Stremio; utiliza vistas Android nativas, no la misma arquitectura
Compose de Streamflix. En su [captura de ajustes](https://github.com/aradhyas8/Alder/blob/343bf8b5fe5742978183049c259186221ee1c5a8/docs/settings.jpg)
se ve una columna de categorías a la izquierda y, a la derecha, opciones con
su valor actual y una explicación. La categoría activa se distingue visualmente.
La [captura de ficha](https://github.com/aradhyas8/Alder/blob/343bf8b5fe5742978183049c259186221ee1c5a8/docs/title.png)
enseña fondo cinematográfico, logo del título, metadatos, resumen y acción
principal. Son capturas del proyecto, no pruebas de funcionamiento realizadas
por nosotros.

En [SettingsActivity.kt](https://github.com/aradhyas8/Alder/blob/343bf8b5fe5742978183049c259186221ee1c5a8/app/src/main/java/dev/alder/SettingsActivity.kt)
se observan grupos, descripción contextual, lista desplazable y manejo del foco
entre categorías y opciones. Las preferencias distinguen algunas opciones por
perfil de otras del dispositivo. **Aplicación a Streamflix:** tomar la idea de
dos zonas, valores visibles y ayuda concisa; conservar la arquitectura Kotlin +
Compose propia y definir explícitamente qué preferencias son globales o del
perfil. No asumir que las funciones de Alder ya existen en Streamflix.

### VortX — granularidad y preferencias conectadas al reproductor

[VortX](https://github.com/VortXTV/VortX) es principalmente Apple; su README
califica Android como vista previa técnica. Su
[captura de ajustes](https://github.com/VortXTV/VortX/blob/60ab24bc58fa93d4c2d1d95e98291cc4ee2c6b4d/docs/screenshots/settings.png)
pertenece a **Apple TV**, no demuestra el diseño Android. Muestra controles
agrupados de apariencia con el valor actual y explicaciones, incluidos tamaño
de texto y rendimiento.

La implementación Android sí contiene [TvSettingsScreen.kt](https://github.com/VortXTV/VortX/blob/60ab24bc58fa93d4c2d1d95e98291cc4ee2c6b4d/android/app/src/main/kotlin/com/vortx/android/ui/tv/TvSettingsScreen.kt)
y [PlayerChrome.kt](https://github.com/VortXTV/VortX/blob/60ab24bc58fa93d4c2d1d95e98291cc4ee2c6b4d/android/app/src/main/kotlin/com/vortx/android/player/PlayerChrome.kt):
el primero conecta preferencias a almacenamiento y contempla restaurar foco;
el segundo declara controles de audio, subtítulos y opciones según capacidad.
**Aplicación:** un ajuste sólo aparece cuando cambia un comportamiento real;
mostrar el valor y una explicación sencilla; separar ajustes permanentes de
opciones de la sesión de reproducción. No suponer que la implementación ni las
capacidades de VortX son equivalentes a las nuestras.

### NeawStreamTV — control remoto y TV en vivo

[NeawStreamTV](https://github.com/WilliamDuartezzz/NeawStreamTV) es un
reproductor Kotlin para listas multimedia y TV. En
[HomeScreen.kt](https://github.com/WilliamDuartezzz/NeawStreamTV/blob/ef600895fdc4eb6de831537a2b7a7e3cc62cb06c/app/src/main/java/com/neawstreamtv/tv/HomeScreen.kt)
se observan tarjetas enfocables, borde y escala; en
[PlayerScreen.kt](https://github.com/WilliamDuartezzz/NeawStreamTV/blob/ef600895fdc4eb6de831537a2b7a7e3cc62cb06c/app/src/main/java/com/neawstreamtv/tv/PlayerScreen.kt),
Media3 y manejo de teclas para canales. Es útil para revisar el flujo de directo;
no encontramos una pantalla de ajustes separada en el árbol principal consultado.
Su enfoque IPTV no equivale a un catálogo de películas y series.

### Netflix_App_UI_Clone — composición visual, no referencia de mando

[Netflix_App_UI_Clone](https://github.com/AkshayShineKrishna/Netflix_App_UI_Clone)
está hecho en Flutter/Dart. Su [inicio](https://github.com/AkshayShineKrishna/Netflix_App_UI_Clone/blob/63b9eb97a91d83d9f69784edfcece535047a7149/lib/Presentation/Home/screen_home.dart)
combina imagen principal y filas temáticas; el código revisado también usa
[navegación inferior](https://github.com/AkshayShineKrishna/Netflix_App_UI_Clone/blob/63b9eb97a91d83d9f69784edfcece535047a7149/lib/Presentation/Main_page/widgets/bottom_nav.dart)
orientada a interacción móvil. Sirve para estudiar jerarquía visual de portada y
filas, no para dar por resuelta la navegación D-pad. Su README advierte pruebas
limitadas y posibles desbordamientos de pantalla.

### Licencias y alcance

Streamflix declara EPL-2.0. GitHub identifica Alder y VortX como GPL-3.0 y
NeawStreamTV como MIT; para el clon Flutter no se identificó una licencia en la
consulta del repositorio. Las licencias, avisos de terceros, marcas y cualquier
condición de distribución deben revisarse antes de copiar código o recursos.
**Esta investigación no incorpora código, logotipos ni capturas de esos proyectos
al producto.** Las ideas generales de organización se implementarán con
componentes propios y sólo con funcionalidades realmente soportadas.


## 9. Inicio de implementación: fase 1

Se comenzó la base de navegación y legibilidad, sin dar la fase por terminada:

- El perfil calcula una zona segura inicial del 5 % de ancho y alto lógico,
  redondeada hacia arriba. Se usa para los márgenes horizontales existentes y
  el borde superior del menú y superior/inferior del buscador. Los fondos
  siguen a sangre; otras pantallas y el foco escalado requieren revisión propia.
- Las etiquetas de las tarjetas tienen mayor contraste sin depender solamente
  de que la tarjeta esté enfocada. El menú expone la selección de categorías
  también mediante semántica de accesibilidad; selección y foco siguen siendo
  estados distintos.
- El teclado no fuerza el foco hacia el panel de búsqueda cuando éste sólo
  contiene mensajes no seleccionables (p. ej., mientras se buscan resultados).
- Hay pruebas unitarias nuevas para los márgenes y los casos sin destinos en
  el panel. La compilación y las pruebas Kotlin/Compose siguen pendientes: el
  entorno no tiene Java ni SDK Android. Las 108 pruebas Python pasaron, pero no
  validan la interfaz.

Pendientes de la fase: probar el recorrido real de foco, retorno desde diálogos,
el teclado en espacios estrechos, accesibilidad, overscan y texto ampliado;
revisar también zonas seguras en el resto de pantallas. El 5 % es una referencia,
no sustituye pruebas en TV ni una calibración manual.


## 10. Inicio de implementación: fase 2

Se avanzó el inicio y el catálogo sin incorporar contenido ajeno ni prometer
fuentes nuevas:

- La portada muestra la fila y posición del destacado. En alturas lógicas más
  reducidas se limita la sinopsis y se reduce el tamaño del título para reservar
  espacio a la navegación. El cambio de posición o metadatos de una misma imagen
  no vuelve a animar el fondo.
- La acción principal coincide con su comportamiento: películas y episodios
  reproducen, una posición de reproducción disponible muestra «Continuar» y
  una serie sin episodio ofrece «Elegir episodio». La disponibilidad de una
  fuente no está garantizada y se sigue resolviendo en el reproductor.
- Si el backdrop falla, se intenta el póster; si también falla, se mantiene el
  arte generado. Los filtros indican que trabajan sobre el catálogo cargado y
  se distinguen los estados vacíos y los resultados de búsqueda.
- Las filas dan claves estables a títulos con tipo e id, con un ordinal para
  duplicados, y el índice del destacado distingue tipo, id y fila. Esto evita
  algunas colisiones; la estabilidad no sustituye una prueba de foco real.
- Se añadieron pruebas Kotlin para acciones del hero, filtros, claves de tarjetas
  e índices. No se ejecutaron ni se ha compilado la app porque faltan Java y el
  SDK Android en este entorno. Las pruebas Python pasan pero no cubren Compose.

Todavía hay que verificar visualmente alturas, foco al volver de fichas y vídeo,
listas largas, texto ampliado y rendimiento en Android TV/Fire OS. La fase 2 no
se considera validada sin estas pruebas.


## 11. Inicio de implementación: fase 3

La ficha de películas y series recibió estos cambios de código:

- La cabecera tiene altura mínima adaptable y su contenido puede crecer con el
  texto; las acciones pasan a otra línea cuando no caben. La ficha completa es
  desplazable. El arte intenta backdrop, póster y, finalmente, un respaldo
  generado; el logo usa el título textual como respaldo.
- La ficha usa la entrada y el perfil actuales y libera su ViewModel al cerrarse,
  en lugar de conservar datos viejos por id en la actividad. Esto implica que
  volver a abrirla puede volver a pedir metadatos.
- Sólo las series y el anime sin episodio resuelven una lista de capítulos.
  Mientras carga, la reproducción de esa ficha está deshabilitada; las películas
  no convierten vídeos de sus metadatos en episodios. Si hay capítulos se
  preselecciona el primero de una temporada normal, cuando existe. Cambiar de
  temporada no inicia la reproducción; OK en una tarjeta sí.
- Si no se consiguen episodios se distingue un error de carga de una lista no
  publicada, se permite reintentar cuando existe una fuente y se ofrece un
  selector manual, sin afirmar que el episodio exista. Sus botones tienen
  etiquetas de accesibilidad y se distribuyen en vertical.
- Los metadatos de imagen o texto vacíos no sustituyen datos válidos. Se añadieron
  pruebas de estado, reproducción, reintento y enriquecimiento, aún sin ejecutar.

No se ha compilado ni probado Compose o la navegación en un dispositivo: faltan
Java y el SDK Android. Es necesario comprobar visualmente desbordamientos, foco
inicial y de retorno, temporadas largas, accesibilidad y episodios de proveedores
reales antes de considerar validada esta fase.


## 12. Inicio de implementación: fase 4

Se sustituyó el cuadro de pestañas por una pantalla de dos columnas: categorías
Conexión, Addons y Actualizaciones a la izquierda, opciones y valores existentes
a la derecha. La categoría enfocada cambia el panel; derecha apunta a su primera
opción. Las filas de modo y los interruptores tienen estados de foco y selección
diferenciados. No se añadieron ajustes ficticios de apariencia o reproducción.

- Conexión y comprobación automática de actualizaciones son un borrador hasta
  pulsar Guardar. Atrás/Cerrar ofrece confirmar si hay cambios pendientes; una
  URL de addon escrita pero no instalada también cuenta como pendiente.
- Instalar, activar, desactivar y eliminar addons se aplica inmediatamente y se
  indica en la pantalla; eliminar solicita confirmación. Después de cada cambio
  se invalida el catálogo y se solicita una recarga en segundo plano.
- La comprobación manual informa de una versión encontrada sin cerrar los
  ajustes ni descartar el borrador. Para abrirla se pide confirmación si hay
  cambios pendientes. La comprobación actual no distingue todos los errores de
  red de la ausencia de un APK nuevo, por lo que la interfaz no afirma que la
  app esté actualizada cuando recibe un resultado ambiguo.
- Se añadieron pruebas unitarias de validación y guardado del borrador. Las 108
  pruebas Python pasaron y el análisis sintáctico de los archivos Kotlin de esta
  fase no reportó errores; ninguno de ellos verifica tipos, Compose ni Android.

Pendiente: compilar con JDK/SDK, ejecutar las pruebas Kotlin y probar navegación
D-pad, restauración de foco, teclado del sistema, scroll, confirmaciones y texto
ampliado en Android TV/Fire OS. La fase no está validada en dispositivos reales.


## 13. Inicio de implementación: fase 5

Se trabajó el reproductor Media3 y su navegación, sin agregar selectores de
calidad, velocidad o pistas ficticios:

- Al pasar a segundo plano se pausa el reproductor, se intenta guardar la
  posición si hay duración conocida y sólo se reanuda al volver si antes había
  intención de reproducir. Una pausa durante buffering cancela el temporizador
  de cambio de fuente; éste sólo avanza si aún se intenta reproducir y sigue
  en buffering.
- Elegir otra fuente conserva el estado de pausa; el reintento tras un fallo sí
  pide reproducir. El panel aclara que fuente no equivale a calidad. Las listas
  de fuentes y pistas empiezan en la opción activa incluso si está más abajo.
- Las pistas se basan en grupos admitidos por Media3; se rechazan selecciones
  obsoletas y el panel sólo aparece cuando hay subtítulos o más de un audio
  compatible. El estado de subtítulos se deriva de las pistas seleccionadas.
- La sesión de reproducción se identifica por carga exitosa, incluso cuando dos
  canales devuelven la misma URL. El progreso se atribuye a la petición de esa
  sesión y no se sobrescribe con una duración desconocida. Se añadieron
  etiquetas accesibles para los saltos y foco inicial en el error sin fuentes.
- Se añadieron pruebas Kotlin de políticas de segundo plano, temporizador,
  pistas y generaciones. Las pruebas Python existentes pasaron y el análisis
  sintáctico Kotlin no detectó errores; no sustituyen compilación ni reproducción.

Pendientes: ejecutar las pruebas Kotlin y verificar en dispositivos reales la
pausa y el retorno, procesos muertos, errores de fuente, HLS/DASH, subtítulos,
foco con mando, audio y rendimiento. No hay evidencia todavía de compatibilidad
con todos los flujos o aparatos.

## 14. Revisión y validación: fase 6 (parcial)

- Se ejecutaron 108 pruebas Python y 281 pruebas Node del servidor. Además, con
  Kotlin 2.1.21 se compilaron y ejecutaron 31 pruebas de lógica seleccionadas con
  sustitutos mínimos de Android, Compose, JUnit, serialización y Media3. Cuatro
  escenarios adicionales del `DetailsViewModel` se ejecutaron con corutinas y
  sustitutos de lifecycle, cliente y ajustes. Son comprobaciones aisladas, no
  sustituyen pruebas Gradle, API Android reales ni integración de la interfaz.
- Se corrigió el rótulo «Continuar» de una entrada de TV programada y se hizo
  que el perfil de dimensiones de catálogo y reproductor se recalcule con la
  configuración observada por Compose. Falta validar ambos cambios en Android.
- Con JDK 17, el wrapper no pudo descargar Gradle: `SSLHandshakeException` desde
  `services.gradle.org`. No hay SDK Android ni acceso a `dl.google.com` en este
  entorno. No se compiló un APK ni se ejecutaron lint, instrumentación, pruebas
  en emulador o en dispositivos físicos.
- El CI `.github/workflows/autonomous-checks.yml` ya contempla Gradle, lint,
  compilación e instrumentación en emulador, pero no se ejecutó para estos
  cambios. Los APK/AAB existentes en `dist/` son anteriores al rediseño.

La matriz, comandos y lista para Android TV, Fire OS y televisores de 32 pulgadas
o mayores están en [validacion-tv.md](validacion-tv.md). La fase no puede darse
por terminada hasta disponer de una compilación real y completar las pruebas de
mando, legibilidad, overscan, reproducción y rendimiento en los equipos.

## 15. Nuevas referencias: Nuvio TV y SkyStream

Se revisaron archivos representativos de catálogo, fichas, episodios, búsqueda,
ajustes, reproductor y foco de Nuvio TV y SkyStream, además de dos capturas de
pantalla grande de SkyStream. Nuvio se presenta como app Android TV basada en
Compose; SkyStream es multiplataforma y las capturas vistas son de escritorio,
no una prueba en televisor. No se ejecutó ninguna de las dos aplicaciones.

Las ideas más útiles para seguir investigando son la restauración de foco y
posición por claves, la interacción con el teclado Fire TV, las opciones de
reproductor basadas en capacidades reales y la medición de memoria de imágenes.
Estas son recomendaciones, no características añadidas ni comportamiento
validado de Streamflix. Ambas referencias declaran GPL-3.0; no se copió código,
imágenes ni marcas. Revisión detallada y enlaces fijados a commits en
[referencias-nuvio-skystream.md](referencias-nuvio-skystream.md).
