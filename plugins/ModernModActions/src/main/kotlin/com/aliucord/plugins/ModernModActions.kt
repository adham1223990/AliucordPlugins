package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.aliucord.utils.RNSuperProperties
import com.discord.stores.StoreStream
import com.discord.widgets.user.profile.UserProfileAdminView
import com.discord.widgets.user.usersheet.WidgetUserSheet
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@AliucordPlugin(requiresRestart = false)
class ModernModActions : Plugin() {

    private object Perm {
        const val KICK_MEMBERS = 0x2L
        const val BAN_MEMBERS = 0x4L
        const val ADMINISTRATOR = 0x8L
        const val MODERATE_MEMBERS = 1L shl 40
    }

    companion object {
        private const val CURRENT_RN_BUILD_NUMBER = 6081
        private const val CURRENT_RN_VERSION_CODE = 341200
        private const val CURRENT_RN_VERSION = "341.0 - rn"
        private const val CURRENT_RN_USER_AGENT = "Discord-Android/$CURRENT_RN_VERSION_CODE;RNA"

        // Discord Color Palette
        private const val COLOR_BG_POPUP = 0xFF313338.toInt()
        private const val COLOR_INPUT_BG = 0xFF1E1F22.toInt()
        private const val COLOR_TEXT_PRIMARY = 0xFFF2F3F5.toInt()
        private const val COLOR_TEXT_MUTED = 0xFF949BA4.toInt()
        private const val COLOR_DANGER = 0xFFDA373C.toInt()
        private const val COLOR_BLURPLE = 0xFF5865F2.toInt()
    }

    @Volatile private var activeUserId: Long = 0L
    @Volatile private var activeGuildId: Long = 0L
    private var cachedSuperProperties: String? = null

