package com.adham1223990.serverapplicationfix

import android.util.Base64
import com.aliucord.Http
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
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val fullPath = if (normalizedPath.startsWith("/api/")) normalizedPath else "/api/v9$normalizedPath"
        val req = Http.Request.newDiscordRNRequest(fullPath, method)
            .setHeader("Accept", "application/json")
        if (guildId != null && guildId > 0L) {
            req.setHeader("X-Context-Properties", buildContextProperties(guildId, contextLocation))
        }
        return req
    }

    /**
     * Confirmed Official Endpoint:
     * GET /api/v9/guilds/{guild.id}/member-verification?with_guild=true&invite_code={code}
     */
    fun fetchMemberVerificationForm(guildId: Long, inviteCode: String? = null): Result<VerificationFormResponse> {
        return runCatching {
            val query = buildString {
                append("/api/v9/guilds/$guildId/member-verification?with_guild=true")
                if (!inviteCode.isNullOrBlank()) {
                    append("&invite_code=")
                    append(URLEncoder.encode(inviteCode.trim(), "UTF-8"))
                }
            }
            val response = createSafeRNRequest(query, "GET", guildId, "Accept Invite Page").execute()
            val statusCode = response.statusCode
            val bodyText = runCatching { response.text() }.getOrDefault("")
            if (statusCode !in 200..299) {
                throw IllegalStateException(parseDiscordError(statusCode, bodyText))
            }

            val root = JSONObject(bodyText)
            val version = root.optString("version", "").ifBlank { null }
            val description = root.optString("description", "").ifBlank { null }

            val guildObj = root.optJSONObject("guild")
            val guildName = guildObj?.optString("name", "")?.ifBlank { null }
            val guildIcon = guildObj?.optString("icon", "")?.ifBlank { null }

            val joinRequestObj = root.optJSONObject("guild_join_request")
            val joinStatus = joinRequestObj?.optString("application_status", "")?.ifBlank { null }
            val requestId = joinRequestObj?.optString("join_request_id", "")?.ifBlank {
                joinRequestObj.optString("id", "").ifBlank { null }
            }

            val parsedFields = mutableListOf<VerificationField>()
            val fieldsArr = root.optJSONArray("form_fields") ?: JSONArray()
            for (i in 0 until fieldsArr.length()) {
                val fieldObj = fieldsArr.optJSONObject(i) ?: continue
                val fieldType = fieldObj.optString("field_type", "TEXT_INPUT").uppercase()
                val label = fieldObj.optString("label", "Question ${i + 1}")
                val fieldDesc = fieldObj.optString("description", "").ifBlank { null }
                val required = fieldObj.optBoolean("required", true)
                val placeholder = fieldObj.optString("placeholder", "").ifBlank { null }
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
     * Confirmed Official Endpoint:
     * GET /api/v9/guilds/{guild.id}/requests/@me/cooldown
     */
    fun fetchApplicationCooldown(guildId: Long): Long {
        return try {
            val response = createSafeRNRequest(
                "/api/v9/guilds/$guildId/requests/@me/cooldown",
                "GET",
                guildId
            ).execute()
            if (response.statusCode in 200..299) {
                val obj = JSONObject(response.text())
                obj.optDouble("cooldown", 0.0).toLong().coerceAtLeast(0L)
            } else {
                0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    /**
     * Confirmed Official Endpoint:
     * PUT /api/v9/guilds/{guild.id}/requests/@me
     * Submits the guild join request form responses and transitions status from STARTED to SUBMITTED/PENDING.
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
                if (!version.isNullOrBlank()) {
                    put("version", version)
                }
            }

            val response = createSafeRNRequest(
                "/api/v9/guilds/$guildId/requests/@me",
                "PUT",
                guildId,
                "Guild Member Verification"
            ).executeWithJson(payload.toString())

            val code = response.statusCode
            val text = runCatching { response.text() }.getOrDefault("")
            if (code in 200..299) {
                val json = if (text.isNotBlank()) JSONObject(text) else JSONObject()
                val reqObj = json.optJSONObject("guild_join_request") ?: json
                JoinRequestResult(
                    ok = true,
                    statusCode = code,
                    requestId = reqObj.optString("join_request_id", reqObj.optString("id", "")).ifBlank { null },
                    applicationStatus = reqObj.optString("application_status", "PENDING").ifBlank { "PENDING" },
                    rejectionReason = reqObj.optString("rejection_reason", "").ifBlank { null },
                    interviewChannelId = reqObj.optString("interview_channel_id", "").ifBlank { null },
                    submittedAt = reqObj.optString("last_seen", reqObj.optString("created_at", "")).ifBlank { null },
                    actionedAt = reqObj.optString("actioned_at", "").ifBlank { null },
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
     * Confirmed Official Endpoint:
     * POST /api/v9/guilds/{guild.id}/requests/@me
     * Resets the current user's guild join request to STARTED so they can re-apply.
     */
    fun resetGuildApplication(guildId: Long): JoinRequestResult {
        return try {
            val response = createSafeRNRequest(
                "/api/v9/guilds/$guildId/requests/@me",
                "POST",
                guildId,
                "Guild Member Verification"
            ).executeWithJson("{}")

            val code = response.statusCode
            val text = runCatching { response.text() }.getOrDefault("")
            if (code in 200..299) {
                val json = if (text.isNotBlank()) JSONObject(text) else JSONObject()
                val reqObj = json.optJSONObject("guild_join_request") ?: json
                JoinRequestResult(
                    ok = true,
                    statusCode = code,
                    requestId = reqObj.optString("join_request_id", reqObj.optString("id", "")).ifBlank { null },
                    applicationStatus = reqObj.optString("application_status", "STARTED").ifBlank { "STARTED" },
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
     * Confirmed Official Endpoint:
     * DELETE /api/v9/guilds/{guild.id}/requests/@me
     * Deletes / withdraws the user's pending or started join request.
     */
    fun withdrawGuildApplication(guildId: Long): JoinRequestResult {
        return try {
            val response = createSafeRNRequest(
                "/api/v9/guilds/$guildId/requests/@me",
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
     * Confirmed Official Endpoint:
     * GET /api/v9/join-requests/{request.id}
     * Fetches a specific join request's status if request_id is known, with fallback to member-verification.
     */
    fun fetchJoinRequestStatus(guildId: Long, requestId: String? = null): JoinRequestResult {
        if (!requestId.isNullOrBlank()) {
            try {
                val res = createSafeRNRequest(
                    "/api/v9/join-requests/$requestId",
                    "GET",
                    guildId
                ).execute()
                if (res.statusCode in 200..299) {
                    val text = runCatching { res.text() }.getOrDefault("")
                    if (text.isNotBlank()) {
                        val json = JSONObject(text)
                        val reqObj = json.optJSONObject("guild_join_request") ?: json
                        return JoinRequestResult(
                            ok = true,
                            statusCode = res.statusCode,
                            requestId = reqObj.optString("join_request_id", reqObj.optString("id", requestId)),
                            applicationStatus = reqObj.optString("application_status", "PENDING"),
                            rejectionReason = reqObj.optString("rejection_reason", "").ifBlank { null },
                            interviewChannelId = reqObj.optString("interview_channel_id", "").ifBlank { null },
                            actionedAt = reqObj.optString("actioned_at", "").ifBlank { null },
                            rawJson = reqObj
                        )
                    }
                }
            } catch (_: Throwable) {
            }
        }

        val formRes = fetchMemberVerificationForm(guildId)
        val form = formRes.getOrNull()
        val cooldown = fetchApplicationCooldown(guildId)
        return if (form != null) {
            JoinRequestResult(
                ok = true,
                statusCode = 200,
                requestId = form.requestId ?: requestId,
                applicationStatus = form.joinRequestStatus,
                cooldownSeconds = cooldown
            )
        } else {
            JoinRequestResult(
                ok = false,
                statusCode = 404,
                cooldownSeconds = cooldown,
                errorMessage = formRes.exceptionOrNull()?.message ?: "Unable to fetch status"
            )
        }
    }

    private fun parseDiscordError(statusCode: Int, rawBody: String): String {
        return try {
            val json = JSONObject(rawBody)
            val msg = json.optString("message", "").ifBlank { "HTTP $statusCode" }
            val code = json.optInt("code", 0)
            if (code != 0) "$msg (Discord Code $code)" else msg
        } catch (_: Throwable) {
            if (rawBody.isNotBlank()) rawBody.take(160) else "HTTP Error $statusCode"
        }
    }
}
