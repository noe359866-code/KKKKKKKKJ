package com.tanasi.streamflix.ui.home

/**
 * Distribución del teclado del buscador y las dos únicas operaciones que
 * necesita: escribir una tecla y borrar.
 *
 * Es data + funciones puras a propósito: el teclado de una TV no puede depender
 * del teclado del sistema (`TextField` abre el IME de Android, que en un mando
 * es inusable), así que la "entrada de texto" es nuestra y se prueba en JVM.
 *
 * Sin teclas de acentos: el catálogo se busca por título y las búsquedas de
 * Cinemeta y de los scrapers no distinguen mayúsculas; meter `Á É Í Ó Ú Ü` en
 * el teclado costaba una fila entera de teclas para un caso que el buscador
 * resuelve igual.
 */
object TvKeyboardLayout {

    /** Tope de la consulta. Mismo límite que acepta el catálogo. */
    const val MAX_QUERY = 60

    /** Filas de letras, tal cual se pintan (arriba a abajo, izquierda a derecha). */
    val rows: List<List<String>> = listOf(
        listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
        listOf("A", "S", "D", "F", "G", "H", "J", "K", "L", "Ñ"),
        listOf("Z", "X", "C", "V", "B", "N", "M"),
    )

    /** Añade [key] al final de [text], respetando el tope y un espacio inicial. */
    fun type(text: String, key: String): String {
        if (key.isEmpty()) return text
        val next = text + key
        return next.take(MAX_QUERY).trimStart()
    }

    /** Quita el último carácter. Cadena vacía si no hay nada que borrar. */
    fun backspace(text: String): String = if (text.isEmpty()) text else text.dropLast(1)

    fun clear(): String = ""

    /** Texto listo para buscar: sin espacios sobrantes en los extremos. */
    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY)
}
