# Experiencia y compatibilidad para televisores

Streamflix es una aplicación para pantallas de TV y navegación con mando. La interfaz toma **ideas de navegación y jerarquía visual** de Apple TV —portada cinematográfica, perfiles y filas de contenido— sin copiar marcas, recursos ni código propietario.

## Dispositivos objetivo

| Plataforma | Alcance |
| --- | --- |
| Android TV / Google TV | Objetivo principal. La app usa Compose, Media3 y navegación D-Pad. |
| Amazon Fire TV / Fire OS | Objetivo de compatibilidad para dispositivos Fire TV basados en Android. Verificar instalación, launcher Leanback, mando y reproducción en cada generación soportada. |
| Otros TV Box Android | Compatibles cuando implementan Android API 24+ y las APIs multimedia requeridas. La memoria, el decodificador y la versión de Android pueden limitar la experiencia. |
| Apple TV (tvOS), Roku y Tizen | No son objetivos de este APK Android; requieren una app nativa independiente. |

## Principios de interfaz

- **Sólo TV:** no diseñar vistas móviles ni depender de pantalla táctil o gestos.
- **Mando primero:** foco visible en cada control, orden D-Pad coherente, foco inicial definido y `Back` predecible. Los elementos accionables deben ser suficientemente grandes y separados para seleccionarse desde el sofá.
- **Perfiles:** selector de “¿Quién está viendo?”, alta y edición accesibles con el mando, límite de perfiles visible y progreso de “Seguir viendo” aislado por perfil.
- **Ajustes:** navegación por categorías en una pantalla amplia, opciones descritas con claridad, foco que se distingue de la selección y cambios pendientes con confirmación al salir.
- **Diseño cinematográfico:** fondo oscuro, arte destacado, texto legible sobre degradados y animaciones breves que no mantengan ocupado un Fire TV Stick o TV Box de recursos limitados.
- **Sin dependencias de servicio:** los perfiles y ajustes locales no deben exigir iniciar sesión ni desplegar un servidor.

## Lista de validación antes de publicar

1. Instalar y abrir desde la fila de aplicaciones en Android TV/Google TV y Fire TV; confirmar que el banner y el icono del launcher se muestran correctamente.
2. Recorrer inicio, perfiles, ficha, ajustes y reproductor únicamente con el mando: arriba/abajo/izquierda/derecha, OK y Back.
3. Verificar foco inicial y retorno del foco después de cerrar perfiles, ajustes, diálogos y fichas.
4. Crear, cambiar y eliminar perfiles; confirmar que el progreso de reproducción no se mezcla entre ellos.
5. Revisar textos, contraste y recortes a 720p, 1080p y 4K, incluidas pantallas con densidad lógica reducida.
6. Probar en hardware de gama baja: carga de imágenes, transiciones, navegación larga y reproducción con decodificadores disponibles.
7. Probar el APK en las versiones mínimas y actuales de Android declaradas por el proyecto. Una aplicación compatible con Android TV no es automáticamente compatible con tvOS, Roku o Tizen.

La compatibilidad final depende de validar el APK en dispositivos físicos: el nombre “Fire TV” abarca generaciones y configuraciones de Fire OS distintas.
