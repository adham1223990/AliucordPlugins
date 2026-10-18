package com.adham1223990.addattachmenttoedit

import android.net.Uri
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.utils.ReflectUtils
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Adds a new attachment to an already-sent message, following the exact same three-step flow
 * Vencord's own AddAttachments plugin uses on desktop (src/plugins/addAttachments/index.tsx),
 * which is the real, working way current Discord clients do this -- not the older single
 * multipart-PATCH approach the REST docs alone would suggest.
 */
object AttachmentEditUploader {

    private fun token(): String = RestAPI.AppHeadersProvider.INSTANCE.authToken
        ?: throw IllegalStateException("Not signed in to Discord.")

    fun addAttachment(channelId: Long, messageId: Long, imageUri: Uri) {
        val authToken = token()
        val (bytes, filename) = readUri(imageUri)

        // Step 1: ask Discord for a place to upload the file. This is a normal Discord API
        // route, so it goes through newDiscordRNRequest like everything else.
        val requestBody = JSONObject().apply {
            put("files", JSONArray().apply {
                put(JSONObject().apply {
                    put("filename", filename)
                    put("file_size", bytes.size)
                    put("id", "0")
                    put("is_clip", false)
                })
            })
        }
        val uploadInfo = Http.Request.newDiscordRNRequest(
            "/channels/$channelId/attachments",
            "POST"
        ).use { request ->
            request.setHeader("Authorization", authToken)
            request.setHeader("Content-Type", "application/json")
            request.setRequestTimeout(30_000)
            request.executeWithBody(requestBody.toString()).use { response ->
                if (!response.ok()) {
                    throw IllegalStateException(
                        "Discord refused the upload slot: HTTP ${response.statusCode} ${response.text()}"
                    )
                }
                JSONObject(response.text()).getJSONArray("attachments").getJSONObject(0)
            }
        }
        val uploadUrl = uploadInfo.getString("upload_url")
        val uploadFilename = uploadInfo.getString("upload_filename")

        // Step 2: upload the raw file bytes to the URL Discord just handed back. That URL
        // points at Discord's storage host (Google Cloud Storage), not a Discord API route, so
        // newDiscordRNRequest (which prefixes Discord's own API base URL and attaches Discord
        // auth/RN headers) does not apply here -- it would turn this into a request to
        // "<discord-api-base>/https://storage.googleapis.com/...", which is wrong. This uses
        // Aliucord's own plain Http.Request against the exact URL Discord provided, nothing
        // invented or unofficial.
        Http.Request(uploadUrl, "PUT").use { request ->
            request.setRequestTimeout(60_000)
            request.executeWithBody(bytes).use { response ->
                if (!response.ok()) {
                    throw IllegalStateException("Upload failed: HTTP ${response.statusCode}")
                }
            }
        }

        // Step 3: attach the uploaded file to the message. Existing attachments must be kept
        // by id, same as the official edit docs describe
        // (https://docs.discord.com/developers/resources/message#edit-message); this is a
        // normal Discord API route again, so it goes through newDiscordRNRequest.
        val attachments = JSONArray()
        try {
            val message = StoreStream.getMessages().getMessage(channelId, messageId)
            message?.attachments?.forEach { attachment ->
                // MessageAttachment's "id" field has no public getter in the generated stub
                // (not even getId()), so it's read through reflection, the same way
                // EditMessagesLocally.java reads/writes private fields on Discord's own model
                // classes.
                val id = runCatching { ReflectUtils.getField(attachment, "id") }.getOrNull()
                if (id != null) {
                    attachments.put(JSONObject().apply { put("id", id.toString()) })
                }
            }
        } catch (e: Throwable) {
            // Non-fatal: worst case we lose the "keep existing attachments" list and only the
            // new one survives, rather than failing the whole request.
        }
        attachments.put(
            JSONObject().apply {
                put("id", "0")
                put("uploaded_filename", uploadFilename)
                put("filename", filename)
            }
        )

        val editBody = JSONObject().apply { put("attachments", attachments) }

        Http.Request.newDiscordRNRequest(
            "/channels/$channelId/messages/$messageId",
            "PATCH"
        ).use { request ->
            request.setHeader("Authorization", authToken)
            request.setHeader("Content-Type", "application/json")
            request.setRequestTimeout(30_000)
            request.executeWithBody(editBody.toString()).use { response ->
                if (!response.ok()) {
                    throw IllegalStateException(
                        "Discord returned HTTP ${response.statusCode}: ${response.text()}"
                    )
                }
            }
        }
    }

    private fun readUri(uri: Uri): Pair<ByteArray, String> {
        val resolver = Utils.appActivity.contentResolver
        val extension = resolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "png"
        val filename = "attachment_${System.currentTimeMillis()}.$extension"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
        } ?: throw IllegalStateException("Could not read the picked image.")
        return bytes to filename
    }
}
