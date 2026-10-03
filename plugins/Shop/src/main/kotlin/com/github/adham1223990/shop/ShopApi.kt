package com.github.adham1223990.shop

import android.net.Uri
import com.aliucord.Http
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject

internal object ShopApi {
    // Collectibles schema: https://docs.discord.food/resources/collectibles
    fun categories(token: String): List<JSONObject> {
        return Http.Request
            .newDiscordRNRequest(
                "/collectibles-categories/v2?include_bundles=true&variants_return_style=1",
                "GET",
            ).use { request ->
                request.setHeader("Authorization", token)
                request.setRequestTimeout(15_000)
                request.execute().use { response ->
                    check(response.ok()) { "Discord returned HTTP ${response.statusCode}" }
                    val categories = JSONObject(response.text()).getJSONArray("categories")
                    objects(categories).filter { products(it).isNotEmpty() }
                }
            }
    }

    // Orbs-exclusive collections live on a separate tab of the shop and are NOT included in
    // /collectibles-categories/v2 (that's why "Orbs Exclusive" was always empty before). This
    // fetches that tab specifically and force-marks every category in it as Orbs-exclusive,
    // since the whole tab is Orbs-only by definition regardless of what is_orbs_exclusive says.
    fun orbsCategories(token: String): List<JSONObject> {
        return Http.Request
            .newDiscordRNRequest(
                "/collectibles-shop?tab=orbs&include_bundles=true&variants_return_style=1",
                "GET",
            ).use { request ->
                request.setHeader("Authorization", token)
                request.setRequestTimeout(15_000)
                request.execute().use { response ->
                    if (!response.ok()) return@use emptyList()
                    val root = JSONObject(response.text())
                    val categories = root.optJSONArray("categories") ?: return@use emptyList()
                    objects(categories)
                        .filter { products(it).isNotEmpty() }
                        .map { category -> JSONObject(category.toString()).put("is_orbs_exclusive", true) }
                }
            }
    }

    // Merges the Orbs-only tab's categories into the main list, matching by id/sku_id so the
    // same collection isn't shown twice; when a category already exists, its is_orbs_exclusive
    // flag is upgraded to true instead of adding a duplicate row.
    fun mergeOrbsCategories(base: List<JSONObject>, orbs: List<JSONObject>): List<JSONObject> {
        fun key(category: JSONObject) = category.optString("sku_id").ifEmpty { category.optString("id") }
            .ifEmpty { category.toString() }

        val merged = LinkedHashMap<String, JSONObject>()
        base.forEach { merged[key(it)] = it }
        orbs.forEach { orbCategory ->
            val existingKey = key(orbCategory)
            val existing = merged[existingKey]
            merged[existingKey] = if (existing != null) {
                JSONObject(existing.toString()).put("is_orbs_exclusive", true)
            } else {
                orbCategory
            }
        }
        return merged.values.toList()
    }

    fun token(): String? = RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(::hasText)

    // Orbs: https://docs.discord.food/resources/store#get-virtual-currency-balance
    fun orbsBalance(token: String): Int? {
        return Http.Request
            .newDiscordRNRequest("/users/@me/virtual-currency/balance", "GET")
            .use { request ->
                request.setHeader("Authorization", token)
                request.setRequestTimeout(15_000)
                request.execute().use { response ->
                    if (!response.ok()) return@use null
                    val balance = JSONObject(response.text()).optInt("balance", -1)
                    if (balance >= 0) balance else null
                }
            }
    }

    // A collectible category can mark every product in it as Orbs-exclusive.
    // https://docs.discord.food/resources/collectibles#collectible-category-object
    fun isOrbsExclusive(category: JSONObject): Boolean = category.optBoolean("is_orbs_exclusive", false)

    // The Orbs price isn't at one fixed path on a collectible product (the schema nests pricing
    // under a purchase-type map), so this walks the JSON tree for any price object whose
    // currency is "discord_orb" rather than assuming one exact path.
    fun orbsPrice(value: JSONObject): Int? = orbsPriceIn(value)

    private fun orbsPriceIn(value: Any?): Int? {
        if (value is JSONObject) {
            if (value.optString("currency") == "discord_orb") {
                val amount = value.optInt("amount", -1)
                if (amount >= 0) return amount
            }
            val keys = value.keys()
            while (keys.hasNext()) {
                orbsPriceIn(value.opt(keys.next()))?.let { return it }
            }
            return null
        }
        if (value is JSONArray) {
            var index = 0
            while (index < value.length()) {
                orbsPriceIn(value.opt(index))?.let { return it }
                index++
            }
        }
        return null
    }

    // Purchases a collectible SKU using the user's Orbs balance.
    // https://docs.discord.food/resources/store#redeem-virtual-currency
    fun redeemWithOrbs(token: String, skuId: String): JSONObject {
        return Http.Request
            .newDiscordRNRequest("/virtual-currency/skus/$skuId/redeem", "POST")
            .use { request ->
                request.setHeader("Authorization", token)
                request.setHeader("Content-Type", "application/json")
                request.setRequestTimeout(15_000)
                request.executeWithBody("{}").use { response ->
                    check(response.ok()) {
                        "Discord returned HTTP ${response.statusCode}: ${response.text()}"
                    }
                    val text = response.text()
                    if (hasText(text)) JSONObject(text) else JSONObject()
                }
            }
    }

    fun products(category: JSONObject): List<JSONObject> = objects(category.optJSONArray("products"))

