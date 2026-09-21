package com.github.orbshop.shop

import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import com.google.gson.JsonElement
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import java.math.BigDecimal

class ShopApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * Talks to Discord's Shop / Orbs endpoints.
 *
 * Responses are parsed manually from JsonObject instead of mapped onto Kotlin data classes:
 * Gson bypasses Kotlin null-safety, and the shop payloads are big and change often, so
 * tolerant parsing is much less likely to crash when Discord tweaks a field.
 */
object ShopApi {
    private const val CDN = "https://cdn.discordapp.com/"

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    fun fetchCatalog(): ShopCatalog {
        val roots = mutableListOf<JsonElement>()
        var lastError: Exception? = null

        val primary = listOf(
            "/collectibles-shop?tab=catalog&include_bundles=true&variants_return_style=1",
            "/collectibles-shop?tab=orbs&include_bundles=true&variants_return_style=1"
        )
        for (path in primary) {
            try {
                roots += request(path)
            } catch (e: Exception) {
                lastError = e
            }
        }

        if (roots.isEmpty()) {
            val fallbacks = listOf(
                "/collectibles-categories/v2?include_bundles=true&variants_return_style=1",
                "/collectibles-categories?include_bundles=true&variants_return_style=1"
            )
            for (path in fallbacks) {
                try {
                    roots += request(path)
                    break
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }

        if (roots.isEmpty()) throw lastError ?: ShopApiException(0, "Could not load the shop")

        val catalog = parseCatalog(roots)
        if (catalog.items.isEmpty()) {
            throw ShopApiException(0, "The shop response did not contain any items")
        }
        return catalog
    }

    fun fetchBalance(): Long {
        val el = request("/users/@me/virtual-currency/balance")
        val obj = el.asObjectOrNull() ?: throw ShopApiException(0, "Unexpected balance response")
        return obj.long("balance") ?: obj.long("amount")
            ?: throw ShopApiException(0, "Balance missing from response")
    }

    /** SKU ids of everything the user already owns (including items inside owned bundles). */
    fun fetchOwned(): Set<String> {
        val el = request("/users/@me/collectibles-purchases?variants_return_style=1")
        val list: JsonArray? = when {
            el.isJsonArray -> el.asJsonArray
            el.isJsonObject -> el.asJsonObject.let {
                it.arr("purchases") ?: it.arr("products") ?: it.arr("collectibles")
            }
            else -> null
        }
        val owned = HashSet<String>()
        list?.forEach { entry ->
            val product = entry.asObjectOrNull() ?: return@forEach
            product.str("sku_id")?.let { owned += it }
            product.arr("items")?.forEach { item ->
                item.asObjectOrNull()?.str("sku_id")?.let { owned += it }
            }
        }
        return owned
    }

    /**
     * Spends Orbs on a SKU.
     *
     * NOTE: this is the one call whose request body could not be verified against docs.
     * If Discord rejects it, the server's error message is shown to the user; adjust the
     * body below to match whatever the error asks for.
     */
    fun redeem(item: ShopItem, expectedPrice: Long) {
        val body = mapOf(
            "expected_amount" to expectedPrice,
            "expected_currency" to "discord_orb"
        )
        request(
            "/virtual-currency/skus/${item.skuId}/redeem",
            method = "POST",
            body = body,
            allowEmpty = true
        )
    }

    // ---------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------

    private fun request(
        path: String,
        method: String = "GET",
        body: Any? = null,
        allowEmpty: Boolean = false
    ): JsonElement {
        val req = Http.Request.newDiscordRNRequest(path, method)
        val response =
            if (body != null) req.executeWithJson(GsonUtils.gsonRestApi, body) else req.execute()
        return response.use { r ->
            if (!r.ok()) throw ShopApiException(r.statusCode, errorMessage(r))
            try {
                r.json(GsonUtils.gsonRestApi, JsonElement::class.java) ?: JsonNull.INSTANCE
            } catch (e: Exception) {
                if (allowEmpty) JsonNull.INSTANCE else throw e
            }
        }
    }

    private fun errorMessage(response: Http.Response): String {
        val httpException = runCatching { response.assertOk() }.exceptionOrNull()
        val errorBody = httpException?.message?.substringAfter('\n', "").orEmpty()
        val parsed = runCatching {
            GsonUtils.fromJson(errorBody, JsonObject::class.java)
        }.getOrNull()
        return parsed?.str("message") ?: "Discord returned HTTP ${response.statusCode}"
    }

    // ---------------------------------------------------------------------------------------
    // Parsing
    // ---------------------------------------------------------------------------------------

    private data class PricePoint(
        val currency: String,
        val amount: Long,
        val exponent: Int,
        val purchaseType: String?
    )

    private fun parseCatalog(roots: List<JsonElement>): ShopCatalog {
        val items = LinkedHashMap<String, ShopItem>()
        val categories = LinkedHashMap<String, ShopCategory>()
        var order = 0

        for (root in roots) {
            val cats: JsonArray = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.arr("categories")
                else -> null
            } ?: continue

            for (catEl in cats) {
                val cat = catEl.asObjectOrNull() ?: continue
                val catId = cat.str("sku_id") ?: cat.str("id") ?: continue
                val catName = cat.str("name") ?: "Collection"
                val logo = cat.str("logo_url")?.let(::absoluteUrl)
                val exclusive = cat.bool("is_orbs_exclusive")
                if (catId !in categories) categories[catId] = ShopCategory(catId, catName)

                val ranking = cat.arr("hero_ranking")
                    ?.mapNotNull { (it as? com.google.gson.JsonPrimitive)?.asString }
                    .orEmpty()

                val products = cat.arr("products")
                    ?.mapNotNull { it.asObjectOrNull() }
                    .orEmpty()
                    .sortedBy { p ->
                        val idx = ranking.indexOf(p.str("sku_id"))
                        if (idx < 0) Int.MAX_VALUE else idx
                    }

                for (product in products) {
                    for (variant in expand(product)) {
                        val item = parseProduct(
                            variant, product.str("name"), catId, catName, logo, exclusive, order
                        ) ?: continue
                        val existing = items[item.skuId]
                        if (existing == null || (existing.orbPrice == null && item.orbPrice != null)) {
                            items[item.skuId] = item.copy(order = existing?.order ?: order)
                        }
                        order++
                    }
                }
            }
        }
        return ShopCatalog(items.values.sortedBy { it.order }, categories.values.toList())
    }

    private fun expand(product: JsonObject): List<JsonObject> {
        val variants = product.arr("variants")?.mapNotNull { it.asObjectOrNull() }.orEmpty()
        return if (variants.isEmpty()) listOf(product) else variants
    }

    private fun parseProduct(
        p: JsonObject,
        parentName: String?,
        catId: String,
        catName: String,
        catLogo: String?,
        catExclusive: Boolean,
        order: Int
    ): ShopItem? {
        val sku = p.str("sku_id") ?: return null
        val name = p.str("name") ?: parentName ?: return null

        val firstItem = p.arr("items")?.firstOrNull()?.asObjectOrNull()
        val type = ItemType.fromCode(p.long("type")?.toInt())
            ?: ItemType.fromCode(firstItem?.long("type")?.toInt())
            ?: ItemType.OTHER

        // Collect every price found under keys like "prices" / "price".
        val prices = ArrayList<PricePoint>()
        for ((key, value) in p.entrySet()) {
            if (key.contains("price", ignoreCase = true)) collectPrices(value, null, prices)
        }
        val orbPoints = prices.filter { it.currency.contains("orb") }
        val orb = orbPoints.firstOrNull { it.purchaseType == "0" } ?: orbPoints.firstOrNull()
        val moneyPoints = prices.filter { !it.currency.contains("orb") }
        val money = moneyPoints.firstOrNull { it.purchaseType == "0" } ?: moneyPoints.firstOrNull()

        return ShopItem(
            skuId = sku,
            name = name,
            summary = p.str("summary").orEmpty(),
            type = type,
            categoryId = catId,
            categoryName = catName,
            orbPrice = orb?.let { scaled(it) },
            moneyPrice = money?.let {
                "${BigDecimal.valueOf(it.amount, it.exponent).toPlainString()} ${it.currency.uppercase()}"
            },
            imageUrl = findImage(p) ?: catLogo,
            orbsExclusive = catExclusive,
            order = order
        )
    }

    private fun scaled(p: PricePoint): Long =
        if (p.exponent <= 0) p.amount else BigDecimal.valueOf(p.amount, p.exponent).toLong()

    private fun collectPrices(el: JsonElement?, type: String?, out: MutableList<PricePoint>) {
        if (el == null) return
        if (el.isJsonArray) {
            el.asJsonArray.forEach { collectPrices(it, type, out) }
            return
        }
        val o = el.asObjectOrNull() ?: return
        val currency = o.str("currency")
        val amount = o.long("amount")
        if (currency != null && amount != null) {
            val exponent = (o.long("exponent") ?: o.long("currency_exponent") ?: 0L).toInt()
            out.add(PricePoint(currency.lowercase(), amount, exponent, type))
            return
        }
        // Keys of the top-level "prices" map are purchase types ("0" = default, ...).
        for ((k, v) in o.entrySet()) collectPrices(v, type ?: k, out)
    }

    private fun findImage(product: JsonObject, depth: Int = 0): String? {
        if (depth > 3) return null

        val item = product.arr("items")?.firstOrNull()?.asObjectOrNull()
        if (item != null) {
            val assets = item.obj("assets")
            val fromAssets = assets?.str("static_image_url") ?: assets?.str("animated_image_url")
            if (fromAssets != null) return absoluteUrl(fromAssets)

            val fromEffect = item.str("thumbnailPreviewSrc") ?: item.str("staticFrameSrc")
            if (fromEffect != null) return absoluteUrl(fromEffect)

            val decoAsset = item.str("asset")
            if (decoAsset != null && item.long("type") == 0L) {
                return "${CDN}avatar-decoration-presets/$decoAsset.png?size=160"
            }
        }

        val preview = product.obj("preview_assets")
        val fromPreview = preview?.str("fg_static") ?: preview?.str("bg_static")
        if (fromPreview != null) return absoluteUrl(fromPreview)

        for (key in listOf("bundled_products", "variants")) {
            product.arr(key)?.forEach { child ->
                val obj = child.asObjectOrNull() ?: return@forEach
                findImage(obj, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun absoluteUrl(u: String): String =
        if (u.startsWith("http")) u else CDN + u.trimStart('/')

    // ---------------------------------------------------------------------------------------
    // JSON helpers
    // ---------------------------------------------------------------------------------------

    private fun JsonElement.asObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.obj(key: String): JsonObject? = get(key)?.asObjectOrNull()

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.bool(key: String): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: false
}
