package hr.smocnica.core.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivacySafeCrashReporter @Inject constructor(@param:ApplicationContext private val context: Context) {
    fun record(code: TechnicalErrorCode, throwable: Throwable? = null) {
        if (FirebaseApp.getApps(context).isEmpty()) return
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCustomKey("technical_error_code", code.name)
        crashlytics.recordException(PrivacySafeException(code.name, throwable?.javaClass?.simpleName))
    }
}

enum class TechnicalErrorCode {
    SYNC_TRANSPORT,
    SYNC_CONFLICT,
    FIREBASE_CONFIGURATION,
    PHOTO_UPLOAD,
    UPDATE_VERIFICATION,
    IMPORT_VALIDATION,
}

private class PrivacySafeException(code: String, causeType: String?) :
    RuntimeException("$code:${causeType ?: "UNKNOWN"}")
