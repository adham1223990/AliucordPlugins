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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

@AliucordPlugin(requiresRestart = false)
class ModernModActions : Plugin() {

    // Official Discord permission bit flags (guild-level), from Discord's own API docs.
    private object Perm {
        const val KICK_MEMBERS = 0x2L
        const val BAN_MEMBERS = 0x4L
        const val ADMINISTRATOR = 0x8L
        const val MODERATE_MEMBERS = 1L shl 40 // Timeout Members
    }

    override fun start(context: Context) {
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
     * updateView(ViewState) only ever touches visibility/text/icons — it never sets a click
     * listener. We patch it purely to ADD an extra hide when this account lacks the relevant
     * permission; we never force a button VISIBLE here, since Discord's own ViewState already
     * encodes other reasons to hide it (e.g. you can't ban/kick/timeout yourself).
     */
    private fun patchVisibility() {
        val methods = UserProfileAdminView::class.java.declaredMethods.filter { it.name == "updateView" }
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
        root.findViewById<View>(resId)?.visibility = View.GONE
    }

    /**
     * The real click wiring happens in these setters (setOnBan/setOnKick/setOnDisableCommunication),
     * NOT in updateView. Discord calls the matching setter with its own lambda, which opens the
     * native dialog. We patch the setter itself and re-assign our own click listener straight
     * after the original runs, so we always have the final word on that button's click listener
     * no matter when Discord calls it relative to updateView.
     */
    private fun patchClick(
        setterName: String,
        resourceName: String,
        permission: Long,
        showDialog: (Context, Long, Long) -> Unit
    ) {
        val method = UserProfileAdminView::class.java.declaredMethods.firstOrNull { it.name == setterName }
        if (method == null) {
            logger.error("ModernModActions: setter '$setterName' not found on UserProfileAdminView")
            Utils.showToast("ModernModActions: '$setterName' not found, this plugin needs an update.")
            return
        }
        try {
            patcher.patch(method, Hook { frame ->
                try {
                    val adminView = frame.thisObject as? UserProfileAdminView ?: return@Hook
                    val resId = Utils.getResId(resourceName, "id")
                    if (resId == 0) {
                        logger.error("ModernModActions: no resource id '$resourceName'")
                        return@Hook
                    }
                    val view = adminView.findViewById<View>(resId)
                    if (view == null) {
                        logger.error("ModernModActions: findViewById('$resourceName') returned null")
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

    private fun currentUserSheet(): WidgetUserSheet? = Utils.appActivity.supportFragmentManager.fragments
        .filterIsInstance<WidgetUserSheet>()
        .firstOrNull { it.isVisible }

    private fun currentUserId(): Long = currentUserSheet()?.arguments?.getLong("ARG_USER_ID") ?: 0L

    private fun currentGuildId(): Long {
        val selectedGuildId = StoreStream.getGuildSelected().selectedGuildId
        val fromArgs = currentUserSheet()?.arguments?.getLong("ARG_GUILD_ID")?.takeIf { it != 0L }
        return fromArgs ?: selectedGuildId
    }

    /**
     * Reads this account's computed permissions in [guildId] from Discord's own permission
     * store, exactly what QuestUI-style plugins use. If the store can't be read for any
     * reason we "fail open" (allow) since Discord's API will still reject the request with
     * HTTP 403 if the permission is genuinely missing — nothing unsafe happens, we just lose
     * the cosmetic hiding/blocking for that one case.
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

    /** A second "are you sure?" step so a stray tap never fires a moderation action by itself. */
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

                val summary = buildString {
                    append("Duration: ").append(durationText)
                    append("\nReason: ").append(if (reason.isBlank()) "(none)" else reason)
                }
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

    private fun parseDuration(input: String): Long? {
        if (input.isBlank()) return null
        var totalSeconds = 0L
        val matcher = Pattern.compile("(\\d+)([smhd])", Pattern.CASE_INSENSITIVE).matcher(input)
        var foundAny = false

        while (matcher.find()) {
            foundAny = true
            val count = matcher.group(1)?.toLongOrNull() ?: return null
            val unitStr = matcher.group(2)?.lowercase(Locale.ROOT) ?: return null
            val unit = unitStr.firstOrNull() ?: return null

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
                val summary = buildString {
                    append("Delete messages: ").append(options[spinner.selectedItemPosition])
                    append("\nReason: ").append(if (reason.isBlank()) "(none)" else reason)
                }
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
                val summary = if (reason.isBlank()) "No reason provided." else "Reason: $reason"
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

