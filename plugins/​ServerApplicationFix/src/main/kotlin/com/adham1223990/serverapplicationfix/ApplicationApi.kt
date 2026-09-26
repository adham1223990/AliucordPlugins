package com.adham1223990.serverapplicationfix

import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject

class ApplicationApiException(val statusCode: Int, message: String) : Exception(message)

/** One entry from "form_fields". [response] is what gets sent back: String for TERMS/TEXT_INPUT/
 *  PARAGRAPH, Int (choice index) for MULTIPLE_CHOICE — matching Discord's own parser exactly. */
data class VerificationField(
    val fieldType: String,
    val label: String,
    val required: Boolean,
    val values: List<String>,
    val choices: List<String>,
    var response: Any
)

data class VerificationForm(val version: String, val fields: List<VerificationField>)

/**
 * Talks to the real endpoints confirmed from RestAPIInterface.java:
 *   GET /guilds/{guildId}/member-verification            -> ModelMemberVerificationForm
 *   PUT /guilds/{guildId}/requests/@me  (RestAPIParams.MemberVerificationForm body)
 *
 * Both go through Http.Request.newDiscordRNRequest, which ServerApplicationFix already patches
 * to force the spoofed User-Agent / X-Super-Properties onto every request to discord.com.
 */
object ApplicationApi {

    fun fetchForm(guildId: String): VerificationForm {
        val root = request("/guilds/$guildId/member-verification?with_guild=true", "GET")
        val version = root.optString("version", "1")
        val fieldsJson = root.optJSONArray("form_fields") ?: JSONArray()
        val fields = ArrayList<VerificationField>()
        for (i in 0 until fieldsJson.length()) {
            val f = fieldsJson.optJSONObject(i) ?: continue
            val fieldType = f.optString("field_type", "UNKNOWN")
            fields.add(
                VerificationField(
                    fieldType = fieldType,
                    label = f.optString("label", ""),
                    required = f.optBoolean("required", false),
                    values = f.optJSONArray("values").toStringList(),
                    choices = f.optJSONArray("choices").toStringList(),
                    response = if (fieldType == "MULTIPLE_CHOICE") -1 else ""
                )
            )
        }
        return VerificationForm(version, fields)
    }

    fun submitForm(guildId: String, form: VerificationForm) {
        val fieldsArr = JSONArray()
        for (field in form.fields) {
            fieldsArr.put(
                JSONObject().apply {
                    put("field_type", field.fieldType)
                    put("label", field.label)
                    put("required", field.required)
                    put("values", JSONArray(field.values))
                    put("choices", JSONArray(field.choices))
                    put("response", field.response)
                }
            )
        }
        val body = JSONObject().apply {
            put("version", form.version)
            put("form_fields", fieldsArr)
        }
        request("/guilds/$guildId/requests/@me", "PUT", body)
    }

    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        val req = Http.Request.newDiscordRNRequest(path, method)
        val response = if (body != null) {
            req.setHeader("Content-Type", "application/json")
            req.executeWithBody(body.toString())
        } else {
            req.execute()
        }
        if (!response.ok()) {
            throw ApplicationApiException(response.statusCode, response.text().take(300))
        }
        val text = response.text()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until length()) out.add(optString(i, ""))
        return out
    }
}
