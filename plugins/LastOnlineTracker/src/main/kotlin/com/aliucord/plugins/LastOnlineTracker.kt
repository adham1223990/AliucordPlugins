package com.aliucord.plugins

import android.content.Context
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.patcher.component1
import com.aliucord.patcher.component2
import com.aliucord.utils.DimenUtils
import com.aliucord.utils.RxUtils.subscribe
import com.discord.api.presence.ClientStatus
import com.discord.models.presence.Presence
import com.discord.stores.StoreStream
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.discord.widgets.user.usersheet.WidgetUserSheetViewModel
import de.robv.android.xposed.XC_MethodHook
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@AliucordPlugin(requiresRestart = false)
class LastOnlineTracker : Plugin() {

    init {
        settingsTab = SettingsTab(LastOnlineSettings::class.java, SettingsTab.Type.PAGE).withArgs(this)
    }

    private val rowViewId = View.generateViewId()
    private val lastKnownStatus = ConcurrentHashMap<Long, ClientStatus>()
    private val records = ConcurrentHashMap<Long, PresenceRecord>()

    data class PresenceRecord(
        var lastOnline: Long? = null,
        var lastIdle: Long? = null,
        var lastDnd: Long? = null,
        var lastSeenOfflineMessage: Long? = null,
        var lastInvisibleAction: String? = null,
    )

    val defaultFormat = "dd/MM/yy, hh:mm:ss a"

    private fun timestampFormat(): String =
        settings.getString("timestamp_format", defaultFormat)

