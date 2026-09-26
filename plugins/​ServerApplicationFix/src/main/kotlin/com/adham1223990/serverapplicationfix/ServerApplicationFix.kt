package com.adham1223990.serverapplicationfix

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.aliucord.utils.RNSuperProperties

import com.discord.api.guild.Guild
import com.discord.databinding.WidgetGuildContextMenuBinding
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaHelper
import com.discord.widgets.guilds.contextmenu.GuildContextMenuViewModel
import com.discord.widgets.guilds.contextmenu.WidgetGuildContextMenu
import com.discord.widgets.guilds.join.GuildJoinHelperKt
import com.discord.widgets.servers.member_verification.WidgetMemberVerification

import com.lytefast.flexinput.R
import kotlin.jvm.functions.Function1
import org.json.JSONArray
import org.json.JSONObject
import rx.Subscription
import kotlin.concurrent.thread

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class ServerApplicationFix : Plugin() {

    companion object {
        const val CURRENT_RN_BUILD_NUMBER = 6081
        const val CURRENT_RN_VERSION_CODE = 341200
        const val CURRENT_RN_VERSION = "341.0 - rn"
        const val CURRENT_RN_USER_AGENT = "Discord-Android/$CURRENT_RN_VERSION_CODE;RNA"
    }

    private var cachedSuperProps: String? = null
    private val checkedGuilds = HashSet<String>()
    private var guildSelectedSubscription: Subscription? = null

    override fun start(context: Context) {
        // 1. حقن الـ Super Properties والـ User-Agent
        try {
            val setHeaderMethod = Http.Request::class.java.getDeclaredMethod("setHeader", String::class.java, String::class.java)
            patcher.patch(setHeaderMethod, Hook { param ->
                val request = param.thisObject as? Http.Request ?: return@Hook
                if (request.conn.url.host != "discord.com") return@Hook

                val headerKey = (param.args[0] as? String)?.lowercase() ?: return@Hook
                when (headerKey) {
                    "user-agent" -> request.conn.setRequestProperty("User-Agent", CURRENT_RN_USER_AGENT)
                    "x-super-properties" -> request.conn.setRequestProperty("X-Super-Properties", getSuperProperties())
                }
            })
        } catch (t: Throwable) {
            logger.error("Failed to patch setHeader", t)
        }

        try {
            val newDiscordRNRequestMethod = Http.Request::class.java.getDeclaredMethod("newDiscordRNRequest", String::class.java, String::class.java)
            patcher.patch(newDiscordRNRequestMethod, Hook { param ->
                val request = param.result as? Http.Request ?: return@Hook
                if (request.conn.url.host != "discord.com") return@Hook

                request.conn.setRequestProperty("User-Agent", CURRENT_RN_USER_AGENT)
                request.conn.setRequestProperty("X-Super-Properties", getSuperProperties())
            })
        } catch (t: Throwable) {
            logger.error("Failed to patch newDiscordRNRequest", t)
        }

        // 2. الاستماع التلقائي المباشر لتغيير السيرفر مع تحديد الأنواع بدقة
        //
        // ملحوظة: subscribe(Action1) الحقيقية في هذه النسخة اسمها V (مش subscribe ولا u).
        // تأكدنا من كده من rx/Observable.java نفسه:
        //   public final Subscription V(Action1<? super T> action1)   <- subscribe(onNext)
        //   public final Subscription W(Action1, Action1<Throwable>)  <- subscribe(onNext, onError)
        // أما u(Action1) فبترجع Observable مش Subscription — دي doOnNext مش subscribe،
        // يعني معملتش حاجة فعليًا لو استخدمناها.
        try {
            guildSelectedSubscription = StoreStream.getGuildSelected()
                .observeSelectedGuildId()
                .V({ guildIdLong: Long? ->
                    try {
                        if (guildIdLong == null || guildIdLong == 0L) return@V
                        val guildId = guildIdLong.toString()

                        if (checkedGuilds.contains(guildId)) return@V

                        val me = StoreStream.getUsers().me ?: return@V
                        val meIdLong = me.id.toLong()
                        val targetGuildIdLong = guildIdLong.toLong()
                        val member = StoreStream.getGuilds().getMember(targetGuildIdLong, meIdLong)

                        // إذا كان العضو بدون رتب أو في حالة معاينة يتم التحقق
                        if (member == null || member.roles.isEmpty()) {
                            checkAndTriggerApplication(guildId, isAuto = true)
                        }
                    } catch (e: Throwable) {
                        logger.error("Error handling guild selection", e)
                    }
                })
        } catch (e: Exception) {
            logger.error("Failed to subscribe to observeSelectedGuildId", e)
        }

        // 2.5 الطريقة الأدق: كل طرق الانضمام (رابط، زرار Join في رسالة، ...) بتمر على
        // GuildJoinHelperKt.joinGuild(...) نفسها. بنعمل PreHook (قبل تنفيذ الأصلية) عشان
        // نستبدل الـ onNext (اللي بينفّذ لما الانضمام ينجح فعليًا) بنسخة بتنادي الأصلية
        // وبعدين تتحقق من التطبيق فورًا - مش مضطرين ننتظر أو نعتمد على تغيير السيرفر المختار.
        try {
            val joinGuildMethod = GuildJoinHelperKt::class.java.getDeclaredMethod(
                "joinGuild",
                Context::class.java,
                Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                Long::class.java,
                String::class.java,
                Class::class.java,
                Function1::class.java,
                Function1::class.java,
                CaptchaHelper.CaptchaPayload::class.java,
                Function1::class.java
            )
            patcher.patch(joinGuildMethod, PreHook { param ->
                try {
                    @Suppress("UNCHECKED_CAST")
                    val originalOnNext = param.args[10] as? Function1<Any?, Unit>
                    val wrapped: (Any?) -> Unit = { guildAny ->
                        try {
                            originalOnNext?.invoke(guildAny)
                        } finally {
                            try {
                                val guild = guildAny as? Guild
                                if (guild != null) {
                                    checkAndTriggerApplication(guild.getId().toString(), isAuto = true)
                                }
                            } catch (e: Throwable) {
                                logger.error("Failed to read joined guild id", e)
                            }
                        }
                    }
                    param.args[10] = wrapped
                } catch (e: Throwable) {
                    logger.error("Failed to wrap joinGuild onNext", e)
                }
            })
        } catch (e: Throwable) {
            logger.error("Failed to patch GuildJoinHelperKt.joinGuild", e)
        }

        // 3. خيار الـ Context Menu اليدوي للسيرفر بأيقونة مضمونة التواجد
        val viewId = View.generateViewId()
        val verifyIcon = ContextCompat.getDrawable(Utils.appActivity, R.e.ic_mail_24dp)?.mutate()
            ?: ContextCompat.getDrawable(Utils.appActivity, android.R.drawable.ic_menu_agenda)?.mutate()
        Utils.tintToTheme(verifyIcon)

        val getServerBindingMethod by lazy {
            WidgetGuildContextMenu::class.java.getDeclaredMethod("getBinding").apply { isAccessible = true }
        }
        val gcmvm_cfg = WidgetGuildContextMenu::class.java.getDeclaredMethod(
            "configureUI",
            GuildContextMenuViewModel.ViewState::class.java
        )

        patcher.patch(gcmvm_cfg, PreHook { param ->
            try {
                val validState = param.args[0] as GuildContextMenuViewModel.ViewState.Valid
                val binding = getServerBindingMethod.invoke(param.thisObject) as WidgetGuildContextMenuBinding
                val lay = binding.e.parent as LinearLayout

                lay.removeView(lay.findViewById(viewId))
                if (lay.findViewById<View>(viewId) == null) {
                    val tw = TextView(lay.context, null, 0, R.i.ContextMenuTextOption).apply {
                        id = viewId
                        text = "Member Verification"
                        setCompoundDrawablesRelativeWithIntrinsicBounds(verifyIcon, null, null, null)
                    }
                    lay.addView(tw)

                    tw.setOnClickListener { v ->
                        lay.visibility = View.GONE
                        val guildId = validState.guild.id.toString()
                        checkAndTriggerApplication(guildId, isAuto = false)
                    }
                }
            } catch (ignored: Exception) {}
        })

        // 4. أمر الشات اليدوي
        commands.registerCommand(
            "apply-verify",
            "Open Server Verification / Application Form",
            listOf()
        ) { ctx ->
            val guildId = StoreStream.getGuildSelected().selectedGuildId.toString()
            if (guildId == "0") {
                return@registerCommand CommandsAPI.CommandResult("You are not currently in a server!", null, false)
            }
            checkAndTriggerApplication(guildId, isAuto = false)
            CommandsAPI.CommandResult("Checking application...", null, false)
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        guildSelectedSubscription?.unsubscribe()
        guildSelectedSubscription = null
        checkedGuilds.clear()
    }

    private fun getSafeActivity(): Activity {
        return Utils.appActivity
    }

    private fun getSuperProperties(): String {
        cachedSuperProps?.let { return it }
        val props = try {
            JSONObject(RNSuperProperties.superProperties.toString())
        } catch (t: Throwable) {
            JSONObject()
        }

        props.put("has_client_mods", false)
        props.put("os", "Android")
        props.put("browser", "Discord Android")
        props.put("client_version", CURRENT_RN_VERSION)
        props.put("release_channel", "canaryRelease")
        props.put("client_build_number", CURRENT_RN_BUILD_NUMBER)
        props.put("launch_signature", (System.currentTimeMillis() * 1_000_000L).toString())

        val encoded = Base64.encodeToString(props.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        cachedSuperProps = encoded
        return encoded
    }

    /**
     * بيشيك (عبر REST) هل السيرفر ده فعلاً عنده Membership Screening شغال، وإذا كان الأمر
     * كذلك بيفتح شاشة Discord الرسمية WidgetMemberVerification مباشرة بدل بناء UI مخصص أو
     * تخمين endpoint إرسال الفورم — الشاشة الأصلية بتحمّل وتبعت الفورم صح لوحدها.
     */
    fun checkAndTriggerApplication(guildId: String, isAuto: Boolean) {
        thread {
            try {
                val req = Http.Request.newDiscordRNRequest("/guilds/$guildId/member-verification?with_guild=true", "GET")
                req.setHeader("User-Agent", CURRENT_RN_USER_AGENT)
                req.setHeader("X-Super-Properties", getSuperProperties())

                val response = req.execute()
                if (!response.ok()) {
                    logger.error(
                        "member-verification check failed for guild $guildId: HTTP ${response.statusCode}",
                        null
                    )
                    if (!isAuto) Utils.showToast("No application required or unable to fetch.", false)
                    return@thread
                }

                val root = JSONObject(response.text())
                val formFields = root.optJSONArray("form_fields") ?: JSONArray()

                if (formFields.length() == 0) {
                    if (!isAuto) Utils.showToast("No active application for this server.", false)
                    return@thread
                }

                checkedGuilds.add(guildId)

                Handler(Looper.getMainLooper()).post {
                    val activity = getSafeActivity()
                    val guildIdLong = guildId.toLongOrNull()
                    if (guildIdLong == null) {
                        Utils.showToast("Invalid guild id", false)
                        return@post
                    }
                    WidgetMemberVerification.create(activity, guildIdLong, "aliucord", null)
                }
            } catch (e: Exception) {
                logger.error("Auto check error for guild $guildId", e)
                if (!isAuto) {
                    Utils.showToast("Failed to check: ${e.message}", false)
                }
            }
        }
    }
}
