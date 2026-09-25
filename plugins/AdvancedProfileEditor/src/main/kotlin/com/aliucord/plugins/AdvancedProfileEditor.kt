package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.stores.StoreStream
import com.discord.widgets.user.usersheet.WidgetUserSheet
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

@AliucordPlugin(requiresRestart = false)
class AdvancedProfileEditor : Plugin() {

    override fun start(context: Context) {
        try {
            // Hook WidgetUserSheet onViewCreated to inject profile editor button
            patcher.patch(
                WidgetUserSheet::class.java.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java),
                Hook { frame ->
                    val sheet = frame.thisObject as WidgetUserSheet
                    val rootView = frame.args[0] as? ViewGroup ?: return@Hook

                    val myId = StoreStream.getUsers().me?.id ?: 0L
                    val sheetUserId = sheet.arguments?.getLong("ARG_USER_ID") ?: 0L
                    val guildId = sheet.arguments?.getLong("ARG_GUILD_ID") ?: StoreStream.getGuildSelected().selectedGuildId

                    // Show editor button only if viewing self profile
                    if (sheetUserId != 0L && sheetUserId != myId) return@Hook

                    val buttonId = View.generateViewId()
                    if (rootView.findViewById<View>(buttonId) != null) return@Hook

                    val editBtn = Button(rootView.context).apply {
                        id = buttonId
                        text = "Edit Profile (Live Preview)"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            setColor(Color.parseColor("#5865F2"))
                            cornerRadius = 16f
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(32, 16, 32, 16)
                        }
                        setOnClickListener {
                            openEditorDialog(rootView.context, guildId)
                        }
                    }

                    if (rootView is LinearLayout) {
                        rootView.addView(editBtn, 0)
                    } else {
                        rootView.addView(editBtn)
                    }
                }
            )
        } catch (e: Throwable) {
            logger.error("Failed to hook WidgetUserSheet for Profile Editor", e)
        }
    }

    private fun openEditorDialog(context: Context, guildId: Long) {
        val scroll = ScrollView(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 24)
        }
        scroll.addView(container)

        // ==========================================
        // 1. Live Preview Card
        // ==========================================
        val previewTitle = TextView(context).apply {
            text = "Live Profile Preview"
            typeface = Typeface.DEFAULT_BOLD
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        container.addView(previewTitle)

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#18191C"))
                cornerRadius = 24f
            }
            setPadding(0, 0, 0, 24)
        }

        val previewBanner = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 160)
            setBackgroundColor(Color.parseColor("#5865F2"))
        }
        cardLayout.addView(previewBanner)

        val infoLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 0)
        }

        val nameRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val myUsername = StoreStream.getUsers().me?.username ?: "User"
        val previewName = TextView(context).apply {
            text = myUsername
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val previewPronouns = TextView(context).apply {
            text = "• pronouns"
            textSize = 13f
            setTextColor(Color.parseColor("#B9BBBE"))
            setPadding(16, 0, 0, 0)
        }
        nameRow.addView(previewName)
        nameRow.addView(previewPronouns)
        infoLayout.addView(nameRow)

        val previewAccentBar = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6).apply {
                setMargins(0, 16, 0, 16)
            }
            setBackgroundColor(Color.parseColor("#EB459E"))
        }
        infoLayout.addView(previewAccentBar)

        val previewBio = TextView(context).apply {
            text = "About Me preview text will appear right here..."
            textSize = 14f
            setTextColor(Color.parseColor("#DCDDDE"))
        }
        infoLayout.addView(previewBio)
        cardLayout.addView(infoLayout)
        container.addView(cardLayout)

        // ==========================================
        // 2. Input Fields & Controls
        // ==========================================
        val modeSwitch = Switch(context).apply {
            text = "Edit Server Profile (This Guild)"
            isChecked = false
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 16)
        }
        if (guildId != 0L) {
            container.addView(modeSwitch)
        }

        val nickInput = EditText(context).apply {
            hint = "Server Nickname"
            visibility = View.GONE
        }
        container.addView(nickInput)

        val pronounsInput = EditText(context).apply {
            hint = "Pronouns (e.g. he/him, they/them)"
        }
        container.addView(pronounsInput)

        val bioInput = EditText(context).apply {
            hint = "About Me (Bio)"
        }
        container.addView(bioInput)

        val primaryColorInput = EditText(context).apply {
            hint = "Primary Theme / Banner Color (#5865F2)"
            setText("#5865F2")
        }
        container.addView(primaryColorInput)

        val accentColorInput = EditText(context).apply {
            hint = "Accent Theme Color (#EB459E)"
            setText("#EB459E")
        }
        container.addView(accentColorInput)

        // ==========================================
        // 3. Live Updating Listeners
        // ==========================================
        modeSwitch.setOnCheckedChangeListener { _, isChecked ->
            nickInput.visibility = if (isChecked) View.VISIBLE else View.GONE
            primaryColorInput.visibility = if (isChecked) View.GONE else View.VISIBLE
            accentColorInput.visibility = if (isChecked) View.GONE else View.VISIBLE
            pronounsInput.visibility = if (isChecked) View.GONE else View.VISIBLE
            previewName.text = if (isChecked && nickInput.text.isNotEmpty()) nickInput.text.toString() else myUsername
        }

        nickInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                previewName.text = if (!s.isNullOrBlank()) s.toString() else myUsername
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        pronounsInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                previewPronouns.text = if (!s.isNullOrBlank()) "• $s" else ""
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        bioInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                previewBio.text = if (!s.isNullOrBlank()) s.toString() else "About Me preview text..."
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        primaryColorInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val colorHex = s?.toString()?.trim() ?: ""
                try {
                    val parsed = Color.parseColor(colorHex)
                    previewBanner.setBackgroundColor(parsed)
                } catch (_: Throwable) {}
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        accentColorInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val colorHex = s?.toString()?.trim() ?: ""
                try {
                    val parsed = Color.parseColor(colorHex)
                    previewAccentBar.setBackgroundColor(parsed)
                } catch (_: Throwable) {}
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // ==========================================
        // 4. Dialog Construction & Action Execution
        // ==========================================
        AlertDialog.Builder(context)
            .setTitle("Advanced Profile Editor")
            .setView(scroll)
            .setPositiveButton("Save Changes") { _, _ ->
                val isServerMode = modeSwitch.isChecked
                val bio = bioInput.text.toString().trim()
                val pronouns = pronounsInput.text.toString().trim()
                val nick = nickInput.text.toString().trim()

                val primaryColor = parseColorToInt(primaryColorInput.text.toString().trim())
                val accentColor = parseColorToInt(accentColorInput.text.toString().trim())

                if (isServerMode && guildId != 0L) {
                    executeServerProfileUpdate(guildId, nick, bio)
                } else {
                    executeGlobalProfileUpdate(bio, pronouns, primaryColor, accentColor)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseColorToInt(hex: String): Int? {
        return try {
            val color = Color.parseColor(hex)
            color and 0xFFFFFF // Extract RGB values without alpha channel
        } catch (_: Throwable) {
            null
        }
    }

    private fun executeGlobalProfileUpdate(bio: String, pronouns: String, primaryColor: Int?, accentColor: Int?) {
        Utils.threadPool.execute {
            try {
                val payload = JSONObject().apply {
                    put("bio", bio)
                    put("pronouns", pronouns)

                    if (accentColor != null) {
                        put("accent_color", accentColor)
                    }

                    if (primaryColor != null && accentColor != null) {
                        put("theme_colors", JSONArray().apply {
                            put(primaryColor)
                            put(accentColor)
                        })
                    }
                }

                val res = Http.Request.newDiscordRequest("/users/@me/profile", "PATCH")
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(payload.toString())

                if (res.ok()) {
                    Utils.showToast("Global profile updated successfully!")
                } else {
                    logger.error("Failed to update global profile: [${res.statusCode}] ${res.text()}", null)
                    Utils.showToast("Update failed: HTTP ${res.statusCode}")
                }
            } catch (e: Throwable) {
                logger.error("Error updating global profile", e)
                Utils.showToast("Error: ${e.message}")
            }
        }
    }

    private fun executeServerProfileUpdate(guildId: Long, nick: String, bio: String) {
        Utils.threadPool.execute {
            try {
                val payload = JSONObject().apply {
                    if (nick.isNotEmpty()) put("nick", nick)
                    put("bio", bio)
                }

                val res = Http.Request.newDiscordRequest("/guilds/$guildId/members/@me", "PATCH")
                    .setHeader("Content-Type", "application/json")
                    .executeWithBody(payload.toString())

                if (res.ok()) {
                    Utils.showToast("Server profile updated successfully!")
                } else {
                    logger.error("Failed to update server profile: [${res.statusCode}] ${res.text()}", null)
                    Utils.showToast("Server update failed: HTTP ${res.statusCode}")
                }
            } catch (e: Throwable) {
                logger.error("Error updating server profile", e)
                Utils.showToast("Error: ${e.message}")
            }
        }
    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }
}
