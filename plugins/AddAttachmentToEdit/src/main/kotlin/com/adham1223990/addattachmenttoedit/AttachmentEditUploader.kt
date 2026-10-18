package com.adham1223990.addattachmenttoedit

import android.net.Uri
import com.aliucord.Http
import com.aliucord.Utils
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Builds and sends the real PATCH /channels/{channel.id}/messages/{message.id} edit request
 * with a newly attached file, through Http.Request.newDiscordRNRequest only. This is the
 * official, documented edit endpoint -- see
 * https://docs.discord.com/developers/resources/message#edit-message -- it accepts a new file
 * as a multipart "files[n]" part plus an "attachments" array (which must list every attachment
 * that should remain after the edit, existing ones by id and new ones by matching their file
 * index). The Discord client's own simplified edit path just never exposes this.
 */
object AttachmentEditUploader {

    private fun token(): String? = RestAPI.AppHeadersProvider.INSTANCE.authToken

    fun sendEditWithAttachment(channelId: Long, messageId: Long, content: String, imageUri: Uri) {
        val authToken = token() ?: throw IllegalStateException("Not signed in to Discord.")

        // executeWithMultipartForm needs a real File for a file part, so copy the picked
        // content Uri into our own cache file first.
        val tempFile = copyUriToCacheFile(imageUri)
        try {
            // The edit endpoint requires the "attachments" array to list every attachment that
            // should remain, so start from the message's current attachments (keeping them by
            // id) and append the new one, whose id must match its files[n] index -- here "0"
            // since we only ever upload a single file.
            val attachments = JSONArray()
            try {
                val message = StoreStream.getMessages().getMessage(channelId, messageId)
                message?.attachments?.forEach { attachment ->
                    attachments.put(JSONObject().apply { put("id", attachment.id.toString()) })
                }
            } catch (e: Throwable) {
                // Non-fatal: worst case we lose the "keep existing attachments" list and only
                // the new file survives the edit, rather than failing the whole request.
            }
            attachments.put(JSONObject().apply { put("id", "0") })

            val payload = JSONObject().apply {
                put("content", content)
                put("attachments", attachments)
            }

            val params = HashMap<String, Any>()
            params["payload_json"] = payload.toString()
            params["files[0]"] = tempFile

            Http.Request.newDiscordRNRequest(
                "/channels/$channelId/messages/$messageId",
                "PATCH"
            ).use { request ->
                request.setHeader("Authorization", authToken)
                request.setRequestTimeout(60_000)
                request.executeWithMultipartForm(params).use { response ->
                    if (!response.ok()) {
                        throw IllegalStateException(
                            "Discord returned HTTP ${response.statusCode}: ${response.text()}"
                        )
                    }
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun copyUriToCacheFile(uri: Uri): File {
        val resolver = Utils.appActivity.contentResolver
        val extension = resolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "png"
        val tempFile = File.createTempFile("edit_attachment_", ".$extension", Utils.appActivity.cacheDir)
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Could not read the picked image.")
        return tempFile
    }
}
