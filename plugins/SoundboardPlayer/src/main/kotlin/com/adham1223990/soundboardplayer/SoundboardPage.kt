package com.adham1223990.soundboardplayer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aliucord.Logger
import com.aliucord.Utils
import com.discord.app.AppFragment
import com.discord.stores.StoreStream
import java.util.concurrent.Executors

// One row in the flat list backing the RecyclerView: either a section header (a server's name,
// or "Default") or a playable sound belonging to the section above it.
private sealed class Row {
    data class Header(val title: String) : Row()
    data class Sound(val sound: SoundboardSound) : Row()
}

/**
 * Lets the user search and play a soundboard sound into a voice channel, grouped by server --
 * the default sounds plus every server the user is currently a member of that has its own
 * soundboard sounds, same as Discord's own in-call Soundboard sheet. [initialChannelId] /
 * [initialGuildId] are pre-filled from the command/call-button context when opened, and
 * [initialGuildId] also doubles as "the channel's own server" for the source_guild_id rule below;
 * [initialChannelId] stays editable in case it's wrong or missing.
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
    private lateinit var searchInput: EditText
    private lateinit var status: TextView
    private lateinit var list: RecyclerView

    // allRows is the full grouped list (every server's section + Default); shownRows is that
    // same list filtered by the search box, which is what's actually displayed.
    private var allRows: List<Row> = emptyList()
    private var shownRows: List<Row> = emptyList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        // This layout is borrowed from Discord's own "Authorized Apps" settings page, which
        // carries its own Toolbar title/subtitle baked into the XML -- clear those first or
        // "Authorized Apps" renders on top of our own "Soundboard" title.
        val viewGroup = view as ViewGroup
        val toolbar = viewGroup.getChildAt(0) as ViewGroup
        (toolbar.getChildAt(0) as Toolbar).apply {
            title = null
            subtitle = null
        }
        setActionBarTitle("Soundboard")

        val column = (viewGroup.getChildAt(1) as ViewGroup).getChildAt(0) as ViewGroup
        val ctx = column.context

        channelInput = EditText(ctx).apply {
            hint = "Voice channel ID"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            setText(initialChannelId.orEmpty())
        }
        column.addView(channelInput)

        searchInput = EditText(ctx).apply {
            hint = "Search sounds by name"
            setHintTextColor(Color.LTGRAY)
            setTextColor(Color.WHITE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    filterRows(s?.toString().orEmpty())
                }
            })
        }
        column.addView(searchInput)

        val reloadButton = Button(ctx).apply {
            text = "Reload sounds"
            setTextColor(Color.WHITE)
        }
        reloadButton.setOnClickListener { loadSounds() }
        column.addView(reloadButton)

        status = TextView(ctx).apply {
            text = "Loading sounds…"
            setTextColor(Color.WHITE)
        }
        column.addView(status)

        list = view.findViewById(Utils.getResId("authorized_apps_list", "id"))
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = RowAdapter()

        loadSounds()
    }

    // Bumped on every loadSounds() call so a stale background load (from a previous tap on
    // Reload) can tell it's no longer the current one and stop appending to the list instead of
    // racing the newer load.
    private var loadGeneration = 0

    // Loads the default sounds plus every currently-joined server's own sounds -- the same set
    // Discord's own Soundboard sheet shows -- instead of only the one server the caller happened
    // to be in. Guild ids come straight from the local guild store (already cached on-device by
    // the gateway), so no extra request is needed just to know which servers to ask about.
    //
    // Each section (Default, then one per server) is fetched one at a time and pushed onto the
    // screen as soon as it arrives, exactly like Discord's own Soundboard sheet: whatever has
    // loaded so far is already visible and tappable while the rest keeps loading quietly in the
    // background, instead of leaving the whole page stuck on "Loading…" until every server has
    // answered.
    private fun loadSounds() {
        val generation = ++loadGeneration
        allRows = emptyList()
        shownRows = emptyList()
        list.adapter?.notifyDataSetChanged()
        status.text = "Loading sounds…"

        val guildIds = runCatching {
            StoreStream.getGuilds().getGuilds().keys.map { it.toString() }
        }.getOrElse {
            logger.error("Failed to read the local guild list", it)
            emptyList()
        }

        worker.execute {
            fun isStale() = closed || generation != loadGeneration

            fun publish(newRows: List<Row>, stillLoading: Boolean) {
                if (newRows.isEmpty() && stillLoading) return
                main.post {
                    if (isStale()) return@post
                    if (newRows.isNotEmpty()) {
                        allRows = allRows + newRows
                        filterRows(searchInput.text?.toString().orEmpty())
                    }
                    val soundCount = allRows.count { it is Row.Sound }
                    val serverCount = allRows.count { it is Row.Header }
                    status.text = when {
                        stillLoading && soundCount == 0 -> "Loading sounds…"
                        stillLoading -> "$soundCount sounds across $serverCount section(s) so far — still loading the rest…"
                        soundCount == 0 -> "No sounds found on any of your servers. Tap Reload to try again."
                        else -> "$soundCount sounds across $serverCount section(s). " +
                            "Enter a voice channel ID above, then tap a sound to play it."
                    }
                }
            }

            val defaults = runCatching { SoundboardApi.defaultSounds() }.getOrElse {
                logger.error("Failed to load default soundboard sounds", it)
                emptyList()
            }
            if (isStale()) return@execute
            if (defaults.isNotEmpty()) {
                publish(listOf(Row.Header("Default")) + defaults.map { Row.Sound(it) }, stillLoading = true)
            }

            // Sorting by name keeps the server list in a stable, predictable order across
            // reloads rather than whatever order the local store happens to iterate in.
            val sortedGuilds = guildIds.mapNotNull { id ->
                val name = runCatching {
                    StoreStream.getGuilds().getGuild(id.toLong())?.name
                }.getOrNull()
                if (name != null) id to name else null
            }.sortedBy { it.second.lowercase() }

            for ((guildId, guildName) in sortedGuilds) {
                if (isStale()) return@execute
                val sounds = runCatching { SoundboardApi.guildSounds(guildId) }.getOrElse {
                    logger.error("Failed to load soundboard sounds for guild $guildId", it)
                    emptyList()
                }
                if (isStale()) return@execute
                if (sounds.isNotEmpty()) {
                    publish(listOf(Row.Header(guildName)) + sounds.map { Row.Sound(it) }, stillLoading = true)
                }
            }

            publish(emptyList(), stillLoading = false)
        }
    }

    // Filters by sound name only; a header stays visible as long as at least one sound under
    // it still matches, so the grouping by server is preserved while searching.
    private fun filterRows(query: String) {
        if (query.isEmpty()) {
            shownRows = allRows
            list.adapter?.notifyDataSetChanged()
            return
        }
        val needle = query.lowercase()
        val result = mutableListOf<Row>()
        var pendingHeader: Row.Header? = null
        for (row in allRows) {
            when (row) {
                is Row.Header -> pendingHeader = row
                is Row.Sound -> {
                    if (row.sound.name.lowercase().contains(needle)) {
                        pendingHeader?.let { result.add(it); pendingHeader = null }
                        result.add(row)
                    }
                }
            }
        }
        shownRows = result
        list.adapter?.notifyDataSetChanged()
    }

    private fun playSound(sound: SoundboardSound) {
        val channelId = channelInput.text?.toString()?.trim().orEmpty()
        if (channelId.isEmpty()) {
            Utils.showToast("Enter the voice channel ID you're connected to first.", true)
            return
        }
        // source_guild_id is only required when the sound belongs to a different server than
        // the one the target channel is in. initialGuildId is that channel's own server, taken
        // from the call/command context the page was opened with.
        val sourceGuildId = if (sound.guildId != null && sound.guildId != initialGuildId) sound.guildId else null

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

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val typeHeader = 0
        private val typeSound = 1

        inner class HeaderHolder(val title: TextView) : RecyclerView.ViewHolder(title)
        inner class SoundHolder(val row: LinearLayout, val name: TextView, val playButton: Button) :
            RecyclerView.ViewHolder(row)

        override fun getItemViewType(position: Int): Int =
            if (shownRows[position] is Row.Header) typeHeader else typeSound

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            if (viewType == typeHeader) {
                val title = TextView(ctx).apply {
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, Typeface.BOLD)
                    textSize = 14f
                    setPadding(32, 24, 32, 8)
                }
                return HeaderHolder(title)
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 16, 32, 16)
            }
            val name = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val playButton = Button(ctx).apply {
                text = "Play"
                setTextColor(Color.WHITE)
            }
            row.addView(name)
            row.addView(playButton)
            return SoundHolder(row, name, playButton)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = shownRows[position]) {
                is Row.Header -> (holder as HeaderHolder).title.text = row.title
                is Row.Sound -> {
                    val sound = row.sound
                    val h = holder as SoundHolder
                    val emoji = sound.emojiName.orEmpty()
                    h.name.text = if (emoji.isNotEmpty()) "$emoji ${sound.name}" else sound.name
                    h.playButton.setOnClickListener { playSound(sound) }
                }
            }
        }

        override fun getItemCount(): Int = shownRows.size
    }
}
