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
import java.util.concurrent.atomic.AtomicBoolean

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

    private val saveScheduled = AtomicBoolean(false)

    /** Batches disk writes: presence updates can arrive hundreds of times per second. */
    private fun scheduleSave() {
        if (saveScheduled.compareAndSet(false, true)) {
            Utils.mainThread.postDelayed({
                saveScheduled.set(false)
                saveRecords()
            }, 3000L)
        }
    }

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
                scheduleSave()
            }
            ClientStatus.IDLE -> {
                record.lastIdle = now
                scheduleSave()
            }
            ClientStatus.DND -> {
                record.lastDnd = now
                scheduleSave()
            }
            else -> {}
        }
    }

    private fun myUserId(): Long = try {
        StoreStream.getUsers().me.getId()
    } catch (_: Throwable) {
        0L
    }

    /**
     * Called when a user does something that is only possible while online (message, typing,
     * reaction, voice) but Discord still shows them as offline/unknown => they are invisible.
     */
    private fun recordInvisibleActivity(userId: Long, actionName: String) {
        if (userId <= 0L || userId == myUserId()) return
        val status = lastKnownStatus[userId]
        if (status == null || status == ClientStatus.OFFLINE || status == ClientStatus.INVISIBLE) {
            val record = records.getOrPut(userId) { PresenceRecord() }
            record.lastSeenOfflineMessage = System.currentTimeMillis()
            record.lastInvisibleAction = actionName
            scheduleSave()
        }
    }

    /** Reads a (possibly private / inherited) field by name; returns null if it does not exist. */
    private fun readField(obj: Any?, name: String): Any? {
        if (obj == null) return null
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
            } catch (_: Exception) {
                return null
            }
            cls = cls.superclass
        }
        return null
    }

    /** Calls a no-arg method by name, trying declared and public methods; null if none match. */
    private fun callNoArg(obj: Any?, vararg names: String): Any? {
        if (obj == null) return null
        for (name in names) {
            try {
                val m = obj.javaClass.getDeclaredMethod(name)
                m.isAccessible = true
                return m.invoke(obj)
            } catch (_: Exception) {
            }
            try {
                return obj.javaClass.getMethod(name).invoke(obj)
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Reads a numeric id from Discord's API models. Field names ("userId", "id") are stable in the
     * obfuscated build while method names are single letters, so fields are tried first and the
     * given method names only act as a fallback.
     */
    private fun extractLong(obj: Any?, vararg names: String): Long? {
        if (obj == null) return null
        for (name in names) {
            val res = readField(obj, name)
            if (res is Number) return res.toLong()
        }
        for (name in names) {
            val res = callNoArg(obj, name)
            if (res is Number) return res.toLong()
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

        // 1. Standard Store observation for already-loaded presences
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

        // 2. Direct Gateway Hook on handlePresenceUpdate & handlePresenceReplace
        // Catches the split-second mobile online spike BEFORE StoreUserPresence flattens/removes it,
        // and checks sub-statuses (desktop/mobile/web) even if main status is masked.
        try {
            for (method in StoreStream::class.java.declaredMethods) {
                if (method.name == "handlePresenceUpdate" && method.parameterTypes.size == 2) {
                    patcher.patch(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val apiPresence = param.args[1] as? com.discord.api.presence.Presence ?: return
                                val userId = apiPresence.g() ?: apiPresence.f()?.id ?: return
                                var effectiveStatus = apiPresence.e()

                                // Check platform-specific statuses (a = desktop, b = mobile, c = web)
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
                                    // Has active status/activity while appearing offline!
                                    recordInvisibleActivity(userId, "Presence Activity")
                                } else {
                                    lastKnownStatus[userId] = ClientStatus.OFFLINE
                                }
                            } catch (e: Exception) {
                                logger.error("handlePresenceUpdate hook", e)
                            }
                        }
                    })
                }
            }
        } catch (e: Exception) {
            logger.error("could not hook handlePresenceUpdate", e)
        }

        // 3. Hook Message Create & Message Update (catches sending or editing a message while invisible)
        try {
            for (method in StoreStream::class.java.declaredMethods) {
                if ((method.name == "handleMessageCreate" || method.name == "handleMessageUpdate") && method.parameterTypes.size == 1) {
                    patcher.patch(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val message = param.args[0] ?: return
                                // com.discord.api.message.Message: the getter is obfuscated ("e"),
                                // so read the "author" field instead (null for partial updates).
                                val author = readField(message, "author") ?: callNoArg(message, "e", "getAuthor") ?: return
                                val authorId = extractLong(author, "id", "getId") ?: return
                                val action = if (method.name == "handleMessageUpdate") "Edited Msg" else "Sent Msg"
                                recordInvisibleActivity(authorId, action)
                            } catch (_: Exception) {}
                        }
                    })
                }
            }
        } catch (e: Exception) {
            logger.error("could not hook messages", e)
        }

        // 4. Hook Typing Start (catches invisible users the moment they touch the keyboard)
        try {
            for (method in StoreStream::class.java.declaredMethods) {
                if (method.name == "handleTypingStart" && method.parameterTypes.size == 1) {
                    patcher.patch(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val typingUser = param.args[0] ?: return
                                val userId = extractLong(typingUser, "userId", "d", "getUserId") ?: return
                                recordInvisibleActivity(userId, "Typing")
                            } catch (_: Exception) {}
                        }
                    })
                }
            }
        } catch (e: Exception) {
            logger.error("could not hook handleTypingStart", e)
        }

        // 5. Hook Reactions Add & Remove (catches invisible users reacting/unreacting to messages)
        try {
            for (method in StoreStream::class.java.declaredMethods) {
                if ((method.name == "handleReactionAdd" || method.name == "handleReactionRemove") && method.parameterTypes.size == 1) {
                    patcher.patch(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val reaction = param.args[0] as? com.discord.api.message.reaction.MessageReactionUpdate ?: return
                                val userId = extractLong(reaction, "userId", "d", "getUserId") ?: return
                                recordInvisibleActivity(userId, "Reaction")
                            } catch (_: Exception) {}
                        }
                    })
                }
            }
        } catch (e: Exception) {
            logger.error("could not hook reactions", e)
        }

        // 6. Hook Voice State Update (catches invisible users joining/moving/muting in voice channels)
        try {
            for (method in StoreStream::class.java.declaredMethods) {
                if (method.name == "handleVoiceStateUpdate" && method.parameterTypes.size == 1) {
                    patcher.patch(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val voiceState = param.args[0] ?: return
                                val userId = extractLong(voiceState, "userId", "m", "getUserId") ?: return
                                recordInvisibleActivity(userId, "Voice")
                            } catch (_: Exception) {}
                        }
                    })
                }
            }
        } catch (e: Exception) {
            logger.error("could not hook handleVoiceStateUpdate", e)
        }

        // Inject the "Last Online:" row right after the About Me card
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
