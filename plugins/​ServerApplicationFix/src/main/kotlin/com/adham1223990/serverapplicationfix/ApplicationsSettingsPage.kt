package com.adham1223990.serverapplicationfix

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Utils
import com.aliucord.api.SettingsAPI
import com.aliucord.fragments.SettingsPage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** White text + blurple background so buttons stay readable while all text stays white. */
fun Button.styleWhite() {
    setTextColor(Color.WHITE)
    setBackgroundColor(0xFF5865F2.toInt())
}

class ApplicationsSettingsPage(private val settings: SettingsAPI) : SettingsPage() {

    private lateinit var container: LinearLayout
    private var refreshing = false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Server Applications")
        ApplicationStore.init(settings)
        container = linearLayout
        render()
        refresh()
    }

    private fun refresh() {
        if (refreshing) return
        val items = ApplicationStore.all()
        if (items.isEmpty()) return
        refreshing = true
        render()
        Utils.threadPool.execute {
            var i = 0
            while (i < items.size) {
                val item = items[i]
                val status = runCatching { ApplicationApi.resolveStatus(item.guildId) }.getOrNull()
                if (status != null && status != item.status && status != "STARTED" && status != "UNKNOWN") {
                    ApplicationStore.updateStatus(item.guildId, status)
                }
                i++
            }
            Utils.mainThread.post {
                refreshing = false
                if (::container.isInitialized) render()
            }
        }
    }

    private fun white(text: String, size: Float, top: Int, bottom: Int): TextView =
        TextView(container.context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = size
            setPadding(32, top, 32, bottom)
        }

    private fun render() {
        val ctx = container.context
        container.removeAllViews()

        container.addView(white("Your applications", 20f, 24, 4))
        container.addView(
            white(
                "Servers you applied to and whether the application was accepted, " +
                    "rejected or is still being reviewed.",
                13f, 0, 16
            )
        )

        val items = ApplicationStore.all()
        if (items.isEmpty()) {
            container.addView(white("You have not applied to any server yet.", 15f, 16, 16))
        } else {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            var i = 0
            while (i < items.size) {
                val item = items[i]
                container.addView(white(item.name, 16f, 16, 2))
                container.addView(white("Status: ${ApplicationApi.statusLabel(item.status)}", 14f, 0, 2))
                if (item.updatedAt > 0L) {
                    container.addView(white("Updated: ${fmt.format(Date(item.updatedAt))}", 12f, 0, 4))
                }
                container.addView(Button(ctx).apply {
                    text = "Remove from list"
                    styleWhite()
                    setOnClickListener {
                        ApplicationStore.remove(item.guildId)
                        ApplicationApi.appliedGuilds.remove(item.guildId)
                        render()
                    }
                })
                i++
            }
        }

        container.addView(Button(ctx).apply {
            text = if (refreshing) "Refreshing…" else "Refresh statuses"
            isEnabled = !refreshing
            styleWhite()
            setOnClickListener { refresh() }
        })
    }
}
