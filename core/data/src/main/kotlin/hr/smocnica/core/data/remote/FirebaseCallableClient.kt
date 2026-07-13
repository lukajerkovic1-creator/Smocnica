package hr.smocnica.core.data.remote

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

class FirebaseNotConfiguredException : IllegalStateException(
    "Firebase nije konfiguriran. Dodajte app/google-services.json prema README uputama.",
)

@Singleton
class FirebaseCallableClient @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun isConfigured(): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    @Suppress("UNCHECKED_CAST")
    suspend fun call(name: String, data: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        if (!isConfigured()) throw FirebaseNotConfiguredException()
        val result = FirebaseFunctions.getInstance(REGION).getHttpsCallable(name).call(data).await().data
        return result as? Map<String, Any?> ?: error("Neispravan odgovor funkcije $name.")
    }

    private companion object { const val REGION = "europe-west1" }
}
