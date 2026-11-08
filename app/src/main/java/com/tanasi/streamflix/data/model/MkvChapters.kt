package com.tanasi.streamflix.data.model

/**
 * Lee los capítulos que lleva dentro un `.mkv` (Matroska).
 *
 * **Para qué.** Las bases de marcas (IntroDB, TheIntroDB) resuelven la intro de
 * muchas series, pero tienen huecos: anime, títulos antiguos y sobre todo
 * **películas** casi nunca están. Muchos releases llevan los capítulos dentro
 * del contenedor, con nombres que delatan justo lo que queremos: «Opening
 * Credits», «Intro», «Recap», «End Credits». Leerlos cuesta una petición HTTP
 * de rango y no depende de ninguna API.
 *
 * **Qué no hace.** No es un demuxer: no toca el vídeo ni el audio, sólo recorre
 * las cabeceras EBML hasta el elemento `Chapters` y se detiene. Un `.mkv` real
 * pesa gigabytes; aquí se leen kilobytes.
 *
 * **Dónde encaja.** El resultado se convierte en [SkipSegment] y se suma a lo
 * que ya devolvieron las bases de marcas: los capítulos del contenedor son el
 * respaldo cuando las APIs no saben nada del título.
 */
object MkvChapters {

    /* --- Identificadores EBML (Matroska) ----------------------------------- */
    private const val ID_EBML_HEADER = 0x1A45DFA3L
    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_EDITION_ENTRY = 0x45B9L
    private const val ID_CHAPTER_ATOM = 0xB6L
    private const val ID_CHAPTER_TIME_START = 0x91L
    private const val ID_CHAPTER_DISPLAY = 0x80L
    private const val ID_CHAPTER_STRING = 0x85L

    /** Tope de bytes que se leen del contenedor: un `.mkv` entero no cabe. */
    const val MAX_READ_BYTES = 4 * 1024 * 1024

