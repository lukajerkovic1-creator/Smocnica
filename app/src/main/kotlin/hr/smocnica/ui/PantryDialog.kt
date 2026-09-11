package hr.smocnica.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** Shared visual treatment; callers retain their validation and confirmation callbacks. */
@Composable
internal fun PantryDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    MaterialTheme(shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            properties = properties,
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            icon = icon,
            title = title?.let { content -> {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) { content() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
                }
            } },
            text = text,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
        )
    }
}

@Composable
internal fun PantryPicker(label: String, options: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val value = options.firstOrNull { it.first == selected }?.second ?: "Odaberite"
    Box(Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width }) {
        Surface(
            onClick = { expanded = true }, enabled = options.isNotEmpty(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$label: $value", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.width(with(density) { anchorWidth.toDp() }),
            shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp) {
            options.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name, fontWeight = if (id == selected) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { expanded = false; select(id) },
                    trailingIcon = if (id == selected) ({ Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null)
            }
        }
    }
}
