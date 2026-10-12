package app.homeydash

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.homeydash.ui.AppRoot
import app.homeydash.ui.theme.HomeyDashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A wall-mounted kitchen screen should stay on.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val container = (application as HomeyDashApp).container
        setContent {
            HomeyDashTheme {
                AppRoot(container)
            }
        }
    }
}
