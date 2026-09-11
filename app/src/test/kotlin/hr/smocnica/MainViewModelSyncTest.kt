package hr.smocnica

import androidx.lifecycle.viewModelScope
import hr.smocnica.core.data.messaging.DeviceRegistration
import hr.smocnica.core.data.messaging.NotificationPrivacyMode
import hr.smocnica.core.data.remote.BackendCompatibilityChecker
import hr.smocnica.core.data.remote.BackendCompatibilityResult
import hr.smocnica.core.domain.PantryRepository
import hr.smocnica.core.domain.SessionRepository
import hr.smocnica.core.domain.SyncRepository
import hr.smocnica.core.domain.SyncResult
import hr.smocnica.core.model.Pantry
import hr.smocnica.core.model.UserSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelSyncTest {
    private val dispatcher = StandardTestDispatcher()
    private val pantry = Pantry("p1", "Test", "u1", createdAt = 1, updatedAt = 1)
    private val cachedPantries = MutableStateFlow(listOf(pantry))
    private val sessions = mockk<SessionRepository>()
    private val pantries = mockk<PantryRepository>()
    private val sync = mockk<SyncRepository>(relaxed = true)
    private val checker = mockk<BackendCompatibilityChecker>()
    private val devices = mockk<DeviceRegistration>(relaxed = true)
    private lateinit var viewModel: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { sessions.session } returns MutableStateFlow(UserSession("u1", "Test", "test@example.invalid"))
        every { pantries.observePantries() } returns cachedPantries
        // Refreshing an unchanged pantry does not emit another StateFlow value.
        coEvery { pantries.refreshPantries() } answers { cachedPantries.value = listOf(pantry) }
        coEvery { sync.synchronize() } returns SyncResult(0, 0, 0)
        every { devices.notificationPrivacyMode } returns NotificationPrivacyMode.PRIVATE
    }

    @After fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun createViewModel() {
        viewModel = MainViewModel(
            sessions, pantries, mockk(relaxed = true), sync,
            mockk(), mockk(), mockk(), mockk(), mockk(), devices, checker, mockk(),
        )
    }

    @Test fun cachedPantryStartsReceivingAfterHandshakeWithoutAnotherPantryEmission() = runTest(dispatcher) {
        val handshake = CompletableDeferred<BackendCompatibilityResult>()
        coEvery { checker.check() } coAnswers { handshake.await() }
        createViewModel()
        runCurrent()
        verify(exactly = 0) { sync.startRealtime(any()) }

        handshake.complete(BackendCompatibilityResult.Compatible())
        runCurrent()

        verify(exactly = 1) { sync.startRealtime("p1") }
        coVerify(exactly = 1) { sync.synchronize() }
    }

    @Test fun blockedBackendCannotStartReceivingButRetryRestoresCachedPantry() = runTest(dispatcher) {
        coEvery { checker.check() } returnsMany listOf(
            BackendCompatibilityResult.Blocked("Test block"), BackendCompatibilityResult.Compatible(),
        )
        createViewModel()
        runCurrent()
        verify(exactly = 0) { sync.startRealtime(any()) }

        viewModel.retryBackendCompatibility()
        runCurrent()
        verify(exactly = 1) { sync.startRealtime("p1") }
    }

    @Test fun readinessRetryRestartsReceivingForAlreadySelectedPantry() = runTest(dispatcher) {
        coEvery { checker.check() } returnsMany listOf(
            BackendCompatibilityResult.Compatible(), BackendCompatibilityResult.Blocked("Test block"),
            BackendCompatibilityResult.Compatible(),
        )
        createViewModel()
        runCurrent()
        viewModel.selectPantry("p1")
        runCurrent()
        viewModel.retryBackendCompatibility()
        runCurrent()
        verify(atLeast = 1) { sync.stopRealtime() }
        io.mockk.clearMocks(sync, answers = false)
        viewModel.retryBackendCompatibility()
        runCurrent()
        verify(exactly = 1) { sync.startRealtime("p1") }
    }
}
