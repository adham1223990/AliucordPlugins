package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.*
import com.aliucord.Http
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.discord.widgets.user.profile.UserProfileAdminView
import com.discord.widgets.user.usersheet.WidgetUserSheet
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@AliucordPlugin(requiresRestart = false)
class ModernModActions : Plugin() {
    private val logger = Logger("ModernModActions")

    override fun start(context: Context) {
        try {
            // عمل Hook على updateView للتأكد من ربط الأزرار بالـ User و الـ Guild الصحيحين
            patcher.patch(
                UserProfileAdminView::class.java,
                "updateView",
                arrayOf(UserProfileAdminView.ViewState::class.java),
                Hook { frame ->
                    val adminView = frame.thisObject as UserProfileAdminView

                    // جلب معرف السيرفر الحالي
                    val selectedGuildId = StoreStream.getGuildSelected().selectedGuildId

                    // استخراج الـ User ID من الـ Context / FragmentManager بأمان
                    val sheet = Utils.appActivity.supportFragmentManager.fragments
                        .filterIsInstance<WidgetUserSheet>()
                        .firstOrNull { it.isVisible }

                    val args = sheet?.arguments
                    val userId = args?.getLong("ARG_USER_ID") ?: 0L
                    val guildId = (args?.getLong("ARG_GUILD_ID")?.takeIf { it != 0L }) ?: selectedGuildId

                    if (userId == 0L || guildId == 0L) return@Hook

                    // 1. زر التايم أوت (Disable Communication)
                    bindClick(adminView, "user_profile_admin_disable_communication") {
                        showTimeoutDialog(adminView.context, guildId, userId)
                    }

                    // 2. زر الباند (Ban)
                    bindClick(adminView, "user_profile_admin_ban") {
                        showBanDialog(adminView.context, guildId, userId)
                    }

                    // 3. زر الكيك (Kick)
                    bindClick(adminView, "user_profile_admin_kick") {
                        showKickDialog(adminView.context, guildId, userId)
                    }
                }
            )
        } catch (e: Throwable) {
            logger.error("Failed to patch UserProfileAdminView", e)
        }
    }

    private fun bindClick(root: View, resourceName: String, onClick: () -> Unit) {
        val resId = Utils.getResId(resourceName, "id")
        if (resId == 0) return
        val view = root.findViewById<View>(resId) ?: return
        view.visibility = View.VISIBLE
        view.setOnClickListener { onClick() }
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
                val durationText = durationInput.text.toString().trim()
                val reason = reasonInput.text.toString().trim()

                val totalSeconds = parseDuration(durationText)
                if (totalSeconds == null) {
                    Utils.showToast("Invalid format! Use s, m, h, or d (e.g. 60s, 30m, 1d)")
                    return@setPositiveButton
                }

                if (totalSeconds < 60) {
                    Utils.showToast("Minimum timeout is 60 seconds!")
                    return@setPositiveButton
                }

                val maxSeconds = 28L * 24 * 3600 // 28 يوم كحد أقصى رسمي
                if (totalSeconds > maxSeconds) {
                    Utils.showToast("Maximum timeout allowed is 28 days!")
                    return@setPositiveButton
                }

                executeTimeout(guildId, userId, totalSeconds, reason)
            }
            .setNeutralButton("Remove Timeout") { _, _ ->
                executeTimeout(guildId, userId, null, "Removed timeout")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseDuration(input: String): Long? {
        if (input.isBlank()) return null
        var totalSeconds = 0L
        val matcher = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE).matcher(input)
        var foundAny = false

        while (matcher.find()) {
            foundAny = true
            val count = matcher.group(1)?.toLongOrNull() ?: return null
            val unit = matcher.group(2)?.lowercaseChar() ?: return null

            totalSeconds += when (unit) {
                's' -> count
                'm' -> TimeUnit.MINUTES.toSeconds(count)
                'h' -> TimeUnit.HOURS.toSeconds(count)
                'd' -> TimeUnit.DAYS.toSeconds(count)
                else -> 0L
            }
        }
        return if (foundAny) totalSeconds else null
    }

    private fun executeTimeout(guildId: Long, userId: Long, durationSeconds: Long?, reason: String) {
        Utils.threadPool.execute {
            try {
                val isoTimestamp = durationSeconds?.let {
                    val date = Date(System.currentTimeMillis() + (it * 1000))
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    sdf.format(date)
                }

                val payload = JSONObject().apply {
                    put("communication_disabled_until", if (isoTimestamp != null) isoTimestamp else JSONObject.NULL)
                }

                val req = Http.Request.newDiscordRequest("/guilds/$guildId/members/$userId", "PATCH")
                    .setHeader("Content-Type", "application/json")
                if (reason.isNotBlank()) {
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
                val reason = reasonInput.text.toString().trim()
                val deleteSeconds = secondsMap[spinner.selectedItemPosition]
                executeBan(guildId, userId, deleteSeconds, reason)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeBan(guildId: Long, userId: Long, deleteSeconds: Long, reason: String) {
        Utils.threadPool.execute {
            try {
                val payload = JSONObject().apply {
                    put("delete_message_seconds", deleteSeconds)
                }

                val req = Http.Request.newDiscordRequest("/guilds/$guildId/bans/$userId", "PUT")
                    .setHeader("Content-Type", "application/json")
                if (reason.isNotBlank()) {
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
                val reason = input.text.toString().trim()
                executeKick(guildId, userId, reason)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun executeKick(guildId: Long, userId: Long, reason: String) {
        Utils.threadPool.execute {
            try {
                val req = Http.Request.newDiscordRequest("/guilds/$guildId/members/$userId", "DELETE")
                if (reason.isNotBlank()) {
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
