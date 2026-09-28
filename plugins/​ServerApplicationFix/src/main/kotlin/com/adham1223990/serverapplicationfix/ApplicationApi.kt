package com.adham1223990.serverapplicationfix

import com.aliucord.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * كود الدعوة لكل سيرفر (بيتسجل من الـ hook لما المستخدم يدوس Join على دعوة). دسكورد بيرجّع
     * 10004 Unknown Guild لطلب الفورم لو المستخدم لسه مش عضو ومفيش invite_code في الطلب.
     */
    val inviteCodes = ConcurrentHashMap<String, String>()

    /** الفورم اللي الـ hook جابه خلاص، عشان الصفحة متعملش طلب تاني (بتاخده مرة واحدة). */
    val prefetchedForms = ConcurrentHashMap<String, VerificationForm>()

    fun fetchForm(guildId: String): VerificationForm {
        val code = inviteCodes[guildId]
        val query = if (!code.isNullOrEmpty()) {
            "with_guild=false&invite_code=" + URLEncoder.encode(code, "UTF-8")
        } else {
            "with_guild=false"
        }
        val root = request("/guilds/$guildId/member-verification?$query", "GET")
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

    // بدائل يدوية للـ isBlank()/take() لأنها بتستخدم IntRange iterator داخليًا وبتتعارض مع
    // نسخة Kotlin المعاد تسميتها جوه دسكورد (ClassCastException: d0.d0.b -> IntIterator).
    private fun isBlankSafe(s: String?): Boolean {
        if (s == null) return true
        var i = 0
        val n = s.length
        while (i < n) {
            if (!Character.isWhitespace(s[i])) return false
            i++
        }
        return true
    }

    private fun limitSafe(s: String, max: Int): String =
        if (s.length <= max) s else s.substring(0, max)

    /**
     * الطريقة الرسمية: Http.Request.newDiscordRNRequest بتبني الـ request بنفسها (الـ route
     * والـ version والـ Authorization والهيدرز الخاصة بـ RN)، فمفيش أي تزوير أو reflection
     * أو hooks من عندنا، وده بيخلي الطلب أقرب ما يكون لطلب التطبيق نفسه.
     */
    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        val req = Http.Request.newDiscordRNRequest(path, method)
        val response = if (body != null) {
            req.setHeader("Content-Type", "application/json")
            req.executeWithBody(body.toString())
        } else {
            req.execute()
        }
        return response.use { res ->
            if (!res.ok()) {
                // assertOk() بترمي exception رسالتها فيها سطر جديد وبعده الـ body الحقيقي من دسكورد
                val raw = runCatching { res.assertOk() }.exceptionOrNull()?.message.orEmpty()
                val nl = raw.indexOf('\n')
                val errorBody = if (nl >= 0) raw.substring(nl + 1) else ""
                val message = if (!isBlankSafe(errorBody)) {
                    "${res.statusCode}: ${limitSafe(errorBody, 300)}"
                } else {
                    "HTTP ${res.statusCode}"
                }
                throw ApplicationApiException(res.statusCode, message)
            }
            val text = res.text()
            if (isBlankSafe(text)) JSONObject() else JSONObject(text)
        }
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
