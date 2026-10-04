package com.adham1223990.soundboardplayer

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Logger
import com.aliucord.Utils
import com.discord.app.AppFragment
import java.util.concurrent.Executors

/**
 * Lets the user search default + current-server soundboard sounds and play one into a voice
 * channel. [initialChannelId] / [initialGuildId] are pre-filled from the command context when
 * opened via /soundboard, but both fields stay editable in case they're wrong or missing.
 */
class SoundboardPage(
    private val initialChannelId: String?,
    private val initialGuildId: String?
) : AppFragment(Utils.getResId("widget_settings_authorized_apps", "layout")) {

    private val logger = Logger("SoundboardPlayer")
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var closed = false

    private lateinit var channelInput: EditText
    private lateinit var guildInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var status: TextView
    private lateinit var list: RecyclerView

    private var allSounds: List<SoundboardSound> = emptyList()
    private var shownSounds: List<SoundboardSound> = emptyList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle("Soundboard")

        val root = view as ViewGroup
        val column = (root.getChildAt(1) as ViewGroup).getChildAt(0) as ViewGroup
        val ctx = column.context

        channelInput = EditText(ctx).apply {
            hint = "Voice channel ID"
            setText(initialChannelId.orEmpty())
        }
        column.addView(channelInput)

        guildInput = EditText(ctx).apply {
            hint = "Current server ID (optional, loads that server's sounds)"
            setText(initialGuildId.orEmpty())
        }
        column.addView(guildInput)

        searchInput = EditText(ctx).apply {
            hint = "Search sounds by name"
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterSounds(s?.toString().orEmpty())
                }
            })
        }
        column.addView(searchInput)

        val reloadButton = Button(ctx).apply { text = "Reload sounds" }
        reloadButton.setOnClickListener { loadSounds() }
        column.addView(reloadButton)

        status = TextView(ctx).apply { text = "Loading sounds…" }
        column.addView(status)

        list = view.findViewById(Utils.getResId("authorized_apps_list", "id"))
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = SoundAdapter()

        loadSounds()
    }

    private fun loadSounds() {
        status.text = "Loading sounds…"
        val guildId = guildInput.text?.toString()?.trim().orEmpty()
        worker.execute {
            val defaults = runCatching { SoundboardApi.defaultSounds() }.getOrElse {
                logger.error("Failed to load default soundboard sounds", it)
                emptyList()
            }
            val guild = if (guildId.isNotEmpty()) {
                runCatching { SoundboardApi.guildSounds(guildId) }.getOrElse {
                    logger.error("Failed to load guild soundboard sounds for $guildId", it)
                    emptyList()
                }
            } else {
                emptyList()
            }
            main.post {
                if (closed) return@post
                allSounds = guild + defaults
                status.text = if (allSounds.isEmpty()) {
                    "No sounds found. Check the server ID, or tap Reload."
                } else {
                    "${allSounds.size} sounds loaded. Enter a voice channel ID above, then tap a sound to play it."
                }
                filterSounds(searchInput.text?.toString().orEmpty())
            }
        }
    }

    private fun filterSounds(query: String) {
        shownSounds = if (query.isEmpty()) {
            allSounds
        } else {
            val needle = query.lowercase()
            allSounds.filter { it.name.lowercase().contains(needle) }
        }
        list.adapter?.notifyDataSetChanged()
    }

    private fun playSound(sound: SoundboardSound) {
        val channelId = channelInput.text?.toString()?.trim().orEmpty()
        if (channelId.isEmpty()) {
            Utils.showToast("Enter the voice channel ID you're connected to first.", true)
            return
        }
        val currentGuildId = guildInput.text?.toString()?.trim().orEmpty()
        // Only send source_guild_id when the sound belongs to a different guild than the one
        // typed above — that's the only case the endpoint actually requires it for.
        val sourceGuildId = if (sound.guildId != null && sound.guildId != currentGuildId) sound.guildId else null

        Utils.showToast("Playing ${sound.name}…", false)
        worker.execute {
            val result = runCatching { SoundboardApi.play(channelId, sound.soundId, sourceGuildId) }
            main.post {
                if (closed) return@post
                result.onFailure {
                    logger.error("Failed to play soundboard sound ${sound.soundId}", it)
                    Utils.showToast("Could not play sound: ${it.message.orEmpty()}", true)
                }
            }
        }
    }

    override fun onDestroyView() {
        closed = true
        list.adapter = null
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private inner class SoundAdapter : RecyclerView.Adapter<SoundAdapter.Holder>() {
        inner class Holder(val row: LinearLayout, val name: TextView, val playButton: Button) :
            RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 16, 32, 16)
            }
            val name = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val playButton = Button(ctx).apply { text = "Play" }
            row.addView(name)
            row.addView(playButton)
            return Holder(row, name, playButton)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val sound = shownSounds[position]
            val emoji = sound.emojiName.orEmpty()
            holder.name.text = if (emoji.isNotEmpty()) "$emoji ${sound.name}" else sound.name
            holder.playButton.setOnClickListener { playSound(sound) }
        }

        override fun getItemCount(): Int = shownSounds.size
    }
}
