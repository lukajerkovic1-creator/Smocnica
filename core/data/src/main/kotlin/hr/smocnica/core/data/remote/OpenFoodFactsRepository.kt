package hr.smocnica.core.data.remote

import hr.smocnica.core.domain.BarcodePolicy
import hr.smocnica.core.domain.CatalogProduct
import hr.smocnica.core.domain.ProductCatalogRepository
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton
import java.net.URI

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
            ?: ""
        val category = mapOpenFoodFactsCategory(product.categories)
        return CatalogProduct(
            barcode = normalized,
            name = name,
            description = product.quantity.orEmpty(),
            category = category,
            imageUrl = sanitizeOpenFoodFactsImageUrl(product.imageUrl),
        )
    }
}

internal fun mapOpenFoodFactsCategory(tags: List<String>): String {
    fun categoryFor(tag: String): String? {
        val value = tag.substringAfter(':').lowercase().replace('-', ' ').replace('_', ' ')
        return when {
            listOf("household", "cleaning", "detergent").any(value::contains) -> "Kućne potrepštine"
            listOf("snack", "chip", "crisp", "chocolate", "candy", "confection", "biscuit", "cookie", "cracker").any(value::contains) -> "Grickalice"
            listOf("beverage", "drink", "juice", "water", "soda", "tea", "coffee", "beer", "wine").any(value::contains) -> "Pića"
            listOf("spice", "seasoning", "condiment", "salt", "pepper", "herb", "sauce").any(value::contains) -> "Začini"
            listOf("canned", "tinned", "preserve").any(value::contains) -> "Konzerve"
            listOf("pasta", "rice", "bread", "flour", "noodle", "cereal", "grain").any(value::contains) -> "Žitarice i tjestenina"
            listOf("meat", "fish", "seafood", "poultry", "sausage", "charcuterie").any(value::contains) -> "Meso i riba"
            listOf("milk", "dairy", "cheese", "yogurt", "yoghurt", "egg").any(value::contains) -> "Mlijeko i jaja"
            listOf("fruit", "vegetable", "legume", "pulse").any(value::contains) -> "Voće i povrće"
            else -> null
        }
    }
    return tags.asReversed().firstNotNullOfOrNull(::categoryFor) ?: "Ostalo"
}

internal fun sanitizeOpenFoodFactsImageUrl(raw: String?): String? {
    if (raw.isNullOrBlank() || raw.length > 2048) return null
    return runCatching {
        val uri = URI(raw)
        raw.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("images.openfoodfacts.org", ignoreCase = true) &&
                uri.userInfo == null && uri.port in listOf(-1, 443) &&
                uri.path.startsWith("/images/products/")
        }
    }.getOrNull()
}
