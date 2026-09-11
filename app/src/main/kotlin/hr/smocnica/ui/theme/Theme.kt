package hr.smocnica.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val Purple = Color(0xFF6B2FC6)
val PurpleDark = Color(0xFF4E1B9A)
val PurpleSoft = Color(0xFFF3ECFF)
val Rose = Color(0xFFD81B52)
// Dovoljno taman za ikone i velike brojke na svijetloj pozadini (WCAG AA, >= 3:1).
val Amber = Color(0xFF8B5000)

private val LightColors = lightColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    primaryContainer = PurpleSoft,
    onPrimaryContainer = Color(0xFF24103D),
    secondary = Color(0xFF75558D),
    tertiary = Amber,
    error = Rose,
    background = Color(0xFFF8F6FB),
    surface = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCFAFF),
    surfaceContainer = Color(0xFFF5F0FA),
    surfaceContainerHigh = Color(0xFFF1EBF7),
    surfaceContainerHighest = Color(0xFFF0EBF5),
    outlineVariant = Color(0xFFE3DCEB),
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
    surface = Color(0xFF201B26),
    surfaceContainerLowest = Color(0xFF151218),
    surfaceContainerLow = Color(0xFF201B26),
    surfaceContainer = Color(0xFF27212E),
    surfaceContainerHigh = Color(0xFF302838),
    surfaceContainerHighest = Color(0xFF352D3E),
    outlineVariant = Color(0xFF4A4056),
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
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content,
    )
}
