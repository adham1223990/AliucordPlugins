package com.github.adham1223990.missingsettings

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.guilds.settings.WidgetGuildSettings
import com.discord.widgets.guilds.settings.WidgetGuildSettingsModel

@AliucordPlugin
class MissingServerSettings : Plugin() {
    private var screen: MissingSettingsScreen? = null

    override fun start(context: Context) {
        // Fix and expand Audit Log types
        AuditLogPatcher.apply(patcher)

        // Patch WidgetGuildSettings.configureUI to inject the missing features button
        patcher.patch(
            WidgetGuildSettings::class.java,
            "configureUI",
            arrayOf(WidgetGuildSettingsModel::class.java),
            Hook { frame ->
                val model = frame.args[0] as? WidgetGuildSettingsModel ?: return@Hook
                val guild = model.guild ?: return@Hook
                val guildId = guild.id
                val userPermissions = model.permissions

                // Verify permissions: Only proceed if user has MANAGE_GUILD, MANAGE_ROLES or ADMINISTRATOR
                val hasPermission = (userPermissions and MissingPermissions.MANAGE_GUILD) != 0L ||
                        (userPermissions and MissingPermissions.MANAGE_ROLES) != 0L ||
                        (userPermissions and MissingPermissions.ADMINISTRATOR) != 0L
                if (!hasPermission) return@Hook

                val widget = frame.thisObject as WidgetGuildSettings
                val rootView = widget.view as? ViewGroup ?: return@Hook

                // Find the main vertical linear container inside guild settings layout
                val container = findLinearLayout(rootView) ?: return@Hook
                if (container.findViewWithTag<View>(TAG_ENTRY) != null) return@Hook

                injectSettingsEntry(container, widget.requireContext(), guildId, userPermissions)
            }
        )
    }

    private fun findLinearLayout(viewGroup: ViewGroup): LinearLayout? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is LinearLayout && child.orientation == LinearLayout.VERTICAL) {
                return child
            } else if (child is ViewGroup) {
                val found = findLinearLayout(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun injectSettingsEntry(
        container: LinearLayout,
        context: Context,
        guildId: Long,
        permissions: Long
    ) {
        val entryHeight = dp(context, 48)
        val entry = FrameLayout(context).apply {
            tag = TAG_ENTRY
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (screen == null) {
                    screen = MissingSettingsScreen(context, guildId, permissions) { screen = null }
                    screen?.show()
                }
            }
        }

        val textColor = ColorCompat.getThemedColor(context, Utils.getResId("colorTextNormal", "attr"))
        val iconColor = ColorCompat.getThemedColor(context, Utils.getResId("colorInteractiveNormal", "attr"))

        val label = TextView(context).apply {
            text = "Modern Server Features"
            setTextColor(textColor)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        entry.addView(label, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or Gravity.START
        ).apply {
            marginStart = dp(context, 16)
        })

        val icon = ImageView(context).apply {
            setImageResource(Utils.getResId("ic_settings_24dp", "drawable"))
            imageTintList = ColorStateList.valueOf(iconColor)
        }

        entry.addView(icon, FrameLayout.LayoutParams(
            dp(context, 24),
            dp(context, 24),
            Gravity.CENTER_VERTICAL or Gravity.END
        ).apply {
            marginEnd = dp(context, 16)
        })

        // Insert at index 1 right beneath the server Overview/header row
        val insertIndex = if (container.childCount > 1) 1 else 0
        container.addView(entry, insertIndex, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            entryHeight
        ))
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        screen?.dismiss()
        screen = null
    }

    private fun dp(context: Context, value: Int) =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val TAG_ENTRY = "missing_server_settings_hub_button"
    }
}
