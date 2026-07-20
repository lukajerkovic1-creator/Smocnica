package hr.smocnica.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import hr.smocnica.MainViewModel
import hr.smocnica.BackendReadiness
import hr.smocnica.ui.theme.ThemeMode

@Composable
fun SmocnicaApp(
    requestedRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val pantries by viewModel.pantries.collectAsStateWithLifecycle()
    val restoringPantries by viewModel.isRestoringPantries.collectAsStateWithLifecycle()
    val backendReadiness by viewModel.backendReadiness.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxSize()) {
        when {
            session == null -> LoginScreen(
                onGoogleSignIn = {
                    runCatching { googleIdToken(context) }
                        .onSuccess(viewModel::signIn)
                        .onFailure { snackbar.showSnackbar(it.message ?: "Google prijava nije uspjela.") }
                },
            )
            backendReadiness is BackendReadiness.Blocked -> BackendCompatibilityScreen(
                message = (backendReadiness as BackendReadiness.Blocked).message,
                onRetry = viewModel::retryBackendCompatibility,
            )
            restoringPantries -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            pantries.isEmpty() -> OnboardingScreen(
                viewModel.deviceIdentity.displayName,
                viewModel::createPantry,
                viewModel::joinPantry,
                viewModel::refreshPantries,
                viewModel::signOut,
            )
            else -> MainNavigation(viewModel, requestedRoute, onRouteConsumed, themeMode, onThemeModeChange)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun BackendCompatibilityScreen(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Poslužitelj treba ažurirati",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(message, textAlign = TextAlign.Center)
            Text(
                text = "Podaci na uređaju nisu promijenjeni. Nakon objave kompatibilnog backenda pokušajte ponovno.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) { Text("Pokušaj ponovno") }
        }
    }
}

@android.annotation.SuppressLint("DiscouragedApi")
private suspend fun googleIdToken(context: Context): String {
    val resourceId = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    require(resourceId != 0) { "Google Sign-In nije konfiguriran. Slijedite README upute." }
    val clientId = context.getString(resourceId)
    require(clientId.isNotBlank()) { "Google web client ID nije konfiguriran." }
    val option = GetGoogleIdOption.Builder()
        .setServerClientId(clientId)
        .setFilterByAuthorizedAccounts(false)
        .setAutoSelectEnabled(false)
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = try {
        CredentialManager.create(context).getCredential(context, request).credential
    } catch (_: NoCredentialException) {
        error("Na uređaju nije dostupan Google račun. Dodajte račun ili pokušajte ponovno.")
    }
    require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        "Google nije vratio podržanu vjerodajnicu."
    }
    return GoogleIdTokenCredential.createFrom(credential.data).idToken
}