    /**
     * Nombres que delatan una cabecera o un resumen.
     *
     * Se exige la palabra completa (`\b`) para no confundir «Intro» con
     * «Introduction to Physics», y no se incluye «End» a secas porque aparece
     * en títulos de capítulo normales («The End of the World»).
     */
    private val introPattern = Regex(
        """\b(intro|opening|op|prologue|prólogo|apertura|presentación)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Resumen del capítulo anterior. Va **antes** que [introPattern] porque
     * «Recap» casa con los dos: el resumen y la cabecera son tramos distintos
     * y el botón lo dice («Saltar resumen» frente a «Saltar intro»).
     */
    private val recapPattern = Regex(
        """\b(recap|previously|resumen|anteriormente)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val creditsPattern = Regex(
        """\b(credits?|ending|outro|closing|end credits|créditos|cierre|final)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Un capítulo del contenedor: dónde empieza y cómo se llama. */
    data class Chapter(
        /** Milisegundos desde el principio del archivo. */
        val startMs: Long,
        val title: String,
    )

    /**
     * Capítulos a partir de los bytes de cabecera del archivo.
     *
     * Devuelve lista vacía —nunca lanza— si el archivo no es Matroska, si los
     * bytes están truncados o si el contenedor no trae capítulos. Un parser que
     * revienta por un archivo raro dejaría al usuario sin reproductor, y esto
     * es información opcional.
     */
    fun parse(buffer: ByteArray): List<Chapter> {
        return try {
            readChapters(buffer)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Convierte los capítulos en tramos saltables.
     *
     * El final de un tramo es el principio del siguiente capítulo: un archivo
     * no dice dónde acaba la intro, sólo dónde empieza el resto.
     *
     * @param durationMs duración total, para cerrar el último tramo. Con 0 se
     *   descarta el último: un tramo sin final no se puede ofrecer como salto.
     */
    fun segmentsFrom(chapters: List<Chapter>, durationMs: Long): List<SkipSegment> {
        if (chapters.isEmpty()) return emptyList()
        val ordered = chapters.sortedBy { it.startMs }
        val result = ArrayList<SkipSegment>(ordered.size)
        ordered.forEachIndexed { index, chapter ->
            /* Los créditos se comprueban **antes** que la intro: un capítulo
             * llamado «Opening Credits» casa con los dos patrones («opening» y
             * «credits»), y son los títulos de crédito del principio, no una
             * cabecera que saltar. Al revés se ofrecería «Saltar intro» al
             * empezar la película, que es justo lo contrario de lo que quiere
             * el espectador. */
            val type = when {
                creditsPattern.containsMatchIn(chapter.title) -> SkipSegmentType.Credits
                recapPattern.containsMatchIn(chapter.title) -> SkipSegmentType.Recap
                introPattern.containsMatchIn(chapter.title) -> SkipSegmentType.Intro
                else -> null
            } ?: return@forEachIndexed
            val next = ordered.getOrNull(index + 1)?.startMs ?: durationMs.takeIf { it > 0L }
            val end = next ?: return@forEachIndexed
            val segment = SkipSegment(type = type, startMs = chapter.startMs, endMs = end)
            /* `isUsable` filtra los tramos de menos de cinco segundos: un
             * capítulo mal etiquetado que dura dos segundos no es una intro,
             * es ruido. */
            if (segment.isUsable) result += segment
        }
        return result
    }

    /* --- Lectura EBML ------------------------------------------------------ */

    private fun readChapters(buffer: ByteArray): List<Chapter> {
        /* El elemento `Chapters` puede estar en cualquier punto de la cabecera,
         * después de unos cuantos MB de metadatos. Se recorre en anchura por el
         * nivel superior en vez de asumir una ruta fija: hay archivos con
         * `SeekHead` antes y otros sin él. */
        val chaptersElement = findElement(buffer, ID_CHAPTERS, 0, buffer.size, depth = 0)
        if (chaptersElement != null) {
            return parseChapters(buffer, chaptersElement.first, chaptersElement.second)
        }
        /* Respaldo: muchos archivos no envuelven los capítulos en `Chapters` o
         * ponen el marcador de tamaño desconocido. En ese caso se buscan los
         * `ChapterAtom` directamente y se valida que traigan tiempo, que es lo
         * único que convierte un `B6` suelto en un capítulo de verdad. */
        return parseChapters(buffer, 0, buffer.size)
    }

    /**
     * Busca un elemento por su id dentro de un rango, bajando como mucho
     * [MAX_DEPTH] niveles.
     *
     * Se salta los elementos que no interesan por su tamaño declarado, así que
     * en el caso normal sólo se miran unas pocas cabeceras.
     */
    private fun findElement(
        buffer: ByteArray,
        target: Long,
        start: Int,
        end: Int,
        depth: Int,
    ): Pair<Int, Int>? {
        if (depth > MAX_DEPTH) return null
        var position = start
        while (position < end) {
            val header = readHeader(buffer, position, end) ?: return null
            if (header.id == target) {
                val dataStart = position + header.headerSize
                val dataEnd = if (header.size < 0) end else (dataStart + header.size).coerceAtMost(end)
                return dataStart to dataEnd
            }
            val dataStart = position + header.headerSize
            val dataEnd = if (header.size < 0) end else dataStart + header.size
            /* Sólo se baja en los elementos contenedores conocidos: intentar
             * interpretar datos arbitrarios como EBML daría falsos positivos. */
            if (header.id in containers && dataEnd <= end) {
                val found = findElement(buffer, target, dataStart, dataEnd, depth + 1)
                if (found != null) return found
            }
            if (dataEnd <= position) return null
            position = dataEnd
        }
        return null
    }

    private val containers = setOf(
        ID_EBML_HEADER, ID_SEGMENT, ID_SEEK_HEAD, ID_SEEK,
        ID_CHAPTERS, ID_EDITION_ENTRY, ID_CHAPTER_ATOM,
    )

    private const val MAX_DEPTH = 8

    private class Header(val id: Long, val size: Int, val headerSize: Int)

    /**
     * Lee la cabecera de un elemento: id (1-4 bytes) y tamaño (VINT, 1-8).
     *
     * `size` = -1 significa tamaño **desconocido** (todos los bits de datos a
     * 1), que en Matroska es legal para los contenedores que se emiten en
     * directo. Se trata como «hasta el final del rango».
     */
    private fun readHeader(buffer: ByteArray, position: Int, limit: Int): Header? {
        val idLength = vintLength(buffer, position, limit) ?: return null
        if (idLength > 4) return null
        var id = 0L
        for (index in 0 until idLength) {
            id = (id shl 8) or (buffer[position + index].toLong() and 0xFF)
        }
        val sizePosition = position + idLength
        val sizeLength = vintLength(buffer, sizePosition, limit) ?: return null
        var size = 0L
        var allOnes = true
        for (index in 0 until sizeLength) {
            val byte = buffer[sizePosition + index].toInt() and 0xFF
            val value = if (index == 0) byte and (0xFF ushr sizeLength) else byte
            size = (size shl 8) or value.toLong()
            if (index > 0 && byte != 0xFF) allOnes = false
        }
        val firstDataMask = 0xFF ushr sizeLength
        val unknown = allOnes && (buffer[sizePosition].toInt() and firstDataMask) == firstDataMask
        /* El tope es el del **buffer**, no el del rango: un `Segment` de 70
         * bytes que termina justo en el límite del padre es legal, y comparar
         * contra `limit` lo descartaba —con lo que el recorrido se paraba en
         * la primera cabecera y no se encontraba nunca `Chapters`—. Un tamaño
         * mayor que el buffer es lo único imposible: ahí sí se corta. */
        if (size > buffer.size.toLong()) return null
        return Header(id, if (unknown) -1 else size.toInt(), idLength + sizeLength)
    }

    /** Longitud en bytes que marca el primer byte (1-8), o `null` si es 0. */
    private fun vintLength(buffer: ByteArray, position: Int, limit: Int): Int? {
        if (position >= limit) return null
        val first = buffer[position].toInt() and 0xFF
        if (first == 0) return null
        var length = 1
        var mask = 0x80
        while (length <= 8) {
            if (first and mask != 0) {
                return if (position + length <= limit) length else null
            }
            mask = mask shr 1
            length++
        }
        return null
    }

    /** Recorre los `ChapterAtom` de un elemento `Chapters` y los lee. */
    private fun parseChapters(buffer: ByteArray, start: Int, end: Int): List<Chapter> {
        val chapters = ArrayList<Chapter>()
        var position = start
        while (position < end) {
            val header = readHeader(buffer, position, end) ?: break
            val dataStart = position + header.headerSize
            val dataEnd = if (header.size < 0) end else (dataStart + header.size).coerceAtMost(end)
            when (header.id) {
                /* Sólo los `ChapterAtom` del primer nivel: los `ChapterAtom`
                 * anidados son subcapítulos y no aportan un salto. */
                ID_EDITION_ENTRY, ID_CHAPTER_ATOM -> {
                    val nested = parseChapterAtom(buffer, dataStart, dataEnd)
                    if (nested != null) chapters += nested
                    else chapters += parseChapters(buffer, dataStart, dataEnd)
                }
                /* El respaldo busca desde la raíz, así que aquí se encuentra el
                 * `Segment` antes que nada: hay que entrar en él o el recorrido
                 * termina en la primera cabecera. */
                ID_SEGMENT -> chapters += parseChapters(buffer, dataStart, dataEnd)
            }
            if (dataEnd <= position) break
            position = dataEnd
        }
        return chapters
    }

    /** Lee `ChapterTimeStart` y `ChapString` de un `ChapterAtom`. */
    private fun parseChapterAtom(buffer: ByteArray, start: Int, end: Int): Chapter? {
        var timeNs = -1L
        var title = ""
        var position = start
        while (position < end) {
            val header = readHeader(buffer, position, end) ?: break
            val dataStart = position + header.headerSize
            val dataEnd = if (header.size < 0) end else (dataStart + header.size).coerceAtMost(end)
            when (header.id) {
                ID_CHAPTER_TIME_START -> timeNs = readUnsigned(buffer, dataStart, dataEnd)
                ID_CHAPTER_DISPLAY -> {
                    /* El nombre va dentro de `ChapterDisplay`; se busca el
                     * `ChapString` de ese bloque. */
                    val name = findString(buffer, dataStart, dataEnd)
                    if (name.isNotBlank()) title = name
                }
            }
            if (dataEnd <= position) break
            position = dataEnd
        }
        if (timeNs < 0) return null
        return Chapter(startMs = timeNs / 1_000_000L, title = title)
    }

    private fun findString(buffer: ByteArray, start: Int, end: Int): String {
        var position = start
        while (position < end) {
            val header = readHeader(buffer, position, end) ?: break
            val dataStart = position + header.headerSize
            val dataEnd = if (header.size < 0) end else (dataStart + header.size).coerceAtMost(end)
            if (header.id == ID_CHAPTER_STRING) {
                return String(buffer, dataStart, (dataEnd - dataStart).coerceAtLeast(0), Charsets.UTF_8)
                    .trim('\u0000', ' ')
            }
            if (dataEnd <= position) break
            position = dataEnd
        }
        return ""
    }

    private fun readUnsigned(buffer: ByteArray, start: Int, end: Int): Long {
        var value = 0L
        for (index in start until end) {
            value = (value shl 8) or (buffer[index].toLong() and 0xFF)
        }
        return value
    }
}
