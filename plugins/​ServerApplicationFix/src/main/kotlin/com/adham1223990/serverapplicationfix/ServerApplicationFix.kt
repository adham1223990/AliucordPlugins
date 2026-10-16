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
import com.aliucord.patcher.PreHook
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.widgets.guilds.join.GuildJoinHelperKt
import com.discord.widgets.settings.WidgetSettings
import java.util.Collections
import java.util.WeakHashMap
import kotlin.jvm.functions.Function1

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

        // 3. The actual, reliable join trigger: every join path (invite link, "Join" button in
        // a message, etc.) funnels through GuildJoinHelperKt.joinGuild(...). We wrap its onNext
        // callback (fired once the join truly succeeds) so a pending application is detected
        // and opened immediately, instead of depending on scraping a visible button somewhere.
        hookGuildJoin()
    }

    private fun hookGuildJoin() {
        val joinGuildMethod = GuildJoinHelperKt::class.java.declaredMethods
            .firstOrNull { it.name == "joinGuild" }

        if (joinGuildMethod == null) {
            logger.error("ServerApplicationFix: joinGuild not found on GuildJoinHelperKt", null)
            return
        }

        // The onNext(Guild) callback is always the last parameter — found by position instead
        // of an exact parameter-type list, since the plugin build stub and the real APK don't
        // always agree on every parameter type and a mismatched getDeclaredMethod(...) call
        // throws silently, which is exactly how this hook went missing last time.
        val onNextIndex = joinGuildMethod.parameterCount - 1
        try {
            patcher.patch(joinGuildMethod, PreHook { param ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    val originalOnNext = param.args[onNextIndex] as? Function1<Any?, Unit>
                    val wrapped: (Any?) -> Unit = { guildAny ->
                        try {
                            originalOnNext?.invoke(guildAny)
                        } finally {
                            try {
                                if (guildAny != null) {
                                    val gid = guildAny.javaClass.getMethod("getId").invoke(guildAny) as? Long
                                    if (gid != null && gid > 0L) onGuildJoined(gid)
                                }
                            } catch (e: Throwable) {
                                logger.error("ServerApplicationFix: failed to read joined guild id", e)
                            }
                        }
                    }
                    param.args[onNextIndex] = wrapped
                } catch (e: Throwable) {
                    logger.error("ServerApplicationFix: failed to wrap joinGuild onNext", e)
                }
            })
            logger.info("ServerApplicationFix: patched GuildJoinHelperKt.joinGuild (onNext at index $onNextIndex)")
        } catch (e: Throwable) {
            logger.error("ServerApplicationFix: failed to patch joinGuild", e)
        }
    }

    // Runs off the main thread (network call), checks whether the just-joined guild actually
    // has an active member-verification form, records it, then opens ApplicationPage.
    private fun onGuildJoined(guildId: Long) {
        Utils.threadPool.execute {
            val form = ApplicationApi.fetchMemberVerificationForm(guildId).getOrNull() ?: return@execute
            if (form.fields.isEmpty()) return@execute

            val guildName = form.guildName
                ?: StoreStream.getGuilds().guilds[guildId]?.name
                ?: "Server $guildId"

            ApplicationStore.upsert(
                settings,
                SavedApplicationRecord(
                    guildId = guildId,
                    guildName = guildName,
                    guildIcon = form.guildIcon,
                    requestId = form.requestId,
                    status = form.joinRequestStatus ?: "STARTED",
                    formDescription = form.description
                )
            )

            Utils.mainThread.post {
                val page = ApplicationPage(settings, guildId, guildName)
                ApplicationsSettingsPage.openPage?.invoke(Utils.appActivity, page)
                    ?: Utils.openPageWithProxy(Utils.appActivity, page)
            }
        }
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
