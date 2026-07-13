package hr.smocnica

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import hr.smocnica.ui.SmocnicaApp
import hr.smocnica.ui.theme.SmocnicaTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var requestedRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedRoute = intent.destinationRoute()
        setContent {
            SmocnicaTheme {
                SmocnicaApp(requestedRoute = requestedRoute, onRouteConsumed = { requestedRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        requestedRoute = intent.destinationRoute()
    }

    private fun Intent.destinationRoute(): String? = getStringExtra("destination")
        ?.takeIf { it in setOf("shopping", "stocks") }
}
