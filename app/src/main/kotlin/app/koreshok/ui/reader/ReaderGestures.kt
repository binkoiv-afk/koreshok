package app.koreshok.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Moon+-style gestures on top of the page: a vertical slide along the left edge sets the
 * brightness, a two-finger pinch sets the font size. Plain taps and page swipes pass through.
 */
fun Modifier.readerGestures(
    brightness: () -> Float,
    fontSize: () -> Float,
    onBrightness: (value: Float, done: Boolean) -> Unit,
    onFontSize: (value: Float, done: Boolean) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val edge = down.position.x < size.width * EDGE
        val startBrightness = brightness().let { if (it < 0) 0.5f else it }
        val startFont = fontSize()
        var sliding = false
        var lastBrightness = startBrightness
        var pinching = false
        var startDistance = 0f
        var lastFont = startFont
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2 && !sliding) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (!pinching) {
                    pinching = true
                    startDistance = distance
                } else if (startDistance > 0f) {
                    val size = (startFont * distance / startDistance).coerceIn(MIN_FONT, MAX_FONT).roundToInt().toFloat()
                    if (size != lastFont) {
                        lastFont = size
                        onFontSize(size, false)
                    }
                }
                event.changes.forEach { it.consume() }
            } else if (edge && !pinching) {
                val change = pressed[0]
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!sliding && abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx) * 1.5f) sliding = true
                if (sliding) {
                    lastBrightness = (startBrightness - dy / (size.height * 0.6f)).coerceIn(0.01f, 1f)
                    onBrightness(lastBrightness, false)
                    change.consume()
                }
            }
        }
        if (sliding) onBrightness(lastBrightness, true)
        if (pinching) onFontSize(lastFont, true)
    }
}

private const val EDGE = 0.12f
private const val MIN_FONT = 12f
private const val MAX_FONT = 36f
