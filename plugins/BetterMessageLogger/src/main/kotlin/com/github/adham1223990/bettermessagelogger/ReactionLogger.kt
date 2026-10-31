package com.github.adham1223990.bettermessagelogger

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.aliucord.Constants
import com.aliucord.Utils
import com.aliucord.api.PatcherAPI
import com.aliucord.api.SettingsAPI
import com.aliucord.utils.GsonUtils.fromJson
import com.aliucord.utils.GsonUtils.toJson
import com.aliucord.patcher.Hook
import com.aliucord.patcher.PreHook
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.message.reaction.MessageReactionUpdate
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import com.discord.models.message.Message
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStream
import com.discord.views.ReactionView
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterEventsHandler
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemReactions
import com.discord.widgets.chat.list.entries.ChatListEntry
import java.io.File
import java.text.DateFormat
import java.util.ArrayList
import java.util.Date
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import com.discord.api.message.Message as ApiMessage

/** One removed reaction. [userId] is 0 and [count] is above 1 for bulk removals (clear all / clear emoji). */
internal data class RemovedReaction(
    val messageId: Long,
    val channelId: Long,
    val emojiKey: String,
    val emojiName: String?,
    val emojiId: String?,
    val userId: Long,
    val userName: String?,
    val count: Int,
    val timestamp: Long,
) {
    val id get() = if (userId == 0L) "$messageId|$emojiKey|0|$timestamp" else "$messageId|$emojiKey|$userId"
    val label get() = if (emojiId != null) ":${emojiName ?: emojiKey}:" else (emojiName ?: emojiKey)
}

/** Thread-safe, bounded log with a per-message index. */
internal class ReactionLogStore {
    private val lock = Any()
    private val entries = LinkedHashMap<String, RemovedReaction>()
    private val byMessage = HashMap<Long, ArrayList<RemovedReaction>>()

    fun put(entry: RemovedReaction) = synchronized(lock) {
        removeLocked(entry.id)
        entries[entry.id] = entry
        byMessage.getOrPut(entry.messageId) { ArrayList() }.add(entry)
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.iterator().next()
            removeLocked(oldest)
        }
    }

    fun remove(id: String): Boolean = synchronized(lock) { removeLocked(id) }

    private fun removeLocked(id: String): Boolean {
        val old = entries.remove(id) ?: return false
        val list = byMessage[old.messageId]
        list?.remove(old)
        if (list != null && list.isEmpty()) byMessage.remove(old.messageId)
        return true
    }

    fun forMessage(messageId: Long): List<RemovedReaction> = synchronized(lock) {
        byMessage[messageId]?.let { ArrayList(it) } ?: emptyList()
    }

    fun hasMessage(messageId: Long) = synchronized(lock) { byMessage.containsKey(messageId) }

    fun snapshot(): List<RemovedReaction> = synchronized(lock) { ArrayList(entries.values) }

    fun size() = synchronized(lock) { entries.size }

    fun clear() = synchronized(lock) {
        entries.clear()
        byMessage.clear()
    }

    fun removeWhere(predicate: (RemovedReaction) -> Boolean): Boolean = synchronized(lock) {
        val doomed = ArrayList<String>()
        entries.values.forEach { if (predicate(it)) doomed.add(it.id) }
        doomed.forEach { removeLocked(it) }
        doomed.isNotEmpty()
    }

    companion object {
        const val MAX_ENTRIES = 3000
    }
}

/**
 * Reaction logger. Mirrors SuperReactions' approach for the pill UI: the reaction list is expanded in
 * `displayReactions`, every produced [MessageReaction] is tracked by identity, and `ReactionView.a`
 * styles the pill. Removed reactions get their own dashed, struck-through pill; active ones stay native.
 */
