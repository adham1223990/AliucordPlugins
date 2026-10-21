package com.adham1223990.serverapplicationfix

import android.app.Activity
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
import com.aliucord.patcher.InsteadHook
import com.aliucord.patcher.PreHook
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
        if (settings.getString(FormStyle.KEY_TEXT_COLOR, null) == null) {
            settings.setString(FormStyle.KEY_TEXT_COLOR, FormStyle.DEFAULT_TEXT_COLOR_HEX)
        }

        ApplicationsSettingsPage.openPage = { ctx, page ->
            pages.add(page)
            Utils.openPageWithProxy(ctx, page)
        }

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

        hookMemberVerificationWidgets()
        patchNativeSubmit()
        patchInvalidVerificationState()
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
                            val inviteCode = extractInviteCodeFromObject(thisObj)

                            FormStyle.applyTextColorRecursively(rootView, settings)
                            interceptApplicationStatusButtons(rootView, guildId, inviteCode)
                        })
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    @Volatile
    private var nativeSubmitting = false

    private fun patchNativeSubmit() {
        try {
            val vmClass = Class.forName(
                "com.discord.widgets.servers.member_verification.WidgetMemberVerificationViewModel"
            )
            patcher.patch(
                vmClass.getDeclaredMethod("applyToJoinGuild"),
                InsteadHook { cf ->
                    handleNativeSubmit(cf.thisObject)
                    null
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun findFieldValue(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(target)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun callNoArg(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val m = c.getDeclaredMethod(name)
                m.isAccessible = true
                return m.invoke(target)
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun handleNativeSubmit(vm: Any) {
        if (nativeSubmitting) return
        val guildId = (findFieldValue(vm, "guildId") as? Long) ?: return
        val inviteCode = (findFieldValue(vm, "inviteCode") as? String)
            ?: extractInviteCodeFromObject(vm)
            ?: ApplicationStore.getByGuildId(settings, guildId)?.inviteCode
        val viewState = callNoArg(vm, "getViewState")
        val termsApproved = (viewState?.let { callNoArg(it, "isTermsApproved") } as? Boolean) ?: false
        val guildObj = viewState?.let { callNoArg(it, "getGuild") }
        val guildName = guildObj?.let { callNoArg(it, "getName") } as? String
        val userInput = HashMap<Int, Any?>()
        (findFieldValue(vm, "formUserInputDataMap") as? Map<*, *>)?.forEach { (k, v) ->
            if (k is Int) userInput[k] = v
        }

        nativeSubmitting = true
        Utils.showToast("Submitting application...", false)
        Utils.threadPool.execute {
            try {
                val form = ApplicationApi.fetchMemberVerificationForm(guildId, inviteCode).getOrElse { err ->
                    Utils.mainThread.post {
                        Utils.showToast("Could not load the application form: ${err.message}", true)
                    }
                    return@execute
                }

                val answers = form.fields.mapIndexed { index, field ->
                    val response: Any? = if (field.fieldType.equals("TERMS", ignoreCase = true)) {
                        termsApproved
                    } else {
                        userInput[index]
                    }
                    field to response
                }

                val res = ApplicationApi.submitGuildApplication(guildId, form.version, answers)
                Utils.mainThread.post {
                    if (res.ok) {
                        val status = res.applicationStatus ?: "PENDING"
                        ApplicationStore.upsert(
                            settings,
                            SavedApplicationRecord(
                                guildId = guildId,
                                guildName = guildName ?: form.guildName ?: "Server $guildId",
                                guildIcon = form.guildIcon,
                                requestId = res.requestId,
                                status = status,
                                rejectionReason = res.rejectionReason,
                                submittedAt = res.submittedAt,
                                inviteCode = inviteCode,
                                formDescription = form.description
                            )
                        )
                        emitNativeSuccess(vm, guildId, guildName, status, res.submittedAt)
                    } else {
                        Utils.showToast(
                            "Application failed: ${res.errorMessage ?: "HTTP ${res.statusCode}"}",
                            true
                        )
                    }
                }
            } catch (t: Throwable) {
                Utils.mainThread.post {
                    Utils.showToast("Application failed: ${t.message}", true)
                }
            } finally {
                nativeSubmitting = false
            }
        }
    }

    private fun emitNativeSuccess(vm: Any, guildId: Long, guildName: String?, status: String, lastSeen: String?) {
        try {
            val loader = vm.javaClass.classLoader
            val statusClass = Class.forName("com.discord.api.guildjoinrequest.ApplicationStatus", true, loader)
            val constants = statusClass.enumConstants
            val wanted = if (status.equals("APPROVED", true)) "APPROVED" else "PENDING"
            val statusValue = constants.first { (it as Enum<*>).name == wanted }

            val successClass = Class.forName(
                "com.discord.widgets.servers.member_verification.WidgetMemberVerificationViewModel\$Event\$Success",
                true,
                loader
            )
            val ctor = successClass.getDeclaredConstructor(
                Long::class.javaObjectType,
                String::class.java,
                statusClass,
                String::class.java
            )
            ctor.isAccessible = true
            val event = ctor.newInstance(
                guildId,
                guildName,
                statusValue,
                if (wanted == "APPROVED") null else lastSeen
            )

            val subject = findFieldValue(vm, "eventSubject") ?: return
            val inner = runCatching { findFieldValue(subject, "k") }.getOrNull()
            val candidates = listOfNotNull(inner, subject)
            for (target in candidates) {
                val m = target.javaClass.methods.firstOrNull { it.name == "onNext" && it.parameterTypes.size == 1 }
                if (m != null) {
                    m.invoke(target, event)
                    return
                }
            }
        } catch (t: Throwable) {
            Utils.showToast("Application submitted.", false)
        }
    }

    private fun patchInvalidVerificationState() {
        try {
            val widgetClass = Class.forName(
                "com.discord.widgets.servers.member_verification.WidgetMemberVerification"
            )
            val stateClass = Class.forName(
                "com.discord.widgets.servers.member_verification.WidgetMemberVerificationViewModel\$ViewState"
            )
            patcher.patch(
                widgetClass.getDeclaredMethod("configureUI", stateClass),
                PreHook { cf ->
                    val state = cf.args[0] ?: return@PreHook
                    if (!state.javaClass.name.endsWith("ViewState\$Invalid")) return@PreHook

                    val widget = cf.thisObject ?: return@PreHook
                    val vm = callNoArg(widget, "getViewModel") ?: return@PreHook
                    val guildId = (findFieldValue(vm, "guildId") as? Long) ?: return@PreHook
                    val inviteCode = (findFieldValue(vm, "inviteCode") as? String)
                        ?: extractInviteCodeFromObject(vm)
                        ?: extractInviteCodeFromObject(widget)
                        ?: ApplicationStore.getByGuildId(settings, guildId)?.inviteCode

                    cf.result = null
                    val activity = callNoArg(widget, "requireActivity") as? Activity
                    val ctx = activity ?: Utils.appActivity
                    val guildName = StoreStream.getGuilds().guilds[guildId]?.name
                    val page = ApplicationPage(settings, guildId, guildName, inviteCode)
                    ApplicationsSettingsPage.openPage?.invoke(ctx, page)
                        ?: Utils.openPageWithProxy(ctx, page)
                    activity?.finish()
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun interceptApplicationStatusButtons(root: View, guildId: Long, inviteCode: String? = null) {
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
                            val effectiveInvite = inviteCode ?: ApplicationStore.getByGuildId(settings, targetGuildId)?.inviteCode
                            ApplicationStore.upsert(
                                settings,
                                SavedApplicationRecord(
                                    guildId = targetGuildId,
                                    guildName = guildName ?: "Server $targetGuildId",
                                    status = "STARTED",
                                    inviteCode = effectiveInvite
                                )
                            )
                            val page = ApplicationPage(settings, targetGuildId, guildName, effectiveInvite)
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

    private fun extractInviteCodeFromObject(instance: Any): String? {
        try {
            var c: Class<*>? = instance.javaClass
            while (c != null) {
                for (field in c.declaredFields) {
                    field.isAccessible = true
                    val v = field.get(instance) ?: continue
                    val fName = field.name.lowercase()
                    if (fName.contains("invite") && v is String) {
                        return ApplicationApi.cleanInviteCode(v)
                    }
                    if (v.javaClass.simpleName.contains("Invite", ignoreCase = true)) {
                        val code = runCatching { callNoArg(v, "getCode") as? String }.getOrNull()
                            ?: runCatching { findFieldValue(v, "code") as? String }.getOrNull()
                        if (!code.isNullOrBlankSafe()) return ApplicationApi.cleanInviteCode(code)
                    }
                }
                c = c.superclass
            }
        } catch (_: Throwable) {
        }
        return null
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