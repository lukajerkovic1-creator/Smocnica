package hr.smocnica.core.data.remote

import hr.smocnica.core.domain.BarcodePolicy
import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.domain.ProductCatalogRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OpenFoodFactsResponse(
    val status: Int = 0,
    val product: OpenFoodFactsProduct? = null,
)

@Serializable
data class OpenFoodFactsProduct(
    @SerialName("product_name_hr") val croatianName: String? = null,
    @SerialName("product_name") val name: String? = null,
    val quantity: String? = null,
    @SerialName("categories_tags") val categories: List<String> = emptyList(),
    @SerialName("image_front_url") val imageUrl: String? = null,
)

interface OpenFoodFactsApi {
    @retrofit2.http.GET("api/v2/product/{barcode}.json")
    suspend fun product(
        @retrofit2.http.Path("barcode") barcode: String,
        @retrofit2.http.Query("fields") fields: String = "product_name_hr,product_name,quantity,categories_tags,image_front_url",
    ): OpenFoodFactsResponse
}

@Singleton
class OpenFoodFactsRepository @Inject constructor(private val api: OpenFoodFactsApi) : ProductCatalogRepository {
    override suspend fun findByBarcode(barcode: String): CatalogProduct? {
        val normalized = BarcodePolicy.requireSupported(barcode)
        val response = api.product(normalized)
        val product = response.product.takeIf { response.status == 1 } ?: return null
        val name = product.croatianName?.trim().takeUnless { it.isNullOrBlank() }
            ?: product.name?.trim().takeUnless { it.isNullOrBlank() }
            ?: return null
        val category = product.categories.firstOrNull()
            ?.substringAfter(':')
            ?.replace('-', ' ')
            ?.replaceFirstChar(Char::titlecase)
            ?: "Ostalo"
        return CatalogProduct(
            barcode = normalized,
            name = name,
            description = product.quantity.orEmpty(),
            category = category,
            imageUrl = product.imageUrl,
        )
    }
}