internal class ReactionLogger(
    private val settings: SettingsAPI,
    private val keep: (userId: Long, channelId: Long) -> Boolean,
    private val persistEnabled: () -> Boolean,
    private val changed: () -> Unit,
    private val report: (String, Throwable) -> Unit,
) {
    private val store = ReactionLogStore()
    private val file = File(Constants.BASE_PATH, FILE_NAME)
    private val emojiCache = ConcurrentHashMap<String, MessageReactionEmoji>()
    private val ghostTypes = LruCache<Long, IdentityHashMap<MessageReaction, Int>>(256)
    private val styles = WeakHashMap<ReactionView, Style>()
    private val visible = WeakHashMap<WidgetChatListAdapterItemReactions, Pair<Int, ChatListEntry>>()
    private val decorated = LruCache<Long, Decorated>(64)
    private var executor: ExecutorService? = null
    private val saveTask = Runnable { if (running && persistEnabled()) pool().execute { write() } }

    @Volatile private var running = true

    private class Decorated(val source: Message, val signature: Int, val result: Message)

    private val emojiConstructor by lazy {
        runCatching {
            MessageReactionEmoji::class.java.getConstructor(String::class.java, String::class.java, java.lang.Boolean.TYPE)
        }.getOrNull()
    }

    private fun logging() = settings.getBool(Keys.LOG_REACTIONS, true)
    private fun showing() = settings.getBool(Keys.SHOW_REMOVED_REACTIONS, true)

    fun patch(patcher: PatcherAPI) {
        val long = Long::class.javaPrimitiveType!!
        val int = Int::class.javaPrimitiveType!!
        val bool = Boolean::class.javaPrimitiveType!!

        // --- Capture -------------------------------------------------------------------------------
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionUpdate",
            arrayOf(List::class.java, bool),
            PreHook { frame ->
                if (!logging()) return@PreHook
                val isAdd = frame.args[1] == true
                safely("log reaction update") {
                    var dirty = false
                    (frame.args[0] as? List<*>)?.filterIsInstance<MessageReactionUpdate>()?.forEach { update ->
                        val result = !isAdd && removed(update)
                        if (result) dirty = true
                    }
                    if (dirty) onLogChanged()
                }
            },
        )
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionsRemoveAll",
            arrayOf(MessageReactionUpdate::class.java),
            PreHook { frame ->
                if (!logging()) return@PreHook
                safely("log cleared reactions") {
                    val update = frame.args[0] as MessageReactionUpdate
                    val message = runCatching { (frame.thisObject as StoreMessages).getMessage(update.a(), update.c()) }
                        .getOrNull()
                    var dirty = false
                    reactionsOf(message).forEach { if (bulk(update, it)) dirty = true }
                    if (dirty) onLogChanged()
                }
            },
        )
        patcher.patch(
            StoreMessages::class.java,
            "handleReactionsRemoveEmoji",
            arrayOf(MessageReactionUpdate::class.java),
            PreHook { frame ->
                if (!logging()) return@PreHook
                safely("log cleared emoji") {
                    val update = frame.args[0] as MessageReactionUpdate
                    val key = update.b()?.c() ?: return@safely
                    val message = runCatching { (frame.thisObject as StoreMessages).getMessage(update.a(), update.c()) }
                        .getOrNull()
                    val match = reactionsOf(message).firstOrNull { it.b()?.c() == key } ?: return@safely
                    if (bulk(update, match)) onLogChanged()
                }
            },
        )

        // --- Display -------------------------------------------------------------------------------
        patcher.patch(
            WidgetChatListAdapterItemReactions::class.java,
            "displayReactions",
            arrayOf(Collection::class.java, long, bool, bool, bool),
            PreHook { frame ->
                val messageId = frame.args[1] as Long
                try {
                    frame.args[0] = expand(frame.args[0] as? Collection<*>, messageId)
                } catch (error: Throwable) {
                    report("expand reactions", error)
                }
            },
        )
        patcher.patch(
            WidgetChatListAdapterItemReactions::class.java,
            "onConfigure",
            arrayOf(int, ChatListEntry::class.java),
            Hook { frame ->
                val entry = frame.args[1] as? ChatListEntry ?: return@Hook
                synchronized(visible) {
                    visible[frame.thisObject as WidgetChatListAdapterItemReactions] = Pair(frame.args[0] as Int, entry)
                }
            },
        )
        patcher.patch(
            ReactionView::class.java,
            "a",
            arrayOf(MessageReaction::class.java, long, bool),
            Hook { frame ->
                val view = frame.thisObject as ReactionView
                val reaction = frame.args[0] as? MessageReaction ?: return@Hook
                val removedCount = ghostCount(frame.args[1] as Long, reaction)
                try {
                    val style = synchronized(styles) { styles.getOrPut(view) { Style(view) } }
                    if (removedCount != null) style.apply(view, color(), removedCount) else style.restore(view)
                } catch (error: Throwable) {
                    report("style reaction", error)
                }
            },
        )
        // A removed pill must never re-add the reaction; tapping it opens the details instead.
        patcher.patch(
            WidgetChatListAdapterEventsHandler.UserReactionHandler::class.java,
            "toggleReaction",
            arrayOf(long, long, long, MessageReaction::class.java),
            PreHook { frame ->
                val messageId = frame.args[2] as Long
                val reaction = frame.args[3] as? MessageReaction ?: return@PreHook
                if (ghostCount(messageId, reaction) == null) return@PreHook
                frame.result = null
                val key = reaction.b()?.c() ?: return@PreHook
                Utils.mainThread.post {
                    Utils.appActivity?.let {
                        ReactionLogDialog.show(it, "Removed reactions", store.forMessage(messageId).filter { e -> e.emojiKey == key }, ::resolveName, false)
                    }
                }
            },
        )
    }

    // --- Model: keeps the reactions row alive when every reaction of a message was removed ---------

    /** Adds invisible placeholders so Discord still builds the reactions row for fully removed emojis. */
    fun decorate(messages: List<Message>): List<Message> {
        if (!showing() || store.size() == 0) return messages
        var output: ArrayList<Message>? = null
        messages.forEachIndexed { index, message ->
            val logged = store.forMessage(message.id)
            if (logged.isEmpty()) return@forEachIndexed
            val present = HashSet<String>()
            reactionsOf(message).forEach { reaction -> reaction.b()?.c()?.let { present.add(it) } }
            val missing = LinkedHashMap<String, RemovedReaction>()
            logged.forEach { if (it.emojiKey !in present && it.emojiKey !in missing) missing[it.emojiKey] = it }
            if (missing.isEmpty()) return@forEachIndexed
            val signature = missing.keys.hashCode()
            val cached = synchronized(decorated) { decorated.get(message.id) }
            val replacement = if (cached != null && cached.source === message && cached.signature == signature) {
                cached.result
            } else {
                val built = withPlaceholders(message, missing.values) ?: return@forEachIndexed
                synchronized(decorated) { decorated.put(message.id, Decorated(message, signature, built)) }
                built
            }
            if (output == null) output = ArrayList(messages)
            output!![index] = replacement
        }
        return output ?: messages
    }

    private fun withPlaceholders(message: Message, missing: Collection<RemovedReaction>): Message? = try {
        val gson = InboundGatewayGsonParser.INSTANCE.gatewayGsonInstance
        val tree = JSONObject(gson.toJson(message.synthesizeApiMessage()))
        val array = tree.optJSONArray("reactions") ?: JSONArray().also { tree.put("reactions", it) }
        missing.forEach { entry ->
            val emoji = JSONObject()
                .put("id", entry.emojiId ?: JSONObject.NULL)
                .put("name", entry.emojiName ?: JSONObject.NULL)
            array.put(JSONObject().put("count", PLACEHOLDER_COUNT).put("me", false).put("emoji", emoji))
        }
        Message(gson.fromJson(tree.toString(), ApiMessage::class.java))
    } catch (error: Throwable) {
        report("add reaction placeholders", error)
        null
    }

    // --- Reaction expansion (same idea as SuperReactions.expandReactions) ----------------------------

    private fun expand(reactions: Collection<*>?, messageId: Long): List<MessageReaction> {
        val source = ArrayList<MessageReaction>()
        reactions?.forEach { if (it is MessageReaction && it.a() != PLACEHOLDER_COUNT) source.add(it) }
        val groups = LinkedHashMap<String, ArrayList<RemovedReaction>>()
        if (showing()) {
            store.forMessage(messageId).forEach { groups.getOrPut(it.emojiKey) { ArrayList() }.add(it) }
        }
        val types = IdentityHashMap<MessageReaction, Int>()
        val output = ArrayList<MessageReaction>(source.size + groups.size)

        fun addGhost(group: ArrayList<RemovedReaction>) {
            val emoji = emojiOf(group[0]) ?: return
            var total = 0
            group.forEach { total += it.count }
            val ghost = MessageReaction(total, emoji, false)
            types[ghost] = total
            output.add(ghost)
        }
        source.forEach { reaction ->
            output.add(reaction)
            reaction.b()?.c()?.let { key -> groups.remove(key)?.let(::addGhost) }
        }
        groups.values.toList().forEach(::addGhost)
        synchronized(ghostTypes) { ghostTypes.put(messageId, types) }
        return output
    }

    private fun ghostCount(messageId: Long, reaction: MessageReaction): Int? =
        synchronized(ghostTypes) { ghostTypes.get(messageId)?.get(reaction) }

    // --- Capture helpers -----------------------------------------------------------------------------

    private fun removed(update: MessageReactionUpdate): Boolean {
        val emoji = update.b() ?: return false
        val key = emoji.c() ?: return false
        val userId = update.d()
        if (settings.getBool(Keys.REACTION_IGNORE_OWN, true) && userId == currentUserId()) return false
        if (!keep(userId, update.a())) return false
        emojiCache[key] = emoji
        val name = runCatching { StoreStream.getUsers().users[userId]?.username }.getOrNull()
        store.put(
            RemovedReaction(update.c(), update.a(), key, emoji.b(), emoji.d(), userId, name, 1, System.currentTimeMillis()),
        )
        return true
    }

    private fun bulk(update: MessageReactionUpdate, reaction: MessageReaction): Boolean {
        val emoji = reaction.b() ?: return false
        val key = emoji.c() ?: return false
        if (!keep(0L, update.a())) return false
        emojiCache[key] = emoji
        store.put(
            RemovedReaction(update.c(), update.a(), key, emoji.b(), emoji.d(), 0L, null, maxOf(reaction.a(), 1), System.currentTimeMillis()),
        )
        return true
    }

    private fun onLogChanged() {
        saveSoon()
        changed()
    }

    private fun reactionsOf(message: Message?): List<MessageReaction> {
        val raw: Any? = message?.reactions
        val items: Collection<*> = when (raw) {
            is Map<*, *> -> raw.values
            is Collection<*> -> raw
            else -> emptyList<Any?>()
        }
        return items.filterIsInstance<MessageReaction>().filter { it.a() != PLACEHOLDER_COUNT }
    }

    private fun emojiOf(entry: RemovedReaction): MessageReactionEmoji? = emojiCache[entry.emojiKey]
        ?: runCatching { emojiConstructor?.newInstance(entry.emojiId, entry.emojiName, false) }.getOrNull()
            ?.also { emojiCache[entry.emojiKey] = it }

    private fun currentUserId(): Long = runCatching { StoreStream.getUsers().me.id }.getOrDefault(0L)

    private fun resolveName(userId: Long): String? = runCatching { StoreStream.getUsers().users[userId]?.username }.getOrNull()

    private fun color(): Int = try {
        Color.parseColor(settings.getString(Keys.REMOVED_REACTION_COLOR, Keys.DEFAULT_REMOVED_REACTION_COLOR))
    } catch (_: IllegalArgumentException) {
        Color.parseColor(Keys.DEFAULT_REMOVED_REACTION_COLOR)
    }

    private inline fun safely(action: String, work: () -> Unit) {
        try {
            work()
        } catch (error: Exception) {
            report(action, error)
        }
    }

    // --- Public API for the plugin and the settings page ---------------------------------------------

    fun count() = store.size()

    fun hasRemoved(messageId: Long) = showing() && store.hasMessage(messageId)

    fun showMessage(context: Context, messageId: Long) =
        ReactionLogDialog.show(context, "Removed reactions", store.forMessage(messageId), ::resolveName, false)

    fun showAll(context: Context) =
        ReactionLogDialog.show(context, "Reaction log", store.snapshot(), ::resolveName, true)

    fun clear() {
        store.clear()
        synchronized(decorated) { decorated.evictAll() }
        saveSoon()
        refreshVisible()
    }

    fun applyFilters() {
        if (store.removeWhere { !keep(it.userId, it.channelId) }) saveSoon()
    }

    /** Re-binds visible reaction rows so style and visibility changes show immediately. */
    fun refreshVisible() {
        Utils.mainThread.post {
            if (!running) return@post
            val snapshot = synchronized(visible) { HashMap(visible) }
            snapshot.forEach { (item, state) ->
                if (item.itemView.isAttachedToWindow) {
                    try {
                        item.onConfigure(state.first, state.second)
                    } catch (error: Throwable) {
                        report("refresh reactions", error)
                    }
                }
            }
        }
    }

    // --- Persistence (only while "Save logs across restarts" is on) ----------------------------------

    private fun pool(): ExecutorService = synchronized(this) {
        executor ?: Executors.newSingleThreadExecutor().also { executor = it }
    }

    fun loadAsync() {
        if (!persistEnabled() || !file.exists()) return
        pool().execute {
            try {
                val array = JSONArray(file.readText())
                var index = 0
                while (index < array.length()) {
                    val o = array.getJSONObject(index++)
                    store.put(
                        RemovedReaction(
                            o.getLong("m"),
                            o.getLong("c"),
                            o.getString("k"),
                            o.optString("n").takeIf { it.isNotEmpty() },
                            o.optString("i").takeIf { it.isNotEmpty() },
                            o.optLong("u"),
                            o.optString("un").takeIf { it.isNotEmpty() },
                            o.optInt("x", 1),
                            o.optLong("t"),
                        ),
                    )
                }
                if (running) {
                    changed()
                    refreshVisible()
                }
            } catch (error: Throwable) {
                report("load reaction log", error)
            }
        }
    }

    fun saveSoon() {
        if (!running || !persistEnabled()) return
        Utils.mainThread.removeCallbacks(saveTask)
        Utils.mainThread.postDelayed(saveTask, 1500)
    }

    private fun write() {
        if (!persistEnabled()) return
        try {
            val array = JSONArray()
            store.snapshot().forEach {
                array.put(
                    JSONObject()
                        .put("m", it.messageId).put("c", it.channelId).put("k", it.emojiKey)
                        .put("n", it.emojiName ?: "").put("i", it.emojiId ?: "")
                        .put("u", it.userId).put("un", it.userName ?: "")
                        .put("x", it.count).put("t", it.timestamp),
                )
            }
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        } catch (error: Throwable) {
            report("save reaction log", error)
        }
    }

    fun close() {
        if (persistEnabled()) write()
        running = false
        Utils.mainThread.removeCallbacks(saveTask)
        synchronized(this) { executor?.shutdown() }
        // Put every styled pill back to Discord's native look.
        synchronized(styles) { styles.forEach { (view, style) -> style.restore(view) } }
        styles.clear()
        visible.clear()
        emojiCache.clear()
        ghostTypes.evictAll()
        decorated.evictAll()
    }

    // --- Pill styling (SuperReactions' ReactionStyle, adapted to a "removed" look) -------------------

    private inner class Style(view: ReactionView) {
        private val background: Drawable? = clone(view.background)
        private val description = view.contentDescription
        private val alpha = view.alpha
        private val first = counter(view, "counter_text_1")
        private val second = counter(view, "counter_text_2")
        private val firstColors = first?.textColors
        private val secondColors = second?.textColors
        private val firstFlags = first?.paintFlags ?: 0
        private val secondFlags = second?.paintFlags ?: 0
        private var removed = false

        fun apply(view: ReactionView, color: Int, count: Int) {
            val density = view.resources.displayMetrics.density
            val shape = GradientDrawable().apply {
                setColor(Color.argb(0x26, Color.red(color), Color.green(color), Color.blue(color)))
                cornerRadius = 8 * density
                setStroke((1.5f * density).toInt().coerceAtLeast(1), Color.argb(0xCC, Color.red(color), Color.green(color), Color.blue(color)), 4 * density, 3 * density)
            }
            setBackground(view, shape)
            first?.let { it.setTextColor(color); it.paintFlags = it.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
            second?.let { it.setTextColor(color); it.paintFlags = it.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
            view.alpha = 0.85f
            view.isActivated = false
            view.isSelected = false
            view.contentDescription = "Removed reaction, $count removed"
            removed = true
        }

        fun restore(view: ReactionView) {
            if (!removed) return
            setBackground(view, clone(background))
            firstColors?.let { first?.setTextColor(it) }
            secondColors?.let { second?.setTextColor(it) }
            first?.paintFlags = firstFlags
            second?.paintFlags = secondFlags
            view.alpha = alpha
            view.contentDescription = description
            removed = false
        }

        private fun setBackground(view: ReactionView, drawable: Drawable?) {
            val l = view.paddingLeft
            val t = view.paddingTop
            val r = view.paddingRight
            val b = view.paddingBottom
            view.background = drawable
            view.setPadding(l, t, r, b)
        }
    }

    private fun clone(drawable: Drawable?): Drawable? = drawable?.let { (it.constantState?.newDrawable() ?: it).mutate() }

    private fun counter(view: ReactionView, id: String): TextView? = view.findViewById(Utils.getResId(id, "id"))

    companion object {
        const val FILE_NAME = "BetterMessageLoggerReactions.json"

        /** Never shown: marks a reaction injected only to keep Discord's reactions row alive. */
        const val PLACEHOLDER_COUNT = 13_370_001
    }
}

private typealias Keys = BetterMessageLogger.Companion

internal object ReactionLogDialog {
    fun show(
        context: Context,
        title: String,
        entries: List<RemovedReaction>,
        resolveName: (Long) -> String?,
        showIds: Boolean,
    ) {
        val ui = LoggerUi(context)
        val format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val content = ui.dialogContent()
        if (entries.isEmpty()) {
            content.addView(ui.text("No removed reactions logged yet.", 14f, ui.muted))
            DiscordDialog(context, title).content(content).positive("Close").show()
            return
        }
        content.addView(
            ui.text("${entries.size} removed", 13f, ui.muted).apply { setPadding(0, 0, 0, ui.dp(8)) },
        )
        val list = ui.column()
        content.addView(ui.scroll(list, 0.55f), LinearLayout.LayoutParams(-1, -2))
        entries.sortedByDescending { it.timestamp }.take(150).forEach { entry ->
            val card = ui.card().apply { setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8)) }
            val who = if (entry.userId == 0L) {
                "Removed by bulk removal (${entry.count} reaction${if (entry.count == 1) "" else "s"})"
            } else {
                "Removed by ${entry.userName ?: resolveName(entry.userId) ?: "Unknown user"} (${entry.userId})"
            }
            val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val labels = ui.column()
            labels.addView(ui.text(entry.label, 16f, ui.primary))
            labels.addView(ui.text(who, 13f, ui.normal).apply { setTextIsSelectable(true) })
            labels.addView(ui.text(format.format(Date(entry.timestamp)), 12f, ui.muted))
            if (showIds) labels.addView(ui.text("Message ${entry.messageId}", 12f, ui.muted).apply { setTextIsSelectable(true) })
            header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (showIds) {
                header.addView(
                    ui.iconButton("ic_copy_24dp", "Copy message ID") {
                        Utils.setClipboard("Message ID", entry.messageId.toString())
                        Utils.showToast("Copied")
                    },
                )
            }
            card.addView(header)
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
        DiscordDialog(context, title).content(content).positive("Close").show()
    }
}
