package com.github.adham1223990.bettermessagelogger

import com.aliucord.api.SettingsAPI
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Cloud account settings of one user. [values] follow [RemoteStorage.hints] of the provider. */
internal class RemoteConfig(val provider: String, val values: List<String>)

/** Optional per-user cloud storage for deleted media, so nothing has to be kept on the device. */
internal object RemoteStorage {
    const val CLOUDINARY = "cloudinary"
    const val SUPABASE = "supabase"
    const val PROVIDER = "remoteProvider"
    private const val TIMEOUT = 30_000

    fun key(provider: String, index: Int) = "remote_${provider}_$index"

    fun label(provider: String) = if (provider == SUPABASE) "Supabase Storage" else "Cloudinary"

    fun next(provider: String) = if (provider == CLOUDINARY) SUPABASE else CLOUDINARY

    fun hints(provider: String) =
        if (provider == SUPABASE) {
            listOf("Project URL (https://xxxx.supabase.co)", "Public bucket name", "API key (anon or service_role)")
        } else {
            listOf("Cloud name", "Unsigned upload preset")
        }

    /** Returns null (media stays local) unless a provider is chosen and every field is filled. */
    fun config(settings: SettingsAPI): RemoteConfig? {
        val provider = settings.getString(PROVIDER, "")
        if (provider != CLOUDINARY && provider != SUPABASE) return null
        val values = hints(provider).indices.map { settings.getString(key(provider, it), "").trim() }
        return if (values.any { it.isEmpty() }) null else RemoteConfig(provider, values)
    }

    /** Streams [input] to the cloud account and returns the public URL. Nothing is written to disk. */
    fun upload(config: RemoteConfig, id: Long, name: String, input: InputStream, mime: String): String =
        if (config.provider == SUPABASE) supabase(config.values, id, name, input, mime)
        else cloudinary(config.values, id, name, input, mime)

    private fun cloudinary(values: List<String>, id: Long, name: String, input: InputStream, mime: String): String {
        val boundary = "BML" + System.currentTimeMillis()
        val connection = URL("https://api.cloudinary.com/v1_1/${values[0]}/auto/upload").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT * 4
            connection.setChunkedStreamingMode(0)
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.outputStream.use { out ->
                fun field(key: String, value: String) =
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$key\"\r\n\r\n$value\r\n".toByteArray())
                field("upload_preset", values[1])
                field("public_id", "bml_${id}_${name.substringBeforeLast('.')}")
                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\n" +
                        "Content-Type: $mime\r\n\r\n").toByteArray(),
                )
                input.copyTo(out)
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }
            val body = response(connection, "Cloudinary")
            return JSONObject(body).getString("secure_url")
        } finally {
            connection.disconnect()
        }
    }

    private fun supabase(values: List<String>, id: Long, name: String, input: InputStream, mime: String): String {
        val base = values[0].trimEnd('/')
        val path = "${values[1]}/$id/$name"
        val connection = URL("$base/storage/v1/object/$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.connectTimeout = TIMEOUT
            connection.readTimeout = TIMEOUT * 4
            connection.setChunkedStreamingMode(0)
            connection.setRequestProperty("Authorization", "Bearer ${values[2]}")
            connection.setRequestProperty("apikey", values[2])
            connection.setRequestProperty("Content-Type", mime)
            connection.setRequestProperty("x-upsert", "true")
            connection.outputStream.use { input.copyTo(it) }
            response(connection, "Supabase")
            return "$base/storage/v1/object/public/$path"
        } finally {
            connection.disconnect()
        }
    }

    private fun response(connection: HttpURLConnection, service: String): String {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        check(code in 200..299) { "$service HTTP $code: ${body.take(200)}" }
        return body
    }
}

/**
 * Remembers which attachment of which message lives at which cloud URL. The links are stored in the
 * database once [attach] is called; the optional legacy JSON [file] of older versions is imported once.
 */
internal class RemoteIndex(private val file: File) {
    private var map: HashMap<Long, HashMap<String, String>>? = null
    private var sink: MessageLoggerDatabase? = null

    /** Merges links loaded from [db], imports the legacy file into it and keeps saving new links there. */
    @Synchronized
    fun attach(db: MessageLoggerDatabase, links: Map<Long, Map<String, String>>) {
        val current = load()
        links.forEach { (id, names) -> current.getOrPut(id) { HashMap() }.putAll(names) }
        sink = db
        if (file.isFile) {
            current.forEach { (id, names) -> names.forEach { (name, url) -> db.putLinkAsync(id, name, url) } }
            file.delete()
        }
    }

    @Synchronized
    private fun load(): HashMap<Long, HashMap<String, String>> {
        map?.let { return it }
        val result = HashMap<Long, HashMap<String, String>>()
        try {
            if (file.isFile) {
                val json = JSONObject(file.readText())
                json.keys().forEach { id ->
                    val inner = json.getJSONObject(id)
                    val names = HashMap<String, String>()
                    inner.keys().forEach { names[it] = inner.getString(it) }
                    id.toLongOrNull()?.let { result[it] = names }
                }
            }
        } catch (_: Exception) {
            // A damaged index only means media is uploaded again.
        }
        map = result
        return result
    }

    @Synchronized
    fun get(id: Long, name: String): String? = load()[id]?.get(name)

    @Synchronized
    fun has(id: Long) = load()[id]?.isNotEmpty() == true

    @Synchronized
    fun has(id: Long, name: String) = get(id, name) != null

    @Synchronized
    fun put(id: Long, name: String, url: String) {
        load().getOrPut(id) { HashMap() }[name] = url
        sink?.putLinkAsync(id, name, url)
    }

    @Synchronized
    fun remove(id: Long) {
        load().remove(id)
    }

    @Synchronized
    fun retain(keep: (Long) -> Boolean) {
        load().keys.removeAll { !keep(it) }
    }

    @Synchronized
    fun clear() {
        map = HashMap()
    }
}
