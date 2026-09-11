package hr.smocnica.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ShelfEmblem() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(64.dp).background(colors.primaryContainer, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(50.dp)) {
            scale(size.width / 64f, size.height / 64f, pivot = Offset.Zero) {
                drawRoundRect(colors.primary.copy(alpha = .7f), Offset(5f, 49f), Size(54f, 4f), CornerRadius(2f))
                drawRoundRect(colors.primary.copy(alpha = .4f), Offset(9f, 53f), Size(3f, 6f), CornerRadius(1f))
                drawRoundRect(colors.primary.copy(alpha = .4f), Offset(52f, 53f), Size(3f, 6f), CornerRadius(1f))
                listOf(Triple(10f, 15f, colors.primary), Triple(27f, 25f, colors.tertiary), Triple(44f, 20f, colors.secondary)).forEach { (x, y, color) ->
                    drawRoundRect(color.copy(alpha = .2f), Offset(x, y), Size(12f, 49f - y), CornerRadius(3f))
                    drawRoundRect(color, Offset(x, y - 4f), Size(12f, 5f), CornerRadius(2f))
                    drawRoundRect(color.copy(alpha = .65f), Offset(x + 3f, y + 9f), Size(6f, 49f - y - 13f), CornerRadius(2f))
                }
            }
        }
    }
}

@Composable
internal fun ProductQuantityButtons(available: Int, increment: () -> Unit, decrement: () -> Unit) {
    Row(
        Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(decrement, Modifier.size(48.dp).semantics { contentDescription = "Izvadi jedan" }, enabled = available > 0) {
            Icon(Icons.Outlined.Remove, null)
        }
        Text(available.toString(), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        FilledIconButton(increment, Modifier.size(48.dp).semantics { contentDescription = "Dodaj jedan" }, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.Add, null)
        }
    }
}
