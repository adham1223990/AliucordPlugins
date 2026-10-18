package com.adham1223990.addattachmenttoedit

import android.net.Uri
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.utils.ReflectUtils
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object AttachmentEditUploader {

    const val MAX_ATTACHMENTS = 10

    private fun token(): String = RestAPI.AppHeadersProvider.INSTANCE.authToken
        ?: throw IllegalStateException("Not signed in to Discord.")

    fun getExistingAttachmentIds(message: Message?): List<String> {
        if (message == null) return emptyList()
        val ids = ArrayList<String>()
        try {
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
                                ids.add(id.toString())
                            }
                        }
                        i++
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return ids
    }

    fun getAttachmentCount(channelId: Long, messageId: Long): Int {
        val message = runCatching { StoreStream.getMessages().getMessage(channelId, messageId) }.getOrNull()
        return getExistingAttachmentIds(message).size
    }

    fun addAttachments(
        channelId: Long,
        messageId: Long,
        imageUris: List<Uri>,
        newContent: String? = null
    ) {
        if (imageUris.isEmpty()) return

        val message = runCatching { StoreStream.getMessages().getMessage(channelId, messageId) }.getOrNull()
        val existingIds = getExistingAttachmentIds(message)

        if (existingIds.size >= MAX_ATTACHMENTS) {
            throw IllegalStateException("Maximum of $MAX_ATTACHMENTS attachments reached.")
        }
        if (existingIds.size + imageUris.size > MAX_ATTACHMENTS) {
            val remaining = MAX_ATTACHMENTS - existingIds.size
            throw IllegalStateException(
                "Cannot add ${imageUris.size} attachments. Only $remaining slot(s) remaining (max $MAX_ATTACHMENTS)."
            )
        }

        val authToken = token()

        // Read all selected URIs into memory first
        val preparedFiles = ArrayList<Pair<ByteArray, String>>(imageUris.size)
        var idx = 0
        while (idx < imageUris.size) {
            preparedFiles.add(readUri(imageUris[idx], idx))
            idx++
        }

        // Step 1: Request upload slots from Discord for all files at once
        val filesJson = JSONArray()
        var i = 0
        while (i < preparedFiles.size) {
            val filePair = preparedFiles[i]
            filesJson.put(JSONObject().apply {
                put("filename", filePair.second)
                put("file_size", filePair.first.size)
                put("id", i.toString())
                put("is_clip", false)
            })
            i++
        }
        val requestBody = JSONObject().apply {
            put("files", filesJson)
        }

        val uploadAttachmentsJson = Http.Request.newDiscordRNRequest(
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
                JSONObject(response.text()).getJSONArray("attachments")
            }
        }

        // Step 2: PUT raw bytes for each file to its corresponding upload_url
        val uploadedEntries = JSONArray()
        var k = 0
        while (k < existingIds.size) {
            uploadedEntries.put(JSONObject().apply {
                put("id", existingIds[k])
            })
            k++
        }

        var u = 0
        while (u < preparedFiles.size) {
            val slotInfo = uploadAttachmentsJson.getJSONObject(u)
            val uploadUrl = slotInfo.getString("upload_url")
            val uploadFilename = slotInfo.getString("upload_filename")
            val filePair = preparedFiles[u]

            Http.Request(uploadUrl, "PUT").use { request ->
                request.setRequestTimeout(60_000)
                request.executeWithBody(filePair.first).use { response ->
                    if (!response.ok()) {
                        throw IllegalStateException("Upload failed: HTTP ${response.statusCode}")
                    }
                }
            }

            uploadedEntries.put(JSONObject().apply {
                put("id", u.toString())
                put("uploaded_filename", uploadFilename)
                put("filename", filePair.second)
            })
            u++
        }

        // Step 3: PATCH the message with existing + new attachments (and updated content if editing)
        val editBody = JSONObject().apply {
            put("attachments", uploadedEntries)
            if (newContent != null) {
                put("content", newContent)
            }
        }

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

    private fun readUri(uri: Uri, index: Int): Pair<ByteArray, String> {
        val resolver = Utils.appActivity.contentResolver
        val mime = resolver.getType(uri)
        val slashIndex = mime?.lastIndexOf('/') ?: -1
        val extension = if (mime != null && slashIndex >= 0 && slashIndex + 1 < mime.length) {
            mime.substring(slashIndex + 1)
        } else {
            "png"
        }
        val filename = "attachment_${System.currentTimeMillis()}_$index.$extension"
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
