package app.homeydash.fridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** A product found from its barcode. */
data class Product(
    val name: String,
    val brand: String? = null,
    val amount: String? = null,
    /** Open Food Facts category tags, general to specific. */
    val tags: List<String> = emptyList(),
    val imageUrl: String? = null,
)

/** Finds products by barcode; null when the barcode isn't known. */
fun interface ProductSource {
    suspend fun find(gtin: String): Product?
}

/**
 * Open Food Facts, the open product database (ODbL). Free and without a key, but limited to
 * about 15 product reads a minute and it asks apps to say who they are.
 */
class OpenFoodFacts(
    private val http: OkHttpClient,
    private val base: String = "https://world.openfoodfacts.org",
) : ProductSource {
    override suspend fun find(gtin: String): Product? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/api/v2/product/$gtin.json?fields=$FIELDS")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("Open Food Facts answered ${response.code}.")
            parse(response.body?.string() ?: return@withContext null)
        }
    }

    companion object {
        private const val FIELDS = "code,product_name,product_name_sv,product_name_en,generic_name_sv,generic_name,brands,quantity,categories_tags,image_front_small_url"
        const val USER_AGENT = "HomeyDashboard/0.2 (Android tablet; https://github.com/GabrielKH/homeydashey)"
        private val json = Json { ignoreUnknownKeys = true }

        /** Reads an API v2 product answer; null when the product isn't in the database. */
        fun parse(body: String): Product? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            if ((root["status"] as? JsonPrimitive)?.intOrNull == 0) return null
            val p = root["product"] as? JsonObject ?: return null
            fun str(key: String) = (p[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val name = str("product_name_sv") ?: str("product_name") ?: str("generic_name_sv") ?: str("product_name_en") ?: str("generic_name")
                ?: return null
            val brand = str("brands")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            val tags = (p["categories_tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            return Product(name = name, brand = brand, amount = str("quantity"), tags = tags, imageUrl = str("image_front_small_url"))
        }
    }
}
