package com.adham1223990.serverapplicationfix

import android.util.Base64
import com.aliucord.Http
import com.aliucord.utils.ReflectUtils
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class VerificationField(
    val fieldType: String,
    val label: String,
    val description: String?,
    val required: Boolean,
    val values: List<String>,
    val choices: List<String>,
    val placeholder: String?,
    val maxLength: Int,
    val rawObject: JSONObject
)

data class VerificationFormResponse(
    val guildId: Long,
    val guildName: String?,
    val guildIcon: String?,
    val description: String?,
    val version: String?,
    val fields: List<VerificationField>,
    val joinRequestStatus: String?,
    val requestId: String?
)

data class JoinRequestResult(
    val ok: Boolean,
    val statusCode: Int,
    val requestId: String? = null,
    val applicationStatus: String? = null,
    val rejectionReason: String? = null,
    val interviewChannelId: String? = null,
    val submittedAt: String? = null,
    val actionedAt: String? = null,
    val cooldownSeconds: Long = 0L,
    val errorMessage: String? = null,
    val rawJson: JSONObject? = null
)

object ApplicationApi {

    fun cleanInviteCode(raw: String?): String? {
        if (raw.isNullOrBlankSafe()) return null
        var cleaned = raw.trim()
        val slashIdx = cleaned.lastIndexOf('/')
        if (slashIdx >= 0 && slashIdx < cleaned.length - 1) {
            cleaned = cleaned.substring(slashIdx + 1)
        }
        val qIdx = cleaned.indexOf('?')
        if (qIdx >= 0) {
            cleaned = cleaned.substring(0, qIdx)
        }
        return cleaned.trim().orNullIfBlank()
    }

