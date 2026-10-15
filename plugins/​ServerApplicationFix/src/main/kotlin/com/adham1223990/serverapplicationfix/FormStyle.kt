package com.adham1223990.serverapplicationfix

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.aliucord.api.SettingsAPI

object FormStyle {
    const val KEY_TEXT_COLOR = "text_color_hex"
    const val DEFAULT_TEXT_COLOR_HEX = "#FFFFFF"
    const val DEFAULT_TEXT_COLOR = Color.WHITE

    const val CARD_BG_COLOR = 0xFF2B2D31.toInt()
    const val PAGE_BG_COLOR = 0xFF1E1F22.toInt()
    const val INPUT_BG_COLOR = 0xFF111214.toInt()
    const val BLURPLE_COLOR = 0xFF5865F2.toInt()
    const val GREEN_COLOR = 0xFF23A55A.toInt()
    const val YELLOW_COLOR = 0xFFF0B232.toInt()
    const val RED_COLOR = 0xFFF23F43.toInt()
    const val STROKE_COLOR = 0xFF3F4147.toInt()

    fun getTextColor(settings: SettingsAPI?): Int {
        val hex = settings?.getString(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR_HEX)?.trim()
            ?: DEFAULT_TEXT_COLOR_HEX
        return parseHexColor(hex, DEFAULT_TEXT_COLOR)
    }

    fun getTextColorHex(settings: SettingsAPI?): String {
        val hex = settings?.getString(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR_HEX)?.trim()
            ?: DEFAULT_TEXT_COLOR_HEX
        return if (isValidHexColor(hex)) hex.uppercase() else DEFAULT_TEXT_COLOR_HEX
    }

    fun setTextColorHex(settings: SettingsAPI?, hex: String): Boolean {
        val cleaned = hex.trim()
        if (!isValidHexColor(cleaned)) return false
        settings?.setString(KEY_TEXT_COLOR, cleaned.uppercase())
        return true
    }

    fun resetTextColor(settings: SettingsAPI?) {
        settings?.setString(KEY_TEXT_COLOR, DEFAULT_TEXT_COLOR_HEX)
    }

    fun isValidHexColor(hex: String): Boolean {
        return try {
            Color.parseColor(hex)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun parseHexColor(hex: String, fallback: Int = DEFAULT_TEXT_COLOR): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Throwable) {
            fallback
        }
    }

    fun applyTextColorRecursively(view: View?, settings: SettingsAPI?) {
        if (view == null) return
        val color = getTextColor(settings)
        when (view) {
            is EditText -> {
                view.setTextColor(color)
                view.setHintTextColor(withAlpha(color, 160))
            }
            is TextView -> {
                view.setTextColor(color)
            }
            is ViewGroup -> {
                for (i in 0 until view.childCount) {
                    applyTextColorRecursively(view.getChildAt(i), settings)
                }
            }
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int {
        val clamped = alpha.coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (clamped shl 24)
    }

    fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun roundedBg(
        context: Context,
        fillColor: Int,
        cornerDp: Int = 8,
        strokeColor: Int = 0,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, cornerDp).toFloat()
            setColor(fillColor)
            if (strokeWidthDp > 0 && strokeColor != 0) {
                setStroke(dp(context, strokeWidthDp), strokeColor)
            }
        }
    }

    fun statusBadgeColor(status: String): Int {
        return when (status.uppercase()) {
            "APPROVED" -> GREEN_COLOR
            "REJECTED" -> RED_COLOR
            "SUBMITTED", "PENDING" -> YELLOW_COLOR
            "STARTED" -> BLURPLE_COLOR
            else -> STROKE_COLOR
        }
    }
}
