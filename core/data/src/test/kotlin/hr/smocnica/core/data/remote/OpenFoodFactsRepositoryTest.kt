package hr.smocnica.core.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class OpenFoodFactsRepositoryTest {
    @Test
    fun partialResponseIsReturnedEvenWhenProductNameIsMissing() = runTest {
        val repository = OpenFoodFactsRepository(object : OpenFoodFactsApi {
            override suspend fun product(barcode: String, fields: String): OpenFoodFactsResponse =
                OpenFoodFactsResponse(
                    status = 1,
                    product = OpenFoodFactsProduct(
                        quantity = "500 ml",
                        categories = emptyList(),
                        imageUrl = "https://images.example/product.jpg",
                    ),
                )
        })

        val result = repository.findByBarcode("4006381333931")

        assertNotNull(result)
        assertEquals("", result?.name)
        assertEquals("500 ml", result?.description)
        assertEquals("", result?.category)
        assertEquals("https://images.example/product.jpg", result?.imageUrl)
    }
}
