package com.adham1223990.serverapplicationfix

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.api.CommandsAPI
import com.aliucord.entities.Plugin
import com.aliucord.patcher.PreHook

import com.discord.databinding.WidgetGuildContextMenuBinding
import com.discord.models.domain.ModelInvite
import com.discord.stores.StoreStream
import com.discord.widgets.guilds.contextmenu.GuildContextMenuViewModel
import com.discord.widgets.guilds.contextmenu.WidgetGuildContextMenu
import com.discord.widgets.guilds.join.GuildJoinHelperKt
import com.discord.widgets.servers.member_verification.WidgetMemberVerification

import com.lytefast.flexinput.R
import kotlin.jvm.functions.Function1
import rx.Subscription
import kotlin.concurrent.thread

@AliucordPlugin(requiresRestart = false)
@Suppress("unused")
class ServerApplicationFix : Plugin() {

    init {
        settingsTab = SettingsTab(ApplicationsSettingsPage::class.java).withArgs(settings)
    }

    private val checkedGuilds = HashSet<String>()

    /** استدعاء WidgetMemberVerification.create الأصلي المحفوظ، عشان نشغّله لو الفورم مجرد قوانين. */
    private class NativeCall(val method: java.lang.reflect.Method, val target: Any?, val args: Array<Any?>)

    private val nativeCalls = java.util.concurrent.ConcurrentHashMap<String, NativeCall>()

    @Volatile
    private var bypassNative = false
    private var guildSelectedSubscription: Subscription? = null

