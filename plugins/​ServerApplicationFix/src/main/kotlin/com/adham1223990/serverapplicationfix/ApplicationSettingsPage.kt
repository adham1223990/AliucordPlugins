package com.adham1223990.serverapplicationfix

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import com.discord.stores.StoreStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ApplicationsSettingsPage(
    private val settings: SettingsAPI?
) : SettingsPage() {

    companion object {
        var openPage: ((Context, SettingsPage) -> Unit)? = null
    }

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val ctx = view.context
        val textColor = FormStyle.getTextColor(settings)

        setActionBarTitle("View Applications Status")
        setActionBarSubtitle("Servers you applied to & pending applications")

        val container = linearLayout
        container.removeAllViews()
        container.setBackgroundColor(FormStyle.PAGE_BG_COLOR)
        val pad = FormStyle.dp(ctx, 16)
        container.setPadding(pad, pad, pad, pad)

        container.addView(buildQuickApplyBox(ctx, view, textColor))

        syncPendingGuildsFromStore()

        val records = ApplicationStore.getAll(settings)
        val listHeader = TextView(ctx).apply {
            text = "Applied Servers (${records.size})"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, FormStyle.dp(ctx, 16), 0, FormStyle.dp(ctx, 8))
        }
        container.addView(listHeader)

        if (records.isEmpty()) {
            val emptyBox = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 10, FormStyle.STROKE_COLOR, 1)
                setPadding(pad, pad, pad, pad)
            }
            emptyBox.addView(
                TextView(ctx).apply {
                    text = "No server applications recorded yet.\n\nWhen you join a server that shows 'View Application Status' or apply to a server above, it will appear here."
                    textSize = 14f
                    setTextColor(textColor)
                }
            )
            container.addView(emptyBox)
        } else {
            records.forEach { rec ->
                container.addView(buildServerRecordCard(ctx, view, rec, textColor))
            }
        }

        FormStyle.applyTextColorRecursively(container, settings)
    }

    @SuppressLint("SetTextI18n")
    private fun buildQuickApplyBox(ctx: Context, rootView: View, textColor: Int): View {
        val pad = FormStyle.dp(ctx, 14)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 10, FormStyle.STROKE_COLOR, 1)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        card.addView(
            TextView(ctx).apply {
                text = "Open or Check Server Application"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textColor)
            }
        )

        card.addView(
            TextView(ctx).apply {
                text = "Enter an Invite Code/Link (recommended if not in server yet) OR a Server ID:"
                textSize = 13f
                setTextColor(textColor)
                setPadding(0, FormStyle.dp(ctx, 4), 0, FormStyle.dp(ctx, 8))
            }
        )

        val guildIdInput = EditText(ctx).apply {
            hint = "Server ID (optional if Invite Code is provided)"
            setTextColor(textColor)
            setHintTextColor(FormStyle.withAlpha(textColor, 150))
            textSize = 14f
            background = FormStyle.roundedBg(ctx, FormStyle.INPUT_BG_COLOR, 8, FormStyle.STROKE_COLOR, 1)
            val iPad = FormStyle.dp(ctx, 10)
            setPadding(iPad, iPad, iPad, iPad)
        }
        card.addView(guildIdInput)

        val inviteInput = EditText(ctx).apply {
            hint = "Invite Code or Link (e.g. discord.gg/abc)"
            setTextColor(textColor)
            setHintTextColor(FormStyle.withAlpha(textColor, 150))
            textSize = 14f
            background = FormStyle.roundedBg(ctx, FormStyle.INPUT_BG_COLOR, 8, FormStyle.STROKE_COLOR, 1)
            val iPad = FormStyle.dp(ctx, 10)
            setPadding(iPad, iPad, iPad, iPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FormStyle.dp(ctx, 8) }
        }
        card.addView(inviteInput)

        val openBtn = createSmallButton(ctx, "View Application Status / Apply", FormStyle.BLURPLE_COLOR, textColor) {
            val rawGuildId = guildIdInput.text?.toString()?.trim()?.toLongOrNull()
            val code = ApplicationApi.cleanInviteCode(inviteInput.text?.toString())

            if ((rawGuildId == null || rawGuildId <= 0L) && code == null) {
                Utils.showToast("Please enter a Server ID or an Invite Code")
                return@createSmallButton
            }

            if (rawGuildId != null && rawGuildId > 0L) {
                val page = ApplicationPage(settings, rawGuildId, null, code)
                openPage?.invoke(ctx, page) ?: Utils.openPageWithProxy(ctx, page)
            } else if (code != null) {
                Utils.showToast("Resolving invite code...")
                Utils.threadPool.execute {
                    val resolved = ApplicationApi.resolveInviteGuild(code)
                    Utils.mainThread.post {
                        if (resolved != null) {
                            val (gId, gName, gIcon) = resolved
                            ApplicationStore.upsert(
                                settings,
                                SavedApplicationRecord(
                                    guildId = gId,
                                    guildName = gName ?: "Server $gId",
                                    guildIcon = gIcon,
                                    inviteCode = code
                                )
                            )
                            val page = ApplicationPage(settings, gId, gName, code)
                            openPage?.invoke(ctx, page) ?: Utils.openPageWithProxy(ctx, page)
                        } else {
                            Utils.showToast("Invalid or expired invite code")
                        }
                    }
                }
            }
        }
        card.addView(openBtn)

        return card
    }

    @SuppressLint("SetTextI18n")
    private fun buildServerRecordCard(
        ctx: Context,
        rootView: View,
        rec: SavedApplicationRecord,
        textColor: Int
    ): View {
        val pad = FormStyle.dp(ctx, 14)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 10, FormStyle.STROKE_COLOR, 1)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = FormStyle.dp(ctx, 12)
            }
        }

        card.addView(
            TextView(ctx).apply {
                text = rec.guildName
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textColor)
            }
        )

        card.addView(
            TextView(ctx).apply {
                text = "Server ID: ${rec.guildId}"
                textSize = 12f
                setTextColor(textColor)
                setPadding(0, FormStyle.dp(ctx, 2), 0, FormStyle.dp(ctx, 6))
            }
        )

        val badge = TextView(ctx).apply {
            text = "Status: ${rec.status.uppercase()}"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            background = FormStyle.roundedBg(ctx, FormStyle.statusBadgeColor(rec.status), 6)
            val hPad = FormStyle.dp(ctx, 8)
            val vPad = FormStyle.dp(ctx, 4)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(badge)

        if (!rec.rejectionReason.isNullOrBlankSafe()) {
            card.addView(
                TextView(ctx).apply {
                    text = "Reason: ${rec.rejectionReason}"
                    textSize = 13f
                    setTextColor(textColor)
                    setPadding(0, FormStyle.dp(ctx, 6), 0, 0)
                }
            )
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        card.addView(
            TextView(ctx).apply {
                text = "Last updated: ${dateFormat.format(Date(rec.lastUpdatedMs))}"
                textSize = 12f
                setTextColor(textColor)
                setPadding(0, FormStyle.dp(ctx, 6), 0, FormStyle.dp(ctx, 8))
            }
        )

        val openFormBtn = createSmallButton(ctx, "View Application Status / Form", FormStyle.BLURPLE_COLOR, textColor) {
            val page = ApplicationPage(settings, rec.guildId, rec.guildName, rec.inviteCode)
            openPage?.invoke(ctx, page) ?: Utils.openPageWithProxy(ctx, page)
        }
        card.addView(openFormBtn)

        val refreshBtn = createSmallButton(ctx, "Refresh Status", FormStyle.GREEN_COLOR, textColor) {
            Utils.showToast("Refreshing status for ${rec.guildName}...")
            Utils.threadPool.execute {
                val res = ApplicationApi.fetchJoinRequestStatus(rec.guildId, rec.requestId, rec.inviteCode)
                Utils.mainThread.post {
                    if (res.ok && !res.applicationStatus.isNullOrBlankSafe()) {
                        ApplicationStore.upsert(
                            settings,
                            rec.copy(
                                requestId = res.requestId ?: rec.requestId,
                                status = res.applicationStatus,
                                rejectionReason = res.rejectionReason ?: rec.rejectionReason,
                                interviewChannelId = res.interviewChannelId ?: rec.interviewChannelId
                            )
                        )
                        Utils.showToast("Updated status: ${res.applicationStatus}")
                    } else {
                        Utils.showToast("Status unchanged (${rec.status})")
                    }
                    if (isAdded) onViewBound(rootView)
                }
            }
        }
        card.addView(refreshBtn)

        val removeBtn = createSmallButton(ctx, "Remove From List", FormStyle.RED_COLOR, textColor) {
            ApplicationStore.removeByGuildId(settings, rec.guildId)
            Utils.showToast("Removed ${rec.guildName} from list")
            if (isAdded) onViewBound(rootView)
        }
        card.addView(removeBtn)

        return card
    }

    private fun syncPendingGuildsFromStore() {
        try {
            val guilds = StoreStream.getGuilds().guilds
            val meId = StoreStream.getUsers().me.id
            val members = StoreStream.getGuilds().members
            for ((guildId, guild) in guilds) {
                val myMember = members[guildId]?.get(meId)
                if (myMember != null && myMember.getPending()) {
                    val existing = ApplicationStore.getByGuildId(settings, guildId)
                    if (existing == null) {
                        ApplicationStore.upsert(
                            settings,
                            SavedApplicationRecord(
                                guildId = guildId,
                                guildName = guild.name ?: "Server $guildId",
                                guildIcon = guild.icon,
                                status = "STARTED"
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun createSmallButton(
        ctx: Context,
        label: String,
        bgColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(ctx).apply {
            text = label
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            background = FormStyle.roundedBg(ctx, bgColor, 8)
            val vPad = FormStyle.dp(ctx, 10)
            val hPad = FormStyle.dp(ctx, 12)
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = FormStyle.dp(ctx, 8)
            }
            setOnClickListener { onClick() }
        }
    }
}

class PluginTextColorSettingsPage(
    private val settings: SettingsAPI?
) : SettingsPage() {

    @SuppressLint("SetTextI18n")
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val ctx = view.context
        val currentTextColor = FormStyle.getTextColor(settings)
        val currentHex = FormStyle.getTextColorHex(settings)

        setActionBarTitle("ServerApplicationFix Settings")
        setActionBarSubtitle("Text Color Customization")

        val container = linearLayout
        container.removeAllViews()
        container.setBackgroundColor(FormStyle.PAGE_BG_COLOR)
        val pad = FormStyle.dp(ctx, 16)
        container.setPadding(pad, pad, pad, pad)

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = FormStyle.roundedBg(ctx, FormStyle.CARD_BG_COLOR, 10, FormStyle.STROKE_COLOR, 1)
            setPadding(pad, pad, pad, pad)
        }

        card.addView(
            TextView(ctx).apply {
                text = "Text Color Preference"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(currentTextColor)
            }
        )

        card.addView(
            TextView(ctx).apply {
                text = "Default color is Pure White (#FFFFFF). All texts in 'View Application Status' and forms use this color."
                textSize = 13f
                setTextColor(currentTextColor)
                setPadding(0, FormStyle.dp(ctx, 6), 0, FormStyle.dp(ctx, 12))
            }
        )

        val hexInput = EditText(ctx).apply {
            setText(currentHex)
            hint = "#FFFFFF"
            setTextColor(currentTextColor)
            setHintTextColor(FormStyle.withAlpha(currentTextColor, 150))
            textSize = 14f
            background = FormStyle.roundedBg(ctx, FormStyle.INPUT_BG_COLOR, 8, FormStyle.STROKE_COLOR, 1)
            val iPad = FormStyle.dp(ctx, 12)
            setPadding(iPad, iPad, iPad, iPad)
        }
        card.addView(hexInput)

        val saveBtn = TextView(ctx).apply {
            text = "Save Text Color"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(currentTextColor)
            gravity = Gravity.CENTER
            background = FormStyle.roundedBg(ctx, FormStyle.GREEN_COLOR, 8)
            val vPad = FormStyle.dp(ctx, 12)
            setPadding(vPad, vPad, vPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FormStyle.dp(ctx, 12) }
            setOnClickListener {
                val inputHex = hexInput.text?.toString()?.trim() ?: ""
                if (FormStyle.setTextColorHex(settings, inputHex)) {
                    Utils.showToast("Text color updated to ${inputHex.uppercase()}")
                    onViewBound(view)
                } else {
                    Utils.showToast("Invalid Hex color! Example: #FFFFFF")
                }
            }
        }
        card.addView(saveBtn)

        val resetBtn = TextView(ctx).apply {
            text = "Reset to Default White (#FFFFFF)"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(currentTextColor)
            gravity = Gravity.CENTER
            background = FormStyle.roundedBg(ctx, FormStyle.BLURPLE_COLOR, 8)
            val vPad = FormStyle.dp(ctx, 12)
            setPadding(vPad, vPad, vPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FormStyle.dp(ctx, 10) }
            setOnClickListener {
                FormStyle.resetTextColor(settings)
                Utils.showToast("Text color reset to #FFFFFF")
                onViewBound(view)
            }
        }
        card.addView(resetBtn)

        val openAppsBtn = TextView(ctx).apply {
            text = "Open View Applications Status"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(currentTextColor)
            gravity = Gravity.CENTER
            background = FormStyle.roundedBg(ctx, FormStyle.STROKE_COLOR, 8)
            val vPad = FormStyle.dp(ctx, 12)
            setPadding(vPad, vPad, vPad, vPad)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = FormStyle.dp(ctx, 16) }
            setOnClickListener {
                Utils.openPageWithProxy(ctx, ApplicationsSettingsPage(settings))
            }
        }
        card.addView(openAppsBtn)

        container.addView(card)
        FormStyle.applyTextColorRecursively(container, settings)
    }
}