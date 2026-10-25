package com.adham1223990.addattachmenttoedit

import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.utils.ReflectUtils
import com.discord.models.message.Message
import com.discord.stores.StoreStream
import com.discord.api.premium.PremiumTier
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.user.UserUtils
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AttachmentEditUploader {

    const val MAX_ATTACHMENTS = 10

    // Per-file size limits by Nitro tier.
    const val LIMIT_FREE_BYTES = 20L * 1024L * 1024L
    const val LIMIT_BASIC_BYTES = 50L * 1024L * 1024L
    const val LIMIT_NITRO_BYTES = 1024L * 1024L * 1024L

    private fun tier(): PremiumTier? = try {
        StoreStream.getUsers().me.getPremiumTier()
    } catch (_: Throwable) {
        null
    }

    /** True for any paid Nitro plan (Basic, Classic or full Nitro). */
    fun hasNitro(): Boolean = try {
        UserUtils.INSTANCE.isPremium(StoreStream.getUsers().me)
    } catch (_: Throwable) {
        false
    }

    fun maxFileBytes(): Long = when (tier()) {
        PremiumTier.TIER_2, PremiumTier.TIER_1 -> LIMIT_NITRO_BYTES // Nitro / Nitro Classic: 1 GB
        PremiumTier.TIER_0 -> LIMIT_BASIC_BYTES // Nitro Basic: 50 MB
        else -> LIMIT_FREE_BYTES // no Nitro: 20 MB
    }

    fun maxFileLabel(): String = when (tier()) {
        PremiumTier.TIER_2, PremiumTier.TIER_1 -> "1 GB"
        PremiumTier.TIER_0 -> "50 MB"
        else -> "20 MB"
    }

    private fun upgradeHint(): String = when (tier()) {
        PremiumTier.TIER_2, PremiumTier.TIER_1 -> "."
        PremiumTier.TIER_0 -> " (full Nitro allows 1 GB)."
        else -> " (Nitro allows 1 GB)."
    }

    fun limitMessage(): String = "over your ${maxFileLabel()} limit" + upgradeHint()

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

    /**
     * Existing attachments to keep on the message. Vencord spreads `msg.attachments` into the
     * PATCH body; here each one is sent as { id, filename } which is all Discord needs to keep it.
     */
    fun getExistingAttachmentEntries(message: Message?): List<JSONObject> {
        val entries = ArrayList<JSONObject>()
        if (message == null) return entries
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
                            val filename = runCatching { ReflectUtils.getField(attachment, "filename") }.getOrNull()
                            if (id != null) {
                                entries.add(JSONObject().apply {
                                    put("id", id.toString())
                                    if (filename != null) put("filename", filename.toString())
                                })
                            }
                        }
                        i++
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return entries
    }

    fun getAttachmentCount(channelId: Long, messageId: Long): Int {
        val message = runCatching { StoreStream.getMessages().getMessage(channelId, messageId) }.getOrNull()
        return getExistingAttachmentIds(message).size
    }

    fun addAttachments(
        channelId: Long,
        messageId: Long,
        fileUris: List<Uri>,
        newContent: String? = null
    ) {
        if (fileUris.isEmpty()) return
        val progress = UploadProgress()
        progress.show("Preparing…")
        try {
            uploadAndEdit(channelId, messageId, fileUris, newContent, progress)
        } finally {
            progress.dismiss()
        }
    }

    private fun uploadAndEdit(
        channelId: Long,
        messageId: Long,
        fileUris: List<Uri>,
        newContent: String?,
        progress: UploadProgress
    ) {

        val message = runCatching { StoreStream.getMessages().getMessage(channelId, messageId) }.getOrNull()
        val existingIds = getExistingAttachmentIds(message)
        val existingEntries = getExistingAttachmentEntries(message)

        if (existingIds.size >= MAX_ATTACHMENTS) {
            throw IllegalStateException("Maximum of $MAX_ATTACHMENTS attachments reached.")
        }
        if (existingIds.size + fileUris.size > MAX_ATTACHMENTS) {
            val remaining = MAX_ATTACHMENTS - existingIds.size
            throw IllegalStateException(
                "Cannot add ${fileUris.size} attachments. Only $remaining slot(s) remaining (max $MAX_ATTACHMENTS)."
            )
        }

        val authToken = token()

        // Inspect every selected file (name, type, size) WITHOUT loading it into memory, and
        // reject anything over the user's limit before any request is made.
        val limit = maxFileBytes()
        val preparedFiles = ArrayList<PickedFile>(fileUris.size)
        var idx = 0
        while (idx < fileUris.size) {
            val file = inspectUri(fileUris[idx], idx)
            if (file.size > limit) {
                throw IllegalStateException("\"${file.name}\" is ${formatSize(file.size)}, ${limitMessage()}")
            }
            preparedFiles.add(file)
            idx++
        }

        var totalSize = 0L
        var sizeIdx = 0
        while (sizeIdx < preparedFiles.size) {
            totalSize += preparedFiles[sizeIdx].size
            sizeIdx++
        }
        progress.setTotal(totalSize, preparedFiles.size)

        // Step 1: request upload slots from Discord for all files at once
        val filesJson = JSONArray()
        var i = 0
        while (i < preparedFiles.size) {
            val file = preparedFiles[i]
            filesJson.put(JSONObject().apply {
                put("filename", file.name)
                put("file_size", file.size)
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

        // Step 2: stream each file straight from storage to the upload_url Discord handed back
        // (an external storage URL, not a Discord API route), then collect
        // { id, uploaded_filename, filename } exactly like Vencord does.
        val uploadedEntries = JSONArray()
        var k = 0
        while (k < existingEntries.size) {
            uploadedEntries.put(existingEntries[k])
            k++
        }

        var u = 0
        while (u < preparedFiles.size) {
            val slotInfo = uploadAttachmentsJson.getJSONObject(u)
            val slotId = slotInfo.opt("id")?.toString() ?: u.toString()
            val uploadUrl = slotInfo.getString("upload_url")
            val uploadFilename = slotInfo.getString("upload_filename")
            val file = preparedFiles[u]

            progress.startFile(u, file.name)
            putFile(uploadUrl, file, progress)

            uploadedEntries.put(JSONObject().apply {
                put("id", slotId)
                put("uploaded_filename", uploadFilename)
                put("filename", file.name)
            })
            u++
        }

        progress.finishing()

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

    private class PickedFile(val uri: Uri, val name: String, val mime: String?, val size: Long)

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
    }

    /** Reads name, type and size of any picked file (image, video, document, archive, ...). */
    private fun inspectUri(uri: Uri, index: Int): PickedFile {
        val resolver = Utils.appActivity.contentResolver
        val mime = resolver.getType(uri)

        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameCol >= 0) name = cursor.getString(nameCol)
                    val sizeCol = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeCol >= 0 && !cursor.isNull(sizeCol)) size = cursor.getLong(sizeCol)
                }
            }
        } catch (_: Throwable) {
        }
        if (name == null || name!!.isEmpty()) {
            val last = uri.lastPathSegment
            name = if (last != null && last.isNotEmpty()) last.substringAfterLast('/') else null
        }
        if (name == null || name!!.isEmpty()) {
            val ext = if (mime != null) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) else null
            name = "file_${System.currentTimeMillis()}_$index" + if (ext != null) ".$ext" else ""
        }

        // Some providers do not report a size; count the bytes once (streamed, nothing is kept).
        if (size < 0) {
            var total = 0L
            val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Could not read the selected file.")
            input.use {
                val data = ByteArray(65536)
                var n: Int
                while (it.read(data, 0, data.size).also { read -> n = read } != -1) total += n
            }
            size = total
        }
        return PickedFile(uri, name!!, mime, size)
    }

    /** Streams the file to the storage URL with a fixed length so memory use stays tiny, even at 1 GB. */
    private fun putFile(uploadUrl: String, file: PickedFile, progress: UploadProgress) {
        val resolver = Utils.appActivity.contentResolver
        val input = resolver.openInputStream(file.uri) ?: throw IllegalStateException("Could not read \"${file.name}\".")
        input.use { source ->
            val conn = URL(uploadUrl).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 600_000
                if (file.mime != null) conn.setRequestProperty("Content-Type", file.mime)
                conn.setFixedLengthStreamingMode(file.size)
                conn.outputStream.use { out ->
                    val data = ByteArray(65536)
                    var n: Int
                    while (source.read(data, 0, data.size).also { read -> n = read } != -1) {
                        out.write(data, 0, n)
                        progress.addBytes(n.toLong())
                    }
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Upload of \"${file.name}\" failed: HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
