package hr.smocnica.core.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import hr.smocnica.core.domain.SyncRepository
import java.util.concurrent.TimeUnit

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val syncRepository: SyncRepository,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching { syncRepository.synchronize() }
        .fold(
            onSuccess = { result -> if (result.failed > 0) Result.retry() else Result.success() },
            onFailure = { Result.retry() },
        )
}

object SyncScheduler {
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(
            "smocnica-immediate-sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build(),
        )
        manager.enqueueUniquePeriodicWork(
            "smocnica-periodic-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build(),
        )
    }
}

