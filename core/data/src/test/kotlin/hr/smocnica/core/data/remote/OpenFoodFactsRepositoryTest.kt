package hr.smocnica.core.data.remote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                        imageUrl = "https://evil.example/product.jpg",
                    ),
                )
        })

        val result = repository.findByBarcode("4006381333931")

        assertNotNull(result)
        assertEquals("", result?.name)
        assertEquals("500 ml", result?.description)
        assertEquals("Ostalo", result?.category)
        assertNull(result?.imageUrl)
    }

    @Test
    fun mapsTaxonomyTagsToTheTenLocalCategoriesAndAllowsOnlyOfficialImages() = runTest {
        val repository = OpenFoodFactsRepository(object : OpenFoodFactsApi {
            override suspend fun product(barcode: String, fields: String) = OpenFoodFactsResponse(
                status = 1,
                product = OpenFoodFactsProduct(
                    name = "Čips",
                    categories = listOf("en:foods", "en:potato-chips"),
                    imageUrl = "https://images.openfoodfacts.org/images/products/400/638/133/3931/front_en.3.400.jpg",
                ),
            )
        })

        val result = repository.findByBarcode("4006381333931")

        assertEquals("Grickalice", result?.category)
        assertEquals("https://images.openfoodfacts.org/images/products/400/638/133/3931/front_en.3.400.jpg", result?.imageUrl)
        assertEquals("Mlijeko i jaja", mapOpenFoodFactsCategory(listOf("en:dairy-products")))
        assertEquals("Pića", mapOpenFoodFactsCategory(listOf("en:beverages")))
        assertEquals("Voće i povrće", mapOpenFoodFactsCategory(listOf("en:fruits")))
        assertNull(sanitizeOpenFoodFactsImageUrl("https://images.openfoodfacts.org.evil.example/images/products/a.jpg"))
        assertNull(sanitizeOpenFoodFactsImageUrl("http://images.openfoodfacts.org/images/products/a.jpg"))
    }
}