    fun items(product: JSONObject): List<JSONObject> {
        val items = objects(product.optJSONArray("items")).toMutableList()
        objects(product.optJSONArray("bundled_products")).forEach { items.addAll(items(it)) }
        objects(product.optJSONArray("variants")).forEach { items.addAll(items(it)) }
        return items.distinctBy { it.optString("sku_id").ifEmpty { it.toString() } }
    }

    fun typeName(item: JSONObject): String = when (item.optInt("type", -1)) {
        0 -> "Avatar decoration"
        1 -> "Profile effect"
        2 -> "Nameplate"
        3 -> "Profile frame"
        1000 -> "Bundle"
        2000 -> "Variants"
        else -> "Collectible"
    }

    fun itemName(item: JSONObject): String = item.optString("name").takeIf(::hasText)
        ?: item.optString("title").takeIf(::hasText)
        ?: item.optString("label").takeIf(::hasText)
        ?: typeName(item)

    fun preview(product: JSONObject): String? {
        image(product.optJSONObject("preview_assets")?.optString("fg_static"))?.let { return it }
        for (item in items(product)) itemImage(item)?.let { return it }
        return image(product.optJSONObject("preview_assets")?.optString("bg_static"))
    }

    fun itemImage(item: JSONObject): String? {
        image(item.optJSONObject("assets")?.optString("static_image_url"))?.let { return it }
        return when (item.optInt("type", -1)) {
            0 -> decorationImage(item)
            1 -> effectImages(item).firstOrNull()
            2 -> nameplateImage(item)
            else -> null
        }
    }

    private fun effectImages(item: JSONObject): List<String> {
        val fields = listOf(
            "thumbnailPreviewSrc",
            "thumbnail_preview_src",
            "staticFrameSrc",
            "static_frame_src",
            "reducedMotionSrc",
            "reduced_motion_src",
        )
        val urls = fields.mapNotNull { image(item.optString(it)) }.toMutableList()
        objects(item.optJSONArray("effects")).forEach { image(it.optString("src"))?.let(urls::add) }
        return urls.distinct()
    }

    private fun previewCandidates(value: JSONObject): List<String> {
        val candidates = mutableListOf<String>()
        preview(value)?.let(candidates::add)
        itemImage(value)?.let(candidates::add)
        val contents = items(value).ifEmpty { listOf(value) }
        for (item in contents) {
            itemImage(item)?.let(candidates::add)
            if (item.optInt("type", -1) == 1) candidates.addAll(effectImages(item))
            if (item.optInt("type", -1) == 2) {
                nameplateImage(item)?.let { candidates.add(it.replace("/static.png", "/img.png")) }
            }
        }
        image(value.optJSONObject("preview_assets")?.optString("bg_static"))?.let(candidates::add)
        return candidates.distinct()
    }

    // Called on the page worker. A populated URL can still refer to a missing CDN file.
    fun resolvePreview(value: JSONObject): String? {
        fun available(candidates: List<String>): String? {
            for (url in candidates) {
                if (Thread.currentThread().isInterrupted) return null
                val exists = runCatching {
                    Http.Request(url, "HEAD").use { request ->
                        request.setRequestTimeout(5_000)
                        request.execute().use { it.ok() && request.conn.contentType?.startsWith("image/") == true }
                    }
                }.getOrDefault(false)
                if (exists) return url
            }
            return null
        }
        val candidates = previewCandidates(value)
        available(candidates)?.let { return it }
        val sku = value.optString("sku_id")
        if (!sku.matches(Regex("[0-9]+")) || Thread.currentThread().isInterrupted) return null
        val full = runCatching {
            Http.Request.newDiscordRNRequest("/collectibles-products/$sku", "GET").use { request ->
                request.setRequestTimeout(10_000)
                request.execute().use { response ->
                    check(response.ok())
                    JSONObject(response.text())
                }
            }
        }.getOrNull() ?: return null
        return available(previewCandidates(full).filter { it !in candidates })
    }

    private fun nameplateImage(item: JSONObject): String? {
        var path = item.optString("asset")
        if (path.endsWith("/")) path = path.substring(0, path.length - 1)
        if (path.isEmpty()) return null
        // Nameplates provide a directory, not an avatar-decoration hash.
        // https://docs.discord.food/reference#cdn-endpoints
        return "https://cdn.discordapp.com/assets/collectibles/${Uri.encode(path, "/")}/static.png"
    }

    fun summary(product: JSONObject): String {
        val summary = product.optString("summary")
        if (!summary.contains("{joinedItems}")) return summary
        val names = objects(product.optJSONArray("bundled_products"))
            .map { it.optString("name") }
            .filter(::hasText)
        val itemNames = names.ifEmpty {
            objects(product.optJSONArray("items"))
                .map { it.optString("label") }
                .filter(::hasText)
        }
        val included = if (itemNames.isEmpty()) "Collectibles" else itemNames.distinct().joinToString(", ")
        return summary.replace("{joinedItems}", included)
    }

    fun objects(array: JSONArray?): List<JSONObject> {
        val items = mutableListOf<JSONObject>()
        var index = 0
        while (index < (array?.length() ?: 0)) {
            array?.optJSONObject(index)?.let(items::add)
            index++
        }
        return items
    }

    // Discord's obfuscated Kotlin runtime cannot use the stdlib's IntIterator casts.
    private fun hasText(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) return true
        }
        return false
    }

    fun image(value: String?): String? = value?.takeIf { it.startsWith("https://") }

    fun decorationImage(item: JSONObject): String? = image(item.optJSONObject("assets")?.optString("static_image_url"))
        ?: item
            .optString("asset")
            .takeIf { it.matches(Regex("[a-zA-Z0-9_]+")) }
            ?.let { "https://cdn.discordapp.com/avatar-decoration-presets/$it.png?size=256" }
}
