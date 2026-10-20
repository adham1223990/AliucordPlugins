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
        // Http.Request.newDiscordRNRequest ALREADY prepends "https://discord.com/api/v9" to the
        // route (see Aliucord's Http.getDiscordRoute), so the route must be relative to that
        // base, e.g. "/guilds/123/requests/@me". The old code added "/api/v9" on top of it, which
        // produced ".../api/v9/api/v9/guilds/..." and made every call return 404.
        // Any leftover "/api/v9" prefix is stripped here defensively.
        val route = "/" + path.trim().removePrefix("/").removePrefix("api/v9").removePrefix("/")
        val req = Http.Request.newDiscordRNRequest(route, method)
            .setHeader("Accept", "application/json")
        if (guildId != null && guildId > 0L) {
            req.setHeader("X-Context-Properties", buildContextProperties(guildId, contextLocation))
        }
        return req
    }

    /**
     * Same endpoint the Discord app itself uses (RestAPIInterface.getGuildMemberVerificationForm):
     * GET /guilds/{guild.id}/member-verification
     * An optional invite_code is tried first (the web client sends it); if that is refused the
     * plain request the mobile app sends is used instead.
     */
    fun fetchMemberVerificationForm(guildId: Long, inviteCode: String? = null): Result<VerificationFormResponse> {
        return runCatching {
            val plainRoute = "/guilds/$guildId/member-verification"
            val first = if (!inviteCode.isNullOrBlankSafe()) {
                runCatching {
                    createSafeRNRequest(
                        "$plainRoute?invite_code=" + URLEncoder.encode(inviteCode.trim(), "UTF-8"),
                        "GET",
                        guildId,
                        "Accept Invite Page"
                    ).execute()
                }.getOrNull()
            } else {
                null
            }
            val response = if (first != null && first.statusCode in 200..299) {
                first
            } else {
                createSafeRNRequest(plainRoute, "GET", guildId, "Accept Invite Page").execute()
            }
            val statusCode = response.statusCode
            val bodyText = runCatching { response.text() }.getOrDefault("")
            if (statusCode !in 200..299) {
                throw IllegalStateException(parseDiscordError(statusCode, bodyText))
            }

            val root = JSONObject(bodyText)
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

            VerificationFormResponse(
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
    }

    /**
     * PUT /guilds/{guild.id}/requests/@me  (RestAPIInterface.createGuildJoinRequest)
     * Submits the form responses; the server answers with the join request
     * (last_seen, application_status, form_responses, guild_id).
     * NOTE: the old "/requests/@me/cooldown" call was removed -- that route does not exist in the
     * Discord client, so it only ever returned an error.
     */
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
                        // TEXT_INPUT, PARAGRAPH, etc.
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
            ).executeWithJson(payload.toString())

            val code = response.statusCode
            val text = runCatching { response.text() }.getOrDefault("")
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

    /**
     * POST /guilds/{guild.id}/requests/@me  (RestAPIInterface.resetGuildJoinRequest)
     * Resets the current user's guild join request to STARTED so they can re-apply.
     */
    fun resetGuildApplication(guildId: Long): JoinRequestResult {
        return try {
            val response = createSafeRNRequest(
                "/guilds/$guildId/requests/@me",
                "POST",
                guildId,
                "Guild Member Verification"
            ).executeWithJson("{}")

            val code = response.statusCode
            val text = runCatching { response.text() }.getOrDefault("")
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

    /**
     * DELETE /guilds/{guild.id}/requests/@me  (RestAPIInterface.deleteGuildJoinRequest)
     * Deletes / withdraws the user's pending or started join request.
     */
    fun withdrawGuildApplication(guildId: Long): JoinRequestResult {
        return try {
            val response = createSafeRNRequest(
                "/guilds/$guildId/requests/@me",
                "DELETE",
                guildId,
                "Guild Member Verification"
            ).execute()

            val code = response.statusCode
            val text = runCatching { response.text() }.getOrDefault("")
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

    /**
     * Current status of the user's join request for a guild.
     *
     * The old "GET /join-requests/{request.id}" route does not exist in the Discord client, so the
     * status is now read the same way the app itself reads it: from the client's own
     * StoreGuildJoinRequest, which the gateway keeps in sync (GUILD_JOIN_REQUEST_CREATE/UPDATE/
     * DELETE) and which is also what the PUT/POST/DELETE "requests/@me" calls feed. No network
     * request is needed for that. Only when the store has nothing for this guild does it fall
     * back to the member-verification form request (through newDiscordRNRequest).
     */
    fun fetchJoinRequestStatus(guildId: Long, requestId: String? = null): JoinRequestResult {
        try {
            // Typed as Any? and read through reflection on purpose: GuildJoinRequest's getters are
            // obfuscated single-letter names, but its fields keep their real names.
            val stored: Any? = StoreStream.getGuildJoinRequests().getGuildJoinRequest(guildId)
            if (stored != null) {
                fun readField(name: String): String? =
                    runCatching { ReflectUtils.getField(stored, name) }.getOrNull()
                        ?.toString()?.takeIf { it.isNotBlankSafe() && it != "null" }

                val status = readField("applicationStatus")
                if (status != null) {
                    return JoinRequestResult(
                        ok = true,
                        statusCode = 200,
                        requestId = requestId,
                        applicationStatus = status,
                        rejectionReason = readField("rejectionReason"),
                        submittedAt = readField("lastSeen") ?: readField("createdAt"),
                        actionedAt = readField("actionedAt")
                    )
                }
            }
        } catch (_: Throwable) {
        }

        val formRes = fetchMemberVerificationForm(guildId)
        val form = formRes.getOrNull()
        return if (form != null) {
            JoinRequestResult(
                ok = true,
                statusCode = 200,
                requestId = form.requestId ?: requestId,
                applicationStatus = form.joinRequestStatus
            )
        } else {
            JoinRequestResult(
                ok = false,
                statusCode = 404,
                errorMessage = formRes.exceptionOrNull()?.message ?: "Unable to fetch status"
            )
        }
    }

    private fun parseDiscordError(statusCode: Int, rawBody: String): String {
        return try {
            val json = JSONObject(rawBody)
            val msg = json.optString("message", "").ifBlankSafe("HTTP $statusCode")
            val code = json.optInt("code", 0)
            if (code != 0) "$msg (Discord Code $code)" else msg
        } catch (_: Throwable) {
            if (rawBody.isNotBlankSafe()) rawBody.take(160) else "HTTP Error $statusCode"
        }
    }
}
