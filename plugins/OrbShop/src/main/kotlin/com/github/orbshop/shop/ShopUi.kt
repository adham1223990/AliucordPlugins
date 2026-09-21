package com.github.orbshop.shop

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.aliucord.Constants
import com.aliucord.utils.DimenUtils
import com.lytefast.flexinput.R

internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

internal val CARD_BG = Color.parseColor("#2B2D31")
internal val IMAGE_BG = Color.parseColor("#1E1F22")
internal val CHIP_BG = Color.parseColor("#383A40")
internal val BLURPLE = Color.parseColor("#5865F2")
internal val BLURPLE_DARK = Color.parseColor("#4752C4")
internal val GREEN = Color.parseColor("#57F287")
internal val RED = Color.parseColor("#ED4245")
internal val YELLOW = Color.parseColor("#FEE75C")
internal val TEXT_NORMAL = Color.parseColor("#DBDEE1")
internal val TEXT_MUTED = Color.parseColor("#B5BAC1")

internal data class Pad(
    val left: Int = 16,
    val top: Int = 8,
    val right: Int = 16,
    val bottom: Int = 8
)

internal fun dp(value: Int): Int = DimenUtils.dpToPx(value)

private val fontCache = mutableMapOf<Int, Typeface?>()

internal fun cachedFont(ctx: Context, res: Int): Typeface? =
    fontCache.getOrPut(res) { ResourcesCompat.getFont(ctx, res) }

internal fun roundedBg(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dp(radiusDp).toFloat()
}

private fun styledText(
    ctx: Context,
    style: Int,
    text: CharSequence,
    pad: Pad,
    color: Int?,
    semibold: Boolean
): TextView = TextView(ctx, null, 0, style).apply {
    this.text = text
    setPadding(dp(pad.left), dp(pad.top), dp(pad.right), dp(pad.bottom))
    if (color != null) setTextColor(color)
    if (semibold) typeface = cachedFont(ctx, Constants.Fonts.whitney_semibold)
}

internal fun headerText(
    ctx: Context,
    text: CharSequence,
    pad: Pad = Pad(),
    color: Int? = null
): TextView = styledText(ctx, R.i.UiKit_Settings_Item_Header, text, pad, color, true)

internal fun labelText(
    ctx: Context,
    text: CharSequence,
    pad: Pad = Pad(),
    color: Int? = null
): TextView = styledText(ctx, R.i.UiKit_Settings_Item_Label, text, pad, color, true)

internal fun subText(
    ctx: Context,
    text: CharSequence,
    pad: Pad = Pad(),
    color: Int? = null
): TextView = styledText(ctx, R.i.UiKit_Settings_Item_SubText, text, pad, color, false)

/** A small rounded pill used for filters and sorting. */
internal fun chip(
    ctx: Context,
    text: CharSequence,
    selected: Boolean,
    onClick: (View) -> Unit
): TextView = subText(
    ctx,
    text,
    Pad(12, 7, 12, 7),
    if (selected) Color.WHITE else TEXT_NORMAL
).apply {
    gravity = Gravity.CENTER
    background = roundedBg(if (selected) BLURPLE else CHIP_BG, 16)
    layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
    setOnClickListener { onClick(it) }
}

/** Tiny label drawn on top of a product image ("OWNED", "ORBS ONLY", ...). */
internal fun badge(ctx: Context, text: CharSequence, bg: Int, fg: Int): TextView =
    subText(ctx, text, Pad(6, 2, 6, 2), fg).apply {
        textSize = 10f
        typeface = cachedFont(ctx, Constants.Fonts.whitney_semibold)
        background = roundedBg(bg, 6)
    }

internal fun primaryButton(ctx: Context, text: CharSequence, onClick: () -> Unit): TextView =
    labelText(ctx, text, Pad(16, 10, 16, 10), Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = roundedBg(BLURPLE, 6)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            setMargins(dp(12), dp(8), dp(12), dp(8))
        }
        setOnClickListener { onClick() }
    }

