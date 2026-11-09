# Revisión de diseño para TV Box, Android TV y Fire TV

Fecha: 8 de noviembre de 2026. Esta revisión combina lectura estática del proyecto e investigación de repositorios públicos de muestras de Android TV/Fire TV. No es una aprobación visual en hardware. El entorno no tiene `java`, así que no se pudo ejecutar ni capturar la app.

## Lo que ya está bien resuelto

- El manifest requiere Leanback y declara el táctil opcional: el producto ya se orienta al televisor, no al teléfono.
- Inicio tiene portada, carruseles horizontales, ficha y estados vacío/error; los elementos se recorren con D-pad y el foco intenta restaurarse al volver de una ficha.
- Las tarjetas mantienen proporciones de póster o 16:9, fondos de respaldo si falla una imagen y área de foco visible.
- El reproductor separa listas de fuentes y pistas; los botones tienen zonas amplias y el catálogo evita animaciones automáticas de fondo.
- `DeviceProfile` adapta margen y tamaño de tarjetas según dimensiones lógicas; Fire TV/TV Box no se tratan como una única resolución.

## Investigación aplicada al proyecto

### Muestras oficiales de Android TV

La muestra oficial de Google `android/tv-samples`, en particular JetStream Compose, es una app de streaming de referencia activa. Enseña componentes TV dedicados —`TvLazyRow`, `TvLazyColumn`, `Carousel`, `ImmersiveList`, tarjetas, chips, diálogos y pestañas— y acompaña la UI con Macrobenchmark y Baseline Profiles para medir y acelerar recorridos de arranque. [Repositorio de muestras](https://github.com/android/tv-samples) · [JetStream Compose](https://github.com/android/tv-samples/tree/main/JetStreamCompose) · [README y cobertura](https://github.com/android/tv-samples/blob/main/JetStreamCompose/README.md)

En Streamflix se usan Compose Foundation/Material3 y componentes propios; no hay dependencia TV Material ni módulo Macrobenchmark/Baseline Profile. **No recomiendo reescribir todo de golpe**: primero comparar un carrusel representativo con las APIs TV, comprobar navegación y restauración de foco, y medir antes/después. Las APIs TV deben resolver problemas observados, no reemplazar automáticamente el diseño existente.

### Muestra pública de Amazon Fire TV

El repositorio de Amazon `firetv-sample-touch-app` documenta ejecución en Fire TV desde Fire OS 5+ y Android TV emulador, con navegación D-pad. Es una muestra de 2021, Android Studio 4.1.1, y también incluye interacción táctil; sirve como referencia histórica mínima para el mando, no como arquitectura actual ni como requisito para Streamflix. Por decisión de producto, se debe conservar la experiencia sólo D-pad y probar en hardware Fire TV real. [Muestra de Amazon](https://github.com/amzn/firetv-sample-touch-app)

## Mejoras prioritarias

### P0 — Validar 480p/720p y altura útil antes de ajustar más

Home reserva 78 % de la altura para el backdrop y coloca el final del contenido del hero al 62 %. En pantallas lógicas de poca altura, título + sinopsis + botones + barra superior podrían apretarse o cruzarse. El modo compacto actual cambia tamaños y espacios, pero no elimina contenido. Hay que capturar 960×540 dp, 640×360 dp y una configuración realmente reportada por Fire TV, y luego añadir una política de hero compacto que priorice título, acción y primera fila sin solapamientos.

### P1 — Probar Compose for TV en una rebanada vertical

Crear una rama de prueba o feature flag con una única fila de catálogo usando `TvLazyRow`/componentes TV compatibles. Comparar: foco inicial, flechas al límite, desplazamiento acelerado, foco restaurado al volver de ficha, animación de foco y consumo en dispositivo lento. Si la API lucha con la lógica actual de `FocusRequester` o `dpadRepeatThrottle`, no migrar esa fila. No actualizar todas las listas, cards y botones en un mismo cambio.

### P1 — Distancia de lectura y legibilidad

- Revisar `labelSmall` (11 sp), ayudas secundarias, badges y metadatos desde una distancia real de sofá; que la información técnica no sea necesaria para entender la acción.
- Probar ampliación de fuente del sistema: los `sp` la respetan, pero títulos de una línea, top bar y tarjetas pueden truncarse.
- Medir contraste de texto sobre arte claro, foco blanco, imagen de póster y placeholder; comprobar color y escala de grises.
- Revisar si calidad, proveedor y rating compiten por las esquinas de una tarjeta pequeña. El título y la acción deben ganar a los badges.

### P1 — Safe area y overscan real

`DeviceProfile` usa un margen inicial del 5 % por lado. Es un buen punto de partida, no detección de overscan. No aumentar ni reducir el margen a ojo: capturar bordes en televisor y Fire TV, probar el ajuste “fit to screen” y registrar la configuración. El banner de launcher también debe revisarse en el carrusel de apps de Android TV y Fire TV, no sólo abrirse como PNG.

### P1 — Rendimiento con hardware de entrada

Usar Macrobenchmark para medir arranque en frío, llegada al primer foco, desplazamiento de carrusel y retorno desde una ficha; complementar con scroll sostenido y foco rápido sobre listas largas, imágenes grandes y conexiones lentas en Fire TV Stick/TV Box de gama baja. Medir jank y memoria; el hero ya espera antes de pedir metadatos, pero conviene verificar que la transición de artwork no mantiene imágenes antiguas y que Coil libera memoria al cambiar de catálogo. Si los recorridos son estables, generar Baseline Profile para el camino crítico y confirmar mejora en release. No ajustar cachés/transiciones sin medición.

### P1 — Consistencia de mando y accesibilidad

Validar el grafo de foco en cada pantalla: izquierda/derecha al borde de carrusel, arriba/abajo entre filas, retorno desde ficha, diálogo y reproductor, y foco tras resultados tardíos. Probar los mandos Bluetooth de Google TV y los de Fire TV: Back, Center, teclas multimedia, teclado físico y búsqueda por voz pueden variar. Probar también TalkBack/servicio de accesibilidad y escala de texto sin perder el indicador visual de foco.

### P2 — Identidad visual propia

El código comentaba muchas decisiones como “Apple TV/tvOS”. Son referencias de análisis, no una necesidad de producto. Ya se renombró la tipografía como sistema visual propio de Streamflix TV; queda documentar reglas de color, radios, escala, motion, foco y estados para que Ajustes, búsqueda, perfiles y reproductor se vean coherentes.

### P2 — Quitar ramas de teléfono de un producto que no lo soporta

En pantallas como `MediaCards`, `HomeScreen`, `HeroBanner` y `DetailsPanel` siguen existiendo comentarios y bifurcaciones “móvil”. El manifest fuerza TV y `DeviceProfile` devuelve siempre foco D-pad, por lo que algunas ramas son inalcanzables. Se limpió la tarjeta de medios; eliminar el resto de código muerto en una pasada específica reduciría mantenimiento y evitaría que un cambio futuro reactive accidentalmente una UI no probada para teléfono.

## No cambiar sin evidencia de dispositivo

- No cambiar el margen del 5 %, tamaño de póster, duración de transición o colores sólo por parecer más modernos.
- No añadir un menú lateral o nuevas pestañas sólo porque otras apps lo usan; comparar trayectorias de mando y cantidad visible de catálogo.
- No afirmar compatibilidad completa con “Fire TV” en general. Verificar versiones concretas de Fire OS, launcher, ABI, teclados y hardware de reproducción.
- No migrar a TV Material en bloque. Hacer primero un prototipo medible y comprobar si aporta algo sobre el control propio actual.

## Matriz visual y funcional de salida

Tomar capturas de Inicio, cada categoría, búsqueda y teclado, ficha larga, ajustes, selector de addons, reproductor con controles, error de fuente y estado sin catálogo. Para cada imagen: dispositivo/modelo, versión de Android/Fire OS, resolución, `screenWidthDp × screenHeightDp`, densidad, escalado de fuente, overscan/fit-to-screen, distancia de observación y recorrido de mando. La revisión se considera lista sólo tras pasar esa matriz con Android TV y Fire TV físicos. Guardar resultados de Macrobenchmark en el mismo registro.

## Referencia investigada: Apple TV en pantallas de sala

Hay que separar **la app Apple TV** (servicio de streaming) del **Home de Apple TV 4K/tvOS** (launcher del dispositivo). Para Streamflix, la referencia útil es la app de streaming en Android TV/Fire TV; copiar el launcher o el aspecto de tvOS exactamente no aplica.

### Lo que Apple documenta para TV y streaming devices

- En Smart TVs y dispositivos de streaming, Apple describe un `Home` cuando está disponible, una fila `Continue Watching` que reúne Watchlist y reproducción incompleta, el área `Apple TV` para Originals y `Search` para encontrar títulos por nombre, reparto o equipo. Apple indica que prestaciones y compras cambian según dispositivo y región. [Guía oficial de Smart TVs, Android TV y Fire TV](https://support.apple.com/guide/tvplus/smart-tvs-and-streaming-devices-apda10aaed81/1.0/web/1.0)
- En su guía para Apple TV 4K, Apple organiza la app con una barra lateral: Search, Home, Apple TV, deportes, Store, Library y canales/apps según disponibilidad. El mando la abre con Back, Up o Left y se cierra con Right o al elegir sección. Esto demuestra un patrón de navegación TV-first, pero **no prueba que todas las versiones de Apple TV para Android TV y Fire TV tengan exactamente la misma barra lateral**. [Navegación oficial del Apple TV app](https://support.apple.com/guide/tvapp/navigate-the-apple-tv-app-atvb5e608b5e/web)
- El patrón `Up Next` / `Continue Watching` resuelve volver al contenido pausado y al siguiente episodio; las filas editoriales agrupan recomendaciones y categorías. La documentación recomienda presentar primero la acción disponible (Play/Open/Subscribe) en el detalle. [Watch Now y Up Next](https://support.apple.com/en-gb/guide/tv/atvbe160da08/15.0/tvos/15.0)
- En Android TV/Fire TV Apple limita algunas funciones: su guía advierte que no se puede comprar contenido desde la app en esos dispositivos (y que compras/rentas deben hacerse en otro dispositivo compatible); país/región también cambia opciones. Por tanto, no copiar botones `Buy/Rent/Store` como si fueran acciones universales. [Disponibilidad y configuración](https://support.apple.com/en-us/102472)

### Observación de la variante Android TV

Una grabación pública de la app en Android TV (no documentación de Apple) muestra una variante con navegación superior `Műsoron`/On Air, `tv+`, `Library` y búsqueda; el hero usa arte ancho, título, género/duración y CTA, seguido por filas editoriales. Es evidencia visual secundaria y de una versión/localización concreta, no especificación estable. [Video de interfaz Android TV](https://manuals.plus/video/720576080fedc77e24353fe08eb403b89925cabab0cfe9e44cc61b2ab64796a8)

### Principios visuales transferibles a Streamflix

1. **Primero continuar, luego descubrir:** poner “Seguir viendo” antes de filas menos personalizadas; progreso y episodio deben reconocerse sin abrir una ficha.
2. **Un solo hero con jerarquía clara:** artwork grande, velo que sostenga contraste, título, una línea corta de metadatos/descripción y una acción primaria. El hero debe ceder altura en pantallas bajas.
3. **Navegación estable y predecible:** pocas secciones; se puede comparar la barra superior actual con un drawer/sidebar que se abra con LEFT/BACK, pero mantener explícito dónde caerá el foco al abrir/cerrar y no ocultar funciones esenciales detrás de gestos.
4. **Filas editoriales con propósito:** títulos de fila legibles, carrusel de foco horizontal, estados cargando/error/vacío que no muevan la geometría. Evitar filas de recomendaciones redundantes.
5. **Arte antes que cromado:** fondo oscuro y overlays discretos; no hacer que vidrio/transparencia reduzca contraste del texto. El foco se expresa con escala/elevación moderadas y un borde inequívoco, sin auto-reproducción de trailers en dispositivos lentos.
6. **Detalle orientado a decidir:** sinopsis breve, metadatos, temporadas/episodios claros y una acción destacada. En Streamflix la acción debe corresponder a una fuente realmente disponible; no simular integración entre servicios que no existe.

### Diferencias que Streamflix no debe imitar

Apple TV puede mostrar catálogo unificado, alquileres/compras, canales, deportes y apps integradas según plataforma y región. Streamflix no debe presentar esas pestañas ni prometer que un título se reproduce en otro servicio si el catálogo o integración real no lo soporta. El estilo visual se puede estudiar; nombres, marca, iconos, tipografía propia y elementos exactos de interfaz de Apple no deben copiarse.

### Estado del prototipo Streamflix

Se reemplazó la navegación superior de categorías por un botón compacto de sección y un sidebar modal D-Pad con Inicio, Películas, Series, Buscar, perfil y Ajustes. Back lo abre desde Home, LEFT en la primera tarjeta de cualquier fila da acceso directo al menú y RIGHT/Back lo cierran; la restauración explícita del foco aún debe verificarse. El hero y las filas se conservaron; no se añadieron compras, deportes ni otras secciones que el backend no soporta. Es un primer prototipo inspirado en patrones documentados de Apple TV, no una afirmación de paridad ni una validación final.

En la siguiente ronda se debe probar en Android TV 1080p y Fire TV 720p: llegar al menú, cambiar de sección, abrir búsqueda/ajustes, volver con Back, confirmar restauración del foco y contar pulsaciones hasta reanudar. Revisar especialmente el alto de la lista en 360 dp y la zona segura del panel. Si la posición de foco tras cerrar el menú no se siente natural, corregirla antes de añadir más motion o adornos.

## Segunda revisión — navegación y gestión QR

Fecha: 8 de noviembre de 2026. Lectura estática posterior al prototipo del sidebar y al nuevo diálogo «Gestionar desde el teléfono · QR». No se dispuso de Java ni de un Android TV/Fire TV para capturas, por lo que los puntos siguientes son inspección del código, no validación visual.

### Ajuste aplicado en esta revisión

- El diálogo QR tenía una imagen fija de 270 dp dentro de una columna centrada y sin scroll. En alturas lógicas pequeñas podía recortar la dirección, las instrucciones o el botón de cierre. La imagen ahora escala con la altura disponible y el contenido del diálogo puede desplazarse con D-pad. Sigue pendiente verificar que el QR conserve tamaño suficiente para escanearlo en la pantalla real.

### Hallazgos aún abiertos

1. **P1 — Hero a 360 dp.** Sigue vigente el riesgo de que sinopsis y acciones compitan por altura en 640×360 dp/480p lógico. El umbral compacto modifica tipografías/espacios, pero no elimina bloques. Revisar el resultado en una captura real antes de decidir una jerarquía compacta.
2. **P1 — Confirmación QR larga.** La propuesta en TV permite scroll vertical en el detalle; probar una lista de varios addons/repositorios y URL largas para asegurar que aprobar/rechazar conserve foco y que el usuario vea qué elemento está aprobando.
3. **P2 — Entrada al QR desde Ajustes.** El botón aparece en la cabecera junto a «Ajustes» y es alcanzable por D-pad; comprobar el recorrido desde cada pestaña y que al cerrar QR el foco vuelve a un control visible. La sesión no debe dejar un diálogo QR vacío al expirar.
4. **P2 — Legibilidad del teléfono vs TV.** La página móvil es una interfaz compañera deliberadamente táctil; no debe confundirse con la app de TV. En el televisor, el QR, la URL, la acción de cierre y la aprobación deben seguir siendo operables sólo con mando. El texto advierte que Nuvio se ejecuta en QuickJS y Cloudstream ejecuta DEX de terceros sin sandbox, con aprobación explícita.

**Cerrado en código, pendiente de probar en dispositivo — Foco al cerrar el sidebar.** El menú devuelve el foco al botón de sección al cerrarse con Right/Back y tras cambiar de categoría, evitando reenfocar una tarjeta que pudo desaparecer. Las acciones de Buscar, Perfil y Ajustes no fuerzan ese retorno, porque abren otra superficie. Verificarlo en Android TV y Fire TV; al cambiar categoría, comprobar también la navegación descendente desde el botón de sección.

### Estado de validación

La revisión visual de salida sigue bloqueada por falta de runtime Java y de dispositivos. En cuanto haya un entorno, priorizar Android TV 1080p y Fire TV de baja altura lógica: abrir/cerrar sidebar desde los tres orígenes, cambiar categoría y confirmar retorno del foco; abrir QR, escanearlo, proponer tanto un cambio simple como varios, aprobar/rechazar, y repetir con fuente de sistema ampliada y sesión expirada. No declarar el diseño validado hasta registrar capturas y esos recorridos.

## Fila «Plataformas» inspirada en Ghostflix

La fila se integra en Inicio como tarjetas horizontales de palabra/monograma y nombre, manejables con D-pad. Se construye a partir de los manifests de addons Stremio habilitados: sólo aparecen los servicios para los que realmente se declara un catálogo compatible. Al seleccionar uno, Streamflix consulta ese catálogo bajo demanda, muestra sus filas de películas/series/anime y Back vuelve a la misma plataforma. Las filas por servicio se retiran del Home general para no duplicar títulos. Se usan tiles locales tipográficos, no se copian ni se enlazan los logos de Ghostflix.

Límite de datos: la cobertura depende de los addons instalados y de sus IDs de catálogo; no hay una lista global de disponibilidad ni garantía de que cada plataforma tenga contenido. La fila no se renderiza si no hay manifests compatibles. Falta compilar y probar los recorridos D-pad en hardware, además de contrastar cuántos catálogos reales declara Streaming Catalogs.

## Jerarquía de Ajustes para TV

Ajustes se organiza como un hub de cuatro secciones, priorizadas por frecuencia e impacto:

1. **Conexión** — decisión principal: motor local o servidor externo; la URL sólo aparece cuando se elige servidor. La opción autónoma queda como recomendada/primera, con Cinemeta subordinado a ella.
2. **Stremio** — integración funcional: añadir un addon, revisar los instalados y, al final, explorar sugerencias. La navegación muestra el total instalado.
3. **Índices y extensiones** — Nuvio/Cloudstream quedan separados de Stremio. Providers Nuvio se instalan/activan para ejecución QuickJS; `.cs3` se descarga, valida y ejecuta con API Cloudstream v4.7.0 tras consentimiento explícito.
4. **Actualizaciones** — versión actual, preferencia de comprobación y búsqueda manual; sección de mantenimiento, por eso al final.

La cabecera identifica «Ajustes» y deja QR como acción secundaria. La rail muestra título, contexto y conteos; el panel de contenido tiene encabezado y subtítulo propios, contenido desplazable y un pie persistente con estado y acciones «Guardar cambios»/«Cerrar». En D-pad el foco inicial cae en Conexión, RIGHT entra en el control principal de la sección y UP desde la primera sección da acceso al QR. No se cambió la semántica de persistencia: los addons/índices aplican al momento; las preferencias generales se guardan desde el pie.

Validación pendiente: la compilación Android y la prueba visual en TV continúan bloqueadas por ausencia de Java/dispositivo. Confirmar especialmente que rail, panel y pie no se aprieten en 720p/altura lógica reducida y que el recorrido foco → QR → Conexión sea natural.
