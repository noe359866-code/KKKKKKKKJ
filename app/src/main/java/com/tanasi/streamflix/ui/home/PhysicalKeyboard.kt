package com.tanasi.streamflix.ui.home

/**
 * Qué hacer con una tecla de un **teclado físico**.
 *
 * Muchos TV box y barras Android llevan un dongle con teclado, y el que escribe
 * así no quiere recorrer 32 teclas con el mando: quiere teclear. El manejador
 * vive aquí, separado del `Composable`, por una razón concreta: **es lo único
 * del teclado que se puede probar en la JVM**. Los eventos reales no llegan en
 * un test unitario, pero la decisión sí.
 *
 * La regla sustituye a un mapa de teclas por letra que tenía un fallo de fondo:
 * recorría `'A'`..`'Z'` en ASCII, así que la `Ñ` **no existía**, y de paso se
 * perdían los acentos, el espacio y cualquier distribución que no fuera latina.
 * Leer el carácter del evento resuelve los cuatro de golpe.
 */
object PhysicalKeyboard {

    /** Lo que se debe hacer con una pulsación. */
    sealed interface Action {
        /** Escribir este texto en el campo (uno o más caracteres). */
        data class Type(val text: String) : Action

        /** Borrar el último carácter. */
        data object Backspace : Action

        /** Borrar hasta la palabra anterior. */
        data object DeleteWord : Action

        /** Ejecutar la búsqueda. */
        data object Submit : Action

        /** Es una flecha o una tecla de foco: el diálogo no la toca. */
        data object Passthrough : Action
    }

    /**
     * Decide qué hacer con una tecla.
     *
     * @param codePoint carácter Unicode de la pulsación (`KeyEvent.utf16CodePoint`).
     *   Es 0 cuando la tecla no produce texto.
     * @param isBackspace, [isDelete], [isEnter], [isDirectional] los identificadores
     *   de tecla ya resueltos por el llamador, para que esto no dependa de Compose
     *   y se pueda probar.
     * @param wordModifier `Ctrl` o `Alt` pulsado: `Ctrl+Retroceso` borra la
     *   palabra entera, que es lo que espera cualquiera que haya escrito
     *   veinte caracteres para buscar una película.
     */
    fun actionFor(
        codePoint: Int,
        isBackspace: Boolean,
        isDelete: Boolean,
        isEnter: Boolean,
        isDirectional: Boolean,
        wordModifier: Boolean,
    ): Action = when {
        /* Los controles van primero: `Retroceso` llega con codePoint 8 y
         * `Enter` con 10/13, y sin esta prioridad se escribirían como
         * caracteres de control invisibles dentro de la consulta. */
        isBackspace -> if (wordModifier) Action.DeleteWord else Action.Backspace
        isDelete -> Action.DeleteWord
        isEnter -> Action.Submit
        /* Las flechas mueven el foco entre teclas: si se trataran como texto,
         * `utf16CodePoint` puede traer el carácter del sistema y acabaría
         * escrito en el campo. */
        isDirectional -> Action.Passthrough
        codePoint in 32..0xFFFD && !isControl(codePoint) -> Action.Type(codePoint.toChar().toString())
        /* Tab, Esc, F1… no escriben nada y tampoco se consumen. */
        else -> Action.Passthrough
    }

    /**
     * `true` para los caracteres de control (C0 y C1). `utf16CodePoint` los
     * devuelve para teclas con nombre —Retroceso, Enter, Esc—, y colarlos en la
     * consulta haría que el catálogo buscara basura invisible.
     */
    fun isControl(codePoint: Int): Boolean =
        codePoint < 32 || codePoint in 0x7F..0x9F
}