    private fun loadRecords() {
        try {
            val json = JSONObject(settings.getString("presence_records", "{}"))
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = json.getJSONObject(key)
                records[key.toLong()] = PresenceRecord(
                    lastOnline = obj.optLong("online", -1).takeIf { it != -1L },
                    lastIdle = obj.optLong("idle", -1).takeIf { it != -1L },
                    lastDnd = obj.optLong("dnd", -1).takeIf { it != -1L },
                    lastSeenOfflineMessage = obj.optLong("msg", -1).takeIf { it != -1L },
                    lastInvisibleAction = obj.optString("act", "").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            logger.error("loadRecords", e)
        }
    }

    private fun saveRecords() {
        try {
            val json = JSONObject()
            for ((userId, record) in records) {
                val obj = JSONObject()
                record.lastOnline?.let { obj.put("online", it) }
                record.lastIdle?.let { obj.put("idle", it) }
                record.lastDnd?.let { obj.put("dnd", it) }
                record.lastSeenOfflineMessage?.let { obj.put("msg", it) }
                record.lastInvisibleAction?.let { obj.put("act", it) }
                json.put(userId.toString(), obj)
            }
            settings.setString("presence_records", json.toString())
        } catch (e: Exception) {
            logger.error("saveRecords", e)
        }
    }

    private fun recordStatus(userId: Long, status: ClientStatus, now: Long = System.currentTimeMillis()) {
        if (userId <= 0L) return
        lastKnownStatus[userId] = status
        val record = records.getOrPut(userId) { PresenceRecord() }
        when (status) {
            ClientStatus.ONLINE -> {
                record.lastOnline = now
                saveRecords()
            }
            ClientStatus.IDLE -> {
                record.lastIdle = now
                saveRecords()
            }
            ClientStatus.DND -> {
                record.lastDnd = now
                saveRecords()
            }
            else -> {}
        }
    }

    private fun recordInvisibleActivity(userId: Long, actionName: String) {
        if (userId <= 0L) return
        val status = lastKnownStatus[userId]
        if (status == null || status == ClientStatus.OFFLINE || status == ClientStatus.INVISIBLE) {
            val record = records.getOrPut(userId) { PresenceRecord() }
            record.lastSeenOfflineMessage = System.currentTimeMillis()
            record.lastInvisibleAction = actionName
            saveRecords()
        }
    }

    private fun extractLong(obj: Any?, vararg methodNames: String): Long? {
        if (obj == null) return null
        val cls = obj.javaClass
        for (name in methodNames) {
            try {
                val res = cls.getDeclaredMethod(name).apply { isAccessible = true }.invoke(obj)
                if (res is Number) return res.toLong()
            } catch (_: Exception) {}
            try {
                val res = cls.getMethod(name).invoke(obj)
                if (res is Number) return res.toLong()
            } catch (_: Exception) {}
        }
        for (fieldName in listOf("userId", "id")) {
            try {
                val f = cls.getDeclaredField(fieldName).apply { isAccessible = true }
                val res = f.get(obj)
                if (res is Number) return res.toLong()
            } catch (_: Exception) {}
        }
        return null
    }

    fun getLastOnlineText(userId: Long): String {
        val record = records[userId] ?: return "undetected yet"
        val map = mutableMapOf<Long, String>()
        record.lastOnline?.let { map[it] = "Online" }
        record.lastIdle?.let { map[it] = "Idle" }
        record.lastDnd?.let { map[it] = "DND" }
        record.lastSeenOfflineMessage?.let {
            val act = record.lastInvisibleAction ?: "Activity"
            map[it] = "Invisible - $act"
        }

        val latest = map.keys.maxOrNull() ?: return "undetected yet"
        val tag = map[latest] ?: ""

        val formattedDate = try {
            SimpleDateFormat(timestampFormat(), Locale.getDefault()).format(Date(latest))
        } catch (e: Exception) {
            SimpleDateFormat(defaultFormat, Locale.getDefault()).format(Date(latest))
        }

        return "$formattedDate ($tag)"
    }

    override fun start(context: Context) {
        loadRecords()

        // 1. مراقبة الـ Store العادية للأصدقاء والحالات المحملة مسبقاً
        try {
            StoreStream.getPresences().observeAllPresences().subscribe {
                val map: Map<Long, Presence> = this
                val now = System.currentTimeMillis()
                for ((userId, presence) in map) {
                    recordStatus(userId, presence.status, now)
                }
            }
        } catch (e: Exception) {
            logger.error("presence subscription failed", e)
        }

        // 2. الـ Hook المباشر على handlePresenceUpdate في StoreStream (بناءً على فحص الـ JADX)
        // يلتقط الومضات الخاطفة للموبايل وتحديثات الحضور الخام فور ورودها
        try {
            val method = StoreStream::class.java.getDeclaredMethod("handlePresenceUpdate", Long::class.javaPrimitiveType, com.discord.api.presence.Presence::class.java)
            patcher.patch(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val apiPresence = param.args[1] as? com.discord.api.presence.Presence ?: return
                        val userId = apiPresence.g() ?: apiPresence.f()?.id ?: return
                        var effectiveStatus = apiPresence.e()

                        // فحص حالات الأجهزة الفرعية (Desktop / Mobile / Web) حتى لو كانت الحالة العامة مطفأة
                        if (effectiveStatus == ClientStatus.OFFLINE || effectiveStatus == ClientStatus.INVISIBLE) {
                            val platforms = apiPresence.c()
                            if (platforms != null) {
                                val pStatus = listOf(platforms.a(), platforms.b(), platforms.c())
                                    .firstOrNull { it != ClientStatus.OFFLINE && it != ClientStatus.INVISIBLE }
                                if (pStatus != null) effectiveStatus = pStatus
                            }
                        }

                        if (effectiveStatus != ClientStatus.OFFLINE && effectiveStatus != ClientStatus.INVISIBLE) {
                            recordStatus(userId, effectiveStatus)
                        } else if (!apiPresence.b().isNullOrEmpty()) {
                            recordInvisibleActivity(userId, "Presence Activity")
                        } else {
                            lastKnownStatus[userId] = ClientStatus.OFFLINE
                        }
                    } catch (e: Exception) {
                        logger.error("handlePresenceUpdate hook", e)
                    }
                }
            })
        } catch (e: Exception) {
            logger.error("could not hook handlePresenceUpdate", e)
        }

        // 3. Hook للرسائل (إرسال أو تعديل رسالة أثناء الاختفاء)
        try {
            for (mMethod in StoreStream::class.java.declaredMethods) {
                if ((mMethod.name == "handleMessageCreate" || mMethod.name == "handleMessageUpdate") && mMethod.parameterTypes.size == 1) {
                    patcher.patch(mMethod, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val message = param.args[0] ?: return
                                val author = message.javaClass.getMethod("getAuthor").invoke(message) ?: return
                                val authorId = extractLong(author, "getId", "a") ?: return
                                val action = if (mMethod.name == "handleMessageUpdate") "Edited Msg" else "Sent Msg"
                                recordInvisibleActivity(authorId, action)
                            } catch (_: Exception) {}
                        }
                    })
                }
            }
        } catch (e: Exception) {
            logger.error("could not hook messages", e)
        }

        // 4. Hook لمؤشر الكتابة (Typing) لاكتشاف المتخفي فور لمس الكيبورد
        try {
            val typingMethod = StoreStream::class.java.getDeclaredMethod("handleTypingStart", com.discord.api.user.TypingUser::class.java)
            patcher.patch(typingMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val typingUser = param.args[0] ?: return
                        val userId = extractLong(typingUser, "getUserId", "b", "a") ?: return
                        recordInvisibleActivity(userId, "Typing")
                    } catch (_: Exception) {}
                }
            })
        } catch (e: Exception) {
            logger.error("could not hook handleTypingStart", e)
        }

        // 5. Hook للريأكشنات (إضافة أو إزالة تفاعل)
        try {
            val reactionMethod = StoreStream::class.java.getDeclaredMethod("handleReactionAdd", com.discord.api.message.reaction.MessageReactionUpdate::class.java)
            patcher.patch(reactionMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val reaction = param.args[0] as? com.discord.api.message.reaction.MessageReactionUpdate ?: return
                        val userId = reaction.d()
                        recordInvisibleActivity(userId, "Reaction")
                    } catch (_: Exception) {}
                }
            })
        } catch (e: Exception) {
            logger.error("could not hook reactions", e)
        }

        // 6. Hook للحالة الصوتية (Voice States) لدخول الرومات الصوتية
        try {
            val voiceMethod = StoreStream::class.java.getDeclaredMethod("handleVoiceStateUpdate", com.discord.api.voice.state.VoiceState::class.java)
            patcher.patch(voiceMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val voiceState = param.args[0] ?: return
                        val userId = extractLong(voiceState, "getUserId", "c", "a") ?: return
                        recordInvisibleActivity(userId, "Voice")
                    } catch (_: Exception) {}
                }
            })
        } catch (e: Exception) {
            logger.error("could not hook handleVoiceStateUpdate", e)
        }

        // حقن صفحة معلومات الظهور داخل البروفايل (User Sheet)
        patcher.after<WidgetUserSheet>(
            "configureNote",
            WidgetUserSheetViewModel.ViewState.Loaded::class.java,
        ) { (_, viewState: WidgetUserSheetViewModel.ViewState.Loaded) ->
            try {
                val root = view ?: return@after
                val userId = viewState.user.id

                val content = root.findViewById<LinearLayout>(Utils.getResId("user_sheet_content", "id"))
                    ?: return@after
                val aboutMeCard = content.findViewById<View>(Utils.getResId("about_me_card", "id"))
                    ?: return@after

                val text = "Last Online: ${getLastOnlineText(userId)}"
                val existing = content.findViewById<TextView>(rowViewId)

                if (existing != null) {
                    existing.text = text
                } else {
                    val dp = DimenUtils.defaultPadding
                    val row = TextView(root.context).apply {
                        id = rowViewId
                        setPadding(dp, dp / 2, dp, dp / 2)
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    }
                    row.text = text

                    content.addView(row, content.indexOfChild(aboutMeCard) + 1)
                }
            } catch (e: Exception) {
                logger.error("configureNote hook", e)
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
        saveRecords()
    }
}
