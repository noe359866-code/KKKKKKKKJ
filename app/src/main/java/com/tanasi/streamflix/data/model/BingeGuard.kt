package com.tanasi.streamflix.data.model

/**
 * Freno para las maratones: cada cuántos episodios seguidos se pregunta
 * «¿sigues ahí?».
 *
 * Un reproductor que avanza solo es cómodo hasta que deja de serlo: alguien se
 * queda dormido en el sofá y por la mañana la tele ha consumido una temporada
 * entera. Eso ensucia el historial («Seguir viendo» apunta al capítulo 8
 * cuando la persona se durmió en el 3), gasta datos y, con un servidor propio,
 * gasta cuota.
 *
 * **El contador vive en la sesión de reproducción, no en disco.** Es
 * deliberado: alguien que vuelve el martes a ver otro capítulo no está
 * haciendo una maratón, y un contador persistente lo trataría como tal a la
 * tercera noche. `BingeGuard` se crea por `PlayerActivity` y muere con ella.
 *
 * Se cuenta **sólo el avance automático** (el que el usuario no pidió). Si
 * pulsa «Ver el siguiente», eso es intención explícita y no debería acercarle
 * al aviso; al contrario, confirma que está despierto, así que reinicia el
 * contador. Igual que hace NuvioTV con su `consecutiveAutoPlayCount`.
 */
object BingeGuard {

    /**
     * Episodios seguidos sin tocar el mando antes de preguntar.
     *
     * Cuatro es el punto donde alguien despierto ya habría hecho algo (subir el
     * volumen, cambiar de episodio, salir) y donde dormido ya se ha ido. Dos se
     * quedaría corto para cualquiera que vea dos capítulos seguidos de verdad;
     * seis son casi hora y media de contenido sin interacción.
     */
    const val EPISODES_BEFORE_PROMPT = 4

    /**
     * ¿Toca preguntar antes de abrir el siguiente?
     *
     * @param consecutiveAutoPlayed cuántos episodios ha abierto la app sola
     *   seguidos, sin que el usuario interviniera.
     * @param enabled si el usuario dejó el aviso activado. Se deja como
     *   parámetro para que un ajuste futuro no obligue a cambiar la regla.
     */
    fun shouldAsk(
        consecutiveAutoPlayed: Int,
        enabled: Boolean = true,
    ): Boolean = enabled && consecutiveAutoPlayed >= EPISODES_BEFORE_PROMPT
}
