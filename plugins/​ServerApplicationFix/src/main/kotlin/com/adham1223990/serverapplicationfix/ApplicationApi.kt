package com.adham1223990.serverapplicationfix

import android.util.Base64
import com.aliucord.Http
import com.aliucord.utils.RNSuperProperties
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.net.HttpURLConnection
import java.net.URL

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
 * Both requests are built and spoofed here directly (see createV10Request below) — upgraded
 * from Discord's default /api/v9/ to /api/v10/, with our own User-Agent / X-Super-Properties
 * set on the connection. This no longer relies on ServerApplicationFix's Http.Request patches.
 */
object ApplicationApi {

    private const val CURRENT_RN_BUILD_NUMBER = 6081
    private const val CURRENT_RN_VERSION = "341.0 - rn"
    private const val CURRENT_RN_USER_AGENT = "Discord-Android/341200;RNA"
    private var cachedSuperProperties: String? = null

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
        val req = createV10Request(path, method)
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

    /**
     * Builds the request through Aliucord's own newDiscordRequest (auth/cookies etc. are set
     * up as usual), then upgrades it from Discord's default /api/v9/ route to /api/v10/ by
     * rewriting HttpURLConnection's private `url` field via reflection — before the connection
     * is actually opened, so this is safe — and sets the spoofed User-Agent / X-Super-Properties
     * directly on the connection. This makes ApplicationApi fully self-contained: it no longer
     * depends on ServerApplicationFix's Http.Request.setHeader / newDiscordRNRequest patches.
     */
    private fun createV10Request(path: String, method: String): Http.Request {
        val req = Http.Request.newDiscordRequest(path, method)
        try {
            val conn = req.conn
            val originalUrl = conn.url.toString()
            if (originalUrl.contains("/api/v9/")) {
                val upgradedUrl = originalUrl.replace("/api/v9/", "/api/v10/")
                val urlField: Field = HttpURLConnection::class.java.getDeclaredField("url")
                urlField.isAccessible = true
                urlField.set(conn, URL(upgradedUrl))
            }
        } catch (t: Throwable) {
            // Best effort: if this fails we still send the request on whatever version
            // newDiscordRequest defaulted to, instead of crashing the call outright.
        }

        req.conn.setRequestProperty("User-Agent", CURRENT_RN_USER_AGENT)
        req.conn.setRequestProperty("X-Super-Properties", currentSuperProperties())
        return req
    }

    private fun currentSuperProperties(): String {
        cachedSuperProperties?.let { return it }
        return synchronized(this) {
            cachedSuperProperties ?: buildSuperProperties().also { cachedSuperProperties = it }
        }
    }

    private fun buildSuperProperties(): String {
        val props = try {
            JSONObject(RNSuperProperties.superProperties.toString())
        } catch (t: Throwable) {
            JSONObject()
        }

        props.put("has_client_mods", false)
        props.put("os", "Android")
        props.put("browser", "Discord Android")
        props.put("client_version", CURRENT_RN_VERSION)
        props.put("release_channel", "canaryRelease")
        props.put("client_build_number", CURRENT_RN_BUILD_NUMBER)
        props.put("launch_signature", (System.currentTimeMillis() * 1_000_000L).toString())

        return Base64.encodeToString(props.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until length()) out.add(optString(i, ""))
        return out
    }
}