    override fun start(context: Context) {
        ApplicationStore.init(settings)

        // الطلبات بتتبعت عن طريق ApplicationApi باستخدام Http.Request.newDiscordRNRequest
        // الرسمية، فمفيش هنا أي hooks على الهيدرز ولا تزوير للـ User-Agent أو الـ Super Properties.

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

                        // member == null معناها بيانات السيرفر لسه بتتحمّل (بيحصل فور ما التطبيق
                        // يفتح بعد ريستارت، أو لحظيًا لسيرفر جديد) — مش إن المستخدم "مش عضو".
                        // كنا بنعامل null كإشارة "افتح الفورم" فكان بيفتح فورم قديم كل ما تعمل
                        // ريستارت للسيرفر اللي كان مفتوح قبل ما تقفل التطبيق. دلوقتي منسيبهوش.
                        if (member == null) return@V

                        // الإشارة الصحيحة إن العضو محتاج تقديم هي getPending() فعلاً (Membership
                        // Screening/Join Request)، مش roles.isEmpty() — كتير من الأعضاء الكاملين
                        // (اتقبلوا فعلاً) مالهومش أي رتب أصلاً وده كان بيخليهم ياخدوا الفورم كل
                        // ما يدخلوا السيرفر حتى لو بس بيتصفحوا محتواه.
                        if (member.getPending()) {
                            checkAndTriggerApplication(guildId, isAuto = true)
                        } else {
                            clearGuildState(guildId)
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
            val joinGuildMethod = GuildJoinHelperKt::class.java.declaredMethods
                .firstOrNull { it.name == "joinGuild" }

            if (joinGuildMethod == null) {
                logger.error("ServerApplicationFix: joinGuild not found on GuildJoinHelperKt", null)
                Utils.showToast("ServerApplicationFix: joinGuild not found, plugin needs an update.", false)
            } else {
                val onNextIndex = joinGuildMethod.parameterCount - 1
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
                                        val idMethod = guildAny.javaClass.getMethod("getId")
                                        val gid = idMethod.invoke(guildAny)
                                        logger.info("ServerApplicationFix: joinGuild succeeded for guild $gid")
                                        checkAndTriggerApplication(gid.toString(), isAuto = true)
                                    }
                                } catch (e: Throwable) {
                                    logger.error("Failed to read joined guild id", e)
                                }
                            }
                        }
                        param.args[onNextIndex] = wrapped
                    } catch (e: Throwable) {
                        logger.error("Failed to wrap joinGuild onNext", e)
                    }
                })
                logger.info("ServerApplicationFix: patched GuildJoinHelperKt.joinGuild (onNext at index $onNextIndex)")
            }
        } catch (e: Throwable) {
            logger.error("Failed to patch GuildJoinHelperKt.joinGuild", e)
        }

        // 2.6 التصحيح الفعلي: لما السيرفر فيه Membership Screening (Verification Gate)،
        // دسكورد أصلاً مش بيعدي على GuildJoinHelperKt.joinGuild خالص — بيقفز على طول لفتح
        // شاشته الأصلية عن طريق MemberVerificationUtils.showMemberVerificationWidget() اللي
        // بتنده على WidgetMemberVerification.Companion.create(...). ده نقطة الاختناق، فبنعمل
        // PreHook هنا ونمنع تنفيذ الأصلي بالكامل (param.setResult(null)) ونفتح ApplicationPage
        // بتاعتنا بدالها.
        //
        // ملحوظة مهمة: create() فيها أكتر من overload — مش بس النسخة اللي بتاخد ModelInvite (لما
        // الانضمام جاي من دعوة). لما تقدّم على سيرفر عن طريق "Apply to Join" من بروفايل السيرفر
        // نفسه (مش من رابط دعوة)، دسكورد بينده على overload تاني بباراميترات مختلفة، وكنا بنهوك
        // النسخة اللي فيها ModelInvite بس. فكان أي فورم بييجي من overload تاني بيعدي من غير ما
        // نتدخل خالص، وده اللي بيفسّر إن أسئلة زي دي (سؤال واحد multiple choice، مش جاي من
        // دعوة) كانت بتفتح شاشة ديسكورد الأصلية عادي. دلوقتي بنهوك كل overload اسمه create.
        try {
            val createMethods = WidgetMemberVerification.Companion::class.java.declaredMethods
                .filter { it.name == "create" }

            if (createMethods.isEmpty()) {
                logger.error("ServerApplicationFix: no create() overload found on WidgetMemberVerification.Companion", null)
                Utils.showToast("ServerApplicationFix: couldn't hook the native verification screen, plugin needs an update.", false)
            }

            for (createMethod in createMethods) {
                val paramTypes = createMethod.parameterTypes
                // الـ guildId هو أول باراميتر من نوع Long/long في الـ overload ده.
                val guildIdIndex = paramTypes.indexOfFirst { it == java.lang.Long.TYPE || it == java.lang.Long::class.java }
                if (guildIdIndex == -1) {
                    logger.error("ServerApplicationFix: create() overload without a guildId param, skipping: $createMethod", null)
                    continue
                }
                // أول باراميتر من نوع ModelInvite لو موجود؛ overloads الـ "Apply to Join" (مش من
                // دعوة) غالبًا ملهاش الباراميتر ده خالص.
                val inviteIndex = paramTypes.indexOfFirst { ModelInvite::class.java.isAssignableFrom(it) }

                patcher.patch(createMethod, PreHook { param ->
                    try {
                        if (bypassNative) return@PreHook
                        val guildIdLong = param.args[guildIdIndex] as Long
                        val guildId = guildIdLong.toString()
                        // عضو كامل: مانتدخلش خالص (مفيش تقديم يتعرض)
                        if (ApplicationApi.isFullMember(guildId)) return@PreHook
                        nativeCalls[guildId] = NativeCall(createMethod, param.thisObject, param.args.copyOf())
                        val invite = if (inviteIndex != -1) param.args[inviteIndex] as? ModelInvite else null
                        val inviteCode = inviteCodeOf(invite)
                        if (!inviteCode.isNullOrEmpty()) ApplicationApi.inviteCodes[guildId] = inviteCode
                        val inviteGuildName = guildNameOf(invite)
                        if (!inviteGuildName.isNullOrEmpty()) ApplicationApi.guildNames[guildId] = inviteGuildName
                        logger.info("ServerApplicationFix: intercepted native WidgetMemberVerification.create(${paramTypes.joinToString { it.simpleName }}) for guild $guildId (invite: $inviteCode)")
                        checkAndTriggerApplication(guildId, isAuto = false)
                        param.setResult(null)
                    } catch (e: Throwable) {
                        logger.error("Failed to intercept WidgetMemberVerification.create for guild", e)
                    }
                })
                logger.info("ServerApplicationFix: patched WidgetMemberVerification.Companion.create(${paramTypes.joinToString { it.simpleName }})")
            }
        } catch (e: Throwable) {
            logger.error("Failed to patch WidgetMemberVerification.Companion.create", e)
            Utils.showToast("ServerApplicationFix: couldn't hook the native verification screen, plugin needs an update.", false)
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
        nativeCalls.clear()
        ApplicationApi.openPages.clear()
    }

    /**
     * true لو مفيش داعي نعرض التقديم تلقائيًا: المستخدم عضو كامل (مش pending)، أو قدّم فعلاً
     * (طلبه PENDING/REJECTED/APPROVED)، أو قدّمنا بنجاح في الجلسة دي.
     */
    private fun shouldSkipAuto(guildId: String): Boolean {
        return try {
            val gid = guildId.toLong()
            val meId = StoreStream.getUsers().me.id
            val member = StoreStream.getGuilds().getMember(gid, meId)
            if (member != null && !member.getPending()) {
                clearGuildState(guildId)
                return true
            }

            // الحالة المحلية ممكن تكون قديمة، فلو قالت "مقدّم" نتأكد من ديسكورد نفسه.
            val localName = ApplicationApi.joinRequestStatusName(gid)
            val local = if (localName != null) ApplicationApi.normalizeStatus(localName) else null
            val looksApplied = ApplicationApi.appliedGuilds.contains(guildId) ||
                (local != null && ApplicationApi.isFinalStatus(local))
            if (!looksApplied) return false

            val remote = ApplicationApi.fetchJoinRequest(guildId)
            val effective = if (remote != null) remote.status else local
            if (effective != null && ApplicationApi.isFinalStatus(effective)) return true

            // الطلب القديم خلص (مثلاً اتقبلت ثم خرجت): نسمح بتقديم جديد.
            ApplicationApi.appliedGuilds.remove(guildId)
            false
        } catch (t: Throwable) {
            logger.error("shouldSkipAuto failed for guild $guildId", t)
            false
        }
    }

    /** يمسح الحالة المحفوظة في الذاكرة لسيرفر معين عشان التقديم يشتغل من جديد لو رجعت له. */
    private fun clearGuildState(guildId: String) {
        checkedGuilds.remove(guildId)
        ApplicationApi.appliedGuilds.remove(guildId)
        ApplicationApi.prefetchedForms.remove(guildId)
    }

    /** اسم السيرفر من الدعوة بالـ reflection (بدل guild.getName() اللي مش موجود في الـ stubs). */
    private fun guildNameOf(invite: ModelInvite?): String? {
        if (invite == null) return null
        return try {
            val guild = try {
                ModelInvite::class.java.getDeclaredField("guild").apply { isAccessible = true }.get(invite)
            } catch (t: Throwable) {
                ModelInvite::class.java.getMethod("getGuild").invoke(invite)
            } ?: return null
            try {
                guild.javaClass.getMethod("getName").invoke(guild) as? String
            } catch (t: Throwable) {
                guild.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(guild) as? String
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** يجيب كود الدعوة من ModelInvite بالـ reflection (حقل code أو getCode) عشان منعتمدش على اسم ثابت. */
    private fun inviteCodeOf(invite: ModelInvite?): String? {
        if (invite == null) return null
        try {
            val f = ModelInvite::class.java.getDeclaredField("code")
            f.isAccessible = true
            val v = f.get(invite) as? String
            if (!v.isNullOrEmpty()) return v
        } catch (t: Throwable) {
        }
        return try {
            ModelInvite::class.java.getMethod("getCode").invoke(invite) as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun getSafeActivity(): Activity {
        return Utils.appActivity
    }

    /**
     * Uses ApplicationApi (official Aliucord RN requests) both to check whether a form exists
     * AND to open our custom ApplicationPage instead of Discord's native verification screen.
     */
    fun checkAndTriggerApplication(guildId: String, isAuto: Boolean) {
        thread {
            try {
                // التشغيل التلقائي مايتعملش لو المستخدم عضو كامل أو قدّم قبل كده — نفس منطق
                // MemberVerificationUtils.maybeShowVerificationGate في التطبيق الأصلي.
                if (isAuto && shouldSkipAuto(guildId)) return@thread

                // عضو كامل: مفيش تقديم يتعرض ولا توست
                if (ApplicationApi.isFullMember(guildId)) {
                    clearGuildState(guildId)
                    return@thread
                }

                val form = ApplicationApi.fetchForm(guildId)

                if (form.fields.isEmpty()) {
                    if (!isAuto) Utils.showToast("No active application for this server.", false)
                    return@thread
                }

                // فورم قوانين بس (TERMS): مش تقديم
                val isApplication = ApplicationApi.isApplicationForm(form)
                val native = nativeCalls.remove(guildId)
                if (!isApplication) {
                    // مش تقديم: صفحة البلوقن ماتظهرش أبدًا. لو الاستدعاء جاي من شاشة ديسكورد
                    // الأصلية نشغّلها، وغير كده ماتعملش حاجة.
                    if (native == null) return@thread
                    Handler(Looper.getMainLooper()).post {
                        try {
                            bypassNative = true
                            native.method.isAccessible = true
                            native.method.invoke(native.target, *native.args)
                        } catch (t: Throwable) {
                            logger.error("Failed to run native verification for guild $guildId", t)
                        } finally {
                            bypassNative = false
                        }
                    }
                    return@thread
                }

                // حماية من التكرار عن طريق API ديسكورد: لو فيه طلب موجود فعلاً منفتحش الفورم
                val existing = if (isApplication) ApplicationApi.fetchJoinRequest(guildId) else null
                if (existing != null && ApplicationApi.isFinalStatus(existing.status)) {
                    ApplicationStore.record(guildId, ApplicationApi.guildNames[guildId], existing.status)
                    ApplicationApi.appliedGuilds.add(guildId)
                    checkedGuilds.add(guildId)
                    if (!isAuto) {
                        Utils.showToast(
                            "You already applied to this server (${ApplicationApi.statusLabel(existing.status)}).",
                            true
                        )
                    }
                    return@thread
                }

                // طلب قديم منتهي (اتقبلت ثم خرجت): مانعتبرش إنك قدمت
                ApplicationApi.appliedGuilds.remove(guildId)

                // صفحة واحدة بس لكل سيرفر: أكتر من hook (join/select/create) كانوا بيفتحوا صفحات
                // مكررة فتفضل صفحة مفتوحة حتى بعد ما تقدم وتتقفل الأولى.
                if (!ApplicationApi.openPages.add(guildId)) return@thread

                checkedGuilds.add(guildId)
                ApplicationApi.prefetchedForms[guildId] = form

                Handler(Looper.getMainLooper()).post {
                    try {
                        Utils.openPageWithProxy(getSafeActivity(), ApplicationPage(guildId))
                    } catch (t: Throwable) {
                        ApplicationApi.openPages.remove(guildId)
                        logger.error("Failed to open application page for guild $guildId", t)
                    }
                }
            } catch (e: ApplicationApiException) {
                logger.error("member-verification check failed for guild $guildId: HTTP ${e.statusCode}", e)
                if (!isAuto) Utils.showToast("Failed to check: ${e.message}", false)
            } catch (e: Exception) {
                logger.error("Auto check error for guild $guildId", e)
                if (!isAuto) {
                    Utils.showToast("Failed to check: ${e.message}", false)
                }
            }
        }
    }
}
