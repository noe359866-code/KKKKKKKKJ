package com.tanasi.streamflix.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.tanasi.streamflix.data.model.MediaEntry
import com.tanasi.streamflix.ui.theme.StreamflixColors

/**
 * Póster 2:3 de una tarjeta.
 *
 * Tres niveles de respaldo, en orden: la URL del catálogo → el arte local de la
 * clave `art` → un color plano. Sin esto, un CDN de imágenes bloqueado deja el
 * Home en gris y da la impresión de que la app no carga.
 */
@Composable
fun PosterImage(
    entry: MediaEntry,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    AsyncImage(
        model = entry.poster,
        contentDescription = entry.title,
        contentScale = contentScale,
        placeholder = ColorPainter(StreamflixColors.PosterPlaceholder),
        error = painterResource(Artwork.posterFallback(entry.art)),
        fallback = painterResource(Artwork.posterFallback(entry.art)),
        modifier = modifier,
    )
}

/**
 * Backdrop 16:9: miniatura de un canal/deporte o fondo del hero.
 *
 * @param url imagen 16:9 del catálogo; si es `null` (o falla) se pinta [artKey].
 */
@Composable
fun BackdropImage(
    url: String?,
    artKey: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val fallback = painterResource(Artwork.backdrop(artKey))
    if (url.isNullOrBlank()) {
        Image(
            painter = fallback,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
        )
        return
    }

    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = contentScale,
        placeholder = ColorPainter(StreamflixColors.PosterPlaceholder),
        error = fallback,
        fallback = fallback,
        modifier = modifier,
    )
}

/** Color del velo que oscurece el arte cuando hay texto encima. */
internal val ScrimBlack = Color.Black
