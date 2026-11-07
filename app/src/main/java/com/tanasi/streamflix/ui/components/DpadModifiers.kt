package com.tanasi.streamflix.ui.components

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import com.tanasi.streamflix.core.DpadFastScroll
import com.tanasi.streamflix.core.DpadRepeatPolicy
import kotlinx.coroutines.launch

/**
 * Frena las repeticiones del D-Pad y mueve el foco a un ritmo conocido.
 *
 * Sin esto, mantener pulsada una dirección deja que el sistema decida: envía
 * repeticiones lo más rápido que puede y Compose mueve el foco en cada una. En
 * un TV box modesto eso satura el hilo de UI y la navegación va a tirones.
 *
 * Lo que **no** hace: tocar la primera pulsación. Ésa se deja pasar tal cual
 * para que el mando responda al instante; la compuerta sólo mira repeticiones
 * (`repeatCount > 0`), que es lo que genera el botón sostenido.
 *
 * El instante de la última repetición vive en un `LongArray` de un elemento y
 * no en un `State`: dentro de `composed` un `State` mutable dispararía una
 * recomposición por cada repetición del mando, justo el trabajo que la
 * compuerta intenta evitar.
 */
internal fun Modifier.dpadRepeatThrottle(
    horizontalGateMs: Long = DpadRepeatPolicy.HORIZONTAL_GATE_MS,
    verticalGateMs: Long = DpadRepeatPolicy.VERTICAL_GATE_MS,
): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val lastRepeatMs = remember { longArrayOf(0L) }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val direction = native.dpadDirection() ?: return@onPreviewKeyEvent false
        if (native.action != KeyEvent.ACTION_DOWN || native.repeatCount == 0) {
            return@onPreviewKeyEvent false
        }
        val vertical = direction == FocusDirection.Up || direction == FocusDirection.Down
        val gate = if (vertical) verticalGateMs else horizontalGateMs
        val now = SystemClock.uptimeMillis()
        if (DpadRepeatPolicy.shouldDrop(now - lastRepeatMs[0], gate)) {
            /* Se consume: si se dejara pasar, Compose movería el foco igual y
             * la compuerta no serviría de nada. */
            return@onPreviewKeyEvent true
        }
        lastRepeatMs[0] = now
        focusManager.moveFocus(direction)
        true
    }
}

/**
 * Convierte ARRIBA/ABAJO sostenidos en un deslizamiento de la lista.
 *
 * En una TV, bajar diez filas pulsando diez veces el mando cansa. Con el botón
 * sostenido la lista avanza sola, como en el lanzador de la propia televisión.
 *
 * **Cómo se mueve.** Cada fotograma recibe su delta de
 * [DpadFastScroll.deltaPx] —velocidad en dp/s, con tope por fotograma— y se lo
 * pide a la lista. No hay corrutina: el propio fotograma es el reloj, y así no
 * queda nada vivo que cancelar si el usuario suelta el mando a mitad.
 *
 * **Qué pasa con el foco.** El foco no viaja con la lista: se queda donde está
 * mientras el arrastre dura, y sólo cuando el usuario ha mantenido el botón
 * [FOCUS_STEPS_AFTER_RELEASE] repeticiones se le entrega la dirección a Compose
 * para que lo lleve a la fila visible. Mover el foco en cada repetición
 * mientras la lista se desliza es justo lo que produce el temblor típico de las
 * apps que lo hacen mal; no hacerlo nunca dejaría al usuario con el foco en una
 * fila que ya no ve.
 *
 * @param scrollableState el `LazyListState` que se desliza.
 * @param enabled sólo en pantallas con D-Pad; en táctil no se instala.
 */
internal fun Modifier.dpadFastVerticalScroll(
    scrollableState: ScrollableState,
    enabled: Boolean,
): Modifier = if (!enabled) this else composed {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val lastFrameNs = remember { longArrayOf(0L) }
    val repeatCount = remember { intArrayOf(0) }

    onPreviewKeyEvent { event ->
        val native = event.nativeKeyEvent
        val direction = native.dpadDirection()
        if (direction != FocusDirection.Up && direction != FocusDirection.Down) {
            return@onPreviewKeyEvent false
        }
        when (native.action) {
            KeyEvent.ACTION_DOWN -> {
                /* La primera pulsación es navegación normal: un paso de foco,
                 * inmediato. El deslizamiento empieza con la repetición. */
                if (native.repeatCount == 0) return@onPreviewKeyEvent false
                repeatCount[0] = native.repeatCount
                val now = System.nanoTime()
                val previous = lastFrameNs[0]
                lastFrameNs[0] = now
                if (previous != 0L) {
                    val elapsedMs = (now - previous) / 1_000_000L
                    val delta = DpadFastScroll.deltaPx(elapsedMs = elapsedMs, density = density)
                    if (delta > 0) {
                        scope.launch { scrollableState.scrollBy(delta.toFloat()) }
                    }
                }
                /* Pasadas unas repeticiones se deja que Compose mueva el foco,
                 * para que no se quede atrás respecto a lo que se ve. */
                if (repeatCount[0] < FOCUS_STEPS_AFTER_RELEASE) {
                    return@onPreviewKeyEvent true
                }
                return@onPreviewKeyEvent false
            }
            KeyEvent.ACTION_UP -> {
                repeatCount[0] = 0
                lastFrameNs[0] = 0L
                false
            }
            else -> false
        }
    }
}

/**
 * Repeticiones que se descartan antes de devolverle el foco a Compose.
 *
 * A ~50 ms por repetición del sistema son unos 400 ms de botón sostenido: el
 * tiempo justo para que el usuario perciba que la lista se desliza sola y no
 * que el foco salta de fila en fila.
 */
private const val FOCUS_STEPS_AFTER_RELEASE = 8

/** La dirección del D-Pad que corresponde a la tecla, o `null` si no es una flecha. */
private fun KeyEvent.dpadDirection(): FocusDirection? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
    KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
    else -> null
}
