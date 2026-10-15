package com.adham1223990.serverapplicationfix

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
import com.aliucord.fragments.SettingsPage
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.settings.WidgetSettings
import java.util.Collections
import java.util.WeakHashMap

@AliucordPlugin
class ServerApplicationFix : Plugin() {

    private val rows = WeakHashMap<WidgetSettings, View>()
    private val pages = Collections.newSetFromMap(WeakHashMap<SettingsPage, Boolean>())

    init {
        settingsTab = SettingsTab(
            PluginTextColorSettingsPage::class.java,
            SettingsTab.Type.PAGE
        ).withArgs(settings)
    }

    override fun start(context: Context) {
        // Ensure default text color is Pure White (#FFFFFF) if not set
        if (settings.getString(FormStyle.KEY_TEXT_COLOR, null) == null) {
            settings.setString(FormStyle.KEY_TEXT_COLOR, FormStyle.DEFAULT_TEXT_COLOR_HEX)
        }

        ApplicationsSettingsPage.openPage = { ctx, page ->
            pages.add(page)
            Utils.openPageWithProxy(ctx, page)
        }

        // 1. Inject "View Applications Status" button in Discord Settings using Shop plugin's exact approach
        patcher.patch(
            WidgetSettings::class.java,
            "onViewBound",
            arrayOf(View::class.java),
            Hook {
                insertRow(it.thisObject as WidgetSettings)
            }
        )
        patcher.patch(
            WidgetSettings::class.java,
            "onViewBoundOrOnResume",
            emptyArray(),
            Hook {
                insertRow(it.thisObject as WidgetSettings)
            }
        )

        // 2. Patch Member Verification & Join Request widgets to fix "View Application Status"
        hookMemberVerificationWidgets()
    }

    private fun insertRow(settingsWidget: WidgetSettings) {
        val anchor = settingsWidget.view?.findViewById<View>(Utils.getResId("qr_scanner", "id")) ?: return
        val parent = anchor.parent as? ViewGroup ?: return
        if (parent.findViewWithTag<View>(ROW_TAG) != null) return

        val template = LayoutInflater.from(anchor.context).inflate(
            Utils.getResId("widget_settings", "layout"),
            null
        )
        val row = template.findViewById<TextView>(Utils.getResId("connections", "id")) ?: return
        (row.parent as? ViewGroup)?.removeView(row)

        row.id = View.NO_ID
        row.tag = ROW_TAG
        row.text = "View Applications Status"
        row.setTextColor(FormStyle.getTextColor(settings))

        val referenceIcon = row.compoundDrawablesRelative[0]
        val iconResId = listOf(
            "ic_assignment_24dp",
            "ic_FactCheck_24dp",
            "ic_shield_24dp",
            "ic_check_circle_24dp",
            "ic_ChannelList_24dp",
            "ic_sticker_shop_icon_32dp"
        ).map { Utils.getResId(it, "drawable") }.firstOrNull { it != 0 } ?: 0

        val icon = if (iconResId != 0) {
            AppCompatResources.getDrawable(row.context, iconResId)?.mutate()
        } else {
            referenceIcon?.mutate()
        }

        val size = (24 * row.resources.displayMetrics.density + 0.5f).toInt()
        icon?.setBounds(
            0,
            0,
            referenceIcon?.bounds?.width() ?: size,
            referenceIcon?.bounds?.height() ?: size
        )

        val settingsTint = ColorStateList.valueOf(
            ColorCompat.getThemedColor(row.context, Utils.getResId("colorInteractiveNormal", "attr"))
        )
        icon?.setTintList(settingsTint)
        row.setCompoundDrawablesRelative(icon, null, null, null)
        TextViewCompat.setCompoundDrawableTintList(row, settingsTint)

        row.setOnClickListener {
            if (settingsWidget.isAdded) {
                ApplicationsSettingsPage.openPage?.invoke(
                    row.context,
                    ApplicationsSettingsPage(settings)
                )
            }
        }

        parent.addView(row, parent.indexOfChild(anchor) + 1)
        rows[settingsWidget] = row
    }

