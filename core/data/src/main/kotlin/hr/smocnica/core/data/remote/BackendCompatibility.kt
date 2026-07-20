package hr.smocnica.core.data.remote

import android.content.Context
import com.google.firebase.functions.FirebaseFunctionsException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BackendCompatibilityResult {
    data class Compatible(val usedCachedConfirmation: Boolean = false) : BackendCompatibilityResult
    data class Blocked(val message: String) : BackendCompatibilityResult
}

@Singleton
class BackendCompatibilityStore @Inject constructor(@param:ApplicationContext context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var confirmedApiVersion: Int
        get() = preferences.getInt(KEY_CONFIRMED_API_VERSION, 0)
        set(value) { preferences.edit().putInt(KEY_CONFIRMED_API_VERSION, value).apply() }

    private companion object {
        const val PREFERENCES = "backend_compatibility"
        const val KEY_CONFIRMED_API_VERSION = "confirmed_api_version"
    }
}

@Singleton
class BackendCompatibilityChecker internal constructor(
    private val client: FirebaseCallableClient,
    private val store: BackendCompatibilityStore,
    private val isTransientFailure: (Exception) -> Boolean,
) {
    @Inject constructor(
        client: FirebaseCallableClient,
        store: BackendCompatibilityStore,
    ) : this(client, store, Exception::isTransientBackendFailure)

    suspend fun check(): BackendCompatibilityResult = try {
        val response = client.call("getBackendCapabilities")
        evaluateBackendCapabilities(response).also { result ->
            if (result is BackendCompatibilityResult.Compatible) {
                store.confirmedApiVersion = response.apiVersion()
            }
        }
    } catch (error: Exception) {
        if (isTransientFailure(error) && store.confirmedApiVersion >= MIN_BACKEND_API_VERSION) {
            BackendCompatibilityResult.Compatible(usedCachedConfirmation = true)
        } else {
            BackendCompatibilityResult.Blocked(
                "Nije moguće potvrditi kompatibilnost poslužitelja. Provjerite internetsku vezu i pokušajte ponovno.",
            )
        }
    }

    internal companion object {
        const val MIN_BACKEND_API_VERSION = 3
        val REQUIRED_CAPABILITIES = setOf(
            "operation:delete_shopping",
            "device-registration:v2",
            "notification-privacy:v1",
            "single-active-pantry:v1",
        )
    }
}

internal fun evaluateBackendCapabilities(response: Map<String, Any?>): BackendCompatibilityResult {
    val version = response.apiVersion()
    if (version < BackendCompatibilityChecker.MIN_BACKEND_API_VERSION) {
        return BackendCompatibilityResult.Blocked(
            "Poslužitelj aplikacije je zastario (API $version). Potrebna je verzija ${BackendCompatibilityChecker.MIN_BACKEND_API_VERSION} ili novija.",
        )
    }
    val capabilities = (response["capabilities"] as? List<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
    val missing = BackendCompatibilityChecker.REQUIRED_CAPABILITIES - capabilities
    return if (missing.isEmpty()) {
        BackendCompatibilityResult.Compatible()
    } else {
        BackendCompatibilityResult.Blocked(
            "Poslužitelju nedostaju obvezne mogućnosti: ${missing.sorted().joinToString()}.",
        )
    }
}

private fun Map<String, Any?>.apiVersion(): Int = (this["backendApiVersion"] as? Number)?.toInt() ?: 0

private fun Exception.isTransientBackendFailure(): Boolean {
    val code = (this as? FirebaseFunctionsException)?.code
    return code == FirebaseFunctionsException.Code.UNAVAILABLE ||
        code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
        code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
}
