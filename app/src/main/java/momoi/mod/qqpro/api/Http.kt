package momoi.mod.qqpro.api

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import kotlin.concurrent.thread

object Http {
    // Link previews only need the document <head>; cap the body we pull into memory so a huge or
    // unbounded response can't OOM the watch. Previously readText() read the whole body into a
    // String — a ~169MB link blew up with OutOfMemoryError.
    const val DEFAULT_MAX_BYTES = 1 shl 20 // 1 MiB

    inline fun get(
        url: String,
        charset: String = "UTF-8",
        maxBytes: Int = DEFAULT_MAX_BYTES,
        crossinline callback: (String) -> Unit
    ) {
        thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                with(connection) {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // Skip obviously non-text bodies (images, video, archives, …): parsing them as
                    // HTML yields nothing and reading them just wastes memory/bandwidth.
                    val type = connection.contentType?.substringBefore(';')?.trim()?.lowercase()
                    if (type != null && !type.startsWith("text/") &&
                        type != "application/xhtml+xml" && type != "application/xml"
                    ) {
                        runCatching { callback("Error: unsupported content type $type") }
                        return@thread
                    }
                    // Read at most maxBytes. HttpURLConnection transparently gunzips, so this bounds
                    // the *decompressed* size that lands in memory.
                    val input = connection.inputStream
                    val buffer = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (total < maxBytes) {
                        val n = input.read(chunk, 0, minOf(chunk.size, maxBytes - total))
                        if (n < 0) break
                        buffer.write(chunk, 0, n)
                        total += n
                    }
                    val response = String(buffer.toByteArray(), Charset.forName(charset))
                    callback(response)
                } else {
                    runCatching {
                        callback("HTTP error: $responseCode")
                    }
                }
            } catch (e: Exception) {
                runCatching {
                    callback("Error: ${e.message ?: "Unknown error"}")
                }
            } finally {
                connection?.disconnect()
            }
        }
    }
}
