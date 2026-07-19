package hr.smocnica.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import hr.smocnica.BuildConfig
import hr.smocnica.MainViewModel
import hr.smocnica.core.domain.ImportPreview
import hr.smocnica.core.domain.ImportStrategy
import hr.smocnica.core.model.MemberRole
import hr.smocnica.core.model.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MembersScreen(viewModel: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val members by viewModel.members.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val invitation by viewModel.invitation.collectAsStateWithLifecycle()
    val isOwner = members.firstOrNull { it.uid == session?.uid }?.role == MemberRole.OWNER
    var removeUid by remember { mutableStateOf<String?>(null) }
    var transferUid by remember { mutableStateOf<String?>(null) }
    var deletePantry by remember { mutableStateOf(false) }
    SecondaryScreenScaffold("Članovi", padding, onBack) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = inner.calculateBottomPadding() + 50.dp)) {
        item { Text("Vlasnik upravlja članstvom i vlasništvom", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (isOwner) item {
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pozovite drugi uređaj", fontWeight = FontWeight.Bold)
                    if (invitation == null) Button(viewModel::createInvitation, Modifier.padding(top = 10.dp)) { Text("Generiraj novi kod i QR") }
                    invitation?.let { invite ->
                        Text(invite.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                        Image(qrBitmap("smocnica://join?code=${invite.code}").asImageBitmap(), "QR pozivni kod", Modifier.size(180.dp))
                        Text("Vrijedi do ${formatDate(invite.expiresAt)}", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(viewModel::createInvitation, Modifier.padding(top = 8.dp)) { Text("Poništi i generiraj novi") }
                    }
                }
            }
        }
        items(members, key = { it.uid }) { member ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(member.displayName, fontWeight = FontWeight.SemiBold)
                    Text(if (member.role == MemberRole.OWNER) "Vlasnik" else "Član", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isOwner && member.uid != session?.uid) {
                    TextButton({ transferUid = member.uid }) { Text("Prenesi vlasništvo") }
                    IconButton({ removeUid = member.uid }) { Icon(Icons.Outlined.PersonRemove, "Ukloni člana") }
                }
            }
            HorizontalDivider()
        }
        if (isOwner) item {
            OutlinedButton({ deletePantry = true }, Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Icon(Icons.Outlined.DeleteForever, null)
                Text("Obriši cijelu smočnicu", Modifier.padding(start = 8.dp))
            }
        }
        }
    }
    removeUid?.let { uid -> ConfirmDialog("Ukloniti člana?", "Uklonjeni član odmah gubi pristup zajedničkoj smočnici.", { removeUid = null }) { viewModel.removeMember(uid); removeUid = null } }
    transferUid?.let { uid -> ConfirmDialog("Prenijeti vlasništvo?", "Postat ćete običan član, a odabrani član novi vlasnik.", { transferUid = null }) { viewModel.transferOwnership(uid); transferUid = null } }
    if (deletePantry) ConfirmDialog(
        "Obrisati cijelu smočnicu?",
        "Smočnica odmah postaje nedostupna svim članovima, a svi podaci i fotografije trajno se uklanjaju nakon 30 dana. Aplikacija ne nudi vraćanje cijele smočnice.",
        { deletePantry = false },
    ) { viewModel.deletePantry(); deletePantry = false }
}

@Composable
fun AboutScreen(padding: PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("O aplikaciji", "Smočnica ${BuildConfig.VERSION_NAME}")
        Text("Zajednička, offline-first evidencija kućnih zaliha.")
        Text("Podaci smočnice pohranjuju se lokalno i u Firebase projektu kojem pripada ova instalacija. Crashlytics ne prima nazive artikala, barkodove, fotografije ni popis za kupnju.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Licenca i izvorni kod dostupni su u GitHub repozitoriju navedenom u README-u distribucije.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun TrashScreen(viewModel: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val trash by viewModel.trashItems.collectAsStateWithLifecycle()
    var purge by remember { mutableStateOf<TrashItem?>(null) }
    SecondaryScreenScaffold("Koš", padding, onBack) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = inner.calculateTopPadding() + 12.dp, bottom = inner.calculateBottomPadding() + 50.dp)) {
        item { Text("Zapisi se automatski trajno brišu nakon 30 dana", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(trash, key = { "${it.type}_${it.id}" }) { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.label, fontWeight = FontWeight.Bold)
                    Text("${item.type.name.lowercase()} · trajno brisanje ${formatDate(item.purgeAfter)}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton({ viewModel.restoreTrash(item) }) { Icon(Icons.Outlined.Restore, "Vrati") }
                IconButton({ purge = item }) { Icon(Icons.Outlined.DeleteForever, "Trajno obriši") }
            }
            HorizontalDivider()
        }
        if (trash.isEmpty()) item { EmptyState("Koš je prazan.") }
        }
    }
    purge?.let { item -> ConfirmDialog("Trajno obrisati?", "Ovu radnju nije moguće poništiti. Fotografija će također biti obrisana.", { purge = null }) { viewModel.purgeTrash(item); purge = null } }
}

