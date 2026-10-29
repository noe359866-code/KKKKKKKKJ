package com.tanasi.streamflix.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * Arte de la app: pósters 2:3, backdrops 16:9 y, sobre todo, qué se pinta
 * cuando la imagen remota no llega.
 *
 * Reglas que sigue este archivo:
 *
 *  1. **La imagen remota manda, pero siempre hay algo debajo.** El póster se
 *     dibuja *encima* de una tarjeta generada con el título; si Coil falla (sin
 *     red, host caído, 403 por hotlink) la tarjeta generada sigue ahí. Antes se
 *     usaba un `drawable` fijo por categoría y una fila entera acababa con la
 *     misma foto repetida: eso es lo que hacía que el Home pareciera roto.
 *  2. **Nada de huecos grises.** La tarjeta generada lleva degradado, iniciales
 *     y título, así que una fila sin pósters remotos sigue leyéndose.
 *  3. **El hero nunca se queda sin fondo.** Si el título no trae backdrop 16:9
 *     se usa su póster desenfocado (efecto "fondo cinematográfico").
 */

/** Póster 2:3 de una tarjeta. */
@Composable
fun PosterImage(
    entry: MediaEntry,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val url = entry.poster?.takeIf { it.isNotBlank() }
        ?: entry.backdrop?.takeIf { it.isNotBlank() }

    Box(modifier = modifier.background(StreamflixColors.PosterPlaceholder)) {
        ArtworkFallback(entry = entry, modifier = Modifier.fillMaxSize())
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = entry.title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Backdrop 16:9: miniatura de un canal/deporte, cabecera de la ficha o fondo
 * del hero.
 *
 * @param url imagen 16:9 del catálogo; si es `null` (o falla) se pinta [artKey]
 *   y, cuando se conoce el título, su tarjeta generada.
 */
@Composable
fun BackdropImage(
    url: String?,
    artKey: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    entry: MediaEntry? = null,
) {
    val remote = url?.takeIf { it.isNotBlank() }

    Box(modifier = modifier) {
        if (entry != null) {
            ArtworkFallback(entry = entry, modifier = Modifier.fillMaxSize(), landscape = true)
        } else {
            Image(
                painter = painterResource(Artwork.backdrop(artKey)),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (remote != null) {
            AsyncImage(
                model = remote,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Fondo del hero.
 *
 * Prioridad: backdrop 16:9 → póster desenfocado (rellena el ancho sin bandas)
 * → arte local. El desenfoque es un no-op en Android < 31, donde el póster
 * recortado sigue siendo mejor que un color plano.
 */
@Composable
fun HeroBackdropImage(entry: MediaEntry, modifier: Modifier = Modifier) {
    val backdrop = entry.backdrop?.takeIf { it.isNotBlank() }
    if (backdrop != null) {
        AsyncImage(
            model = backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
        return
    }

    val poster = entry.poster?.takeIf { it.isNotBlank() }
    if (poster != null) {
        Box(modifier = modifier) {
            AsyncImage(
                model = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(36.dp),
            )
            /* El desenfoque sube el brillo medio: se apaga un poco para que el
             * texto blanco del hero siga teniendo contraste. */
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f)),
            )
        }
        return
    }

    Image(
        painter = painterResource(Artwork.backdrop(entry.art)),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * Tarjeta generada: degradado determinista + iniciales + título.
 *
 * Es el "póster de emergencia" y también el fondo mientras carga la imagen
 * real. El color sale del título, así que cada fila queda variada en vez de
 * repetir el mismo dibujo.
 */
@Composable
fun ArtworkFallback(
    entry: MediaEntry,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
) {
    val palette = remember(entry.title) { fallbackPalette(entry.title) }
    val title = entry.title.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                colors = palette,
                start = androidx.compose.ui.geometry.Offset.Zero,
                end = androidx.compose.ui.geometry.Offset.Infinite,
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = initialsOf(title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
            )
            if (title != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = if (landscape) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Paletas frías/oscuras: el texto blanco siempre tiene contraste. */
private val FALLBACK_PALETTES = listOf(
    listOf(Color(0xFF3A1C71), Color(0xFF0C0A16)),
    listOf(Color(0xFF14463A), Color(0xFF08130F)),
    listOf(Color(0xFF41295A), Color(0xFF0D0A16)),
    listOf(Color(0xFF6D2437), Color(0xFF16060B)),
    listOf(Color(0xFF1B3A5C), Color(0xFF070D14)),
    listOf(Color(0xFF5C3A1B), Color(0xFF140C05)),
    listOf(Color(0xFF2C3E50), Color(0xFF080D12)),
    listOf(Color(0xFF4B1248), Color(0xFF11050E)),
)

private fun fallbackPalette(title: String): List<Color> {
    val index = (title.hashCode() and 0x7FFFFFFF) % FALLBACK_PALETTES.size
    return FALLBACK_PALETTES[index]
}

/** "Spider-Man: Un nuevo día" → "SU". Un título vacío → "▶". */
private fun initialsOf(title: String?): String {
    val words = title.orEmpty()
        .split(' ', '-', ':', '.', ',', '(', ')', '|')
        .filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2)
    if (words.isEmpty()) return "▶"
    return words.map { it.first().uppercaseChar() }.joinToString("")
}

/** Color del velo que oscurece el arte cuando hay texto encima. */
internal val ScrimBlack = Color.Black
