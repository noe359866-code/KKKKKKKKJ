# Diseño y navegación para TV

La pantalla de inicio usa una portada con imagen, metadatos y acciones, seguida de
filas horizontales. Se estudiaron las páginas de referencia como inspiración de
organización, no como fuentes de contenido ni como diseños para copiar:

- Cinejoy: destacado con reproducción, exploración por proveedor y filas de
  películas y series en tendencia.
- Willow: portada con imagen y descripción, acciones de reproducción e
  información y una navegación superior por tipo de contenido.
- CinemaCity: destacados con reproducción y ficha, metadatos breves y filas de
  tendencias que mezclan películas y series.

En Streamflix, Inicio, Películas, Series (incluye anime) y En vivo filtran las
filas **ya cargadas**. No son catálogos completos ni cambian los proveedores.
Una sección sin títulos muestra un estado vacío. Una búsqueda vuelve a Inicio;
Inicio permite salir de sus resultados. Seguir viendo es del perfil y no se
mezcla con resultados de búsqueda. Los canales y eventos programados se agrupan
por tipo `tv`, aunque todavía no estén en emisión.

El mando utiliza el foco nativo de Compose: izquierda/derecha recorre filas y
menú, arriba desde cualquier tarjeta de la primera fila va a la portada, y
abajo desde la portada llega a la primera tarjeta. El anillo blanco se dibuja
encima del contenido. La portada no se anima constantemente, para reducir el
trabajo gráfico en equipos modestos; las imágenes fallidas tienen un respaldo
generado con el título. La ficha se desplaza verticalmente para que episodios
y selección manual sigan accesibles en pantallas bajas; las tarjetas de episodio
conservan su respaldo si falla la miniatura. El reproductor reduce el tamaño y
la separación de sus controles en paneles estrechos, y distingue las teclas
multimedia independientes de reproducir y pausar. No se han añadido imágenes ni
catálogos de las webs.

## Comprobación en un dispositivo real

1. Abrir en Android TV o Fire TV, comprobar márgenes de pantalla y legibilidad.
2. Moverse con el D-pad por el menú, la portada y todas las filas; probar Atrás,
   búsqueda, cambio de perfil, ficha y reproducción.
3. Cambiar de categoría después de desplazarse y probar una sección vacía.
4. Comprobar Seguir viendo con un episodio, incluyendo su posición de reanudación.
5. Con la ficha abierta en una pantalla baja, bajar con el mando hasta las
   temporadas, episodios y selector manual y volver a subir.
6. Probar play y pausa multimedia independientes, con controles visibles y
   ocultos; confirmar que pulsar dos veces pausa no reanuda la reproducción.
7. Probar red lenta y URLs de imagen fallidas. Verificar consumo y fluidez en un
   Fire TV Stick de gama baja. Estas pruebas requieren dispositivo o emulador.

## Análisis ampliado de las referencias

Se consultó el contenido de las siguientes rutas además de las portadas:

- [Ficha de película de Cinejoy](https://cinejoy.pk/movie/1263337-heart-of-the-beast-2026):
  imagen de fondo, logo del título cuando existe, géneros, reproducción, año,
  duración, clasificación, nota, sinopsis, reparto, tráileres y recomendaciones.
- [Ficha de serie de Cinejoy](https://cinejoy.pk/series/225385-black-doves-2024):
  añade temporadas y episodios con miniatura, duración y descripción. Cada
  episodio enlaza directamente a su reproducción.
- [Películas de Willow](https://willowmovies.com/movies) y
  [series de Willow](https://willowmovies.com/tv-shows): páginas separadas por
  tipo, con filas temáticas, póster, año y género. Su
  [lista](https://willowmovies.com/watchlist) tiene un estado vacío explícito.
- [Película](https://cinemacity.cc/movies/3172-heart-of-the-beast-2026.html) y
  [serie](https://cinemacity.cc/tv-series/3614-tulsa-king-20222025.html) de
  CinemaCity: ficha con sinopsis y datos, más un área de reproducción cuyos
  controles expuestos incluyen calidad, audio, subtítulos y velocidad.

La consulta disponible devolvió el contenido textual de las páginas, no una
sesión de navegador interactiva: las rutas de reproducción de Cinejoy y Willow
quedaron en «Probing Server» y «Loading streaming options». No se pudieron probar
visualmente sus ajustes emergentes, comportamiento con mando ni reproducción.
Tampoco se han añadido reparto, tráileres, recomendaciones o velocidad como
funciones ficticias: dependen de datos y soporte que esta app aún no ofrece.

Traducción al diseño nativo de TV: usar el logo sólo si el proveedor lo entrega
(y texto si falla), mantener reproducción e información legibles, abrir el
capítulo directamente desde su tarjeta y diferenciar foco y pestaña activa en
Ajustes. Los controles de fuente, audio y subtítulos existentes siguen usando
las opciones reales del reproductor, no las anunciadas por las webs.
