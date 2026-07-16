package hr.smocnica.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onGoogleSignIn: suspend () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Inventory2, null, Modifier.size(84.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Smočnica", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "Zajednička zaliha hrane, kupnja i inventura — usklađeno na svim vašim uređajima.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Button(onClick = { scope.launch { onGoogleSignIn() } }, Modifier.fillMaxWidth().height(54.dp)) {
            Text("Nastavi s Google računom")
        }
        Text(
            "Prijavom se podaci sigurno povezuju s Firebase projektom konfiguriranim za ovu aplikaciju.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
fun OnboardingScreen(
    initialDeviceName: String,
    onCreate: (String, String) -> Unit,
    onJoin: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    var pantryName by remember { mutableStateOf("Moja smočnica") }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(initialDeviceName) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val invitationScanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build(),
        )
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Postavite zajedničku smočnicu", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Kreirajte novu ili se pridružite pozivnim kodom.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onRefresh, Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Obnovi postojeće smočnice")
            }
            OutlinedTextField(deviceName, { deviceName = it.take(40) }, label = { Text("Naziv ovog uređaja") }, supportingText = { Text("Prikazuje se u povijesti aktivnosti") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(24.dp))
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Nova smočnica", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(pantryName, { pantryName = it }, label = { Text("Naziv") }, modifier = Modifier.fillMaxWidth())
                    Button({ onCreate(pantryName, deviceName) }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = deviceName.trim().length in 2..40) { Text("Kreiraj smočnicu") }
                }
            }
            Spacer(Modifier.height(16.dp))
            Card(shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text("Pridruži se", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(code, { code = it.uppercase().filter(Char::isLetterOrDigit).take(32) }, label = { Text("Pozivni kod") }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(
                        onClick = {
                            scanError = null
                            invitationScanner.startScan()
                                .addOnSuccessListener { barcode ->
                                    val scanned = barcode.rawValue?.let(::inviteCodeFromQr)
                                    if (scanned == null) {
                                        scanError = "QR kod nije valjani poziv za Smočnicu."
                                    } else {
                                        code = scanned
                                        onJoin(scanned, deviceName)
                                    }
                                }
                                .addOnFailureListener {
                                    scanError = "Skener se nije mogao pokrenuti. Provjerite Google Play Services i pokušajte ponovno."
                                }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Skeniraj pozivni QR") }
                    scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button({ onJoin(code, deviceName) }, Modifier.fillMaxWidth().padding(top = 12.dp), enabled = code.length >= 6 && deviceName.trim().length in 2..40) { Text("Pridruži se") }
                }
            }
            OutlinedButton(onSignOut, Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp)) { Text("Odjava") }
        }
    }
}

internal fun inviteCodeFromQr(value: String): String? = runCatching {
    val uri = URI(value)
    require(uri.scheme == "smocnica" && uri.host == "join")
    val codes = uri.rawQuery.orEmpty().split('&').mapNotNull { parameter ->
        val parts = parameter.split('=', limit = 2)
        if (parts.size == 2 && parts[0] == "code") {
            URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
        } else {
            null
        }
    }
    require(codes.size == 1)
    codes.single().uppercase().takeIf { it.matches(Regex("[A-Z0-9]{6,32}")) }
}.getOrNull()
