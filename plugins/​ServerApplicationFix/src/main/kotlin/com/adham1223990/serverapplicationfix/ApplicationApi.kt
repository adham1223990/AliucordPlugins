package com.adham1223990.serverapplicationfix

import com.aliucord.Http
import com.discord.stores.StoreStream
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class ApplicationApiException(val statusCode: Int, message: String) : Exception(message)

/** Thrown when Discord already has an application from this user for the server. */
class AlreadyAppliedException(val status: String) :
    Exception("You already applied to this server (${ApplicationApi.statusLabel(status)}).")

data class JoinRequestInfo(val status: String, val rejectionReason: String?)

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

    /** السيرفرات اللي قدّمنا عليها بنجاح في الجلسة دي، عشان مايتعرضش التقديم تاني. */
    val appliedGuilds: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** أسماء السيرفرات (من الدعوة) عشان تظهر في قائمة الإعدادات. */
    val guildNames = ConcurrentHashMap<String, String>()

    /** Human readable English label for a normalized status. */
    fun statusLabel(status: String): String = when (status) {
        "APPROVED" -> "Accepted"
        "REJECTED" -> "Rejected"
        "PENDING" -> "Under review"
        "STARTED" -> "Not submitted"
        else -> "Unknown"
    }

    fun normalizeStatus(raw: String?): String {
        if (raw == null) return "UNKNOWN"
        return when (raw.uppercase()) {
            "SUBMITTED", "PENDING" -> "PENDING"
            "APPROVED" -> "APPROVED"
            "REJECTED" -> "REJECTED"
            "STARTED" -> "STARTED"
            else -> "UNKNOWN"
        }
    }

    /** True if this status means Discord already holds a finished application. */
    fun isFinalStatus(status: String): Boolean =
        status == "PENDING" || status == "APPROVED" || status == "REJECTED"

    /**
     * Asks Discord directly (GET /guilds/{id}/requests/@me) for this user's join request.
     * Returns null when Discord has no request for us or the endpoint is not available.
     */
    fun fetchJoinRequest(guildId: String): JoinRequestInfo? {
        return try {
            val root = request("/guilds/$guildId/requests/@me", "GET")
            val raw = root.optString("application_status", "")
            if (isBlankSafe(raw)) {
                null
            } else {
                val reason = root.optString("rejection_reason", "")
                JoinRequestInfo(normalizeStatus(raw), if (isBlankSafe(reason)) null else reason)
            }
        } catch (e: ApplicationApiException) {
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** Status known by Discord's own local stores (kept in sync by the gateway). */
    private fun localStatus(guildId: String): String? {
        return try {
            val gid = guildId.toLong()
            val meId = StoreStream.getUsers().meSnapshot.id
            val member = StoreStream.getGuilds().getMember(gid, meId)
            if (member != null && !member.pending) return "APPROVED"
            val name = StoreStream.getGuildJoinRequests().getGuildJoinRequest(gid)?.applicationStatus?.name
            if (name != null) normalizeStatus(name) else null
        } catch (t: Throwable) {
            null
        }
    }

    /** Best available status: full membership, then Discord's API, then the local stores. */
    fun resolveStatus(guildId: String): String? {
        if (localStatus(guildId) == "APPROVED") return "APPROVED"
        val fromApi = fetchJoinRequest(guildId)
        if (fromApi != null) return fromApi.status
        return localStatus(guildId)
    }

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

    fun submitForm(guildId: String, form: VerificationForm): String {
        // حماية من التكرار بالتواصل مع API ديسكورد: لو فيه طلب موجود فعلاً منقدمش تاني
        val existing = fetchJoinRequest(guildId)
        if (existing != null && isFinalStatus(existing.status)) {
            ApplicationStore.record(guildId, guildNames[guildId], existing.status)
            appliedGuilds.add(guildId)
            throw AlreadyAppliedException(existing.status)
        }

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
        val response = request("/guilds/$guildId/requests/@me", "PUT", body)
        val raw = response.optString("application_status", "")
        val status = if (isBlankSafe(raw)) "PENDING" else normalizeStatus(raw)
        val finalStatus = if (status == "UNKNOWN" || status == "STARTED") "PENDING" else status
        ApplicationStore.record(guildId, guildNames[guildId], finalStatus)
        return finalStatus
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
