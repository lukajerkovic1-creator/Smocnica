package hr.smocnica

import androidx.lifecycle.viewModelScope
import hr.smocnica.core.data.DeviceIdentity
import hr.smocnica.core.data.messaging.DeviceRegistration
import hr.smocnica.core.data.messaging.NotificationPrivacyMode
import hr.smocnica.core.data.remote.BackendCompatibilityChecker
import hr.smocnica.core.data.remote.BackendCompatibilityResult
import hr.smocnica.core.domain.*
import hr.smocnica.core.model.*
import hr.smocnica.ui.ProductEditorSubmission
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainViewModelPhotoTest {
    private val dispatcher = StandardTestDispatcher()
    private val inventory = mockk<InventoryRepository>(relaxed = true)
    private val photos = mockk<ProductPhotoRepository>()
    private val sync = mockk<SyncRepository>(relaxed = true)
    private val product = Product("product", "pantry", "Brašno", createdAt = 1, updatedAt = 1)
    private val variant = ProductVariant("variant", "pantry", "product", "Glatko brašno", createdAt = 1, updatedAt = 1)
    private lateinit var viewModel: MainViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        val sessions = mockk<SessionRepository>()
        every { sessions.session } returns MutableStateFlow(UserSession("user", "Test", "test@example.invalid"))
        val pantries = mockk<PantryRepository>(relaxed = true)
        every { pantries.observePantries() } returns MutableStateFlow(listOf(Pantry("pantry", "Test", "user", createdAt = 1, updatedAt = 1)))
        val checker = mockk<BackendCompatibilityChecker>()
        coEvery { checker.check() } returns BackendCompatibilityResult.Compatible()
        val devices = mockk<DeviceRegistration>(relaxed = true)
        every { devices.notificationPrivacyMode } returns NotificationPrivacyMode.PRIVATE
        val identity = mockk<DeviceIdentity>()
        every { identity.displayName } returns "Test device"
        coEvery { sync.synchronize() } returns SyncResult(0, 0, 0)
        coEvery { inventory.upsertProduct(any(), any(), any()) } returns product
        coEvery { inventory.upsertVariant(any(), any(), any()) } coAnswers { firstArg() }
        coEvery { photos.uploadJpeg("pantry", "variant", "selected.jpg") } returns "gs://test/variants/variant/main.jpg"
        viewModel = MainViewModel(sessions, pantries, inventory, sync, mockk(), mockk(), mockk(), photos, mockk(), devices, checker, identity)
    }

    @After fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test fun selectedPhotoIsUploadedAndSavedOnTheSameStockedVariant() = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.selectedPantry.collect {} }
        runCurrent()
        var saved = false
        viewModel.createProductAndStock(ProductEditorSubmission(product, variant), "shelf", 1,
            "selected.jpg", PhotoSource.CAMERA, onCreated = { saved = true })
        runCurrent()

        assertTrue(saved)
        coVerify { inventory.adjustVariantStock("variant", "shelf", 1, "user", "Test device") }
        coVerify { inventory.upsertVariant(match { it.id == "variant" && it.productId == "product" &&
            it.photoUri == "gs://test/variants/variant/main.jpg" && it.photoSource == PhotoSource.CAMERA }, "user", "Test device") }
    }

    @Test fun newProductUsesItsCanonicalFirstVariantForStockAndPhoto() = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.selectedPantry.collect {} }
        runCurrent()
        coEvery { inventory.upsertProduct(any(), any(), any(), any()) } returns product.copy(preferredVariantId = "first")
        coEvery { photos.uploadJpeg("pantry", "first", "selected.jpg") } returns "gs://test/variants/first/main.jpg"
        viewModel.createProductAndStock(ProductEditorSubmission(product.copy(id = ""), variant.copy(id = "")), "shelf", 1,
            "selected.jpg", PhotoSource.CAMERA)
        runCurrent()
        coVerify { inventory.upsertProduct(match { it.id.isBlank() }, "user", "Test device", match { it.id.isBlank() }) }
        coVerify { inventory.adjustVariantStock("first", "shelf", 1, "user", "Test device") }
        coVerify(exactly = 1) { inventory.upsertVariant(match { it.id == "first" && it.photoUri == "gs://test/variants/first/main.jpg" }, any(), any()) }
        coVerify(exactly = 0) { inventory.upsertVariant(match { it.id.isBlank() }, any(), any()) }
    }

    @Test fun manualNewProductDoesNotCreateASecondVariant() = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.selectedPantry.collect {} }
        runCurrent()
        coEvery { inventory.upsertProduct(any(), any(), any(), any()) } returns product.copy(preferredVariantId = "first")
        viewModel.saveProduct(ProductEditorSubmission(product.copy(id = ""), variant.copy(id = "")))
        runCurrent()
        coVerify(exactly = 1) { inventory.upsertProduct(any(), any(), any(), match { it.id.isBlank() }) }
        coVerify(exactly = 0) { inventory.upsertVariant(any(), any(), any()) }
    }

    @Test fun failedInitialSyncDoesNotClaimPhotoWasSaved() = runTest(dispatcher) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.selectedPantry.collect {} }
        runCurrent()
        coEvery { sync.synchronize() } returns SyncResult(0, 0, 1)
        var saved = false
        var failed = false
        viewModel.createProductAndStock(ProductEditorSubmission(product, variant), "shelf", 1,
            "selected.jpg", PhotoSource.CAMERA, onCreated = { saved = true }, onFailure = { failed = true })
        runCurrent()
        assertFalse(saved)
        assertTrue(failed)
        coVerify(exactly = 0) { photos.uploadJpeg(any(), any(), any()) }
    }
}
