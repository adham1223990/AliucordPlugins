package com.github.adham1223990.bettermessagelogger

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import com.aliucord.Utils
import com.discord.api.message.attachment.MessageAttachment
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.models.message.Message
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Keeps image and video attachments of deleted messages. Discord purges deleted attachments from its CDN
 * almost immediately and signed URLs expire, so media is downloaded into a bounded temporary cache when
 * messages arrive. Once a message is deleted, one of two things happens:
 * - database on ("links only"): nothing is kept on the device. With a cloud account configured the media is
 *   uploaded and only its URL is remembered; without one, only the links stored in the database remain.
 * - database off: the media is moved into the permanent media folder on the device.
 */
internal class MessageMediaStore(
    private val root: File,
    private val reportError: (String, Throwable) -> Unit,
    private val changed: () -> Unit,
    private val remote: () -> RemoteConfig? = { null },
) {
    private val executor = executor("BetterMessageLogger-Media")
    private val prefetchExecutor = executor("BetterMessageLogger-Prefetch")
    private val pending = File(root, PENDING_DIR)

    // Legacy session folder from older versions; cleared on start.
    private val session = File(root, SESSION_DIR)
    private val rootUri = Uri.fromFile(root).toString() + "/"
    private val moveLock = Any()

    // Optional cloud copies: media of deleted messages goes to the user's own account instead of the device.
    private val remoteIndex = RemoteIndex(File(root, REMOTE_INDEX))
    private val uploading = HashSet<String>()

    // Deleted messages handled this session, mapped to whether only links are kept (database on).
    // Also protects fresh downloads from a concurrent sweep. Guarded by itself.
    private val attempted = HashMap<Long, Boolean>()

    // Guarded by attempted.
    private val prefetched = LinkedHashSet<Long>()
    private var stopped = false

    init {
        // Pending media of messages that were not deleted while the app ran is no longer useful.
        enqueue(prefetchExecutor, "clear pending media") { pending.deleteRecursively() }
        enqueue(executor, "clear session media") { synchronized(moveLock) { session.deleteRecursively() } }
        // Existing installs already have the folder; hide it from the gallery right away.
        // Decided before any download can create the marker, so a fast prefetch can't skip the migration.
        val migrate = root.isDirectory && !File(root, NO_MEDIA).exists()
        if (migrate) {
            enqueue(executor, "hide media from gallery") {
                hideFromGallery()
                removeFromGallery()
            }
        }
    }

    /** Downloads media of a live message into the pending cache, in case it gets deleted later. */
    fun prefetchAsync(message: Message) {
        val attachments = message.attachments
        if (attachments == null || attachments.isEmpty()) return
        val id = message.id
        synchronized(attempted) {
            if (attempted.containsKey(id) || !prefetched.add(id)) return
            if (prefetched.size > MAX_PREFETCHED_IDS) prefetched.remove(prefetched.first())
        }
        enqueue(prefetchExecutor, "pre-download media") {
            val directory = File(pending, id.toString())
            var index = 0
            while (index < attachments.size) {
                val attachment = attachments[index]
                val name = name(index, attachment)
                if (name != null && attachment.d() <= MAX_PREFETCH_BYTES) {
                    try {
                        save(attachment, File(directory, name), MAX_PREFETCH_BYTES)
                    } catch (_: Exception) {
                        // Best effort; the deletion handler tries again.
                    }
                }
                index++
            }
            // The message may have been deleted while its media was downloading.
            val linksOnly = synchronized(attempted) { attempted[id] }
            if (linksOnly != null) {
                if (linksOnly) {
                    val config = remote()
                    if (config != null) uploadPending(id, directory, attachments, config) else directory.deleteRecursively()
                } else {
                    promote(directory, target(id))
                }
                changed()
            } else {
                directory.setLastModified(now())
            }
            trimPending()
        }
    }

    /**
     * Stores media of a deleted message: as links only when [linksOnly] (nothing is written to the device),
     * otherwise in the permanent media folder. Each message is handled once per mode.
     * Never blocks the calling thread.
     */
    fun saveAsync(record: MessageRecord, linksOnly: Boolean) {
        if (!record.deleted) return
        synchronized(attempted) {
            if (attempted[record.id] == linksOnly) return
            attempted[record.id] = linksOnly
        }
        val directory = target(record.id)
        val config = if (linksOnly) remote() else null
        enqueue(executor, "save logged media") {
            if (linksOnly) {
                if (config != null) saveRemote(record, config) else File(pending, record.id.toString()).deleteRecursively()
                return@enqueue
            }
            promote(File(pending, record.id.toString()), directory)
            promote(File(session, record.id.toString()), directory)
            val attachments = record.toMessage().attachments
            if (attachments == null || attachments.isEmpty()) return@enqueue
            changed()
            var index = 0
            while (index < attachments.size) {
                val attachment = attachments[index]
                val target = name(index, attachment)?.let { File(directory, it) }
                if (target != null) {
                    try {
                        save(attachment, target, MAX_BYTES)
                    } catch (error: Exception) {
                        // The CDN often purges deleted attachments; the message still shows its text.
                        reportError("save attachment ${attachment.a()}", error)
                    }
                }
                index++
            }
            changed()
        }
    }

    /** Connects the cloud link index to the database. */
    fun attachLinks(db: MessageLoggerDatabase, links: Map<Long, Map<String, String>>) = remoteIndex.attach(db, links)

    fun hasMedia(id: Long) =
        File(root, id.toString()).isDirectory || File(session, id.toString()).isDirectory || remoteIndex.has(id)

    /** Points attachments of a freshly parsed message at their local copies. Never pass a live message. */
    fun localize(message: Message): Message {
        val attachments = message.attachments ?: return message
        val urlField = urlField ?: return message
        val proxyUrlField = proxyUrlField ?: return message
        var index = 0
        while (index < attachments.size) {
            val attachment = attachments[index]
            val name = name(index, attachment)
            val local = name?.let { File(target(message.id), it).takeIf { file -> file.isFile } }
            if (local != null) {
                val uri = Uri.fromFile(local).toString()
                urlField.set(attachment, uri)
                proxyUrlField.set(attachment, uri)
            } else if (name != null) {
                remoteIndex.get(message.id, name)?.let {
                    urlField.set(attachment, it)
                    proxyUrlField.set(attachment, it)
                }
            }
            index++
        }
        return message
    }

    /** Discord appends media-proxy parameters to preview URLs; local files need the plain path. */
    fun previewUrls(url: String): List<String>? {
        if (!url.startsWith(rootUri)) return null
        val file = File(Uri.parse(url).path ?: return null)
        val thumbnail = thumbnail(file)
        return listOf(if (thumbnail.isFile) Uri.fromFile(thumbnail).toString() else url)
    }

    fun removeAsync(id: Long) = enqueue(executor, "remove logged media") {
        synchronized(attempted) { attempted.remove(id) }
        remoteIndex.remove(id)
        synchronized(moveLock) {
            File(root, id.toString()).deleteRecursively()
            File(session, id.toString()).deleteRecursively()
            File(pending, id.toString()).deleteRecursively()
        }
    }

    fun clearAsync() = enqueue(executor, "clear logged media") {
        synchronized(attempted) { attempted.clear() }
        remoteIndex.clear()
        synchronized(moveLock) { root.deleteRecursively() }
    }

    /**
     * Forgets cloud links of messages that are no longer in the database. Files in the media folder are
     * kept: they belong to messages logged while the database was off and are removed with their message.
     */
    fun retainAsync(ids: Set<Long>) = enqueue(executor, "clean up logged media") {
        val protected = synchronized(attempted) { HashSet(attempted.keys) }
        remoteIndex.retain { it in ids || it in protected }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true
        executor.shutdownNow()
        prefetchExecutor.shutdownNow()
    }

    /** Uploads media of a deleted message straight from the CDN (or the temporary cache) and keeps only the URL. */
    private fun saveRemote(record: MessageRecord, config: RemoteConfig) {
        val attachments = record.toMessage().attachments
        if (attachments == null || attachments.isEmpty()) return
        val directory = File(pending, record.id.toString())
        var index = 0
        while (index < attachments.size) {
            val attachment = attachments[index]
            val name = name(index, attachment)
            if (name != null) {
                try {
                    val cached = File(directory, name).takeIf { it.isFile }
                    uploadOnce(record.id, name) {
                        if (cached != null) {
                            cached.inputStream().use { RemoteStorage.upload(config, record.id, name, it, mime(name)) }
                        } else {
                            openSource(attachment) { RemoteStorage.upload(config, record.id, name, it, mime(name)) }
                        }
                    }
                    cached?.delete()
                    changed()
                } catch (error: Exception) {
                    reportError("upload attachment ${attachment.a()}", error)
                }
            }
            index++
        }
        directory.delete()
    }

    /** Uploads files a prefetch finished after their message was already deleted. */
    private fun uploadPending(id: Long, directory: File, attachments: List<MessageAttachment>, config: RemoteConfig) {
        var index = 0
        while (index < attachments.size) {
            val name = name(index, attachments[index])
            val file = name?.let { File(directory, it) }?.takeIf { it.isFile }
            if (name != null && file != null) {
                try {
                    uploadOnce(id, name) { file.inputStream().use { RemoteStorage.upload(config, id, name, it, mime(name)) } }
                    file.delete()
                } catch (error: Exception) {
                    reportError("upload attachment $name", error)
                }
            }
            index++
        }
        directory.delete()
    }

    private fun uploadOnce(id: Long, name: String, upload: () -> String) {
        val key = "$id/$name"
        synchronized(uploading) { if (remoteIndex.has(id, name) || !uploading.add(key)) return }
        try {
            remoteIndex.put(id, name, upload())
        } finally {
            synchronized(uploading) { uploading.remove(key) }
        }
    }

    private fun <T> openSource(attachment: MessageAttachment, use: (InputStream) -> T): T {
        check(attachment.d() <= MAX_BYTES) { "Attachment is too large" }
        var failure: Exception? = null
        for (source in listOfNotNull(attachment.f(), attachment.c()).distinct()) {
            val connection = URL(source).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT
                connection.readTimeout = TIMEOUT
                val code = connection.responseCode
                check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
                return connection.inputStream.use(use)
            } catch (error: Exception) {
                failure = error
            } finally {
                connection.disconnect()
            }
        }
        throw failure ?: IllegalStateException("No attachment source")
    }

    private fun mime(name: String) = when (name.substringAfterLast('.')) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }

    private fun target(id: Long) = File(root, id.toString())

    /** Moves completed downloads from [source] into [target]. */
    private fun promote(source: File, target: File) = synchronized(moveLock) {
        val files = source.listFiles() ?: return@synchronized
        if (!target.isDirectory && !target.mkdirs()) return@synchronized
        hideFromGallery()
        var downloading = false
        for (file in files) {
            if (file.name.endsWith(PART)) {
                downloading = true
                continue
            }
            val destination = File(target, file.name)
            if (destination.exists()) file.delete() else file.renameTo(destination)
        }
        // A running prefetch promotes its remaining file when it finishes.
        if (!downloading) source.deleteRecursively()
    }

    private fun trimPending() {
        val directories = pending.listFiles()?.filter { it.isDirectory } ?: return
        var total = 0L
        val sizes = HashMap<File, Long>()
        for (directory in directories) {
            val size = directory.listFiles()?.sumOf { it.length() } ?: 0L
            sizes[directory] = size
            total += size
        }
        var count = directories.size
        for (directory in directories.sortedBy { it.lastModified() }) {
            if (total <= MAX_PENDING_BYTES && count <= MAX_PREFETCHED_IDS) break
            synchronized(moveLock) { directory.deleteRecursively() }
            total -= sizes[directory] ?: 0L
            count--
        }
    }

    private fun name(index: Int, attachment: MessageAttachment): String? {
        val url = attachment.f() ?: return null
        val type = attachment.e()
        if (type != MessageAttachmentType.IMAGE && type != MessageAttachmentType.VIDEO) return null
        // Keep the original extension; Discord derives the attachment type from it.
        val extension = Uri.parse(url).lastPathSegment.orEmpty().substringAfterLast('.', "").toLowerCase(Locale.ROOT)
        if (extension.isEmpty() || extension.length > 5 || !extension.all { it in 'a'..'z' || it in '0'..'9' }) {
            return null
        }
        return "$index.$extension"
    }

    /**
     * Keeps the media scanner from indexing logged attachments, which otherwise show up in the gallery
     * and Discord's attachment picker. Covers every subfolder of [root], and is recreated after a clear.
     */
    private fun hideFromGallery() {
        val marker = File(root, NO_MEDIA)
        if (marker.exists()) return
        try {
            marker.createNewFile()
        } catch (error: Exception) {
            reportError("create $NO_MEDIA", error)
        }
    }

    /**
     * One-time migration for media saved before [NO_MEDIA] existed: those files are already indexed.
     * Rescanning a file that now sits under a .nomedia folder makes the media scanner drop it.
     */
    private fun removeFromGallery() {
        if (!File(root, NO_MEDIA).exists()) return
        val paths = root.walkTopDown()
            .filter { it.isFile && it.name != NO_MEDIA && !it.name.endsWith(PART) }
            .map { it.absolutePath }
            .toList()
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(Utils.appContext, paths.toTypedArray(), null, null)
    }

    private fun thumbnail(file: File) = File(file.parentFile, file.nameWithoutExtension + ".thumb.jpg")

    private fun save(attachment: MessageAttachment, target: File, limit: Long) {
        download(attachment, target, limit)
        if (attachment.e() == MessageAttachmentType.VIDEO) createThumbnail(target)
    }

    private fun download(attachment: MessageAttachment, target: File, limit: Long) {
        if (target.isFile) return
        if (attachment.d() > limit) return
        val directory = requireNotNull(target.parentFile)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create media folder" }
        hideFromGallery()
        val sources = listOfNotNull(attachment.f(), attachment.c()).distinct()
        var failure: Exception? = null
        for (source in sources) {
            try {
                fetch(source, target, limit)
                return
            } catch (error: Exception) {
                failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun fetch(source: String, target: File, limit: Long) {
        val connection = URL(source).openConnection() as HttpURLConnection
        val partial = File(target.parentFile, target.name + PART)
        try {
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        check(total <= limit) { "Attachment is too large" }
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(partial.renameTo(target)) { "Could not save attachment" }
        } finally {
            partial.delete()
            connection.disconnect()
        }
    }

    private fun createThumbnail(video: File) {
        val output = thumbnail(video)
        if (output.isFile || !video.isFile) return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val frame = retriever.getFrameAtTime(0) ?: return
            val partial = File(output.parentFile, output.name + PART)
            try {
                FileOutputStream(partial).use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                check(partial.renameTo(output)) { "Could not save video thumbnail" }
            } finally {
                partial.delete()
                frame.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    @Synchronized
    private fun enqueue(target: ExecutorService, action: String, work: () -> Unit) {
        if (stopped) return
        target.execute {
            try {
                work()
            } catch (error: Exception) {
                reportError(action, error)
            }
        }
    }

    private fun executor(name: String) = Executors.newSingleThreadExecutor { task ->
        Thread(task, name).apply { isDaemon = true }
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val PENDING_DIR = ".pending"
        private const val SESSION_DIR = ".session"
        private const val PART = ".part"
        private const val NO_MEDIA = ".nomedia"
        private const val REMOTE_INDEX = "remote_index.json"
        private const val MAX_BYTES = 100L * 1024 * 1024
        private const val MAX_PREFETCH_BYTES = 25L * 1024 * 1024
        private const val MAX_PENDING_BYTES = 256L * 1024 * 1024
        private const val MAX_PREFETCHED_IDS = 1024
        private const val TIMEOUT = 30_000

        private val urlField = attachmentField("url")
        private val proxyUrlField = attachmentField("proxyUrl")

        private fun attachmentField(name: String) =
            runCatching { MessageAttachment::class.java.getDeclaredField(name).apply { isAccessible = true } }.getOrNull()
    }
}