    fun resolveInviteGuild(inviteCode: String): Triple<Long, String?, String?>? {
        val code = cleanInviteCode(inviteCode) ?: return null
        return try {
            val res = createSafeRNRequest(
                "/invites/${URLEncoder.encode(code, "UTF-8")}?with_counts=true&with_expiration=true",
                "GET",
                null,
                "Accept Invite Page"
            ).execute()
            if (res.statusCode in 200..299) {
                val root = JSONObject(readBody(res))
                val guildObj = root.optJSONObject("guild") ?: return null
                val id = guildObj.optString("id", "").toLongOrNull() ?: return null
                val name = guildObj.optString("name", "").orNullIfBlank()
                val icon = guildObj.optString("icon", "").orNullIfBlank()
                Triple(id, name, icon)
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildContextProperties(guildId: Long, location: String = "Accept Invite Page"): String {
        return try {
            val json = JSONObject().apply {
                put("location", location)
                put("location_guild_id", guildId.toString())
            }
            Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } catch (_: Throwable) {
            "e30="
        }
    }

    private fun createSafeRNRequest(
        path: String,
        method: String,
        guildId: Long? = null,
        contextLocation: String = "Guild Member Verification"
    ): Http.Request {
        val route = "/" + path.trim().removePrefix("/").removePrefix("api/v9").removePrefix("/")
        val req = Http.Request.newDiscordRNRequest(route, method)
            .setHeader("Accept", "application/json")
        req.setRequestTimeout(20_000)
        if (guildId != null && guildId > 0L) {
            req.setHeader("X-Context-Properties", buildContextProperties(guildId, contextLocation))
        }
        return req
    }

    fun fetchMemberVerificationForm(guildId: Long, inviteCode: String? = null): Result<VerificationFormResponse> {
        return runCatching {
            val plainRoute = "/guilds/$guildId/member-verification"
            val cleanedInvite = cleanInviteCode(inviteCode)

            val response = if (cleanedInvite != null) {
                val encoded = URLEncoder.encode(cleanedInvite, "UTF-8")
                val withInvite = runCatching {
                    createSafeRNRequest(
                        "$plainRoute?with_guild=true&invite_code=$encoded",
                        "GET",
                        guildId,
                        "Accept Invite Page"
                    ).execute()
                }.getOrNull()

                if (withInvite != null && withInvite.statusCode in 200..299) {
                    withInvite
                } else {
                    createSafeRNRequest("$plainRoute?with_guild=true", "GET", guildId, "Accept Invite Page").execute()
                }
            } else {
                createSafeRNRequest("$plainRoute?with_guild=true", "GET", guildId, "Accept Invite Page").execute()
            }

            val statusCode = response.statusCode
            val bodyText = readBody(response)
            if (statusCode !in 200..299) {
                throw IllegalStateException(parseDiscordError(statusCode, bodyText))
            }

            val root = JSONObject(bodyText)
            parseVerificationFormJson(guildId, root)
        }
    }

    private fun parseVerificationFormJson(guildId: Long, root: JSONObject): VerificationFormResponse {
        val version = root.optString("version", "").orNullIfBlank()
        val description = root.optString("description", "").orNullIfBlank()

        val guildObj = root.optJSONObject("guild")
        val guildName = guildObj?.optString("name", "")?.orNullIfBlank()
        val guildIcon = guildObj?.optString("icon", "")?.orNullIfBlank()

        val joinRequestObj = root.optJSONObject("guild_join_request")
        val joinStatus = joinRequestObj?.optString("application_status", "")?.orNullIfBlank()
        val requestId = joinRequestObj?.optString("join_request_id", "")?.let { jr ->
            if (jr.isBlankSafe()) joinRequestObj.optString("id", "").orNullIfBlank() else jr
        }

        val parsedFields = mutableListOf<VerificationField>()
        val fieldsArr = root.optJSONArray("form_fields") ?: JSONArray()
        for (i in 0 until fieldsArr.length()) {
            val fieldObj = fieldsArr.optJSONObject(i) ?: continue
            val fieldType = fieldObj.optString("field_type", "TEXT_INPUT").uppercase()
            val label = fieldObj.optString("label", "Question ${i + 1}")
            val fieldDesc = fieldObj.optString("description", "").orNullIfBlank()
            val required = fieldObj.optBoolean("required", true)
            val placeholder = fieldObj.optString("placeholder", "").orNullIfBlank()
            val maxLength = fieldObj.optInt("max_length", 300).coerceAtLeast(1)

            val valuesList = mutableListOf<String>()
            val valuesArr = fieldObj.optJSONArray("values")
            if (valuesArr != null) {
                for (j in 0 until valuesArr.length()) {
                    val v = valuesArr.optString(j, "")
                    if (v.isNotEmpty()) valuesList.add(v)
                }
            }

            val choicesList = mutableListOf<String>()
            val choicesArr = fieldObj.optJSONArray("choices")
            if (choicesArr != null) {
                for (j in 0 until choicesArr.length()) {
                    val c = choicesArr.optString(j, "")
                    if (c.isNotEmpty()) choicesList.add(c)
                }
            }

            parsedFields.add(
                VerificationField(
                    fieldType = fieldType,
                    label = label,
                    description = fieldDesc,
                    required = required,
                    values = valuesList,
                    choices = choicesList,
                    placeholder = placeholder,
                    maxLength = maxLength,
                    rawObject = JSONObject(fieldObj.toString())
                )
            )
        }

        return VerificationFormResponse(
            guildId = guildId,
            guildName = guildName,
            guildIcon = guildIcon,
            description = description,
            version = version,
            fields = parsedFields,
            joinRequestStatus = joinStatus,
            requestId = requestId
        )
    }

    fun submitGuildApplication(
        guildId: Long,
        version: String?,
        fieldsWithResponses: List<Pair<VerificationField, Any?>>
    ): JoinRequestResult {
        return try {
            val formFieldsPayload = JSONArray()
            for ((field, userResponse) in fieldsWithResponses) {
                val fieldCopy = JSONObject(field.rawObject.toString())
                when (field.fieldType.uppercase()) {
                    "TERMS" -> {
                        fieldCopy.put("response", userResponse as? Boolean ?: true)
                    }
                    "MULTIPLE_CHOICE" -> {
                        when (userResponse) {
                            is Int -> fieldCopy.put("response", userResponse)
                            is String -> {
                                val idx = field.choices.indexOf(userResponse)
                                if (idx >= 0) fieldCopy.put("response", idx) else fieldCopy.put("response", userResponse)
                            }
                            else -> fieldCopy.put("response", 0)
                        }
                    }
                    "VERIFICATION" -> {
                        fieldCopy.put("response", userResponse?.toString() ?: true)
                    }
                    else -> {
                        fieldCopy.put("response", userResponse?.toString()?.trim() ?: "")
                    }
                }
                formFieldsPayload.put(fieldCopy)
            }

            val payload = JSONObject().apply {
                put("form_fields", formFieldsPayload)
                if (!version.isNullOrBlankSafe()) {
                    put("version", version)
                }
            }

            val response = createSafeRNRequest(
                "/guilds/$guildId/requests/@me",
                "PUT",
                guildId,
                "Guild Member Verification"
            ).setHeader("Content-Type", "application/json").executeWithBody(payload.toString())

            val code = response.statusCode
            val text = readBody(response)
            if (code in 200..299) {
                val json = if (text.isNotBlankSafe()) JSONObject(text) else JSONObject()
                val reqObj = json.optJSONObject("guild_join_request") ?: json
                JoinRequestResult(
                    ok = true,
                    statusCode = code,
                    requestId = reqObj.optString("join_request_id", reqObj.optString("id", "")).orNullIfBlank(),
                    applicationStatus = reqObj.optString("application_status", "PENDING").ifBlankSafe("PENDING"),
                    rejectionReason = reqObj.optString("rejection_reason", "").orNullIfBlank(),
                    interviewChannelId = reqObj.optString("interview_channel_id", "").orNullIfBlank(),
                    submittedAt = reqObj.optString("last_seen", reqObj.optString("created_at", "")).orNullIfBlank(),
                    actionedAt = reqObj.optString("actioned_at", "").orNullIfBlank(),
                    rawJson = reqObj
                )
            } else {
                JoinRequestResult(
                    ok = false,
                    statusCode = code,
                    errorMessage = parseDiscordError(code, text)
                )
            }
        } catch (t: Throwable) {
            JoinRequestResult(
                ok = false,
                statusCode = -1,
                errorMessage = t.message ?: "Request failed"
            )
        }
    }

    fun resetGuildApplication(guildId: Long): JoinRequestResult {
        return try {
            val response = createSafeRNRequest(
                "/guilds/$guildId/requests/@me",
                "POST",
                guildId,
                "Guild Member Verification"
            ).setHeader("Content-Type", "application/json").executeWithBody("{}")

            val code = response.statusCode
            val text = readBody(response)
            if (code in 200..299) {
                val json = if (text.isNotBlankSafe()) JSONObject(text) else JSONObject()
                val reqObj = json.optJSONObject("guild_join_request") ?: json
                JoinRequestResult(
                    ok = true,
                    statusCode = code,
                    requestId = reqObj.optString("join_request_id", reqObj.optString("id", "")).orNullIfBlank(),
                    applicationStatus = reqObj.optString("application_status", "STARTED").ifBlankSafe("STARTED"),
                    rawJson = reqObj
                )
            } else {
                JoinRequestResult(
                    ok = false,
                    statusCode = code,
                    errorMessage = parseDiscordError(code, text)
                )
            }
        } catch (t: Throwable) {
            JoinRequestResult(
                ok = false,
                statusCode = -1,
                errorMessage = t.message ?: "Failed to reset application"
            )
        }
    }

    fun withdrawGuildApplication(guildId: Long): JoinRequestResult {
        return try {
            val response = createSafeRNRequest(
                "/guilds/$guildId/requests/@me",
                "DELETE",
                guildId,
                "Guild Member Verification"
            ).execute()

            val code = response.statusCode
            val text = readBody(response)
            if (code in 200..299) {
                JoinRequestResult(
                    ok = true,
                    statusCode = code,
                    applicationStatus = "WITHDRAWN"
                )
            } else {
                JoinRequestResult(
                    ok = false,
                    statusCode = code,
                    errorMessage = parseDiscordError(code, text)
                )
            }
        } catch (t: Throwable) {
            JoinRequestResult(
                ok = false,
                statusCode = -1,
                errorMessage = t.message ?: "Failed to withdraw application"
            )
        }
    }

    fun fetchJoinRequestStatus(
        guildId: Long,
        requestId: String? = null,
        inviteCode: String? = null,
        allowNetwork: Boolean = true
    ): JoinRequestResult {
        try {
            val stored: Any? = StoreStream.getGuildJoinRequests().getGuildJoinRequest(guildId)
            if (stored != null) {
                fun readField(name: String): String? =
                    runCatching { ReflectUtils.getField(stored, name) }.getOrNull()
                        ?.toString()?.takeIf { it.isNotBlankSafe() && it != "null" }

                val status = readField("applicationStatus")
                val jrId = readField("joinRequestId") ?: readField("id") ?: requestId
                if (status != null) {
                    return JoinRequestResult(
                        ok = true,
                        statusCode = 200,
                        requestId = jrId,
                        applicationStatus = status,
                        rejectionReason = readField("rejectionReason"),
                        interviewChannelId = readField("interviewChannelId"),
                        submittedAt = readField("lastSeen") ?: readField("createdAt"),
                        actionedAt = readField("actionedAt")
                    )
                }
            }
        } catch (_: Throwable) {
        }

        if (!allowNetwork) {
            return JoinRequestResult(ok = false, statusCode = 0, errorMessage = "No stored status")
        }
        val formRes = fetchMemberVerificationForm(guildId, inviteCode)
        val form = formRes.getOrNull()
        return if (form != null) {
            JoinRequestResult(
                ok = true,
                statusCode = 200,
                requestId = form.requestId ?: requestId,
                applicationStatus = form.joinRequestStatus ?: "STARTED"
            )
        } else {
            JoinRequestResult(
                ok = false,
                statusCode = 404,
                errorMessage = formRes.exceptionOrNull()?.message ?: "Unable to fetch status"
            )
        }
    }

    private fun readBody(response: Http.Response): String {
        if (response.ok()) return runCatching { response.text() }.getOrDefault("")
        return runCatching { response.assertOk() }.exceptionOrNull()?.message ?: ""
    }

    private fun parseDiscordError(statusCode: Int, rawBody: String): String {
        val start = rawBody.indexOf('{')
        val end = rawBody.lastIndexOf('}')
        if (start >= 0 && end > start) {
            try {
                val json = JSONObject(rawBody.substring(start, end + 1))
                val msg = json.optString("message", "").ifBlankSafe("HTTP $statusCode")
                val code = json.optInt("code", 0)
                val details = json.optJSONObject("errors")?.toString()
                val head = if (code != 0) "$msg (Discord Code $code)" else msg
                return if (details != null) "$head $details".take(300) else head
            } catch (_: Throwable) {
            }
        }
        return if (rawBody.isNotBlankSafe()) rawBody.take(200) else "HTTP Error $statusCode"
    }
}