package com.github.orbshop.shop

import com.aliucord.Http
import com.aliucord.utils.GsonUtils
import com.aliucord.utils.SerializedName
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

class ShopApiException(val statusCode: Int, message: String) : Exception(message)

private data class RedeemRequest(
    @SerializedName("expected_amount") val expectedAmount: Long,
    @SerializedName("expected_currency") val expectedCurrency: String
)

/**
 * Talks to Discord's Shop / Orbs endpoints.
 *
 * Discord's bundled Gson is obfuscated, so its JsonElement API can't be used at compile time.
 * Responses are read through Gson as plain Maps/Lists (proven to work via GsonUtils, exactly
 * like the other plugins do) and then converted to android's built-in org.json types, which
 * are safe to use and tolerant of missing fields.
 */
object ShopApi {
    private const val CDN = "https://cdn.discordapp.com/"

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    fun fetchCatalog(): ShopCatalog {
        val roots = mutableListOf<Any>()
        var lastError: Exception? = null

        val primary = listOf(
            "/collectibles-shop?tab=catalog&include_bundles=true&variants_return_style=1",
            "/collectibles-shop?tab=orbs&include_bundles=true&variants_return_style=1"
        )
        for (path in primary) {
            try {
                request(path)?.let { roots.add(it) }
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
                    val res = request(path)
                    if (res != null) {
                        roots.add(res)
                        break
                    }
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
        val obj = request("/users/@me/virtual-currency/balance") as? JSONObject
            ?: throw ShopApiException(0, "Unexpected balance response")
        return obj.long("balance") ?: obj.long("amount")
            ?: throw ShopApiException(0, "Balance missing from response")
    }

    /** SKU ids of everything the user already owns (including items inside owned bundles). */
    fun fetchOwned(): Set<String> {
        val el = request("/users/@me/collectibles-purchases?variants_return_style=1")
        val list: JSONArray? = when (el) {
            is JSONArray -> el
            is JSONObject -> el.arr("purchases") ?: el.arr("products") ?: el.arr("collectibles")
            else -> null
        }
        val owned = HashSet<String>()
        if (list != null) {
            for (product in list.objects()) {
                product.str("sku_id")?.let { owned.add(it) }
                product.arr("items")?.objects()?.forEach { item ->
                    item.str("sku_id")?.let { owned.add(it) }
                }
            }
        }
        return owned
    }

    /**
     * Spends Orbs on a SKU.
     *
     * NOTE: this is the one call whose request body could not be verified against docs.
     * If Discord rejects it, the server's error message is shown to the user; adjust
     * [RedeemRequest] to match whatever the error asks for.
     */
    fun redeem(item: ShopItem, expectedPrice: Long) {
        request(
            "/virtual-currency/skus/${item.skuId}/redeem",
            method = "POST",
            body = RedeemRequest(expectedPrice, "discord_orb"),
            allowEmpty = true
        )
    }

    // ---------------------------------------------------------------------------------------
    // HTTP
    // ---------------------------------------------------------------------------------------

    /** Returns a JSONObject, a JSONArray, or null for an empty body. */
    private fun request(
        path: String,
        method: String = "GET",
        body: Any? = null,
        allowEmpty: Boolean = false
    ): Any? {
        val req = Http.Request.newDiscordRNRequest(path, method)
        val response =
            if (body != null) req.executeWithJson(GsonUtils.gsonRestApi, body) else req.execute()
        return response.use { r ->
            if (!r.ok()) throw ShopApiException(r.statusCode, errorMessage(r))
            try {
                val raw: Any? = r.json(GsonUtils.gsonRestApi, Any::class.java)
                toOrgJson(raw)
            } catch (e: Exception) {
                if (allowEmpty) null else throw e
            }
        }
    }

    private fun errorMessage(response: Http.Response): String {
        val httpException = runCatching { response.assertOk() }.exceptionOrNull()
        val errorBody = httpException?.message?.substringAfter('\n', "").orEmpty()
        val parsed = runCatching { JSONObject(errorBody) }.getOrNull()
        return parsed?.str("message") ?: "Discord returned HTTP ${response.statusCode}"
    }

    /** Gson deserialises `Any` into Map / List / String / Double / Boolean. Convert to org.json. */
    private fun toOrgJson(v: Any?): Any? {
        if (v is Map<*, *>) {
            val obj = JSONObject()
            for ((k, x) in v.entries) {
                obj.put(k.toString(), toOrgJson(x) ?: JSONObject.NULL)
            }
            return obj
        }
        if (v is List<*>) {
            val arr = JSONArray()
            for (x in v) arr.put(toOrgJson(x) ?: JSONObject.NULL)
            return arr
        }
        return v
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

    private fun parseCatalog(roots: List<Any>): ShopCatalog {
        val items = LinkedHashMap<String, ShopItem>()
        val categories = LinkedHashMap<String, ShopCategory>()
        var order = 0

        for (root in roots) {
            val cats: JSONArray = when (root) {
                is JSONArray -> root
                is JSONObject -> root.arr("categories")
                else -> null
            } ?: continue

            for (cat in cats.objects()) {
                val catId = cat.str("sku_id") ?: cat.str("id") ?: continue
                val catName = cat.str("name") ?: "Collection"
                val logo = cat.str("logo_url")?.let { absoluteUrl(it) }
                val exclusive = cat.bool("is_orbs_exclusive")
                if (catId !in categories) categories[catId] = ShopCategory(catId, catName)

                val ranking = cat.arr("hero_ranking")?.strings().orEmpty()
                val products = cat.arr("products")?.objects().orEmpty().sortedBy { p ->
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

    private fun expand(product: JSONObject): List<JSONObject> {
        val variants = product.arr("variants")?.objects().orEmpty()
        return if (variants.isEmpty()) listOf(product) else variants
    }

    private fun parseProduct(
        p: JSONObject,
        parentName: String?,
        catId: String,
        catName: String,
        catLogo: String?,
        catExclusive: Boolean,
        order: Int
    ): ShopItem? {
        val sku = p.str("sku_id") ?: return null
        val name = p.str("name") ?: parentName ?: return null

        val firstItem = p.arr("items")?.optJSONObject(0)
        val type = ItemType.fromCode(p.long("type")?.toInt())
            ?: ItemType.fromCode(firstItem?.long("type")?.toInt())
            ?: ItemType.OTHER

        // Collect every price found under keys like "prices" / "price".
        val prices = ArrayList<PricePoint>()
        for (key in p.keys()) {
            if (key.contains("price", ignoreCase = true)) collectPrices(p.opt(key), null, prices)
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

    private fun collectPrices(el: Any?, type: String?, out: MutableList<PricePoint>) {
        if (el is JSONArray) {
            for (i in 0 until el.length()) collectPrices(el.opt(i), type, out)
            return
        }
        if (el !is JSONObject) return

        val currency = el.str("currency")
        val amount = el.long("amount")
        if (currency != null && amount != null) {
            val exponent = (el.long("exponent") ?: el.long("currency_exponent") ?: 0L).toInt()
            out.add(PricePoint(currency.lowercase(), amount, exponent, type))
            return
        }
        // Keys of the top-level "prices" map are purchase types ("0" = default, ...).
        for (k in el.keys()) collectPrices(el.opt(k), type ?: k, out)
    }

    private fun findImage(product: JSONObject, depth: Int = 0): String? {
        if (depth > 3) return null

        val item = product.arr("items")?.optJSONObject(0)
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
            val children = product.arr(key)?.objects() ?: continue
            for (child in children) {
                val found = findImage(child, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun absoluteUrl(u: String): String =
        if (u.startsWith("http")) u else CDN + u.trimStart('/')

    // ---------------------------------------------------------------------------------------
    // org.json helpers
    // ---------------------------------------------------------------------------------------

    private fun JSONObject.str(key: String): String? {
        val v = opt(key) ?: return null
        if (v == JSONObject.NULL || v is JSONObject || v is JSONArray) return null
        return v.toString()
    }

    private fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)

    private fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

    private fun JSONObject.long(key: String): Long? = (opt(key) as? Number)?.toLong()

    private fun JSONObject.bool(key: String): Boolean = (opt(key) as? Boolean) ?: false

    private fun JSONArray.objects(): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (i in 0 until length()) {
            optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    private fun JSONArray.strings(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until length()) {
            val v = opt(i) ?: continue
            if (v == JSONObject.NULL || v is JSONObject || v is JSONArray) continue
            out.add(v.toString())
        }
        return out
    }
}
