package com.github.orbshop

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.widget.NestedScrollView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.widgets.settings.WidgetSettings
import com.github.orbshop.shop.ShopPage
import com.lytefast.flexinput.R

@AliucordPlugin
class OrbShop : Plugin() {
    override fun start(context: Context) {
        // Inject a "Shop" entry into the Settings menu, right below "Scan QR Code".
        patcher.patch(
            WidgetSettings::class.java.getDeclaredMethod("onViewBound", View::class.java),
            Hook { callFrame ->
                val view = callFrame.args[0] as CoordinatorLayout
                val layout =
                    (view.getChildAt(1) as NestedScrollView).getChildAt(0) as
                        LinearLayoutCompat
                val ctx = layout.context

                val anchor = layout.findViewById<TextView>(Utils.getResId("qr_scanner", "id"))
                val baseIndex = layout.indexOfChild(anchor)

                val entry = TextView(ctx, null, 0, R.i.UiKit_Settings_Item_Icon).apply {
                    text = "Shop"
                    setCompoundDrawablesWithIntrinsicBounds(
                        Utils.tintToTheme(ctx.getDrawable(R.e.ic_gift_24dp)),
                        null,
                        null,
                        null
                    )
                    setOnClickListener { Utils.openPageWithProxy(ctx, ShopPage(settings)) }
                }

                if (baseIndex >= 0) layout.addView(entry, baseIndex + 1) else layout.addView(entry)
            }
        )
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
