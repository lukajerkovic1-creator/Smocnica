package hr.smocnica.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

internal fun shouldExplainNotificationPermission(
    previousMinimum: String,
    newMinimum: String,
    permissionRequired: Boolean,
    explanationAlreadyShown: Boolean,
): Boolean = permissionRequired &&
    !explanationAlreadyShown &&
    (previousMinimum.toIntOrNull() ?: 0) <= 0 &&
    (newMinimum.toIntOrNull() ?: 0) > 0

@Composable
internal fun NotificationPermissionExplanationDialog(
    requestPermission: () -> Unit,
    dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Obavijesti o minimalnoj zalihi") },
        text = {
            Text(
                "Smočnica vas može obavijestiti kada zaliha prvi put padne ispod postavljenog minimuma. " +
                    "Aplikaciju možete nastaviti koristiti i bez dopuštenja za obavijesti.",
            )
        },
        confirmButton = { Button(requestPermission) { Text("Dopusti obavijesti") } },
        dismissButton = { TextButton(dismiss) { Text("Ne sada") } },
    )
}
