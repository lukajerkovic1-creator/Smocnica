package hr.smocnica.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** A bounded reveal: a gesture never changes stock or dismisses the card. */
@Composable
internal fun SwipeQuantityActions(
    enabled: Boolean,
    available: Int,
    increment: () -> Unit,
    decrement: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val revealWidth = with(density) { 144.dp.toPx() }
    val flingThreshold = with(density) { 125.dp.toPx() }
    var targetOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val offset by animateFloatAsState(targetOffset, if (dragging) snap() else tween(180), label = "quantityActions")
    LaunchedEffect(enabled, revealWidth) {
        targetOffset = if (enabled) targetOffset.coerceIn(-revealWidth, 0f) else 0f
    }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))) {
        if (enabled && offset < -0.5f) {
            Box(Modifier.matchParentSize()) {
                Row(Modifier.align(Alignment.CenterEnd).width(144.dp).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.primary)
                        .clickable { targetOffset = 0f; increment() }
                        .semantics { contentDescription = "Dodaj jedan gestom" }, contentAlignment = Alignment.Center) {
                        Text("+1", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(enabled = available > 0) { targetOffset = 0f; decrement() }
                        .semantics { contentDescription = "Izvadi jedan gestom" }, contentAlignment = Alignment.Center) {
                        Text("−1", color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = if (available > 0) 1f else .38f),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Box(Modifier.offset { IntOffset(offset.roundToInt(), 0) }.draggable(
            orientation = Orientation.Horizontal,
            enabled = enabled,
            state = rememberDraggableState { delta -> targetOffset = (targetOffset + delta).coerceIn(-revealWidth, 0f) },
            onDragStarted = { targetOffset = offset; dragging = true },
            onDragStopped = { velocity ->
                dragging = false
                targetOffset = when {
                    velocity < -flingThreshold -> -revealWidth
                    velocity > flingThreshold -> 0f
                    targetOffset < -revealWidth / 2 -> -revealWidth
                    else -> 0f
                }
            },
        )) { content() }
    }
}
