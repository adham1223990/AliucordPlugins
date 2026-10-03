package com.github.adham1223990.shop

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Utils
import com.discord.app.AppFragment
import com.facebook.drawee.drawable.`ScalingUtils$ScaleType`
import com.facebook.drawee.view.SimpleDraweeView
import org.json.JSONObject
import java.util.concurrent.Executors

class ShopPage : AppFragment(Utils.getResId("widget_settings_authorized_apps", "layout")) {
    private var category: JSONObject? = null
    private var product: JSONObject? = null
    private var bound: View? = null
    private var generation = 0
    private var closed = false
    private var worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var header: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private var loading = false
    private val previews = mutableMapOf<String, String?>()
    private val pendingPreviews = mutableMapOf<String, MutableList<(String?) -> Unit>>()

    // Orbs: balance pill + Shop Menu filter (root collections screen only).
    private var orbsBalance: Int? = null
    private var allCategories: List<JSONObject> = emptyList()
    private var sortMode = SortMode.FEATURED
    private var orbsRow: TextView? = null
    private var filterButton: Button? = null

    private enum class SortMode(val label: String) {
        FEATURED("Featured"),
        ALL("Shop All"),
        ORBS("Orbs Exclusives"),
    }

    // Orbs: purchase button (product detail screen only).
    private var purchaseButton: Button? = null
    private var purchasing = false

