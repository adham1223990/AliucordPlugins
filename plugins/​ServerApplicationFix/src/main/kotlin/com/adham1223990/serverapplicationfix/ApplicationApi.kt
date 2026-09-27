package com.adham1223990.serverapplicationfix

import android.os.Build
import android.util.Base64
import com.aliucord.Http
import com.aliucord.utils.RNSuperProperties
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ApplicationApiException(val statusCode: Int, message: String) : Exception(message)

data class VerificationField(
    val fieldType: String,
    val label: String,
    val required: Boolean,
    val values: List<String>,
    val choices: List<String>,
    var response: Any
)

data class VerificationForm(val version: String, val fields: List<VerificationField>)

object ApplicationApi {

    private const val API_BASE = "https://discord.com/api/v10"
    
    private const val RN_VERSION = "225.17 - rn"
    private const val RN_BUILD_NUMBER = 225017
    private const val RN_NATIVE_BUILD = 4320
    private const val RN_RELEASE_CHANNEL = "googleRelease"
    const val CURRENT_RN_USER_AGENT = "Discord-Android/$RN_BUILD_NUMBER;RNA"

    @Volatile
    private var cachedSuperProperties: String? = null

    fun fetchForm(guildId: String): VerificationForm {
        val root = request("/guilds/$guildId/member-verification?with_guild=true", "GET")
        val version = root.optString("version", "1")
        val fieldsJson = root.optJSONArray("form_fields") ?: JSONArray()
        val fields = ArrayList<VerificationField>()
        var i = 0
        while (i < fieldsJson.length()) {
            val f = fieldsJson.optJSONObject(i)
            if (f == null) {
                i++
                continue
            }
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
            i++
        }
        return VerificationForm(version, fields)
    }

    fun submitForm(guildId: String, form: VerificationForm) {
        val fieldsArr = JSONArray()
        var fi = 0
        while (fi < form.fields.size) {
            val field = form.fields[fi]
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
            fi++
        }
        val body = JSONObject().apply {
            put("version", form.version)
            put("form_fields", fieldsArr)
        }
        request("/guilds/$guildId/requests/@me", "PUT", body)
    }

    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        val req = createV10Request(path, method)
        try {
            val response = if (body != null) {
                req.setHeader("Content-Type", "application/json")
                req.executeWithBody(body.toString())
            } else {
                req.execute()
            }
            if (!response.ok()) {
                throw ApplicationApiException(response.statusCode, "${response.statusCode}: ${response.text().take(300)}")
            }
            val text = response.text()
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: ApplicationApiException) {
            throw e
        } catch (t: Throwable) {
            // Aliucord's Http.Request throws its own exception for non-2xx responses before we
            // ever get to read the body ourselves, so its message is just the generic HTTP
            // reason phrase (e.g. "403: Forbidden"), not Discord's actual JSON error. Pull the
            // real body straight off the connection's error stream instead.
            val conn = req.conn
            val code = try { conn.responseCode } catch (e2: Throwable) { -1 }
            val errorBody = try {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            } catch (e2: Throwable) {
                ""
            }
            val message = if (errorBody.isNotBlank()) "$code: ${errorBody.take(300)}" else "$code: ${t.message}"
            throw ApplicationApiException(code, message)
        }
    }

    fun createV10Request(path: String, method: String): Http.Request {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val fullUrl = "$API_BASE$cleanPath"

        val req = Http.Request(fullUrl, method)

        try {
            val token = currentAuthToken()
            if (!token.isNullOrEmpty()) {
                req.setHeader("Authorization", token)
            }
        } catch (t: Throwable) {
            throw IllegalStateException("failed setting Authorization header: ${t.message}", t)
        }

        try {
            req.setHeader("User-Agent", CURRENT_RN_USER_AGENT)
        } catch (t: Throwable) {
            throw IllegalStateException("failed setting User-Agent header: ${t.message}", t)
        }

        try {
            req.setHeader("X-Super-Properties", getSuperProperties())
        } catch (t: Throwable) {
            throw IllegalStateException("failed setting X-Super-Properties header: ${t.message}", t)
        }

        try {
            req.setHeader("Accept-Language", Locale.getDefault().toLanguageTag())
        } catch (t: Throwable) {
            throw IllegalStateException("failed setting Accept-Language header: ${t.message}", t)
        }

        return req
    }

    /** Confirmed correct source (from a plugin that prints this exact value): the live app
     *  token lives at RestAPI.AppHeadersProvider.INSTANCE.authToken, not on StoreAuthentication. */
    private fun currentAuthToken(): String? {
        return try {
            RestAPI.AppHeadersProvider.INSTANCE.authToken
        } catch (e: Throwable) {
            null
        }
    }

    fun getSuperProperties(): String {
        cachedSuperProperties?.let { return it }
        return synchronized(this) {
            cachedSuperProperties ?: buildDeviceSuperProperties().also { cachedSuperProperties = it }
        }
    }

    private fun buildDeviceSuperProperties(): String {
        val properties = runCatching {
            JSONObject(RNSuperProperties.superProperties.toString())
        }.getOrElse { JSONObject() }

        properties.put("os", "Android")
        properties.put("browser", "Discord Android")
        properties.put("device", Build.MODEL)
        properties.put("device_manufacturer", Build.MANUFACTURER)
        properties.put("device_model", Build.MODEL)
        properties.put("os_version", Build.VERSION.RELEASE)
        properties.put("os_sdk_version", Build.VERSION.SDK_INT.toString())
        properties.put("system_locale", Locale.getDefault().toLanguageTag())
        properties.put("client_version", RN_VERSION)
        properties.put("release_channel", RN_RELEASE_CHANNEL)
        properties.put("client_build_number", RN_BUILD_NUMBER)
        properties.put("native_build_number", RN_NATIVE_BUILD)
        properties.put("has_client_mods", false)
        properties.put("design_id", 0)
        properties.put("launch_signature", (System.currentTimeMillis() * 1_000_000L).toString())

        return Base64.encodeToString(
            properties.toString().toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < length()) {
            out.add(optString(i, ""))
            i++
        }
        return out
    }
}
