package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.discord.widgets.user.profile.UserProfileAdminView
import com.discord.widgets.user.usersheet.WidgetUserSheet
import org.json.JSONObject
import java.lang.reflect.Method
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@AliucordPlugin(requiresRestart = false)
class ModernModActions : Plugin() {

    // Official Discord permission bit flags (guild-level), from Discord's own API docs.
    private object Perm {
        const val KICK_MEMBERS = 0x2L
        const val BAN_MEMBERS = 0x4L
        const val ADMINISTRATOR = 0x8L
        const val MODERATE_MEMBERS = 1L shl 40 // Timeout Members
    }

    @Volatile private var activeUserId: Long = 0L
    @Volatile private var activeGuildId: Long = 0L

    // ==========================================================
    // Safe helpers: بدائل لدوال Kotlin stdlib اللي بتعمل كراش
    // (isBlank / trim / toLongOrNull / lowercase / regex / firstOrNull ...)
    // كلها بتستخدم while loops و charAt بس، من غير IntRange/IntIterator.
    // ==========================================================

    private fun isBlankSafe(s: String?): Boolean {
        if (s == null) return true
        var i = 0
        val n = s.length
        while (i < n) {
            if (!Character.isWhitespace(s[i])) return false
            i++
        }
        return true
    }

    private fun trimSafe(s: String?): String {
        if (s == null) return ""
        var start = 0
        var end = s.length
        while (start < end && Character.isWhitespace(s[start])) start++
        while (end > start && Character.isWhitespace(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun findMethod(cls: Class<*>, name: String, paramCount: Int = -1): Method? {
        val all = cls.declaredMethods
        var i = 0
        while (i < all.size) {
            val m = all[i]
            if (m.name == name && (paramCount < 0 || m.parameterTypes.size == paramCount)) return m
            i++
        }
        return null
    }

    private fun findMethods(cls: Class<*>, name: String): List<Method> {
        val result = ArrayList<Method>()
        val all = cls.declaredMethods
        var i = 0
        while (i < all.size) {
            if (all[i].name == name) result.add(all[i])
            i++
        }
        return result
    }

    // ==========================================================

    override fun start(context: Context) {
        patchSheetArgs()
        patchVisibility()
        patchClick("setOnBan", "user_profile_admin_ban", Perm.BAN_MEMBERS) { ctx, guildId, userId ->
            showBanDialog(ctx, guildId, userId)
        }
        patchClick("setOnKick", "user_profile_admin_kick", Perm.KICK_MEMBERS) { ctx, guildId, userId ->
            showKickDialog(ctx, guildId, userId)
        }
        patchClick(
            "setOnDisableCommunication",
            "user_profile_admin_disable_communication",
            Perm.MODERATE_MEMBERS
        ) { ctx, guildId, userId ->
            showTimeoutDialog(ctx, guildId, userId)
        }
    }

    /**
     * WidgetUserSheet.onViewCreated(View, Bundle) بيقرا ARG_USER_ID من الـ arguments بتاعته،
     * فبنعمل hook عليه ونحفظ الـ ids من نفس الـ fragment instance اللي ديسكورد بيربطه.
     */
    private fun patchSheetArgs() {
        val method = findMethod(WidgetUserSheet::class.java, "onViewCreated", 2)
        if (method == null) {
            Utils.showToast(
                "ModernModActions: could NOT find onViewCreated on WidgetUserSheet. " +
                    "Discord's class layout changed; this plugin needs an update."
            )
            return
        }
        try {
            patcher.patch(method, Hook { frame ->
                try {
                    val sheet = frame.thisObject as? WidgetUserSheet ?: return@Hook
                    val args = sheet.arguments ?: return@Hook
                    activeUserId = args.getLong("ARG_USER_ID")
                    val argGuildId = args.getLong("ARG_GUILD_ID")
                    activeGuildId =
                        if (argGuildId != 0L) argGuildId else StoreStream.getGuildSelected().selectedGuildId
                } catch (e: Throwable) {
                    logger.error("ModernModActions: sheet-args hook crashed", e)
                }
            })
            logger.info("ModernModActions: patched WidgetUserSheet.onViewCreated for reliable id capture")
        } catch (e: Throwable) {
            logger.error("ModernModActions: failed to patch WidgetUserSheet.onViewCreated", e)
        }
    }

    /**
     * بنخفي الزرار بس لو الحساب مالوش الصلاحية. عمرنا ما بنجبر زرار يظهر
     * لأن ديسكورد عنده أسباب تانية للإخفاء (مثلاً مينفعش تبان نفسك).
     */
    private fun patchVisibility() {
        val methods = findMethods(UserProfileAdminView::class.java, "updateView")
        if (methods.isEmpty()) {
            Utils.showToast(
                "ModernModActions: could NOT find updateView on UserProfileAdminView. " +
                    "Discord's class layout changed; this plugin needs an update."
            )
            return
        }
        for (method in methods) {
            try {
                patcher.patch(method, Hook { frame ->
                    try {
                        val adminView = frame.thisObject as? UserProfileAdminView ?: return@Hook
                        val guildId = currentGuildId()
                        if (guildId == 0L) return@Hook
                        hideIfMissing(adminView, "user_profile_admin_ban", guildId, Perm.BAN_MEMBERS)
                        hideIfMissing(adminView, "user_profile_admin_kick", guildId, Perm.KICK_MEMBERS)
                        hideIfMissing(
                            adminView,
                            "user_profile_admin_disable_communication",
                            guildId,
                            Perm.MODERATE_MEMBERS
                        )
                    } catch (e: Throwable) {
                        logger.error("ModernModActions: visibility hook crashed", e)
                    }
                })
                logger.info("ModernModActions: patched updateView for permission-based hiding")
            } catch (e: Throwable) {
                logger.error("ModernModActions: failed to patch updateView", e)
            }
        }
    }

    private fun hideIfMissing(root: View, resourceName: String, guildId: Long, permission: Long) {
        if (hasPermission(guildId, permission)) return // leave Discord's own decision alone
        val resId = Utils.getResId(resourceName, "id")
        if (resId == 0) return
        val v = root.findViewById<View>(resId)
        if (v != null) v.visibility = View.GONE
    }

    /**
     * الربط الحقيقي للـ click بيحصل في الـ setters (setOnBan/setOnKick/setOnDisableCommunication).
     * بنعمل patch للـ setter ونعيّن الـ listener بتاعنا بعد ما الأصلي يخلص.
     */
    private fun patchClick(
        setterName: String,
        resourceName: String,
        permission: Long,
        showDialog: (Context, Long, Long) -> Unit
    ) {
        val method = findMethod(UserProfileAdminView::class.java, setterName)
        if (method == null) {
            logger.error("ModernModActions: setter '$setterName' not found on UserProfileAdminView", null)
            Utils.showToast("ModernModActions: '$setterName' not found, this plugin needs an update.")
            return
        }
        try {
            patcher.patch(method, Hook { frame ->
                try {
                    val adminView = frame.thisObject as? UserProfileAdminView ?: return@Hook
                    val resId = Utils.getResId(resourceName, "id")
                    if (resId == 0) {
                        logger.error("ModernModActions: no resource id '$resourceName'", null)
                        return@Hook
                    }
                    val view = adminView.findViewById<View>(resId)
                    if (view == null) {
                        logger.error("ModernModActions: findViewById('$resourceName') returned null", null)
                        return@Hook
                    }
                    view.setOnClickListener {
                        val guildId = currentGuildId()
                        val userId = currentUserId()
                        if (guildId == 0L || userId == 0L) {
                            Utils.showToast("ModernModActions: couldn't identify this member, try reopening the profile.")
                            return@setOnClickListener
                        }
                        if (!hasPermission(guildId, permission)) {
                            Utils.showToast("You don't have permission to do that here.")
                            return@setOnClickListener
                        }
                        showDialog(adminView.context, guildId, userId)
                    }
                    logger.info("ModernModActions: re-wired click for $resourceName via $setterName")
                } catch (e: Throwable) {
                    logger.error("ModernModActions: click hook for $setterName crashed", e)
                }
            })
            logger.info("ModernModActions: patched $setterName")
        } catch (e: Throwable) {
            logger.error("ModernModActions: failed to patch $setterName", e)
        }
    }

    private fun currentUserId(): Long = activeUserId

    private fun currentGuildId(): Long = activeGuildId

    /**
     * بيقرا صلاحيات الحساب من permission store بتاع ديسكورد. لو فشل القراءة بنسمح (fail open)
     * لأن API ديسكورد هيرفض بـ 403 لو الصلاحية فعلاً ناقصة.
     */
    private fun currentGuildPermissions(guildId: Long): Long? = try {
        StoreStream.getPermissions().getGuildPermissions()[guildId]
    } catch (e: Throwable) {
        logger.error("Failed to read guild permissions for $guildId", e)
        null
    }

    private fun hasPermission(guildId: Long, flag: Long): Boolean {
        val perms = currentGuildPermissions(guildId) ?: return true // unknown -> fail open
        return (perms and flag) != 0L || (perms and Perm.ADMINISTRATOR) != 0L
    }

    /** خطوة تأكيد تانية عشان ضغطة بالغلط ماتنفذش إجراء عقاب لوحدها. */
    private fun confirmAction(context: Context, title: String, summary: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setMessage(summary)
            .setPositiveButton("Confirm") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ==========================================
    // واجهة وطلب الـ Timeout الحديث
    // ==========================================
    private fun showTimeoutDialog(context: Context, guildId: Long, userId: Long) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val durationInput = EditText(context).apply {
            hint = "Duration (e.g. 60s, 10m, 2h, 7d)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val reasonInput = EditText(context).apply {
            hint = "Reason (Audit Log)"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        layout.addView(durationInput)
        layout.addView(reasonInput)

        AlertDialog.Builder(context)
            .setTitle("Timeout Member")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val durationText = trimSafe(durationInput.text.toString())
                val reason = trimSafe(reasonInput.text.toString())

                val totalSeconds = parseDuration(durationText)
                if (totalSeconds == null) {
                    Utils.showToast("Invalid format! Use s, m, h, or d (e.g. 60s, 30m, 1d)")
                    return@setPositiveButton
                }

                if (totalSeconds < 60L) {
                    Utils.showToast("Minimum timeout is 60 seconds!")
                    return@setPositiveButton
                }

                val maxSeconds = 28L * 24L * 3600L // 28 يوم كحد أقصى رسمي
                if (totalSeconds > maxSeconds) {
                    Utils.showToast("Maximum timeout allowed is 28 days!")
                    return@setPositiveButton
                }

                val summary = "Duration: " + durationText +
                    "\nReason: " + (if (isBlankSafe(reason)) "(none)" else reason)

                confirmAction(context, "Confirm Timeout", summary) {
                    executeTimeout(guildId, userId, totalSeconds, reason)
                }
            }
            .setNeutralButton("Remove Timeout") { _, _ ->
                confirmAction(
                    context,
                    "Remove Timeout?",
                    "This clears any active timeout for this member."
                ) {
                    executeTimeout(guildId, userId, null, "Removed timeout")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Parser يدوي (char by char) من غير Regex ولا isBlank ولا toLongOrNull ولا lowercase.
     * بيقبل: 60s / 10m / 2h / 7d / 1h30m / "1h 30m"
     */
    private fun parseDuration(input: String?): Long? {
        if (input == null) return null
        val n = input.length
        var total = 0L
        var current = -1L // -1 = مفيش أرقام لسه
        var foundAny = false
        var i = 0

        while (i < n) {
            val c = input[i]
            if (c >= '0' && c <= '9') {
                val base = if (current < 0L) 0L else current
                current = base * 10L + (c.code - '0'.code).toLong()
                if (current > 100000000000L) return null // حماية من overflow
            } else if (Character.isWhitespace(c)) {
                // تجاهل المسافات
            } else {
                if (current < 0L) return null
                val mult: Long = when (Character.toLowerCase(c)) {
                    's' -> 1L
                    'm' -> 60L
                    'h' -> 3600L
                    'd' -> 86400L
                    else -> return null
                }
                total += current * mult
                if (total > 36500L * 86400L) return null
                current = -1L
                foundAny = true
            }
            i++
        }

        if (current >= 0L) return null // رقم من غير وحدة (مثلاً "30")
        return if (foundAny) total else null
    }

    private fun executeTimeout(guildId: Long, userId: Long, durationSeconds: Long?, reason: String) {
        Utils.threadPool.execute {
            try {
                var isoTimestamp: String? = null
                if (durationSeconds != null) {
                    val date = Date(System.currentTimeMillis() + (durationSeconds * 1000L))
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    isoTimestamp = sdf.format(date)
                }

                val payload = JSONObject()
                payload.put("communication_disabled_until", isoTimestamp ?: JSONObject.NULL)

                val req = Http.Request.newDiscordRequest("/guilds/$guildId/members/$userId", "PATCH")
                    .setHeader("Content-Type", "application/json")
                if (!isBlankSafe(reason)) {
                    req.setHeader("X-Audit-Log-Reason", URLEncoder.encode(reason, "UTF-8"))
                }

                val res = req.executeWithBody(payload.toString())
                if (res.ok()) {
                    Utils.showToast(if (durationSeconds != null) "Timeout applied!" else "Timeout removed!")
                } else {
                    logger.error("Timeout Error: [${res.statusCode}] ${res.text()}", null)
                    Utils.showToast("Failed: HTTP ${res.statusCode}")
                }
            } catch (e: Exception) {
                logger.error("executeTimeout error", e)
                Utils.showToast("Error: ${e.message}")
            }
        }
    }

    // ==========================================
    // واجهة وطلب الـ Ban الحديث
    // ==========================================
    private fun showBanDialog(context: Context, guildId: Long, userId: Long) {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val reasonInput = EditText(context).apply {
            hint = "Ban Reason (Audit Log)"
        }

        val deleteMsgLabel = TextView(context).apply {
            text = "Delete Message History:"
            setPadding(0, 24, 0, 8)
        }

        val options = arrayOf(
            "Don't Delete Any",
            "Previous 1 Hour",
            "Previous 6 Hours",
            "Previous 12 Hours",
            "Previous 24 Hours",
            "Previous 3 Days",
            "Previous 7 Days"
        )
        val secondsMap = longArrayOf(
            0L,
            3600L,
            21600L,
            43200L,
            86400L,
            259200L,
            604800L
        )

        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
        }

        layout.addView(deleteMsgLabel)
        layout.addView(spinner)
        layout.addView(reasonInput)

        AlertDialog.Builder(context)
            .setTitle("Ban Member")
            .setView(layout)
            .setPositiveButton("Ban") { _, _ ->
                val reason = trimSafe(reasonInput.text.toString())
                val pos = spinner.selectedItemPosition
                val deleteSeconds = secondsMap[pos]
                val summary = "Delete messages: " + options[pos] +
                    "\nReason: " + (if (isBlankSafe(reason)) "(none)" else reason)
                confirmAction(context, "Confirm Ban", summary) {
                    executeBan(guildId, userId, deleteSeconds, reason)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeBan(guildId: Long, userId: Long, deleteSeconds: Long, reason: String) {
        Utils.threadPool.execute {
            try {
                val payload = JSONObject()
                payload.put("delete_message_seconds", deleteSeconds)

                val req = Http.Request.newDiscordRequest("/guilds/$guildId/bans/$userId", "PUT")
                    .setHeader("Content-Type", "application/json")
                if (!isBlankSafe(reason)) {
                    req.setHeader("X-Audit-Log-Reason", URLEncoder.encode(reason, "UTF-8"))
                }

                val res = req.executeWithBody(payload.toString())
                if (res.ok()) {
                    Utils.showToast("User banned successfully!")
                } else {
                    logger.error("Ban Error: [${res.statusCode}] ${res.text()}", null)
                    Utils.showToast("Failed: HTTP ${res.statusCode}")
                }
            } catch (e: Exception) {
                logger.error("executeBan error", e)
                Utils.showToast("Error: ${e.message}")
            }
        }
    }

    // ==========================================
    // واجهة وطلب الـ Kick الحديث
    // ==========================================
    private fun showKickDialog(context: Context, guildId: Long, userId: Long) {
        val input = EditText(context).apply {
            hint = "Kick Reason (Audit Log)"
        }

        AlertDialog.Builder(context)
            .setTitle("Kick Member")
            .setView(input)
            .setPositiveButton("Kick") { _, _ ->
                val reason = trimSafe(input.text.toString())
                val summary = if (isBlankSafe(reason)) "No reason provided." else "Reason: $reason"
                confirmAction(context, "Confirm Kick", summary) {
                    executeKick(guildId, userId, reason)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeKick(guildId: Long, userId: Long, reason: String) {
        Utils.threadPool.execute {
            try {
                val req = Http.Request.newDiscordRequest("/guilds/$guildId/members/$userId", "DELETE")
                if (!isBlankSafe(reason)) {
                    req.setHeader("X-Audit-Log-Reason", URLEncoder.encode(reason, "UTF-8"))
                }

                val res = req.execute()
                if (res.ok()) {
                    Utils.showToast("User kicked successfully!")
                } else {
                    logger.error("Kick Error: [${res.statusCode}] ${res.text()}", null)
                    Utils.showToast("Failed: HTTP ${res.statusCode}")
                }
            } catch (e: Exception) {
                logger.error("executeKick error", e)
                Utils.showToast("Error: ${e.message}")
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}

