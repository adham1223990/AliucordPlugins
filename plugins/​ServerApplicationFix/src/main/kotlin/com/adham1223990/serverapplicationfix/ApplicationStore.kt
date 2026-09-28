package com.adham1223990.serverapplicationfix

import com.aliucord.api.SettingsAPI
import org.json.JSONArray
import org.json.JSONObject

data class TrackedApplication(
    val guildId: String,
    val name: String,
    val status: String,
    val updatedAt: Long
)

/**
 * Keeps a persistent list of the servers the user applied to (saved in the plugin settings),
 * together with the last known status: PENDING, APPROVED, REJECTED or UNKNOWN.
 */
object ApplicationStore {
    private const val KEY = "tracked_applications"
    private val lock = Any()

    @Volatile
    private var settings: SettingsAPI? = null

    fun init(api: SettingsAPI) {
        settings = api
    }

    fun all(): List<TrackedApplication> = synchronized(lock) { load() }

    fun record(guildId: String, name: String?, status: String) {
        synchronized(lock) {
            val list = load().toMutableList()
            var index = -1
            var i = 0
            while (i < list.size) {
                if (list[i].guildId == guildId) {
                    index = i
                    break
                }
                i++
            }
            val oldName = if (index >= 0) list[index].name else null
            val finalName = when {
                !name.isNullOrEmpty() -> name
                !oldName.isNullOrEmpty() -> oldName
                else -> "Server $guildId"
            }
            val entry = TrackedApplication(guildId, finalName, status, System.currentTimeMillis())
            if (index >= 0) list[index] = entry else list.add(0, entry)
            save(list)
        }
    }

    fun updateStatus(guildId: String, status: String) {
        record(guildId, null, status)
    }

    fun remove(guildId: String) {
        synchronized(lock) {
            val list = ArrayList<TrackedApplication>()
            val current = load()
            var i = 0
            while (i < current.size) {
                if (current[i].guildId != guildId) list.add(current[i])
                i++
            }
            save(list)
        }
    }

    private fun load(): List<TrackedApplication> {
        val api = settings ?: return emptyList()
        val out = ArrayList<TrackedApplication>()
        try {
            val arr = JSONArray(api.getString(KEY, "[]"))
            var i = 0
            while (i < arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    out.add(
                        TrackedApplication(
                            guildId = o.optString("guildId", ""),
                            name = o.optString("name", ""),
                            status = o.optString("status", "UNKNOWN"),
                            updatedAt = o.optLong("updatedAt", 0L)
                        )
                    )
                }
                i++
            }
        } catch (t: Throwable) {
            return emptyList()
        }
        return out
    }

    private fun save(list: List<TrackedApplication>) {
        val api = settings ?: return
        val arr = JSONArray()
        var i = 0
        while (i < list.size) {
            val e = list[i]
            arr.put(
                JSONObject().apply {
                    put("guildId", e.guildId)
                    put("name", e.name)
                    put("status", e.status)
                    put("updatedAt", e.updatedAt)
                }
            )
            i++
        }
        api.setString(KEY, arr.toString())
    }
}
