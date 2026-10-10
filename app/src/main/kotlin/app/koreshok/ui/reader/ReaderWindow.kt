package app.koreshok.ui.reader

import android.app.Activity
import android.content.Context
import android.os.BatteryManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Volume buttons turning pages. The activity offers every key here first; a reader that is
 * on screen and allowed to use them sets [turn] (+1 forward, −1 back).
 */
object PageKeys {
    var turn: ((Int) -> Unit)? = null

    /** True when the key was used, so the system does not change the volume as well. */
    fun handle(event: KeyEvent): Boolean {
        val turn = turn ?: return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) turn(direction)
        return true
    }
}

/** Lets the volume buttons call [turn] while this is on screen and [enabled]. */
@Composable
fun VolumeKeyPaging(enabled: Boolean, turn: (Int) -> Unit) {
    val current by rememberUpdatedState(turn)
    DisposableEffect(enabled) {
        val handler: (Int) -> Unit = { current(it) }
        if (enabled) PageKeys.turn = handler
        onDispose { if (PageKeys.turn === handler) PageKeys.turn = null }
    }
}

/** Screen brightness and "keep the screen on" while a book is open. */
@Composable
fun ReaderWindow(prefs: ReaderPrefs) {
    val view = LocalView.current
    DisposableEffect(prefs.brightness, prefs.keepScreenOn) {
        val window = (view.context as? Activity)?.window
        window?.let {
            val attributes = it.attributes
            attributes.screenBrightness = if (prefs.brightness < 0) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                prefs.brightness.coerceIn(0.01f, 1f)
            }
            it.attributes = attributes
            if (prefs.keepScreenOn) it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.let {
                val attributes = it.attributes
                attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                it.attributes = attributes
                it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

/** "21:47 · 63%" for the page footer, refreshed every half minute. */
@Composable
fun ClockAndBattery(theme: ReaderTheme, modifier: Modifier = Modifier) {
    val preview = LocalInspectionMode.current
    val context = LocalContext.current
    var text by remember { mutableStateOf(if (preview) "21:47 · 63%" else statusText(context)) }
    LaunchedEffect(preview) {
        if (preview) return@LaunchedEffect
        while (true) {
            text = statusText(context)
            delay(30_000)
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(8.dp))
        Text(text, color = theme.secondary, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.width(8.dp))
    }
}

private fun statusText(context: Context): String {
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    val battery = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in 0..100 }
    return if (battery != null) "$time · $battery%" else time
}
