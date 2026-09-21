package com.github.orbshop.shop

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.facebook.drawee.view.SimpleDraweeView
import java.text.NumberFormat
import kotlin.math.min

class ShopPage(private val prefs: SettingsAPI) : SettingsPage() {
    private val logger = Logger("OrbShop")
    private val handler = Handler(Looper.getMainLooper())
    private val filters = ShopFilters()

    // Data
    private var catalog: ShopCatalog? = null
    private var owned: Set<String> = emptySet()
    private var balance: Long? = null
    private var loading = true
    private var loadError: String? = null
    private var redeeming = false

    // Rendering state
    private var currentList: List<ShopItem> = emptyList()
    private var rendered = 0
    private var moreButton: View? = null

    // Views
    private lateinit var balanceView: TextView
    private lateinit var walletHint: TextView
    private lateinit var searchInput: EditText
    private lateinit var typeRow: LinearLayout
    private lateinit var toggleRow: LinearLayout
    private lateinit var sortRow: LinearLayout
    private lateinit var countView: TextView
    private lateinit var results: LinearLayout

    private val searchRunnable = Runnable {
        filters.query = searchInput.text.toString().trim()
        renderResults()
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Shop")
        val ctx = view.context

        loadSavedFilters()

        headerBar.menu.add("Refresh").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                reload()
                true
            }
        }

        buildBalanceCard(ctx)
        buildSearch(ctx)
        typeRow = addChipRow(ctx)
        toggleRow = addChipRow(ctx)
        sortRow = addChipRow(ctx)

        countView = subText(ctx, "", Pad(16, 6, 16, 2), TEXT_MUTED)
        linearLayout.addView(countView)

        results = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        linearLayout.addView(results)

        refreshControls()
        reload()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    // ---------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------

    private fun reload() {
        loading = true
        loadError = null
        renderResults()

        Utils.threadPool.execute {
            val result = runCatching { ShopApi.fetchCatalog() }
            Utils.mainThread.post {
                if (!isAdded) return@post
                loading = false
                result
                    .onSuccess { catalog = it }
                    .onFailure {
                        logger.error("Failed to load shop", it)
                        loadError = it.message ?: "Unknown error"
                    }
                updateBalanceView()
                refreshControls()
                renderResults()
            }
        }
        refreshWallet()
    }

    private fun refreshWallet() {
        Utils.threadPool.execute {
            val bal = runCatching { ShopApi.fetchBalance() }
            val own = runCatching { ShopApi.fetchOwned() }
            Utils.mainThread.post {
                if (!isAdded) return@post
                bal.onSuccess { balance = it }
                    .onFailure { logger.error("Failed to load Orbs balance", it) }
                own.onSuccess { owned = it }
                    .onFailure { logger.error("Failed to load owned items", it) }
                updateBalanceView()
                renderResults()
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Header: balance, search, filters
    // ---------------------------------------------------------------------------------------

    private fun buildBalanceCard(ctx: Context) {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(BLURPLE_DARK, 12)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(dp(12), dp(12), dp(12), dp(4))
            }
        }
        card.addView(subText(ctx, "Your Orbs", Pad(0, 0, 0, 0), Color.parseColor("#E0E3FF")))
        balanceView = headerText(ctx, "…", Pad(0, 2, 0, 0), Color.WHITE).apply { textSize = 30f }
        card.addView(balanceView)
        walletHint = subText(ctx, "", Pad(0, 4, 0, 0), Color.parseColor("#E0E3FF"))
        card.addView(walletHint)
        linearLayout.addView(card)
    }

    private fun updateBalanceView() {
        val bal = balance
        balanceView.text = if (bal != null) fmt(bal) else "—"

        val cat = catalog
        walletHint.text = if (cat == null) {
            if (bal == null) "Balance unavailable" else ""
        } else {
            val redeemable = cat.items.count { it.orbPrice != null }
            if (bal != null) {
                val reachable = cat.items.count {
                    it.orbPrice != null && it.orbPrice <= bal && it.skuId !in owned
                }
                "$reachable of $redeemable Orbs-eligible items are within reach"
            } else {
                "$redeemable items can be redeemed with Orbs"
            }
        }
    }

    private fun buildSearch(ctx: Context) {
        searchInput = EditText(ctx).apply {
            hint = "Search items, collections, styles…"
            setHintTextColor(Color.parseColor("#949BA4"))
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            background = roundedBg(IMAGE_BG, 8)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                setMargins(dp(12), dp(8), dp(12), dp(6))
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    handler.removeCallbacks(searchRunnable)
                    handler.postDelayed(searchRunnable, 250)
                }
            })
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    true
                } else {
                    false
                }
            }
        }
        linearLayout.addView(searchInput)
    }

    private fun addChipRow(ctx: Context): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(3), dp(6), dp(3))
        }
        linearLayout.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        return row
    }

    private fun refreshControls() {
        val ctx = linearLayout.context

        typeRow.removeAllViews()
        typeRow.addView(chip(ctx, "All types", filters.type == null) {
            filters.type = null
            onFilterChanged()
        })
        ItemType.values().forEach { t ->
            typeRow.addView(chip(ctx, t.label, filters.type == t) {
                filters.type = if (filters.type == t) null else t
                onFilterChanged()
            })
        }

        toggleRow.removeAllViews()
        toggleRow.addView(chip(ctx, "Orbs only", filters.orbsOnly) {
            filters.orbsOnly = !filters.orbsOnly
            onFilterChanged()
        })
        toggleRow.addView(chip(ctx, "I can afford", filters.affordableOnly) {
            if (balance == null) {
                Utils.showToast("Your Orbs balance isn't available")
            } else {
                filters.affordableOnly = !filters.affordableOnly
                onFilterChanged()
            }
        })
        toggleRow.addView(chip(ctx, "Hide owned", filters.hideOwned) {
            filters.hideOwned = !filters.hideOwned
            onFilterChanged()
        })
        val selectedCategory = catalog?.categories?.firstOrNull { it.id == filters.categoryId }
        val collectionLabel = selectedCategory?.name?.let {
            if (it.length > 18) it.take(17) + "…" else it
        } ?: "All collections"
        toggleRow.addView(chip(ctx, "$collectionLabel ▾", selectedCategory != null) { anchor ->
            showCollectionMenu(anchor)
        })

        sortRow.removeAllViews()
        SortMode.values().forEach { mode ->
            sortRow.addView(chip(ctx, mode.label, filters.sort == mode) {
                filters.sort = mode
                onFilterChanged()
            })
        }
    }

    private fun showCollectionMenu(anchor: View) {
        val cats = catalog?.categories?.sortedBy { it.name.lowercase() } ?: return
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, 0, 0, "All collections")
        cats.forEachIndexed { i, c -> popup.menu.add(0, i + 1, i + 1, c.name) }
        popup.setOnMenuItemClickListener { mi ->
            filters.categoryId = if (mi.itemId == 0) null else cats[mi.itemId - 1].id
            onFilterChanged()
            true
        }
        popup.show()
    }

    private fun onFilterChanged() {
        saveFilters()
        refreshControls()
        renderResults()
    }

    private fun loadSavedFilters() {
        filters.type = runCatching { ItemType.valueOf(prefs.getString("shop_type", "")) }.getOrNull()
        filters.orbsOnly = prefs.getBool("shop_orbs_only", false)
        filters.affordableOnly = prefs.getBool("shop_affordable", false)
        filters.hideOwned = prefs.getBool("shop_hide_owned", false)
        filters.sort = runCatching { SortMode.valueOf(prefs.getString("shop_sort", "")) }
            .getOrDefault(SortMode.FEATURED)
    }

    private fun saveFilters() {
        prefs.setString("shop_type", filters.type?.name ?: "")
        prefs.setBool("shop_orbs_only", filters.orbsOnly)
        prefs.setBool("shop_affordable", filters.affordableOnly)
        prefs.setBool("shop_hide_owned", filters.hideOwned)
        prefs.setString("shop_sort", filters.sort.name)
    }

    // ---------------------------------------------------------------------------------------
    // Results
    // ---------------------------------------------------------------------------------------

    private fun renderResults() {
        if (!::results.isInitialized) return
        val ctx = results.context
        results.removeAllViews()
        moreButton = null
        rendered = 0
        currentList = emptyList()

        val cat = catalog
        when {
            loading -> {
                countView.text = ""
                results.addView(centerText(ctx, "Loading the shop…"))
            }

            loadError != null -> {
                countView.text = ""
                results.addView(centerText(ctx, "Failed to load the shop:\n$loadError", RED))
                results.addView(primaryButton(ctx, "Try again") { reload() })
            }

            cat == null -> {}

            else -> {
                currentList = filters.apply(cat.items, owned, balance)
                countView.text = "Showing ${currentList.size} of ${cat.items.size} items"
                if (currentList.isEmpty()) {
                    results.addView(centerText(ctx, "Nothing matches your filters."))
                    if (!filters.isDefault) {
                        results.addView(primaryButton(ctx, "Clear filters") {
                            filters.reset()
                            searchInput.setText("")
                            onFilterChanged()
                        })
                    }
                } else {
                    if (cat.items.none { it.orbPrice != null }) {
                        results.addView(
                            centerText(
                                ctx,
                                "No Orbs prices were found in the shop response, so nothing " +
                                    "can be redeemed right now.",
                                YELLOW
                            )
                        )
                    }
                    appendMore(ctx)
                }
            }
        }
    }

    private fun appendMore(ctx: Context) {
        moreButton?.let { results.removeView(it) }
        moreButton = null

        val end = min(rendered + PAGE_SIZE, currentList.size)
        currentList.subList(rendered, end).chunked(2).forEach { pair ->
            results.addView(buildRow(ctx, pair))
        }
        rendered = end

        if (rendered < currentList.size) {
            val left = currentList.size - rendered
            val button = primaryButton(ctx, "Show more ($left left)") { appendMore(ctx) }
            moreButton = button
            results.addView(button)
        }
    }

    private fun centerText(ctx: Context, text: String, color: Int = TEXT_MUTED): TextView =
        subText(ctx, text, Pad(16, 32, 16, 16), color).apply { gravity = Gravity.CENTER }

    private fun buildRow(ctx: Context, pair: List<ShopItem>): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(8), 0, dp(8), 0)
        }
        pair.forEach { addView(buildCard(ctx, it)) }
        if (pair.size == 1) {
            addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
    }

    private fun buildCard(ctx: Context, item: ShopItem): View {
        val isOwned = item.skuId in owned
        val price = item.orbPrice
        val bal = balance
        val canAfford = if (price != null && bal != null) bal >= price else null

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(CARD_BG, 10)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            alpha = if (price == null && !isOwned) 0.7f else 1f
            setOnClickListener { showDetails(ctx, item) }
        }

        // Image with badges
        val imageBox = FrameLayout(ctx).apply {
            background = roundedBg(IMAGE_BG, 10)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(124))
        }
        imageBox.addView(SimpleDraweeView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            item.imageUrl?.let { setImageURI(it) }
        })
        if (item.orbsExclusive) {
            imageBox.addView(badge(ctx, "ORBS EXCLUSIVE", BLURPLE, Color.WHITE).apply {
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START)
            })
        }
        if (isOwned) {
            imageBox.addView(badge(ctx, "OWNED", GREEN, Color.parseColor("#0B2B14")).apply {
                layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END)
            })
        }
        card.addView(imageBox)

        // Name + collection
        card.addView(labelText(ctx, item.name, Pad(10, 8, 10, 0)).apply {
            setLines(2)
            ellipsize = TextUtils.TruncateAt.END
        })
        card.addView(subText(ctx, "${item.type.singular} · ${item.categoryName}", Pad(10, 0, 10, 4), TEXT_MUTED).apply {
            setLines(1)
            ellipsize = TextUtils.TruncateAt.END
            textSize = 12f
        })

        // Price line
        val (priceText, priceColor) = when {
            isOwned -> "Owned" to GREEN
            price == null -> ((item.moneyPrice?.let { "$it · " } ?: "") + "No Orbs price") to TEXT_MUTED
            canAfford == true -> "${fmt(price)} Orbs" to GREEN
            canAfford == false -> "${fmt(price)} Orbs" to RED
            else -> "${fmt(price)} Orbs" to TEXT_NORMAL
        }
        card.addView(labelText(ctx, priceText, Pad(10, 0, 10, if (canAfford == false && bal != null && price != null) 0 else 10), priceColor))
        if (!isOwned && canAfford == false && bal != null && price != null) {
            card.addView(subText(ctx, "Need ${fmt(price - bal)} more", Pad(10, 0, 10, 10), YELLOW).apply {
                textSize = 12f
            })
        }
        return card
    }

    // ---------------------------------------------------------------------------------------
    // Details + purchase
    // ---------------------------------------------------------------------------------------

    private fun showDetails(ctx: Context, item: ShopItem) {
        val price = item.orbPrice
        val bal = balance
        val isOwned = item.skuId in owned

        val message = buildString {
            append(item.type.singular).append(" · ").append(item.categoryName)
            if (item.summary.isNotBlank()) append("\n\n").append(item.summary)
            append("\n\n")
            when {
                isOwned -> append("You already own this item.")
                price == null -> {
                    append("This item can't be redeemed with Orbs.")
                    item.moneyPrice?.let { append("\nRegular price: ").append(it) }
                }
                else -> {
                    append("Price: ${fmt(price)} Orbs")
                    if (bal != null) {
                        append("\nBalance: ${fmt(bal)} Orbs")
                        if (bal >= price) {
                            append("\nAfter purchase: ${fmt(bal - price)} Orbs")
                        } else {
                            append("\nYou need ${fmt(price - bal)} more Orbs.")
                        }
                    }
                    append("\n\nOrb purchases are final and can't be refunded.")
                }
            }
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle(item.name)
            .setMessage(message)
            .setNegativeButton("Close", null)

        if (!isOwned && price != null && (bal == null || bal >= price)) {
            builder.setPositiveButton("Redeem for ${fmt(price)} Orbs") { _, _ ->
                redeem(item, price)
            }
        }
        builder.show()
    }

    private fun redeem(item: ShopItem, price: Long) {
        if (redeeming) return
        redeeming = true
        Utils.showToast("Redeeming ${item.name}…")

        Utils.threadPool.execute {
            val result = runCatching { ShopApi.redeem(item, price) }
            Utils.mainThread.post {
                redeeming = false
                result
                    .onSuccess {
                        Utils.showToast("Redeemed ${item.name}!")
                        if (isAdded) {
                            owned = owned + item.skuId
                            balance = balance?.minus(price)
                            updateBalanceView()
                            renderResults()
                            refreshWallet() // sync with the real numbers from Discord
                        }
                    }
                    .onFailure {
                        logger.error("Failed to redeem ${item.skuId}", it)
                        Utils.showToast(it.message ?: "Failed to redeem", true)
                    }
            }
        }
    }

    private fun fmt(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    private companion object {
        const val PAGE_SIZE = 24
    }
}
