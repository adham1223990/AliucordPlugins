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

object AttachmentEditUploader {

    private fun token(): String = RestAPI.AppHeadersProvider.INSTANCE.authToken
        ?: throw IllegalStateException("Not signed in to Discord.")

    fun addAttachment(channelId: Long, messageId: Long, imageUri: Uri) {
        val authToken = token()
        val fileData = readUri(imageUri)
        val bytes = fileData.first
        val filename = fileData.second

        // Step 1: Request an upload URL from Discord
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

        // Step 2: PUT the raw file bytes to the Google Cloud Storage URL returned by Discord
        Http.Request(uploadUrl, "PUT").use { request ->
            request.setRequestTimeout(60_000)
            request.executeWithBody(bytes).use { response ->
                if (!response.ok()) {
                    throw IllegalStateException("Upload failed: HTTP ${response.statusCode}")
                }
            }
        }

        // Step 3: PATCH the message with existing attachments + the newly uploaded attachment
        val attachments = JSONArray()
        try {
            val message = StoreStream.getMessages().getMessage(channelId, messageId)
            val rawAttachments = runCatching { ReflectUtils.getField(message, "attachments") }.getOrNull()
            if (rawAttachments != null) {
                val listClass = rawAttachments.javaClass
                val size = runCatching { listClass.getMethod("size").invoke(rawAttachments) as? Int }.getOrNull()
                val getMethod = runCatching { listClass.getMethod("get", Int::class.javaPrimitiveType) }.getOrNull()
                if (size != null && getMethod != null) {
                    var i = 0
                    while (i < size) {
                        val attachment = runCatching { getMethod.invoke(rawAttachments, i) }.getOrNull()
                        if (attachment != null) {
                            val id = runCatching { ReflectUtils.getField(attachment, "id") }.getOrNull()
                            if (id != null) {
                                attachments.put(JSONObject().apply { put("id", id.toString()) })
                            }
                        }
                        i++
                    }
                }
            }
        } catch (e: Throwable) {
            // Non-fatal: keep going with the new attachment if reading existing ones fails
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
        val mime = resolver.getType(uri)
        val slashIndex = mime?.lastIndexOf('/') ?: -1
        val extension = if (mime != null && slashIndex >= 0 && slashIndex + 1 < mime.length) {
            mime.substring(slashIndex + 1)
        } else {
            "png"
        }
        val filename = "attachment_${System.currentTimeMillis()}.$extension"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArrayOutputStream()
            val data = ByteArray(8192)
            var nRead: Int
            while (input.read(data, 0, data.size).also { nRead = it } != -1) {
                buffer.write(data, 0, nRead)
            }
            buffer.toByteArray()
        } ?: throw IllegalStateException("Could not read the picked image.")
        return Pair(bytes, filename)
    }
}
