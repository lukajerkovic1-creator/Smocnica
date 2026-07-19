package hr.smocnica.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import hr.smocnica.core.data.Clock
import hr.smocnica.core.data.IdGenerator
import hr.smocnica.core.data.local.SmocnicaDatabase
import hr.smocnica.core.data.local.MIGRATION_1_2
import hr.smocnica.core.data.remote.FirebaseOperationGateway
import hr.smocnica.core.data.remote.OpenFoodFactsApi
import hr.smocnica.core.data.remote.OpenFoodFactsRepository
import hr.smocnica.core.data.remote.OperationGateway
import hr.smocnica.core.data.repository.FirebasePantryRepository
import hr.smocnica.core.data.repository.FirebaseSessionRepository
import hr.smocnica.core.data.repository.FirebaseProductPhotoRepository
import hr.smocnica.core.data.repository.BackupRepositoryImpl
import hr.smocnica.core.data.repository.GitHubUpdateRepository
import hr.smocnica.core.data.repository.FirebaseTrashRepository
import hr.smocnica.core.data.repository.LocalInventoryRepository
import hr.smocnica.core.data.repository.OutboxSyncRepository
import hr.smocnica.core.domain.InventoryRepository
import hr.smocnica.core.domain.BackupRepository
import hr.smocnica.core.domain.PantryRepository
import hr.smocnica.core.domain.ProductCatalogRepository
import hr.smocnica.core.domain.ProductPhotoRepository
import hr.smocnica.core.domain.SessionRepository
import hr.smocnica.core.domain.SyncRepository
import hr.smocnica.core.domain.UpdateRepository
import hr.smocnica.core.domain.TrashRepository
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataProviders {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): SmocnicaDatabase =
        Room.databaseBuilder(context, SmocnicaDatabase::class.java, "smocnica.db")
            .addMigrations(MIGRATION_1_2)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        classDiscriminator = "type"
    }

    @Provides
    fun clock(): Clock = Clock(System::currentTimeMillis)

    @Provides
    fun ids(): IdGenerator = IdGenerator { UUID.randomUUID().toString() }

    @Provides
    @Singleton
    fun openFoodFactsApi(json: Json): OpenFoodFactsApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "SmocnicaAndroid/1.0 (https://github.com/smocnica-app/releases)")
                        .build(),
                )
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://world.openfoodfacts.org/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenFoodFactsApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds abstract fun inventory(implementation: LocalInventoryRepository): InventoryRepository
    @Binds abstract fun session(implementation: FirebaseSessionRepository): SessionRepository
    @Binds abstract fun pantry(implementation: FirebasePantryRepository): PantryRepository
    @Binds abstract fun sync(implementation: OutboxSyncRepository): SyncRepository
    @Binds abstract fun operationGateway(implementation: FirebaseOperationGateway): OperationGateway
    @Binds abstract fun backup(implementation: BackupRepositoryImpl): BackupRepository
    @Binds abstract fun updates(implementation: GitHubUpdateRepository): UpdateRepository
    @Binds abstract fun trash(implementation: FirebaseTrashRepository): TrashRepository
    @Binds abstract fun catalog(implementation: OpenFoodFactsRepository): ProductCatalogRepository
    @Binds abstract fun photos(implementation: FirebaseProductPhotoRepository): ProductPhotoRepository
}
