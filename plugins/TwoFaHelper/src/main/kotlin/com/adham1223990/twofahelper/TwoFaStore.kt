package com.adham1223990.twofahelper

import com.aliucord.api.SettingsAPI
import org.json.JSONArray
import org.json.JSONObject

data class TwoFaEntry(val id: String, val name: String, val secret: String)

/**
 * Stores {name, secret} pairs locally in the plugin's own settings storage. Nothing here is
 * ever sent over the network — this is purely a local code generator, like a phone
 * authenticator app.
 */
object TwoFaStore {
    private const val KEY = "two_fa_entries"
    private val lock = Any()

    @Volatile
    private var settings: SettingsAPI? = null

    fun init(api: SettingsAPI) {
        settings = api
    }

    fun all(): List<TwoFaEntry> = synchronized(lock) { load() }

    fun add(name: String, secret: String): TwoFaEntry {
        synchronized(lock) {
            val list = load().toMutableList()
            val entry = TwoFaEntry(id = System.currentTimeMillis().toString(), name = name, secret = secret)
            list.add(entry)
            save(list)
            return entry
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val list = ArrayList<TwoFaEntry>()
            val current = load()
            var i = 0
            while (i < current.size) {
                if (current[i].id != id) list.add(current[i])
                i++
            }
            save(list)
        }
    }

    private fun load(): List<TwoFaEntry> {
        val api = settings ?: return emptyList()
        val out = ArrayList<TwoFaEntry>()
        try {
            val arr = JSONArray(api.getString(KEY, "[]"))
            var i = 0
            while (i < arr.length()) {
                val o = arr.optJSONObject(i)
                if (o != null) {
                    out.add(
                        TwoFaEntry(
                            id = o.optString("id", ""),
                            name = o.optString("name", ""),
                            secret = o.optString("secret", "")
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

    private fun save(list: List<TwoFaEntry>) {
        val api = settings ?: return
        val arr = JSONArray()
        var i = 0
        while (i < list.size) {
            val e = list[i]
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("name", e.name)
                    put("secret", e.secret)
                }
            )
            i++
        }
        api.setString(KEY, arr.toString())
    }
}
