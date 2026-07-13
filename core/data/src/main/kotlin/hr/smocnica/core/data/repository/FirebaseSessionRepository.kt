package hr.smocnica.core.data.repository

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import hr.smocnica.core.data.remote.FirebaseNotConfiguredException
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.domain.SessionRepository
import hr.smocnica.core.model.UserSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSessionRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: SmocnicaDatabase,
) : SessionRepository {
    override val session: Flow<UserSession?> = callbackFlow {
        if (FirebaseApp.getApps(context).isEmpty()) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { instance -> trySend(instance.currentUser?.session()) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    override suspend fun signInWithGoogleIdToken(idToken: String): Result<UserSession> = runCatching {
        if (FirebaseApp.getApps(context).isEmpty()) throw FirebaseNotConfiguredException()
        require(idToken.isNotBlank()) { "Google ID token nedostaje." }
        val result = FirebaseAuth.getInstance()
            .signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
            .await()
        result.user?.session() ?: error("Firebase nije vratio prijavljenog korisnika.")
    }

    override suspend fun signOut() {
        if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseAuth.getInstance().signOut()
        withContext(Dispatchers.IO) {
            database.clearAllTables()
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("product-image-") || it.name.startsWith("product-photo-") }
                ?.forEach { it.delete() }
        }
    }

    private fun com.google.firebase.auth.FirebaseUser.session() = UserSession(
        uid = uid,
        displayName = displayName.orEmpty().ifBlank { "Korisnik" },
        email = email.orEmpty(),
        photoUrl = photoUrl?.toString(),
    )
}