    private fun hookMemberVerificationWidgets() {
        val candidateClasses = listOf(
            "com.discord.widgets.servers.member_verification.WidgetMemberVerification",
            "com.discord.widgets.servers.member_verification.MemberVerificationPendingDialog",
            "com.discord.widgets.servers.member_verification.MemberVerificationRulesAdapter",
            "com.discord.widgets.guilds.invite.WidgetGuildInvite",
            "com.discord.widgets.home.WidgetHomeHeaderManager"
        )

        for (className in candidateClasses) {
            try {
                val clazz = Class.forName(className)
                for (method in clazz.declaredMethods) {
                    if (method.name == "onViewBound" || method.name == "configureUI" || method.name == "onViewBoundOrOnResume") {
                        patcher.patch(method, Hook { param ->
                            val thisObj = param.thisObject ?: return@Hook
                            val rootView = extractRootView(thisObj, param.args) ?: return@Hook
                            val guildId = extractGuildIdFromObject(thisObj)
                                ?: StoreStream.getGuildSelected().selectedGuildId

                            // Apply user's preferred text color (default Pure White #FFFFFF)
                            FormStyle.applyTextColorRecursively(rootView, settings)

                            // Intercept "View Application Status" / "Complete" / "Apply" buttons
                            interceptApplicationStatusButtons(rootView, guildId)
                        })
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun interceptApplicationStatusButtons(root: View, guildId: Long) {
        val queue = ArrayDeque<View>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is TextView) {
                val label = current.text?.toString()?.trim() ?: ""
                if (label.contains("View Application Status", ignoreCase = true) ||
                    label.contains("Application Status", ignoreCase = true) ||
                    label.contains("Complete Application", ignoreCase = true) ||
                    label.contains("Apply to Join", ignoreCase = true)
                ) {
                    current.setTextColor(FormStyle.getTextColor(settings))
                    current.setOnClickListener { v ->
                        val targetGuildId = if (guildId > 0L) guildId else StoreStream.getGuildSelected().selectedGuildId
                        if (targetGuildId > 0L) {
                            val guildName = StoreStream.getGuilds().guilds[targetGuildId]?.name
                            ApplicationStore.upsert(
                                settings,
                                SavedApplicationRecord(
                                    guildId = targetGuildId,
                                    guildName = guildName ?: "Server $targetGuildId",
                                    status = "STARTED"
                                )
                            )
                            val page = ApplicationPage(settings, targetGuildId, guildName)
                            ApplicationsSettingsPage.openPage?.invoke(v.context, page)
                                ?: Utils.openPageWithProxy(v.context, page)
                        } else {
                            val listPage = ApplicationsSettingsPage(settings)
                            ApplicationsSettingsPage.openPage?.invoke(v.context, listPage)
                                ?: Utils.openPageWithProxy(v.context, listPage)
                        }
                    }
                }
            }
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    queue.add(current.getChildAt(i))
                }
            }
        }
    }

    private fun extractRootView(instance: Any, args: Array<Any?>?): View? {
        val fromArgs = args?.firstOrNull { it is View } as? View
        if (fromArgs != null) return fromArgs
        return try {
            val getViewMethod = instance.javaClass.getMethod("getView")
            getViewMethod.invoke(instance) as? View
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractGuildIdFromObject(instance: Any): Long? {
        return try {
            for (field in instance.javaClass.declaredFields) {
                field.isAccessible = true
                val name = field.name.lowercase()
                if (name.contains("guildid") || name == "guild_id") {
                    val value = field.get(instance)
                    if (value is Long && value > 0L) return value
                }
            }
            val getMostRecentIntent = instance.javaClass.getMethod("getMostRecentIntent")
            val intent = getMostRecentIntent.invoke(instance) as? android.content.Intent
            val fromExtra = intent?.getLongExtra("com.discord.intent.extra.EXTRA_GUILD_ID", 0L) ?: 0L
            if (fromExtra > 0L) fromExtra else null
        } catch (_: Throwable) {
            null
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        ApplicationsSettingsPage.openPage = null
        rows.values.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        rows.clear()
        pages.toList().forEach { it.close() }
        pages.clear()
    }

    private companion object {
        const val ROW_TAG = "server_application_fix_status_row"
    }
}
