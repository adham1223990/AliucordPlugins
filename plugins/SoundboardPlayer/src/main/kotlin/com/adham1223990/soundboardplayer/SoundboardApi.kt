package com.adham1223990.soundboardplayer

import com.aliucord.Http
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject

data class SoundboardSound(
    val soundId: String,
    val name: String,
    val emojiName: String?,
    val guildId: String?,
    val volume: Double
)

class SoundboardApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * Thin wrapper around Discord's official, documented Soundboard REST endpoints:
 * https://docs.discord.com/developers/resources/soundboard
 *
 * Includes proactive cooldown and reactive HTTP 429 rate limit handling.
 */
object SoundboardApi {

    @Volatile
    private var cooldownUntilMillis: Long = 0L

    // 2-second baseline delay between requests to prevent accidental spam
    private const val MIN_COOLDOWN_MS = 2_000L

    private fun hasText(s: String?): Boolean {
        if (s == null) return false
        var i = 0
        while (i < s.length) {
            if (!Character.isWhitespace(s[i])) return true
            i++
        }
        return false
    }

    fun token(): String? = RestAPI.AppHeadersProvider.INSTANCE.authToken?.takeIf(::hasText)

    // GET /soundboard-default-sounds
    fun defaultSounds(): List<SoundboardSound> {
        val token = token() ?: return emptyList()
        return Http.Request.newDiscordRNRequest("/soundboard-default-sounds", "GET").use { request ->
            request.setHeader("Authorization", token)
            request.setRequestTimeout(15_000)
            request.execute().use { response ->
                if (!response.ok()) {
                    throw SoundboardApiException(response.statusCode, "HTTP ${response.statusCode}: ${response.text()}")
                }
                parseSounds(JSONArray(response.text()))
            }
        }
    }

    // GET /guilds/{guild.id}/soundboard-sounds
    fun guildSounds(guildId: String): List<SoundboardSound> {
        if (!hasText(guildId)) return emptyList()
        val token = token() ?: return emptyList()
        return Http.Request.newDiscordRNRequest("/guilds/$guildId/soundboard-sounds", "GET").use { request ->
            request.setHeader("Authorization", token)
            request.setRequestTimeout(15_000)
            request.execute().use { response ->
                if (!response.ok()) {
                    throw SoundboardApiException(response.statusCode, "HTTP ${response.statusCode}: ${response.text()}")
                }
                val json = JSONObject(response.text())
                parseSounds(json.optJSONArray("items") ?: JSONArray())
            }
        }
    }

    // POST /channels/{channel.id}/send-soundboard-sound
    fun play(channelId: String, soundId: String, sourceGuildId: String?) {
        if (!hasText(channelId)) throw SoundboardApiException(0, "No voice channel ID was given.")
        val token = token() ?: throw SoundboardApiException(0, "Not signed in to Discord.")

        // 1. Proactive Client-side Cooldown Check
        val now = System.currentTimeMillis()
        if (now < cooldownUntilMillis) {
            val remainingSec = (cooldownUntilMillis - now + 999) / 1000
            throw SoundboardApiException(429, "Please wait ${remainingSec}s before playing another sound.")
        }

        val body = JSONObject().apply {
            put("sound_id", soundId)
            if (!sourceGuildId.isNullOrEmpty()) put("source_guild_id", sourceGuildId)
        }

        Http.Request.newDiscordRNRequest("/channels/$channelId/send-soundboard-sound", "POST").use { request ->
            request.setHeader("Authorization", token)
            request.setHeader("Content-Type", "application/json")
            request.setRequestTimeout(15_000)
            request.executeWithBody(body.toString()).use { response ->
                val responseText = response.text()

                // 2. Reactive Official Discord HTTP 429 Handling
                if (response.statusCode == 429) {
                    val retryAfterSeconds = runCatching {
                        JSONObject(responseText).optDouble("retry_after", 2.5)
                    }.getOrDefault(2.5)

                    cooldownUntilMillis = System.currentTimeMillis() + (retryAfterSeconds * 1000).toLong()
                    val waitSec = String.format("%.1f", retryAfterSeconds)
                    throw SoundboardApiException(429, "Rate limited by Discord. Please wait ${waitSec}s.")
                }

                if (!response.ok()) {
                    throw SoundboardApiException(response.statusCode, "HTTP ${response.statusCode}: $responseText")
                }

                // 3. Set standard debounce window after successful dispatch
                cooldownUntilMillis = System.currentTimeMillis() + MIN_COOLDOWN_MS
            }
        }
    }

    private fun parseSounds(array: JSONArray): List<SoundboardSound> {
        val out = ArrayList<SoundboardSound>()
        var i = 0
        while (i < array.length()) {
            val o = array.optJSONObject(i)
            if (o != null) {
                out.add(
                    SoundboardSound(
                        soundId = o.optString("sound_id"),
                        name = o.optString("name"),
                        emojiName = if (o.has("emoji_name") && !o.isNull("emoji_name")) o.optString("emoji_name") else null,
                        guildId = if (o.has("guild_id") && !o.isNull("guild_id")) o.optString("guild_id") else null,
                        volume = o.optDouble("volume", 1.0)
                    )
                )
            }
            i++
        }
        return out
    }
}
