package hr.smocnica.ui

import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.domain.ProductCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScannerLookupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun timeoutLeavesBarcodeAvailableForManualContinuation() = runTest(dispatcher) {
        val viewModel = ScannerLookupViewModel(object : ProductCatalogRepository {
            override suspend fun findByBarcode(barcode: String): CatalogProduct? {
                delay(9_000)
                return null
            }
        })

        viewModel.lookup("4006381333931")
        assertEquals(CatalogLookupOutcome.LOADING, viewModel.state.value.outcome)
        advanceTimeBy(8_001)
        runCurrent()

        assertEquals("4006381333931", viewModel.state.value.barcode)
        assertEquals(CatalogLookupOutcome.TIMEOUT, viewModel.state.value.outcome)
        viewModel.continueManually()
        assertEquals(CatalogLookupOutcome.IDLE, viewModel.state.value.outcome)
    }
}