@Composable
fun BackupScreen(viewModel: MainViewModel, padding: PaddingValues, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { exportBytes?.let { bytes -> context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: kotlin.error("Odredišnu datoteku nije moguće otvoriti.") } }
            .onFailure { error = it.message ?: "JSON izvoz nije uspio." }
        exportBytes = null
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runCatching { exportBytes?.let { bytes -> context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: kotlin.error("Odredišnu datoteku nije moguće otvoriti.") } }
            .onFailure { error = it.message ?: "CSV izvoz nije uspio." }
        exportBytes = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { viewModel.previewImport(it.readBytes()) } ?: kotlin.error("Datoteku nije moguće otvoriti.") } }
                .onSuccess { preview = it; error = null }
                .onFailure { error = it.message ?: "Uvoz nije uspio." }
        }
    }
    SecondaryScreenScaffold("Sigurnosna kopija", padding, onBack) { inner ->
    Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Izvoz ostaje lokalno na uređaju", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button({ scope.launch { runCatching { viewModel.exportJson() }.onSuccess { exportBytes = it; error = null; jsonLauncher.launch("smocnica-backup.json") }.onFailure { error = it.message ?: "JSON izvoz nije uspio." } } }, Modifier.fillMaxWidth()) { Text("Izvezi JSON") }
        OutlinedButton({ scope.launch { runCatching { viewModel.exportCsv() }.onSuccess { exportBytes = it; error = null; csvLauncher.launch("smocnica-zalihe.csv") }.onFailure { error = it.message ?: "CSV izvoz nije uspio." } } }, Modifier.fillMaxWidth()) { Text("Izvezi CSV za Excel") }
        OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/json")) }, Modifier.fillMaxWidth()) { Text("Uvezi JSON sigurnosnu kopiju") }
        Text("Uvoz uvijek prvo prikazuje pregled. Opcija „zamijeni” šalje postojeće zapise u koš prije atomarne sinkronizacije.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    }
    preview?.let { value ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Pregled uvoza") },
            text = { Column { Text(value.pantryName, fontWeight = FontWeight.Bold); Text("${value.shelfCount} polica · ${value.productCount} artikala · ${value.shoppingCount} stavki kupnje"); value.conflicts.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { Button({ viewModel.importBackup(value, ImportStrategy.MERGE); preview = null }, enabled = value.conflicts.isEmpty()) { Text("Spoji") } },
            dismissButton = { Row { TextButton({ preview = null }) { Text("Odustani") }; TextButton({ viewModel.importBackup(value, ImportStrategy.REPLACE); preview = null }, enabled = value.conflicts.isEmpty()) { Text("Zamijeni") } } },
        )
    }
}

@Composable
fun UpdateScreen(viewModel: MainViewModel, padding: PaddingValues, onBack: (() -> Unit)?) {
    val context = LocalContext.current
    val update by viewModel.latestUpdate.collectAsStateWithLifecycle()
    var verifiedUri by remember { mutableStateOf<Uri?>(null) }
    LaunchedEffect(Unit) { viewModel.checkUpdate(BuildConfig.VERSION_CODE.toLong()) }
    LaunchedEffect(Unit) { viewModel.verifiedApk.collect { verifiedUri = Uri.parse(it.contentUri) } }
    SecondaryScreenScaffold("Ažuriranje aplikacije", padding, onBack) { inner ->
    Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Instalirana verzija ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (update == null) Text("Nema novijeg provjerenog izdanja ili GitHub Releases još nije konfiguriran.")
        update?.let { available ->
            Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Verzija ${available.versionName}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (available.isMandatory(BuildConfig.VERSION_CODE.toLong())) Text("Obavezno ažuriranje", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    Text(available.releaseNotes.ifBlank { "Nema bilješki izdanja." })
                    if (available.sizeBytes > 0) Text("Veličina: ${available.sizeBytes / 1024 / 1024} MiB")
                    Button({ viewModel.downloadUpdate(available) }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Download, null); Text("Preuzmi najnoviju verziju", Modifier.padding(start = 8.dp)) }
                }
            }
        }
        verifiedUri?.let { uri ->
            Button({ installVerifiedApk(context, uri) }, Modifier.fillMaxWidth()) { Text("Otvori Android potvrdu instalacije") }
            Text("SHA-256 i certifikat potpisnika uspješno su provjereni.", color = MaterialTheme.colorScheme.primary)
        }
        OutlinedButton({ viewModel.checkUpdate(BuildConfig.VERSION_CODE.toLong()) }, Modifier.fillMaxWidth()) { Text("Provjeri ponovno") }
    }
    }
}

private fun installVerifiedApk(context: android.content.Context, uri: Uri) {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        return
    }
    context.startActivity(Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}

@Composable
internal fun ConfirmDialog(title: String, body: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(body) }, confirmButton = { Button(confirm) { Text("Potvrdi") } }, dismissButton = { TextButton(dismiss) { Text("Odustani") } })
}

private fun qrBitmap(value: String): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 512, 512)
    return Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
        for (x in 0 until 512) for (y in 0 until 512) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
}

private fun formatDate(value: Long): String = SimpleDateFormat("dd. MM. yyyy. HH:mm", Locale.forLanguageTag("hr-HR")).format(Date(value))
