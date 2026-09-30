package com.github.adham1223990.missingsettings

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.aliucord.Http
import com.aliucord.Utils
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

internal class MissingSettingsScreen(
    private val context: Context,
    private val guildId: Long,
    private val userPermissions: Long,
    private val onClosed: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val dialog = Dialog(context)

    private val primary = color("colorBackgroundPrimary", 0xFF313338.toInt())
    private val secondary = color("colorBackgroundSecondary", 0xFF2B2D31.toInt())
    private val normal = color("colorTextNormal", 0xFFF2F3F5.toInt())
    private val muted = color("colorTextMuted", 0xFFB5BAC1.toInt())

    private lateinit var contentContainer: LinearLayout
    private lateinit var progress: ProgressBar

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(createLayout())
        dialog.setOnDismissListener {
            worker.shutdownNow()
            onClosed()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(primary))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            statusBarColor = primary
            navigationBarColor = primary
        }
        loadGuildData()
    }

    fun dismiss() = dialog.dismiss()

    private fun createLayout(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(primary)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        header.addView(TextView(context).apply {
            text = "Modern Server Features"
            textSize = 21f
            setTextColor(normal)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })

        header.addView(TextView(context).apply {
            text = "✕"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))

        root.addView(header)

        progress = ProgressBar(context).apply {
            visibility = View.VISIBLE
            setPadding(0, dp(24), 0, 0)
        }
        root.addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })

        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            visibility = View.GONE
        }

        val scroll = ScrollView(context).apply { isFillViewport = true }
        scroll.addView(contentContainer)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    private fun loadGuildData() {
        worker.execute {
            val result = runCatching {
                getJson("/guilds/$guildId?with_counts=true", "GET") as JSONObject
            }
            main.post {
                progress.visibility = View.GONE
                result.onSuccess { data ->
                    renderSettings(data)
                }.onFailure { error ->
                    contentContainer.visibility = View.VISIBLE
                    contentContainer.addView(TextView(context).apply {
                        text = "Failed to load guild data: ${error.message}"
                        setTextColor(0xFFED4245.toInt())
                    })
                }
            }
        }
    }

    private fun renderSettings(data: JSONObject) {
        contentContainer.visibility = View.VISIBLE
        contentContainer.removeAllViews()

        val canManageGuild = (userPermissions and MissingPermissions.MANAGE_GUILD) != 0L || (userPermissions and MissingPermissions.ADMINISTRATOR) != 0L
        val canManageRoles = (userPermissions and MissingPermissions.MANAGE_ROLES) != 0L || (userPermissions and MissingPermissions.ADMINISTRATOR) != 0L
        val canManageChannels = (userPermissions and MissingPermissions.MANAGE_CHANNELS) != 0L || (userPermissions and MissingPermissions.ADMINISTRATOR) != 0L
        val canKick = (userPermissions and MissingPermissions.KICK_MEMBERS) != 0L || canManageGuild

        // 1. Role & Channel Modern Permissions (Use Activities, Soundboard, Voice Messages, Polls)
        if (canManageRoles || canManageChannels) {
            addSettingTile(
                title = "Modern Permissions Editor",
                description = "Configure modern 64-bit permissions: Activities, Polls, Voice Messages & Soundboard.",
                onClick = { openRolePermissionsPicker() }
            )
        }

        // 2. Member Verification / Join Applications & Interview Feature
        if (canKick) {
            addSettingTile(
                title = "Join Applications & Interviews",
                description = "Approve, reject, or start an interview chat with server applicants.",
                onClick = { openJoinRequestsDialog() }
            )
        }

        // 3. Server Onboarding System
        if (canManageGuild) {
            addSettingTile(
                title = "Server Onboarding",
                description = "Setup custom welcome questions, selectable roles, and default channels.",
                onClick = { openOnboardingSettings() }
            )
        }

        // 4. Auto Moderation (AutoMod)
        if (canManageGuild) {
            addSettingTile(
                title = "Auto Moderation (AutoMod)",
                description = "Manage automatic rule filters, regex blockers, and mention spam limits.",
                onClick = { fetchAutomodRules() }
            )
        }

        // 5. Soundboard Audio Manager
        if (canManageGuild) {
            addSettingTile(
                title = "Soundboard Manager",
                description = "Manage server soundboard audio files and sound permissions.",
                onClick = { querySimpleRoute("/guilds/$guildId/soundboard-sounds", "Soundboard") }
            )
        }

        // 6. Welcome Screen Setup
        if (canManageGuild) {
            addSettingTile(
                title = "Welcome Screen",
                description = "Configure top recommended channels shown to incoming members.",
                onClick = { querySimpleRoute("/guilds/$guildId/welcome-screen", "Welcome Screen") }
            )
        }

        // 7. Server Templates
        if (canManageGuild) {
            addSettingTile(
                title = "Server Templates",
                description = "Create and sync shareable server blueprint templates.",
                onClick = { querySimpleRoute("/guilds/$guildId/templates", "Server Templates") }
            )
        }

        // 8. Creator Monetization & Server Subscriptions
        if (canManageGuild) {
            addSettingTile(
                title = "Server Subscriptions",
                description = "Configure monetization tiers, group listings, and role subscriptions.",
                onClick = { querySimpleRoute("/guilds/$guildId/role-subscriptions/group-listings", "Monetization") }
            )
        }

        // 9. Safety Setup & Active Raid Mitigation
        if (canManageGuild) {
            addSettingTile(
                title = "Safety & Raid Mitigation",
                description = "Toggle raid alerts, pause invites, and restrict DMs for member safety.",
                onClick = { querySimpleRoute("/guilds/$guildId/incident-actions", "Incident Actions") }
            )
        }
    }

    private fun addSettingTile(title: String, description: String, onClick: () -> Unit) {
        val tile = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(secondary, 12)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        tile.addView(TextView(context).apply {
            text = title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(normal)
        })

        tile.addView(TextView(context).apply {
            text = description
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(4), 0, 0)
        })

        contentContainer.addView(tile, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(10)
        })
    }

    // Role Modern Permissions Override Logic
    private fun openRolePermissionsPicker() {
        worker.execute {
            runCatching {
                val rolesArray = getJson("/guilds/$guildId/roles", "GET") as JSONArray
                val roles = mutableListOf<Pair<Long, String>>()
                for (i in 0 until rolesArray.length()) {
                    val obj = rolesArray.getJSONObject(i)
                    roles.add(obj.getLong("id") to obj.getString("name"))
                }
                main.post {
                    val roleNames = roles.map { it.second }.toTypedArray()
                    AlertDialog.Builder(context)
                        .setTitle("Select Role to Modify")
                        .setItems(roleNames) { _, which ->
                            val selected = roles[which]
                            showRolePermissionsEditor(selected.first, selected.second)
                        }
                        .show()
                }
            }.onFailure {
                main.post { Utils.showToast("Failed to fetch roles: ${it.message}") }
            }
        }
    }

    private fun showRolePermissionsEditor(roleId: Long, roleName: String) {
        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val checkBoxes = mutableListOf<Pair<Long, CheckBox>>()
        for ((name, flag) in MissingPermissions.ALL_MODERN_PERMISSIONS) {
            val cb = CheckBox(context).apply {
                text = name
                setTextColor(normal)
            }
            checkBoxes.add(flag to cb)
            dialogView.addView(cb)
        }

        AlertDialog.Builder(context)
            .setTitle("Permissions: $roleName")
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                var addedFlags = 0L
                for ((flag, cb) in checkBoxes) {
                    if (cb.isChecked) addedFlags = addedFlags or flag
                }
                applyPermissionsToRole(roleId, addedFlags)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyPermissionsToRole(roleId: Long, additionalPerms: Long) {
        worker.execute {
            runCatching {
                val roleJson = getJson("/guilds/$guildId/roles/$roleId", "GET") as JSONObject
                val currentPerms = roleJson.getString("permissions").toLong()
                val updatedPerms = (currentPerms or additionalPerms).toString()

                val patchBody = JSONObject().apply {
                    put("permissions", updatedPerms)
                }

                getJson("/guilds/$guildId/roles/$roleId", "PATCH", patchBody.toString())
                main.post { Utils.showToast("Modern permissions updated successfully!") }
            }.onFailure {
                main.post { Utils.showToast("Failed to update permissions: ${it.message}") }
            }
        }
    }

    // Join Requests & Interview System
    private fun openJoinRequestsDialog() {
        worker.execute {
            runCatching {
                val requestsObj = getJson("/guilds/$guildId/requests/@me", "GET")
                val requestsArray = when (requestsObj) {
                    is JSONArray -> requestsObj
                    is JSONObject -> requestsObj.optJSONArray("requests") ?: JSONArray()
                    else -> JSONArray()
                }

                main.post {
                    if (requestsArray.length() == 0) {
                        Utils.showToast("No pending join applications.")
                        return@post
                    }

                    val names = mutableListOf<String>()
                    val userIds = mutableListOf<Long>()
                    for (i in 0 until requestsArray.length()) {
                        val req = requestsArray.getJSONObject(i)
                        val user = req.getJSONObject("user")
                        names.add(user.getString("username"))
                        userIds.add(user.getLong("id"))
                    }

                    AlertDialog.Builder(context)
                        .setTitle("Pending Applications")
                        .setItems(names.toTypedArray()) { _, which ->
                            val targetUserId = userIds[which]
                            val targetName = names[which]
                            showApplicantActionDialog(targetUserId, targetName)
                        }
                        .show()
                }
            }.onFailure {
                main.post { Utils.showToast("Error loading applications: ${it.message}") }
            }
        }
    }

    private fun showApplicantActionDialog(userId: Long, username: String) {
        val actions = arrayOf("Approve Application", "Reject Application", "Interview Applicant (DM)")
        AlertDialog.Builder(context)
            .setTitle("Applicant: $username")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> handleApplicationDecision(userId, "APPROVED")
                    1 -> handleApplicationDecision(userId, "REJECTED")
                    2 -> openInterviewChat(userId)
                }
            }
            .show()
    }

    private fun handleApplicationDecision(userId: Long, action: String) {
        worker.execute {
            runCatching {
                val payload = JSONObject().apply { put("application_status", action) }
                getJson("/guilds/$guildId/requests/@me/$userId", "PUT", payload.toString())
                main.post { Utils.showToast("Application marked as $action") }
            }.onFailure {
                main.post { Utils.showToast("Error updating application: ${it.message}") }
            }
        }
    }

    private fun openInterviewChat(userId: Long) {
        worker.execute {
            runCatching {
                val dmPayload = JSONObject().apply { put("recipient_id", userId.toString()) }
                val dmChannel = getJson("/users/@me/channels", "POST", dmPayload.toString()) as JSONObject
                val channelId = dmChannel.getLong("id")

                val messagePayload = JSONObject().apply {
                    put("content", "Hello! Regarding your application to join our server, we would like to conduct a brief interview.")
                }
                getJson("/channels/$channelId/messages", "POST", messagePayload.toString())
                main.post { Utils.showToast("Interview message sent successfully!") }
            }.onFailure {
                main.post { Utils.showToast("Failed to initiate interview: ${it.message}") }
            }
        }
    }

    // Onboarding Configuration
    private fun openOnboardingSettings() {
        worker.execute {
            runCatching {
                val onboarding = getJson("/guilds/$guildId/onboarding", "GET") as JSONObject
                main.post {
                    val isEnabled = onboarding.optBoolean("enabled", false)
                    AlertDialog.Builder(context)
                        .setTitle("Server Onboarding")
                        .setMessage("Current status: ${if (isEnabled) "ENABLED" else "DISABLED"}")
                        .setPositiveButton(if (isEnabled) "Disable" else "Enable") { _, _ ->
                            toggleOnboarding(!isEnabled)
                        }
                        .setNeutralButton("Add Prompt Question") { _, _ ->
                            showCreatePromptDialog()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            }.onFailure {
                main.post { Utils.showToast("Error fetching onboarding: ${it.message}") }
            }
        }
    }

    private fun toggleOnboarding(enabled: Boolean) {
        worker.execute {
            runCatching {
                val payload = JSONObject().apply { put("enabled", enabled) }
                getJson("/guilds/$guildId/onboarding", "PUT", payload.toString())
                main.post { Utils.showToast("Onboarding status changed to: $enabled") }
            }.onFailure {
                main.post { Utils.showToast("Error updating onboarding: ${it.message}") }
            }
        }
    }

    private fun showCreatePromptDialog() {
        val input = EditText(context).apply {
            hint = "Question (e.g. Which roles do you want?)"
        }
        AlertDialog.Builder(context)
            .setTitle("New Onboarding Prompt")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val text = input.text.toString()
                if (text.isNotEmpty()) createOnboardingPrompt(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createOnboardingPrompt(title: String) {
        worker.execute {
            runCatching {
                val payload = JSONObject().apply {
                    put("title", title)
                    put("options", JSONArray())
                    put("single_select", false)
                    put("required", false)
                }
                getJson("/guilds/$guildId/onboarding", "PUT", payload.toString())
                main.post { Utils.showToast("Prompt question added successfully!") }
            }.onFailure {
                main.post { Utils.showToast("Failed to add prompt: ${it.message}") }
            }
        }
    }

    // AutoMod Rules Logic
    private fun fetchAutomodRules() {
        worker.execute {
            runCatching {
                val rulesArray = getJson("/guilds/$guildId/auto-moderation/rules", "GET") as JSONArray
                main.post {
                    val ruleNames = mutableListOf<String>()
                    for (i in 0 until rulesArray.length()) {
                        ruleNames.add(rulesArray.getJSONObject(i).getString("name"))
                    }
                    AlertDialog.Builder(context)
                        .setTitle("AutoMod Rules (${rulesArray.length()})")
                        .setItems(ruleNames.toTypedArray(), null)
                        .setPositiveButton("Create Rule") { _, _ ->
                            showCreateAutomodRuleDialog()
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            }.onFailure {
                main.post { Utils.showToast("Error fetching AutoMod: ${it.message}") }
            }
        }
    }

    private fun showCreateAutomodRuleDialog() {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val nameInput = EditText(context).apply { hint = "Rule Name" }
        val wordsInput = EditText(context).apply { hint = "Blocked words (comma-separated)" }
        layout.addView(nameInput)
        layout.addView(keywordsInput)

        AlertDialog.Builder(context)
            .setTitle("New Keyword Filter")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = nameInput.text.toString()
                val words = wordsInput.text.toString().split(",").map { it.trim() }
                if (name.isNotEmpty() && words.isNotEmpty()) {
                    createAutomodRule(name, words)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createAutomodRule(name: String, words: List<String>) {
        worker.execute {
            runCatching {
                val payload = JSONObject().apply {
                    put("name", name)
                    put("event_type", 1) // MESSAGE_SEND
                    put("trigger_type", 1) // KEYWORD
                    put("trigger_metadata", JSONObject().apply {
                        put("keyword_filter", JSONArray(words))
                    })
                    put("actions", JSONArray().apply {
                        put(JSONObject().apply { put("type", 1) }) // BLOCK_MESSAGE
                    })
                    put("enabled", true)
                }
                getJson("/guilds/$guildId/auto-moderation/rules", "POST", payload.toString())
                main.post { Utils.showToast("AutoMod rule created!") }
            }.onFailure {
                main.post { Utils.showToast("Failed to create rule: ${it.message}") }
            }
        }
    }

    private fun querySimpleRoute(route: String, label: String) {
        Utils.showToast("Fetching $label...")
        worker.execute {
            runCatching {
                getJson(route, "GET")
                main.post { Utils.showToast("$label data verified successfully.") }
            }.onFailure {
                main.post { Utils.showToast("Failed to fetch $label") }
            }
        }
    }

    // Official Discord Request Core Wrapper
    private fun getJson(route: String, method: String, jsonBody: String? = null): Any {
        val token = StoreAuthentication.`access$getAuthState$p`(StoreStream.getAuthentication())?.token
            ?: RestAPI.AppHeadersProvider.INSTANCE.authToken
            ?: error("Authentication token not found")

        return Http.Request.newDiscordRequest(route, method).use { request ->
            request.setRequestTimeout(15_000)
            request.setHeader("Authorization", token)
            if (jsonBody != null) {
                request.setHeader("Content-Type", "application/json")
                request.body = jsonBody
            }
            request.execute().use { response ->
                if (!response.ok()) error("HTTP ${response.statusCode}:${response.text()}")
                val text = response.text()
                var index = 0
                while (index < text.length && Character.isWhitespace(text[index])) index++
                if (index < text.length && text[index] == '[') JSONArray(text) else JSONObject(text)
            }
        }
    }

    private fun color(attribute: String, fallback: Int): Int {
        val id = Utils.getResId(attribute, "attr")
        return if (id != 0) ColorCompat.getThemedColor(context, id) else fallback
    }

    private fun rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
