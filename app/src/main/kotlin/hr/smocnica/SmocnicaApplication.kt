package hr.smocnica

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage
import dagger.hilt.android.HiltAndroidApp
import hr.smocnica.core.data.sync.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class SmocnicaApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isNotEmpty()) {
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                appCheckProviderFactory(),
            )
            FirebaseFirestore.getInstance().firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
            if (BuildConfig.FIREBASE_EMULATORS) {
                FirebaseAuth.getInstance().useEmulator("10.0.2.2", 9099)
                FirebaseFirestore.getInstance().useEmulator("10.0.2.2", 8080)
                FirebaseFunctions.getInstance("europe-west1").useEmulator("10.0.2.2", 5001)
                FirebaseStorage.getInstance().useEmulator("10.0.2.2", 9199)
            }
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = !BuildConfig.DEBUG
            SyncScheduler.schedule(this)
        }
    }
}
