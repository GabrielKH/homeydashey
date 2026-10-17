package app.homeydash.fridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** What the internet AI found out about a barcode: the name, and how long it keeps. */
data class Researched(
    val product: Product,
    val category: String?,
    /** Days it keeps unopened in its usual place, from the day it is bought. */
    val keepsDays: Int? = null,
    /** Days it keeps once opened. */
    val openedDays: Int? = null,
    val useBy: Boolean? = null,
)

/**
 * Asks the internet AI (with its web search on) what a barcode is, the first time no database
 * knows it, and how long that product keeps. [ask] sends one prompt and returns the answer,
 * or null when no internet AI is set up.
 */
class ProductResearch(private val ask: suspend (String) -> String?) {
    suspend fun research(gtin: String, partial: Product?): Researched? {
        val answer = ask(prompt(gtin, partial)) ?: return null
        return parse(answer)
    }

    companion object {
        private val Lenient = Json { ignoreUnknownKeys = true; isLenient = true }

        fun prompt(gtin: String, partial: Product?): String = buildString {
            append("Identify the grocery product with barcode (EAN/GTIN) $gtin, bought in a Swedish shop. ")
            append("Search the web: GS1 Sweden ProductSearch (productsearch.gs1.se), ICA, Coop, Willys, Hemköp, Lidl, Mathem and Open Food Facts are good sources. ")
            partial?.let { p ->
                append("A database says it may be: ").append(p.name)
                p.brand?.let { append(" by ").append(it) }
                append(". ")
            }
            append("Then say how long that product typically keeps in a home fridge (or pantry or freezer if that is where it belongs).\n")
            append("Reply with only one line of JSON and nothing else, like ")
            append("""{"name":"Mellanmjölk 1,5%","brand":"Arla","size":"1 l","kind":"milk","keeps_days":10,"opened_days":5,"use_by":false}""")
            append(". name is the Swedish product name as printed on the pack, without the brand. ")
            append("kind is one of: ").append(FoodRules.all.joinToString(", ") { it.key }).append(". ")
            append("keeps_days counts from the day it is bought, unopened; opened_days once opened; ")
            append("use_by is true when the pack says sista förbrukningsdag (meat, fish, poultry, ready meals) rather than bäst före. ")
            append("""If you can't find the product, reply {"name":null}.""")
        }

        /** Reads the AI's JSON answer, tolerating text or code fences around it. */
        fun parse(answer: String): Researched? {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            val o = runCatching { Lenient.parseToJsonElement(answer.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
            fun str(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
            fun int(key: String) = (o[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }?.takeIf { it in 0..3650 }
            val name = str("name") ?: return null
            val kind = str("kind")?.let { k -> FoodRules.all.firstOrNull { it.key == k }?.key ?: FoodRules.find(k)?.key }
            return Researched(
                product = Product(name = name, brand = str("brand"), amount = str("size")),
                category = kind,
                keepsDays = int("keeps_days"),
                openedDays = int("opened_days"),
                useBy = (o["use_by"] as? JsonPrimitive)?.booleanOrNull,
            )
        }
    }
}

/**
 * UPCitemdb's free lookup (about 100 a day, no key). It knows many international brands
 * but few Swedish store products, so it comes after Open Food Facts.
 */
class UpcItemDb(private val http: OkHttpClient) : ProductSource {
    override suspend fun find(gtin: String): Product? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.upcitemdb.com/prod/trial/lookup?upc=$gtin")
            .header("User-Agent", OpenFoodFacts.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404 || response.code == 400) return@withContext null
            if (!response.isSuccessful) throw IOException("UPCitemdb answered ${response.code}.")
            parse(response.body?.string() ?: return@withContext null)
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): Product? {
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
            val item = (root["items"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            fun str(key: String) = (item[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val title = str("title") ?: return null
            return Product(name = title, brand = str("brand"), amount = str("size"), tags = emptyList())
        }
    }
}
