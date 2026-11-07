# Lote 2: foco, navegación y accesibilidad

Se abrieron completos mediante fetch 25 archivos más: 15 de [Nuvio TV, commit `fe5c314`](https://github.com/NuvioMedia/NuvioTV/tree/fe5c314bdca956696155f2b649de7070c2d03ba4) y 10 de [SkyStream, commit `f5523ae`](https://github.com/akashdh11/skystream/tree/f5523ae07d729b579be30976df8783a0b98c4559). Las rutas completas y hashes están en los inventarios [Nuvio](nuvio.tsv) y [SkyStream](sky.tsv). No se ejecutaron sus pruebas: el contenido de una prueba demuestra lo que pretende comprobar, no que haya pasado en este entorno.

## Nuvio TV: 15 archivos

1. `app/src/main/java/com/nuvio/tv/ui/util/DpadThrottleModifier.kt`: limita eventos repetidos del D-pad y deja pasar la pulsación inicial. Aplicación: medir la navegación mantenida en hardware lento antes de introducir regulación; consumir repeticiones puede cambiar el comportamiento esperado del mando.
2. `app/src/main/java/com/nuvio/tv/ui/util/DpadFastScrollModifier.kt`: separa pulsaciones individuales y desplazamiento vertical rápido; detiene el desplazamiento al soltar, alcanzar un borde o agotar un temporizador si se pierde la liberación. Usa límites por fotograma y resuelve un destino de foco al terminar. Streamflix no debe adoptar este mecanismo sin pruebas: durante ese desplazamiento la referencia congela el foco y puede ocultar su decoración.
3. `app/src/main/java/com/nuvio/tv/ui/util/DrawerFocusRequesters.kt`: conserva solicitantes de foco por ruta y retira rutas que dejan de existir.
4. `app/src/test/java/com/nuvio/tv/ui/util/DrawerFocusRequesterStateTest.kt`: verifica que insertar o quitar rutas no cambie la identidad de las restantes. Para Streamflix: probar una categoría que desaparece y volver a una opción válida.
5. `app/src/main/java/com/nuvio/tv/ui/theme/FocusRingStyle.kt`: centraliza el estilo del borde y admite color o degradado con opacidad ajustable.
6. `app/src/test/java/com/nuvio/tv/ui/theme/FocusRingStyleTest.kt`: contrasta estilos para varios temas. No valida contraste o legibilidad sobre imágenes reales; eso requiere inspección visual.
7. `app/src/main/java/com/nuvio/tv/ui/theme/MotionFocusTokens.kt`: agrupa escalas, duraciones y valores de movimiento. Para Streamflix: considerar coherencia de animaciones y movimiento reducido, sin asumir que valores concretos de la otra app son óptimos.
8. `app/src/androidTest/java/com/nuvio/tv/ui/screens/settings/SettingsAccessibilitySemanticsTest.kt`: comprueba que pestañas de ajustes expongan nombre, rol, estado seleccionado y una sola acción. En Streamflix hay semántica de selección y controles radio/switch; aún hay que comprobar el árbol efectivo, roles y anuncios con Compose/TalkBack.
9. `app/src/main/java/com/nuvio/tv/ui/navigation/SourceSelectionRestorePolicy.kt`: limita el índice de restauración a la lista de fuentes actual y devuelve ausencia de destino si está vacía.
10. `app/src/test/java/com/nuvio/tv/ui/navigation/SourceSelectionRestorePolicyTest.kt`: cubre lista vacía, índices fuera de rango y condiciones de retorno. Streamflix debe probar que una fuente desaparecida no deje foco inválido; guardar por identidad puede ser más seguro que por índice si cambia el orden.
11. `app/src/main/java/com/nuvio/tv/ui/components/FocusMarqueeText.kt`: desplaza un rótulo largo enfocado un número finito de veces y considera dirección del texto.
12. `app/src/test/java/com/nuvio/tv/ui/components/FocusMarqueeTextTest.kt`: limita las iteraciones para evitar animaciones indefinidas. No prueba por sí mismo consumo energético o facilidad de lectura.
13. `app/src/main/java/com/nuvio/tv/ui/components/FocusScrollingText.kt`: revela descripciones largas tras una espera, con desplazamiento lento y ciclos limitados sin cambiar el tamaño de la tarjeta.
14. `app/src/test/java/com/nuvio/tv/ui/components/FocusScrollingTextTest.kt`: prueba desbordamiento y duración, incluidos distancia nula y velocidad cero. Para Streamflix: primero evaluar si el texto truncado necesita una acción accesible de expansión; no iniciar animaciones automáticas sin valorar movimiento reducido y rendimiento.
15. `app/src/test/java/com/nuvio/tv/ui/screens/detail/EpisodeOverlayNavigationTest.kt`: describe pruebas de límites, episodio ausente y orden reflejado en RTL. Comprobar en Streamflix episodios inicial/final, datos que cambian y dirección de idioma, antes de introducir controles equivalentes.

## SkyStream: 10 archivos

1. `lib/shared/focus/android_text_editing.dart`: notifica al host Android cuando un campo Flutter editable gana o pierde el foco, sólo cuando el dispositivo indica necesitarlo.
2. `android/app/src/main/kotlin/dev/akash/cloudstream/TextEditorProxy.kt`: código nativo de apoyo para una limitación descrita en la integración **Flutter/IME**; sólo se activa bajo condiciones concretas del dispositivo. No es evidencia de que Compose tenga el mismo problema y no se debe trasladar el proxy a Streamflix.
3. `android/app/src/test/kotlin/dev/akash/cloudstream/TextEditorProxyTest.kt`: pruebas Robolectric de foco, conexión de entrada, ciclo de vida y exclusión de accesibilidad del proxy.
4. `test/shared/focus/android_text_editing_test.dart`: prueba notificaciones de edición y ausencia de ellas en campos de sólo lectura u otras plataformas. Son pruebas de la integración Flutter específica.
5. `lib/shared/focus/text_field_keys.dart`: cuando el teclado Android está visible, deja las flechas al IME; con él cerrado, permite salir del campo con arriba/abajo y reabrirlo con Seleccionar. Su detección tiene matices para teclados flotantes.
6. `test/features/nuvio/nuvio_scraper_settings_dialog_focus_test.dart`: describe una prueba de salida del campo hacia Guardar con teclado oculto. Aplicación: validar campos de servidor y addon de Streamflix con los IME reales de Android TV y Fire OS.
7. `lib/shared/widgets/focusable_item.dart`: diferencia foco de hover, centra horizontalmente el elemento y sólo ajusta el scroll vertical si está recortado. La referencia evita escalar el foco direccional en este componente; es una decisión visual, no una regla universal.
8. `lib/shared/focus/app_focus.dart`: centraliza el indicador de foco; lo muestra desde el primer fotograma en TV, distingue entrada con puntero y mando y adapta el color al tema. Para Streamflix, de uso TV, la prioridad es foco visible en cada transición y suficiente contraste, no necesariamente reproducir la lógica multiplataforma.
9. `test/shared/focus/app_focus_test.dart`: describe pruebas de visibilidad inicial, cambios de método de entrada y teclas que no deberían activar el anillo. No constituye una prueba de Streamflix ni de un mando físico.
10. `test/features/details/episode_picker_focus_test.dart`: prueba que cada episodio sea una sola parada de foco y que Cerrar siga alcanzable. Es una buena prueba de regresión para filas compuestas: un contenedor y su hijo no deberían crear dos paradas visualmente idénticas.

## Consecuencias concretas para Streamflix

- **Búsqueda:** el diálogo actual tiene un teclado D-pad propio; no es el teclado del sistema. Deben probarse sus teclas, entrada física, resultados y Back. Los campos `OutlinedTextField` de conexión/addons sí requieren comprobar el IME del sistema, sin asumir que el problema específico de Flutter se repite en Compose.
- **Ajustes:** comprobar con pruebas Compose y TalkBack la etiqueta, acción única, rol y selección de categorías; en opciones de radio y switches comprobar su estado y que no haya nodos duplicados. Actualmente se aplican `selected`, `selectable` y `toggleable`, pero su resultado efectivo no se ha medido en un dispositivo.
- **Foco:** probar primera carga, vuelta de diálogo/ficha/player, elemento eliminado, filas irregulares, listas largas, pulsación mantenida y escala de fuente grande. Si se agrega restauración explícita, emplear claves estables y un destino alternativo sólo cuando exista.
- **Rendimiento:** medir el efecto de animaciones, halo y desplazamientos prolongados en Fire TV antes de añadir scroll rápido o texto animado. No se han incorporado esos mecanismos a Streamflix.

Estas conclusiones son de revisión estática; no se ejecutaron pruebas Android, Flutter ni dispositivos reales en este lote. Se usan sólo como referencias; no se copió código de ambos repositorios GPL-3.0.
