package app.homeydash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import app.homeydash.ui.theme.Inter
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.homeydash.ui.AppRoot
import app.homeydash.ui.theme.HomeyDashTheme
import app.homeydash.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A wall-mounted kitchen screen should stay on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val container = (application as HomeyDashApp).container
        screen = ScreenControl(this, container.display, lifecycleScope).also { it.start() }
        setContent {
            val themeMode by container.display.themeMode.collectAsStateWithLifecycle()
            val accent by container.display.accent.collectAsStateWithLifecycle()
            val accentOpacity by container.display.accentOpacity.collectAsStateWithLifecycle()
            val palette by container.display.palette.collectAsStateWithLifecycle()
            val flat by container.display.flat.collectAsStateWithLifecycle()
            HomeyDashTheme(themeMode, accent, accentOpacity, Palette.of(palette), flat) {
                Box(Modifier.fillMaxSize()) {
                    AppRoot(container)
                    val dimmed by screen.dimmed.collectAsStateWithLifecycle()
                    if (dimmed) DimmedScreen()
                }
            }
        }
    }

    private lateinit var screen: ScreenControl

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // A touch on the dark screen only wakes it.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && screen.onTouch()) {
            swallowGesture = true
            return true
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private var swallowGesture = false

    override fun onResume() {
        super.onResume()
        screen.onResume()
    }

    override fun onDestroy() {
        screen.stop()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Full screen like a kiosk; a swipe from the edge shows the bars for a moment. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/** The dimmed screen: black, with a faint clock so it still tells the time. */
@Composable
private fun DimmedScreen() {
    val time by produceState(LocalTime.now()) {
        while (true) {
            value = LocalTime.now()
            delay(20_000)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text(
            time.format(DateTimeFormatter.ofPattern("HH:mm")),
            color = Color.White.copy(alpha = 0.35f),
            fontSize = 96.sp,
            fontFamily = Inter,
            fontWeight = FontWeight.Light,
        )
    }
}
