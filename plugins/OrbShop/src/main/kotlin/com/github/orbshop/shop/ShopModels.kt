package com.github.orbshop.shop

import java.util.Locale

enum class ItemType(val label: String, val singular: String) {
    DECORATION("Decorations", "Avatar decoration"),
    EFFECT("Effects", "Profile effect"),
    NAMEPLATE("Nameplates", "Nameplate"),
    FRAME("Frames", "Profile frame"),
    BUNDLE("Bundles", "Bundle"),
    OTHER("Other", "Other item");

    companion object {
        fun fromCode(code: Int?): ItemType? = when (code) {
            0 -> DECORATION
            1 -> EFFECT
            2 -> NAMEPLATE
            3 -> FRAME
            1000 -> BUNDLE
            else -> null
        }
    }
}

enum class SortMode(val label: String) {
    FEATURED("Featured"),
    CHEAPEST("Cheapest"),
    PRICIEST("Priciest"),
    NAME("A–Z")
}

data class ShopItem(
    val skuId: String,
    val name: String,
    val summary: String,
    val type: ItemType,
    val categoryId: String,
    val categoryName: String,
    /** Price in Orbs, or null when the item can't be redeemed with Orbs. */
    val orbPrice: Long?,
    /** Regular money price for display only (e.g. "4.99 USD"), if known. */
    val moneyPrice: String?,
    val imageUrl: String?,
    val orbsExclusive: Boolean,
    val order: Int
) {
    val searchText: String =
        "$name $categoryName ${type.label} ${type.singular} $summary".lowercase(Locale.ROOT)
}

data class ShopCategory(val id: String, val name: String)

data class ShopCatalog(val items: List<ShopItem>, val categories: List<ShopCategory>)

class ShopFilters {
    var query: String = ""
    var type: ItemType? = null
    var categoryId: String? = null
    var orbsOnly = false
    var affordableOnly = false
    var hideOwned = false
    var sort = SortMode.FEATURED

    val isDefault: Boolean
        get() = query.isEmpty() && type == null && categoryId == null &&
            !orbsOnly && !affordableOnly && !hideOwned

    fun reset() {
        query = ""
        type = null
        categoryId = null
        orbsOnly = false
        affordableOnly = false
        hideOwned = false
    }

    fun apply(items: List<ShopItem>, owned: Set<String>, balance: Long?): List<ShopItem> {
        val tokens = query.lowercase(Locale.ROOT).split(' ').filter { it.isNotBlank() }
        val filtered = items.filter { item ->
            (type == null || item.type == type) &&
                (categoryId == null || item.categoryId == categoryId) &&
                (!orbsOnly || item.orbPrice != null) &&
                (!affordableOnly ||
                    (item.orbPrice != null && balance != null && item.orbPrice <= balance)) &&
                (!hideOwned || item.skuId !in owned) &&
                (tokens.isEmpty() || tokens.all { token -> token in item.searchText })
        }
        return when (sort) {
            SortMode.FEATURED -> filtered
            SortMode.CHEAPEST -> filtered.sortedWith(
                compareBy<ShopItem>({ it.orbPrice ?: Long.MAX_VALUE }, { it.order })
            )
            SortMode.PRICIEST -> filtered.sortedWith(
                compareBy<ShopItem>({ it.orbPrice?.let { p -> -p } ?: Long.MAX_VALUE }, { it.order })
            )
            SortMode.NAME -> filtered.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }
}