    // ==========================================================
    // UI Helpers (تصميم يحاكي ديسكورد تماماً)
    // ==========================================================

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun createRoundedBg(bgColor: Int, cornerRadiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusDp * 3f
            setColor(bgColor)
        }
    }

    private fun buildDiscordDialog(
        context: Context,
        titleText: String,
        contentView: View,
        confirmBtnText: String,
        confirmBtnColor: Int,
        onConfirm: () -> Unit,
        neutralBtnText: String? = null,
        onNeutral: (() -> Unit)? = null
    ): AlertDialog {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBg(COLOR_BG_POPUP, 16f)
            setPadding(dpToPx(context, 20), dpToPx(context, 20), dpToPx(context, 20), dpToPx(context, 20))
        }

        // Header Title
        val title = TextView(context).apply {
            text = titleText
            setTextColor(COLOR_TEXT_PRIMARY)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(context, 16))
        }
        root.addView(title)
        root.addView(contentView)

        // Buttons Bar
        val btnBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(context, 20), 0, 0)
        }

        var dialog: AlertDialog? = null

        val cancelBtn = TextView(context).apply {
            text = "Cancel"
            setTextColor(COLOR_TEXT_MUTED)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dpToPx(context, 16), dpToPx(context, 10), dpToPx(context, 16), dpToPx(context, 10))
            setOnClickListener { dialog?.dismiss() }
        }
        btnBar.addView(cancelBtn)

        if (neutralBtnText != null && onNeutral != null) {
            val neutralBtn = TextView(context).apply {
                text = neutralBtnText
                setTextColor(COLOR_DANGER)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dpToPx(context, 12), dpToPx(context, 10), dpToPx(context, 12), dpToPx(context, 10))
                setOnClickListener {
                    dialog?.dismiss()
                    onNeutral()
                }
            }
            btnBar.addView(neutralBtn)
        }

        val confirmBtn = TextView(context).apply {
            text = confirmBtnText
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = createRoundedBg(confirmBtnColor, 4f)
            setPadding(dpToPx(context, 20), dpToPx(context, 10), dpToPx(context, 20), dpToPx(context, 10))
            setOnClickListener {
                dialog?.dismiss()
                onConfirm()
            }
        }
        btnBar.addView(confirmBtn)
        root.addView(btnBar)

        dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }

    private fun createDiscordInput(context: Context, hintText: String): EditText {
        return EditText(context).apply {
            hint = hintText
            setHintTextColor(COLOR_TEXT_MUTED)
            setTextColor(COLOR_TEXT_PRIMARY)
            background = createRoundedBg(COLOR_INPUT_BG, 4f)
            textSize = 14f
            setPadding(dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12), dpToPx(context, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(context, 10)
            }
        }
    }

    // ==========================================================
    // Safe string helpers
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
    // v10 request & spoofing
    // ==========================================================

    private fun currentSuperProperties(): String {
        cachedSuperProperties?.let { return it }
        return synchronized(this) {
            cachedSuperProperties ?: buildCurrentSuperProperties().also { cachedSuperProperties = it }
        }
    }

    private fun buildCurrentSuperProperties(): String {
        val properties = try {
            JSONObject(RNSuperProperties.superProperties.toString())
        } catch (throwable: Throwable) {
            JSONObject()
        }

        properties.put("has_client_mods", false)
        properties.put("os", "Android")
        properties.put("browser", "Discord Android")
        properties.put("client_version", CURRENT_RN_VERSION)
        properties.put("release_channel", "canaryRelease")
        properties.put("client_build_number", CURRENT_RN_BUILD_NUMBER)
        properties.put("launch_signature", (System.currentTimeMillis() * 1_000_000L).toString())
        return Base64.encodeToString(properties.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun createV10Request(path: String, method: String): Http.Request {
        val req = Http.Request.newDiscordRequest(path, method)
        try {
            val conn = req.conn
            val originalUrl = conn.url.toString()
            if (originalUrl.contains("/api/v9/")) {
                val upgradedUrl = originalUrl.replace("/api/v9/", "/api/v10/")
                val urlField: Field = HttpURLConnection::class.java.getDeclaredField("url")
                urlField.isAccessible = true
                urlField.set(conn, URL(upgradedUrl))
            }
        } catch (t: Throwable) {
            logger.error("Failed to upgrade endpoint to v10", t)
        }

        req.conn.setRequestProperty("User-Agent", CURRENT_RN_USER_AGENT)
        req.conn.setRequestProperty("X-Super-Properties", currentSuperProperties())
        return req
    }

    // ==========================================================
    // Hooks & logic
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

    private fun patchSheetArgs() {
        val method = findMethod(WidgetUserSheet::class.java, "onViewCreated", 2) ?: return
        try {
            patcher.patch(method, Hook { frame ->
                try {
                    val sheet = frame.thisObject as? WidgetUserSheet ?: return@Hook
                    val args = sheet.arguments ?: return@Hook
                    activeUserId = args.getLong("ARG_USER_ID", 0L)
                    val argGuildId = args.getLong("ARG_GUILD_ID", 0L)

                    // منع التقاط قروبات الـ DMs كـ GuildId
                    activeGuildId = if (argGuildId > 0L) {
                        argGuildId
                    } else {
                        val sel = StoreStream.getGuildSelected().selectedGuildId
                        if (sel > 0L) sel else 0L
                    }
                } catch (e: Throwable) {
                    logger.error("ModernModActions: sheet-args hook crashed", e)
                }
            })
        } catch (e: Throwable) {
            logger.error("ModernModActions: failed to patch WidgetUserSheet.onViewCreated", e)
        }
    }

    private fun patchVisibility() {
        val methods = findMethods(UserProfileAdminView::class.java, "updateView")
        for (method in methods) {
            try {
                patcher.patch(method, Hook { frame ->
                    try {
                        val adminView = frame.thisObject as? UserProfileAdminView ?: return@Hook
                        val guildId = currentGuildId()
                        // لو مش داخل سيرفر حقيقي (قروب أو DM)، لا تتدخل نهائياً
                        if (guildId <= 0L) return@Hook

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
            } catch (e: Throwable) {
                logger.error("ModernModActions: failed to patch updateView", e)
            }
        }
    }

    private fun hideIfMissing(root: View, resourceName: String, guildId: Long, permission: Long) {
        if (hasPermission(guildId, permission)) return
        val resId = Utils.getResId(resourceName, "id")
        if (resId == 0) return
        val v = root.findViewById<View>(resId)
        if (v != null) v.visibility = View.GONE
    }

    private fun patchClick(
        setterName: String,
        resourceName: String,
        permission: Long,
        showDialog: (Context, Long, Long) -> Unit
    ) {
        val method = findMethod(UserProfileAdminView::class.java, setterName) ?: return
        try {
            patcher.patch(method, Hook { frame ->
                try {
                    val adminView = frame.thisObject as? UserProfileAdminView ?: return@Hook
                    val resId = Utils.getResId(resourceName, "id")
                    if (resId == 0) return@Hook
                    val view = adminView.findViewById<View>(resId) ?: return@Hook

                    view.setOnClickListener {
                        val guildId = currentGuildId()
                        val userId = currentUserId()

                        // إذا كان الإجراء في قروب خاص (Remove from Group) دع ديسكورد يتعامل معه
                        if (guildId <= 0L || userId <= 0L) {
                            return@setOnClickListener
                        }

                        if (!hasPermission(guildId, permission)) {
                            Utils.showToast("Missing permissions")
                            return@setOnClickListener
                        }
                        showDialog(adminView.context, guildId, userId)
                    }
                } catch (e: Throwable) {
                    logger.error("ModernModActions: click hook for $setterName crashed", e)
                }
            })
        } catch (e: Throwable) {
            logger.error("ModernModActions: failed to patch $setterName", e)
        }
    }

    private fun currentUserId(): Long = activeUserId
    private fun currentGuildId(): Long = activeGuildId

    private fun currentGuildPermissions(guildId: Long): Long? = try {
        StoreStream.getPermissions().getGuildPermissions()[guildId]
    } catch (e: Throwable) {
        null
    }

    private fun hasPermission(guildId: Long, flag: Long): Boolean {
        val perms = currentGuildPermissions(guildId) ?: return true
        return (perms and flag) != 0L || (perms and Perm.ADMINISTRATOR) != 0L
    }

    // ==========================================
    // واجهة الـ Timeout الحديثة المطابقة لديسكورد
    // ==========================================
    private fun showTimeoutDialog(context: Context, guildId: Long, userId: Long) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val desc = TextView(context).apply {
            text = "Prevent member from sending messages, reacting, or speaking in voice channels."
            setTextColor(COLOR_TEXT_MUTED)
            textSize = 13f
        }
        content.addView(desc)

        val durationInput = createDiscordInput(context, "Duration (Empty = 7 days, e.g. 60s, 2h, 7d)")
        val reasonInput = createDiscordInput(context, "Reason (Audit Log)")
        content.addView(durationInput)
        content.addView(reasonInput)

        val dialog = buildDiscordDialog(
            context = context,
            titleText = "Timeout Member",
            contentView = content,
            confirmBtnText = "Apply",
            confirmBtnColor = COLOR_BLURPLE,
            onConfirm = {
                val durationText = trimSafe(durationInput.text.toString())
                val reason = trimSafe(reasonInput.text.toString())

                // الشرط: إذا لم تحدد مدة = 7 أيام
                val totalSeconds: Long = if (isBlankSafe(durationText)) {
                    7L * 24L * 3600L
                } else {
                    val parsed = parseDuration(durationText)
                    if (parsed == null) {
                        Utils.showToast("Invalid format! Use s, m, h, or d")
                        return@buildDiscordDialog
                    }
                    parsed
                }

                if (totalSeconds < 60L) {
                    Utils.showToast("Minimum timeout is 60 seconds!")
                    return@buildDiscordDialog
                }

                val maxSeconds = 28L * 24L * 3600L
                if (totalSeconds > maxSeconds) {
                    Utils.showToast("Maximum timeout allowed is 28 days!")
                    return@buildDiscordDialog
                }

                executeTimeout(guildId, userId, totalSeconds, reason)
            },
            neutralBtnText = "Remove",
            onNeutral = {
                executeTimeout(guildId, userId, null, "Removed timeout")
            }
        )
        dialog.show()
    }

    private fun parseDuration(input: String?): Long? {
        if (input == null) return null
        val n = input.length
        var total = 0L
        var current = -1L
        var foundAny = false
        var i = 0

        while (i < n) {
            val c = input[i]
            if (c in '0'..'9') {
                val base = if (current < 0L) 0L else current
                current = base * 10L + (c.code - '0'.code).toLong()
                if (current > 100000000000L) return null
            } else if (Character.isWhitespace(c)) {
                // skip
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

        if (current >= 0L) return null
        return if (foundAny) total else null
    }

    private fun targetIsAdmin(guildId: Long, userId: Long): Boolean? {
        try {
            val memRes = createV10Request("/guilds/$guildId/members/$userId", "GET").execute()
            if (!memRes.ok()) return null
            val memberRoles = JSONObject(memRes.text()).optJSONArray("roles") ?: return null

            val rolesRes = createV10Request("/guilds/$guildId/roles", "GET").execute()
            if (!rolesRes.ok()) return null
            val allRoles = JSONArray(rolesRes.text())

            val ids = HashSet<String>()
            ids.add(guildId.toString())
            var i = 0
            while (i < memberRoles.length()) {
                ids.add(memberRoles.getString(i))
                i++
            }

            var j = 0
            while (j < allRoles.length()) {
                val role = allRoles.getJSONObject(j)
                if (ids.contains(role.optString("id"))) {
                    val perms = java.lang.Long.parseLong(role.optString("permissions", "0"))
                    if ((perms and Perm.ADMINISTRATOR) != 0L) return true
                }
                j++
            }
            return false
        } catch (e: Throwable) {
            return null
        }
    }

    private fun executeTimeout(guildId: Long, userId: Long, durationSeconds: Long?, reason: String) {
        Utils.threadPool.execute {
            try {
                if (durationSeconds != null && targetIsAdmin(guildId, userId) == true) {
                    Utils.showToast("Administrators can't be timed out")
                    return@execute
                }

                var isoTimestamp: String? = null
                if (durationSeconds != null) {
                    val date = Date(System.currentTimeMillis() + (durationSeconds * 1000L))
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    isoTimestamp = sdf.format(date)
                }

                val payload = JSONObject()
                payload.put("communication_disabled_until", isoTimestamp ?: JSONObject.NULL)

                val req = createV10Request("/guilds/$guildId/members/$userId", "PATCH")
                    .setHeader("Content-Type", "application/json")
                if (!isBlankSafe(reason)) {
                    req.setHeader("X-Audit-Log-Reason", URLEncoder.encode(reason, "UTF-8"))
                }

                val res = req.executeWithBody(payload.toString())
                if (res.ok()) {
                    Utils.showToast(if (durationSeconds != null) "Timeout applied!" else "Timeout removed!")
                } else {
                    Utils.showToast("Failed: HTTP ${res.statusCode}")
                }
            } catch (e: Exception) {
                Utils.showToast("Error: ${e.message}")
            }
        }
    }

    // ==========================================
    // واجهة الـ Ban المطابقة لديسكورد
    // ==========================================
    private fun showBanDialog(context: Context, guildId: Long, userId: Long) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val deleteMsgLabel = TextView(context).apply {
            text = "DELETE MESSAGE HISTORY"
            setTextColor(COLOR_TEXT_MUTED)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(context, 6))
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

        val spinnerContainer = FrameLayout(context).apply {
            background = createRoundedBg(COLOR_INPUT_BG, 4f)
            setPadding(dpToPx(context, 8), dpToPx(context, 4), dpToPx(context, 8), dpToPx(context, 4))
        }

        val spinner = Spinner(context).apply {
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, options) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent) as TextView
                    view.setTextColor(COLOR_TEXT_PRIMARY)
                    view.textSize = 14f
                    return view
                }
            }
        }
        spinnerContainer.addView(spinner)

        val reasonInput = createDiscordInput(context, "Reason (Audit Log)")

        content.addView(deleteMsgLabel)
        content.addView(spinnerContainer)
        content.addView(reasonInput)

        val dialog = buildDiscordDialog(
            context = context,
            titleText = "Ban Member",
            contentView = content,
            confirmBtnText = "Ban",
            confirmBtnColor = COLOR_DANGER,
            onConfirm = {
                val reason = trimSafe(reasonInput.text.toString())
                val deleteSeconds = secondsMap[spinner.selectedItemPosition]
                executeBan(guildId, userId, deleteSeconds, reason)
            }
        )
        dialog.show()
    }

    private fun executeBan(guildId: Long, userId: Long, deleteSeconds: Long, reason: String) {
        Utils.threadPool.execute {
            try {
                val payload = JSONObject()
                payload.put("delete_message_seconds", deleteSeconds)

                val req = createV10Request("/guilds/$guildId/bans/$userId", "PUT")
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
    // واجهة الـ Kick المطابقة لديسكورد
    // ==========================================
    private fun showKickDialog(context: Context, guildId: Long, userId: Long) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val desc = TextView(context).apply {
            text = "Are you sure you want to kick this member? They will be able to rejoin with a new invite."
            setTextColor(COLOR_TEXT_MUTED)
            textSize = 13f
        }
        val reasonInput = createDiscordInput(context, "Reason (Audit Log)")

        content.addView(desc)
        content.addView(reasonInput)

        val dialog = buildDiscordDialog(
            context = context,
            titleText = "Kick Member",
            contentView = content,
            confirmBtnText = "Kick",
            confirmBtnColor = COLOR_DANGER,
            onConfirm = {
                val reason = trimSafe(reasonInput.text.toString())
                executeKick(guildId, userId, reason)
            }
        )
        dialog.show()
    }

    private fun executeKick(guildId: Long, userId: Long, reason: String) {
        Utils.threadPool.execute {
            try {
                val req = createV10Request("/guilds/$guildId/members/$userId", "DELETE")
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