    private fun resolvePreview(value: JSONObject, callback: (String?) -> Unit) {
        val key = value.toString()
        if (previews.containsKey(key)) {
            callback(previews[key])
            return
        }
        pendingPreviews[key]?.let {
            it.add(callback)
            return
        }
        pendingPreviews[key] = mutableListOf(callback)
        val current = generation
        worker.execute {
            val url = runCatching { ShopApi.resolvePreview(value) }.getOrNull()
            main.post {
                if (bound == null || closed || generation != current) return@post
                previews[key] = url
                pendingPreviews.remove(key)?.forEach { it(url) }
            }
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // AppFragment uses ToolbarTitleLayout; remove the template Toolbar's separate XML labels.
        val toolbar = (view as ViewGroup).getChildAt(0) as ViewGroup
        (toolbar.getChildAt(0) as Toolbar).apply {
            title = null
            subtitle = null
        }
        if (closed) {
            activity?.finish()
            return
        }
        bound = view
        category = arguments?.getString("shop_category")?.let(::JSONObject)
        product = arguments?.getString("shop_product")?.let(::JSONObject)
        generation++
        loading = false
        if (worker.isShutdown) worker = Executors.newSingleThreadExecutor()
        setActionBarTitle(product?.optString("name") ?: category?.optString("name") ?: "Shop")
        setActionBarSubtitle(product?.let(ShopApi::typeName) ?: "Shop")
        setActionBarDisplayHomeAsUpEnabled()
        val column = ((view as ViewGroup).getChildAt(1) as ViewGroup).getChildAt(0) as ViewGroup
        header = column.getChildAt(0) as TextView
        status = column.getChildAt(1) as TextView
        header.setTextColor(Color.WHITE)
        status.setTextColor(Color.WHITE)
        list = view.findViewById(Utils.getResId("authorized_apps_list", "id"))
        list.layoutManager = LinearLayoutManager(view.context)
        product?.let {
            header.text = it.optString("name")
            status.text = ShopApi.summary(it)
            val items = ShopApi.items(it).ifEmpty { listOf(it) }
            show(items, 2)
            addPurchaseButton(column, it)
            return
        }
        category?.let {
            header.text = it.optString("name")
            status.text = ShopApi.summary(it)
            show(ShopApi.products(it), 1)
            return
        }
        // The Orbs balance + Shop Menu row goes in at index 0, before the header/status text,
        // so it's the very first thing visible under the toolbar and never requires scrolling.
        addOrbsRow(column)
        header.text = "COLLECTIONS"
        status.setOnClickListener { load() }
        load()
    }

    // Adds a Redeem-with-Orbs button under the summary on a product detail page, only if the
    // product actually has an Orbs price.
    private fun addPurchaseButton(column: ViewGroup, product: JSONObject) {
        val orbsPrice = ShopApi.orbsPrice(product) ?: return
        val skuId = product.optString("sku_id")
        if (skuId.isEmpty()) return

        val button = Button(column.context).apply {
            text = "Redeem for $orbsPrice Orbs"
            setTextColor(Color.WHITE)
        }
        button.setOnClickListener { confirmPurchase(skuId, orbsPrice) }
        column.addView(button)
        purchaseButton = button
    }

    private fun confirmPurchase(skuId: String, orbsPrice: Int) {
        if (closed) return
        AlertDialog.Builder(requireContext())
            .setTitle("Redeem with Orbs")
            .setMessage("Spend $orbsPrice Orbs on this item? This cannot be undone.")
            .setPositiveButton("Redeem") { _, _ -> purchase(skuId) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun purchase(skuId: String) {
        if (purchasing || closed || bound == null) return
        val token = ShopApi.token()
        if (token == null) {
            Utils.showToast("Sign in to Discord to redeem items.", true)
            return
        }
        purchasing = true
        purchaseButton?.isEnabled = false
        purchaseButton?.text = "Redeeming…"
        val current = generation
        worker.execute {
            val result = runCatching { ShopApi.redeemWithOrbs(token, skuId) }
            main.post {
                purchasing = false
                if (bound == null || closed || generation != current) return@post
                result
                    .onSuccess {
                        Utils.showToast("Redeemed! Check your profile to see the item.", true)
                        purchaseButton?.text = "Redeemed"
                        purchaseButton?.isEnabled = false
                    }
                    .onFailure {
                        Utils.showToast("Could not redeem: ${it.message.orEmpty()}", true)
                        purchaseButton?.isEnabled = true
                        purchaseButton?.text = "Redeem"
                    }
            }
        }
    }

    // Adds the Orbs balance pill and the "Shop Menu" filter button (Featured / Shop All /
    // Orbs Exclusives — matching Discord's own shop menu) at the very top of the column, above
    // the header/status text. Only used on the root screen (no category/product selected yet).
    private fun addOrbsRow(column: ViewGroup) {
        val ctx = column.context
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val balanceText = TextView(ctx).apply {
            text = "Orbs: …"
            setTextColor(Color.WHITE)
        }
        row.addView(balanceText)
        orbsRow = balanceText

        val menuButton = Button(ctx).apply {
            text = "Shop Menu ▾"
            setTextColor(Color.WHITE)
        }
        menuButton.setOnClickListener { showShopMenu(ctx) }
        row.addView(menuButton)
        filterButton = menuButton

        column.addView(row, 0)
        loadOrbsBalance()
    }

    // Shows the same three options as Discord's own "Shop Menu" sheet (Featured, Shop All,
    // Orbs Exclusives) as a single-choice dialog, with a checkmark on the active one.
    private fun showShopMenu(ctx: Context) {
        val options = SortMode.values()
        val labels = options.map { it.label }.toTypedArray()
        // This specific dialog renders with a light background on this build, so its text
        // stays the default (dark) colour here — forcing white would make it unreadable, as
        // it did before. The "white text" request applies to the dark Shop screens, not this.
        AlertDialog.Builder(ctx)
            .setTitle("Shop Menu")
            .setSingleChoiceItems(labels, options.indexOf(sortMode)) { dialog, which ->
                sortMode = options[which]
                dialog.dismiss()
                applySortMode()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadOrbsBalance() {
        val token = ShopApi.token() ?: return
        val current = generation
        worker.execute {
            val balance = runCatching { ShopApi.orbsBalance(token) }.getOrNull()
            main.post {
                if (bound == null || closed || generation != current) return@post
                orbsBalance = balance
                orbsRow?.text = if (balance != null) "Orbs: $balance" else "Orbs: unavailable"
            }
        }
    }

    // Re-renders the root collections list for the selected Shop Menu option: Featured keeps
    // the API's natural order, Shop All sorts every collection alphabetically, and Orbs
    // Exclusives filters down to collections marked Orbs-exclusive (including the ones merged
    // in from the separate Orbs tab — see ShopApi.orbsCategories).
    private fun applySortMode() {
        filterButton?.isEnabled = false
        filterButton?.text = "Shop Menu: ${sortMode.label} ▾"
        filterButton?.isEnabled = true
        val filtered = when (sortMode) {
            SortMode.FEATURED -> allCategories
            SortMode.ALL -> allCategories.sortedBy { it.optString("name") }
            SortMode.ORBS -> allCategories.filter(ShopApi::isOrbsExclusive)
        }
        status.text = if (filtered.isEmpty()) {
            "No Orbs-exclusive collections right now. Tap to refresh."
        } else {
            "Browse collections and select a collectible to view its details. Tap here to refresh."
        }
        show(filtered, 0)
    }

    private fun load() {
        if (loading || closed || bound == null) return
        val token = ShopApi.token()
        if (token == null) {
            status.text = "Sign in to Discord to browse the Shop."
            return
        }
        loading = true
        status.text = "Loading collections…"
        val current = generation
        worker.execute {
            val result = runCatching {
                val base = ShopApi.categories(token)
                val orbs = runCatching { ShopApi.orbsCategories(token) }.getOrDefault(emptyList())
                ShopApi.mergeOrbsCategories(base, orbs)
            }
            main.post {
                if (bound == null || closed || generation != current) return@post
                loading = false
                if (ShopApi.token() != token) {
                    status.text = "Your account changed. Tap to reload the Shop."
                    list.adapter = null
                    return@post
                }
                result
                    .onSuccess {
                        allCategories = it
                        if (it.isEmpty()) {
                            status.text = "No collectibles are currently available. Tap to refresh."
                        }
                        applySortMode()
                    }.onFailure {
                        status.text = "Could not load the Shop. ${it.message.orEmpty()} Tap to retry."
                    }
            }
        }
    }

    private fun show(items: List<JSONObject>, mode: Int) {
        list.adapter = object : RecyclerView.Adapter<Card>() {
            override fun getItemCount() = items.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card = Card(
                LayoutInflater
                    .from(
                        parent.context,
                    ).inflate(Utils.getResId("view_gift_sku_list_item", "layout"), parent, false),
            )

            override fun onBindViewHolder(holder: Card, position: Int) {
                val item = items[position]
                holder.itemView.tag = item
                holder.name.setTextColor(Color.WHITE)
                holder.details.setTextColor(Color.WHITE)
                val widePreview = mode == 0 || (mode == 2 && item.optInt("type", -1) == 2)
                holder.name.text =
                    if (mode == 2) ShopApi.itemName(item) else item.optString("name")
                // Show the Orbs price right in the list (mode 1, product rows) so you can see
                // what something costs without opening it first.
                val orbsPrice = if (mode == 1) ShopApi.orbsPrice(item) else null
                holder.details.text = when (mode) {
                    0 -> "${ShopApi.products(item).size} products"
                    1 -> {
                        val base = "${ShopApi.typeName(item)} · ${ShopApi.summary(item)}"
                        if (orbsPrice != null) "$base · $orbsPrice Orbs" else base
                    }
                    else -> ShopApi.typeName(item)
                }
                val url = when (mode) {
                    0 -> {
                        ShopApi.image(item.optString("mobile_banner_url"))
                            ?: ShopApi.image(item.optJSONObject("catalog_banner_asset")?.optString("static"))
                            ?: ShopApi.image(item.optString("logo_url"))
                    }

                    1 -> {
                        ShopApi.preview(item)
                    }

                    else -> {
                        ShopApi.itemImage(item) ?: product?.let(ShopApi::preview)
                    }
                }
                holder.image.setImageURI(url)
                if (mode == 2 && url == null) {
                    holder.details.text = "${ShopApi.typeName(item)} · Loading preview…"
                }
                if (mode != 0) {
                    resolvePreview(item) { resolved ->
                        if (holder.itemView.tag !== item) return@resolvePreview
                        holder.image.setImageURI(resolved)
                        if (mode == 2) {
                            holder.details.text = if (resolved == null) {
                                "${ShopApi.typeName(item)} · Preview unavailable"
                            } else {
                                ShopApi.typeName(item)
                            }
                        }
                    }
                }
                holder.image.hierarchy.n(
                    if (mode == 0) `ScalingUtils$ScaleType`.i else `ScalingUtils$ScaleType`.e,
                )
                holder.image.layoutParams = holder.image.layoutParams.apply {
                    val size = (if (mode == 2) 160 else 80) * holder.itemView.resources.displayMetrics.density
                    width = if (widePreview) ViewGroup.LayoutParams.MATCH_PARENT else size.toInt()
                    height =
                        if (mode == 0) {
                            (160 * holder.itemView.resources.displayMetrics.density).toInt()
                        } else {
                            size.toInt()
                        }
                }
                if (widePreview) {
                    holder.image.layoutParams = (holder.image.layoutParams as RelativeLayout.LayoutParams).apply {
                        removeRule(RelativeLayout.CENTER_VERTICAL)
                        addRule(RelativeLayout.ALIGN_PARENT_TOP)
                        marginEnd = 0
                    }
                    holder.name.layoutParams = (holder.name.layoutParams as RelativeLayout.LayoutParams).apply {
                        removeRule(RelativeLayout.ALIGN_PARENT_TOP)
                        addRule(RelativeLayout.BELOW, holder.image.id)
                    }
                }
                // Retain the native store card's typography, background and spacing.
                val offset =
                    if (widePreview) {
                        0
                    } else {
                        holder.image.layoutParams.width +
                            (16 * holder.itemView.resources.displayMetrics.density).toInt()
                    }
                listOf(holder.name, holder.details).forEach { text ->
                    text.layoutParams =
                        (text.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = offset }
                }
                holder.arrow.visibility = if (mode == 1) View.VISIBLE else View.GONE
                holder.itemView.isClickable = mode != 2
                holder.itemView.setOnClickListener(
                    if (mode == 2) {
                        null
                    } else {
                        View.OnClickListener {
                            val next = ShopPage().apply {
                                arguments = Bundle().apply {
                                    putString(if (mode == 0) "shop_category" else "shop_product", item.toString())
                                }
                            }
                            openPage?.invoke(holder.itemView.context, next)
                        }
                    },
                )
            }

            override fun onViewRecycled(holder: Card) {
                holder.itemView.tag = null
                holder.image.setImageURI(null as String?)
                holder.itemView.setOnClickListener(null)
            }
        }
    }

    override fun onDestroyView() {
        generation++
        bound?.findViewById<RecyclerView>(Utils.getResId("authorized_apps_list", "id"))?.adapter = null
        bound = null
        orbsRow = null
        filterButton = null
        purchaseButton = null
        pendingPreviews.clear()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    internal fun close() {
        closed = true
        generation++
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        if (isAdded) activity?.finish()
    }

    private class Card(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(Utils.getResId("gift_sku_name", "id"))
        val details: TextView = view.findViewById(Utils.getResId("gift_sku_copies", "id"))
        val image: SimpleDraweeView = view.findViewById(Utils.getResId("gift_sku_icon", "id"))
        val arrow: View = view.findViewById(Utils.getResId("gift_sku_arrow", "id"))
    }

    companion object {
        internal var openPage: ((Context, ShopPage) -> Unit)? = null
    }
}
