package hr.smocnica.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val Purple = Color(0xFF6B2FC6)
val PurpleDark = Color(0xFF4E1B9A)
val PurpleSoft = Color(0xFFF3ECFF)
val Rose = Color(0xFFD81B52)
val Amber = Color(0xFFE99512)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleSoft,
    onPrimaryContainer = Color(0xFF24103D),
    secondary = Color(0xFF75558D),
    tertiary = Amber,
    error = Rose,
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF5F0F8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD2B3FF),
    onPrimary = Color(0xFF3B006E),
    primaryContainer = Color(0xFF54209D),
    onPrimaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFFDCC0F4),
    tertiary = Color(0xFFFFB94F),
    error = Color(0xFFFFB1C0),
    background = Color(0xFF151218),
    surface = Color(0xFF151218),
    surfaceVariant = Color(0xFF29232F),
)

@Composable
fun SmocnicaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            window.isNavigationBarContrastEnforced = false
        }
    }
    MaterialTheme(colorScheme = colors, typography = androidx.compose.material3.Typography(), content = content)
}
