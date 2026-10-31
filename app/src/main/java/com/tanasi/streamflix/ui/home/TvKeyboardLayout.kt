package com.tanasi.streamflix.ui.home

/**
 * Distribución del teclado del buscador y las dos únicas operaciones que
 * necesita: escribir una tecla y borrar.
 *
 * Es data + funciones puras a propósito: el teclado de una TV no puede depender
 * del teclado del sistema (`TextField` abre el IME de Android, que en un mando
 * es inusable), así que la "entrada de texto" es nuestra y se prueba en JVM.
 *
 * Los acentos **sí** están, pero en una segunda página de la fila de símbolos:
 * `123` / `ÁÉÍ` la conmuta. Antes no existían y un título que sólo se encuentra
 * escrito con tilde (o con `¿`/`¡`) era imposible de buscar desde el mando. Una
 * fila extra de teclas no cabe en 540 dp; dos páginas sobre la misma fila sí.
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

    /**
     * Fila de dígitos.
     *
     * Es la página **por defecto**: media cartelera tiene números en el título
     * (1917, 2001, 24, 1984) y sin teclado numérico esas búsquedas son
     * imposibles con el mando. Una fila de diez teclas cuesta 48 dp de alto, así
     * que esa fila se comparte con [accentRow] en vez de añadir una segunda: los
     * dígitos quedan a la vista y los acentos, a una pulsación.
     */
    val digitRow: List<String> = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    /**
     * Segunda página de la misma fila: acentos y signos que sí aparecen en los
     * títulos ("¿Qué pasó ayer?", "¡Asu mare!").
     *
     * Diez teclas, igual que los dígitos, para no mover nada del resto del
     * teclado: la fila mide lo mismo y el foco cae en el mismo sitio al cambiar
     * de página.
     */
    val accentRow: List<String> = listOf("Á", "É", "Í", "Ó", "Ú", "Ü", "Ñ", "¿", "?", "¡")

    /** Qué página de símbolos se está viendo. */
    enum class SymbolPage { DIGITS, ACCENTS }

    /** Teclas de la fila de símbolos según la página activa. */
    fun symbolRow(page: SymbolPage): List<String> = when (page) {
        SymbolPage.DIGITS -> digitRow
        SymbolPage.ACCENTS -> accentRow
    }

    /** La tecla de página es un interruptor: `123` ⇄ `ÁÉÍ`. */
    fun toggle(page: SymbolPage): SymbolPage = when (page) {
        SymbolPage.DIGITS -> SymbolPage.ACCENTS
        SymbolPage.ACCENTS -> SymbolPage.DIGITS
    }

    /** Etiqueta de la tecla que cambia de página: dice **a dónde** lleva. */
    fun toggleLabel(page: SymbolPage): String = when (page) {
        SymbolPage.DIGITS -> "ÁÉÍ"
        SymbolPage.ACCENTS -> "123"
    }

    /** Añade [key] al final de [text], respetando el tope y un espacio inicial. */
    fun type(text: String, key: String): String {
        if (key.isEmpty()) return text
        val next = text + key
        return next.take(MAX_QUERY).trimStart()
    }

    /** Quita el último carácter. Cadena vacía si no hay nada que borrar. */
    fun backspace(text: String): String = if (text.isEmpty()) text else text.dropLast(1)

    /** Texto listo para buscar: sin espacios sobrantes en los extremos. */
    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ").take(MAX_QUERY)
}
