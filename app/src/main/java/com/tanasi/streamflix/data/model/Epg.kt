package com.tanasi.streamflix.data.model

import kotlinx.serialization.Serializable

/**
 * Una emisión de la guía de un canal: qué se ve y a qué hora.
 *
 * Viaja **dentro** de la parrilla (`PlaybackChannel.epg`), no en una petición
 * aparte: al zapear no se puede pedir la programación de cada canal (sería una
 * petición por pulsación de `CH+` y el rótulo aparecería vacío la primera vez).
 *
 * @param title lo que se emite ("Lima vs Alianza", o el nombre del programa).
 * @param start hora de comienzo tal como la publica la fuente ("19:00"). Si la
 *   fuente no da hora, queda `null` y la guía no inventa una.
 * @param subtitle competición o canal de origen ("Liga 1", "ESPN").
 * @param live `true` si esa emisión **es** la que está al aire ahora.
 */
@Serializable
data class EpgEntry(
    val title: String,
    val start: String? = null,
    val subtitle: String? = null,
    val live: Boolean = false,
)

/**
 * Reglas de la guía de programación.
 *
 * Estado real de los datos: **no hay EPG de parrilla**. Ninguno de los
 * proveedores de TV del proyecto publica rejilla horaria (ni XMLTV ni una API de
 * programas) y desde aquí no se puede verificar un sitio nuevo. Lo que sí existe
 * es la **agenda deportiva**: cada partido trae hora, competición y si está en
 * vivo, y esos partidos son canales zapeables. Por eso la guía de un canal
 * deportivo enseña su emisión —«AHORA · 19:00 · Liga 1»— y la de un canal
 * general (ESPN de un índice IPTV, por ejemplo) no enseña nada: preferimos una
 * guía vacía a una inventada.
 *
 * Cuando algún proveedor publique horarios, basta con llenar `epg` en el
 * catálogo: el transporte, el recorte y el dibujo ya están hechos.
 */
object Epg {

    /** Tope de emisiones por canal que viaja al reproductor. */
    const val MAX_ENTRIES = 8

    /**
     * La programación que el catálogo conoce de un título.
     *
     * Hoy sólo los partidos de la agenda: un partido es **una** emisión, con su
     * hora y su competición. Un canal sin agenda conocida devuelve lista vacía
     * (y la guía lo dice callándose).
     */
    fun from(entry: MediaEntry): List<EpgEntry> {
        if (!entry.isMatch && entry.startTime.isNullOrBlank()) return emptyList()

        val title = if (entry.isMatch) {
            listOfNotNull(entry.homeTeam, entry.awayTeam).joinToString(" vs ")
        } else {
            entry.title
        }
        if (title.isBlank()) return emptyList()

        return listOf(
            EpgEntry(
                title = title,
                start = entry.startTime?.takeIf { it.isNotBlank() },
                subtitle = entry.league?.takeIf { it.isNotBlank() },
                live = entry.isLive,
            ),
        ).take(MAX_ENTRIES)
    }

    /** La emisión que está al aire; si ninguna lo declara, la primera. */
    fun now(entries: List<EpgEntry>): EpgEntry? = entries.firstOrNull { it.live } ?: entries.firstOrNull()

    /**
     * Línea de la guía para el rótulo y la tarjeta: `"AHORA · 19:00 · Liga 1"`.
     *
     * `null` cuando no hay nada que contar: es lo que impide que la guía enseñe
     * un hueco con un guion.
     */
    fun line(entries: List<EpgEntry>): String? {
        val entry = now(entries) ?: return null
        val head = if (entry.live) "AHORA" else "HOY"
        return listOfNotNull(head, entry.start, entry.subtitle)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
}
