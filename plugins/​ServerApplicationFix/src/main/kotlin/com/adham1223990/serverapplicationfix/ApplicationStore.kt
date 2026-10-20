package com.adham1223990.serverapplicationfix

import com.aliucord.api.SettingsAPI
import org.json.JSONArray
import org.json.JSONObject

data class SavedApplicationRecord(
    val guildId: Long,
    val guildName: String,
    val guildIcon: String? = null,
    val requestId: String? = null,
    val status: String = "STARTED", // STARTED, SUBMITTED, PENDING, APPROVED, REJECTED, WITHDRAWN
    val rejectionReason: String? = null,
    val createdAt: String? = null,
    val submittedAt: String? = null,
    val actionedAt: String? = null,
    val interviewChannelId: String? = null,
    val inviteCode: String? = null,
    val formDescription: String? = null,
    val lastUpdatedMs: Long = System.currentTimeMillis()
)

object ApplicationStore {
    private const val KEY_APPLICATIONS_JSON = "saved_guild_applications_v2"

    @Synchronized
    fun getAll(settings: SettingsAPI?): List<SavedApplicationRecord> {
        if (settings == null) return emptyList()
        val raw = settings.getString(KEY_APPLICATIONS_JSON, "[]") ?: "[]"
        val list = mutableListOf<SavedApplicationRecord>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val guildId = obj.optLong("guild_id", 0L)
                if (guildId <= 0L) continue
                list.add(
                    SavedApplicationRecord(
                        guildId = guildId,
                        guildName = obj.optString("guild_name", "Server $guildId"),
                        guildIcon = obj.optString("guild_icon", "").orNullIfBlank(),
                        requestId = obj.optString("request_id", "").orNullIfBlank(),
                        status = obj.optString("status", "STARTED").ifBlankSafe("STARTED"),
                        rejectionReason = obj.optString("rejection_reason", "").orNullIfBlank(),
                        createdAt = obj.optString("created_at", "").orNullIfBlank(),
                        submittedAt = obj.optString("submitted_at", "").orNullIfBlank(),
                        actionedAt = obj.optString("actioned_at", "").orNullIfBlank(),
                        interviewChannelId = obj.optString("interview_channel_id", "").orNullIfBlank(),
                        inviteCode = obj.optString("invite_code", "").orNullIfBlank(),
                        formDescription = obj.optString("form_description", "").orNullIfBlank(),
                        lastUpdatedMs = obj.optLong("last_updated_ms", System.currentTimeMillis())
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return list.sortedByDescending { it.lastUpdatedMs }
    }

    @Synchronized
    fun getByGuildId(settings: SettingsAPI?, guildId: Long): SavedApplicationRecord? {
        return getAll(settings).firstOrNull { it.guildId == guildId }
    }

    @Synchronized
    fun upsert(settings: SettingsAPI?, record: SavedApplicationRecord) {
        if (settings == null || record.guildId <= 0L) return
        val current = getAll(settings).toMutableList()
        val index = current.indexOfFirst { it.guildId == record.guildId }
        if (index >= 0) {
            val old = current[index]
            current[index] = record.copy(
                guildName = if (record.guildName.isBlankSafe() || record.guildName.startsWith("Server ")) old.guildName else record.guildName,
                guildIcon = record.guildIcon ?: old.guildIcon,
                requestId = record.requestId ?: old.requestId,
                inviteCode = record.inviteCode ?: old.inviteCode,
                formDescription = record.formDescription ?: old.formDescription,
                createdAt = record.createdAt ?: old.createdAt,
                submittedAt = record.submittedAt ?: old.submittedAt,
                interviewChannelId = record.interviewChannelId ?: old.interviewChannelId,
                lastUpdatedMs = System.currentTimeMillis()
            )
        } else {
            current.add(0, record.copy(lastUpdatedMs = System.currentTimeMillis()))
        }
        saveAll(settings, current)
    }

    @Synchronized
    fun removeByGuildId(settings: SettingsAPI?, guildId: Long) {
        if (settings == null) return
        val filtered = getAll(settings).filterNot { it.guildId == guildId }
        saveAll(settings, filtered)
    }

    @Synchronized
    private fun saveAll(settings: SettingsAPI, records: List<SavedApplicationRecord>) {
        val arr = JSONArray()
        records.forEach { rec ->
            val obj = JSONObject().apply {
                put("guild_id", rec.guildId)
                put("guild_name", rec.guildName)
                put("guild_icon", rec.guildIcon ?: "")
                put("request_id", rec.requestId ?: "")
                put("status", rec.status)
                put("rejection_reason", rec.rejectionReason ?: "")
                put("created_at", rec.createdAt ?: "")
                put("submitted_at", rec.submittedAt ?: "")
                put("actioned_at", rec.actionedAt ?: "")
                put("interview_channel_id", rec.interviewChannelId ?: "")
                put("invite_code", rec.inviteCode ?: "")
                put("form_description", rec.formDescription ?: "")
                put("last_updated_ms", rec.lastUpdatedMs)
            }
            arr.put(obj)
        }
        settings.setString(KEY_APPLICATIONS_JSON, arr.toString())
    }
}
