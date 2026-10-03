package com.github.adham1223990.shop

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.widget.TextViewCompat
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.settings.WidgetSettings
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class Shop : Plugin() {
    private val rows = WeakHashMap<WidgetSettings, View>()
    private val pages = Collections.newSetFromMap(WeakHashMap<ShopPage, Boolean>())

    override fun start(context: Context) {
        ShopPage.openPage = { context, page ->
            pages.add(page)
            Utils.openPageWithProxy(context, page)
        }
        patcher.patch(
            WidgetSettings::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook {
                insertRow(it.thisObject as WidgetSettings)
            },
        )
        patcher.patch(
            WidgetSettings::class.java,
            "onViewBoundOrOnResume",
            emptyArray(),
            Hook {
                insertRow(it.thisObject as WidgetSettings)
            },
        )
    }

    private fun insertRow(settings: WidgetSettings) {
        val anchor = settings.view?.findViewById<View>(Utils.getResId("qr_scanner", "id")) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(ROW_TAG) != null) return
        val template = LayoutInflater.from(anchor.context).inflate(Utils.getResId("widget_settings", "layout"), null)
        val row = template.findViewById<TextView>(Utils.getResId("connections", "id"))
        (row.parent as ViewGroup).removeView(row)
        row.id = View.NO_ID
        row.tag = ROW_TAG
        row.text = "Shop"
        val referenceIcon = row.compoundDrawablesRelative[0]
        val icon = AppCompatResources
            .getDrawable(
                row.context,
                Utils.getResId("ic_sticker_shop_icon_32dp", "drawable"),
            )?.mutate()
        val size = (24 * row.resources.displayMetrics.density + 0.5f).toInt()
        icon?.setBounds(0, 0, referenceIcon?.bounds?.width() ?: size, referenceIcon?.bounds?.height() ?: size)
        // Preserve the settings row's native colorInteractiveNormal tint.
        val settingsTint = ColorStateList.valueOf(
            ColorCompat.getThemedColor(row.context, Utils.getResId("colorInteractiveNormal", "attr")),
        )
        icon?.setTintList(settingsTint)
        row.setCompoundDrawablesRelative(icon, null, null, null)
        TextViewCompat.setCompoundDrawableTintList(row, settingsTint)
        row.setOnClickListener { if (settings.isAdded) ShopPage.openPage?.invoke(row.context, ShopPage()) }
        parent.addView(row, parent.indexOfChild(anchor) + 1)
        rows[settings] = row
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        ShopPage.openPage = null
        rows.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        rows.clear()
        pages.toList().forEach { it.close() }
        pages.clear()
    }

    private companion object {
        const val ROW_TAG = "yutaplug_shop_row"
    }
}
