package com.phrag.artdream

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A non-2xx answer. [retryAfter] is the raw Retry-After header (seconds or an
 * HTTP-date), if the server sent one; see [Backoff]. The message keeps the
 * "HTTP <code>" form that [Diagnostics.shortError] and the retry logic read.
 */
class HttpStatusException(val code: Int, val retryAfter: String?, url: String) : IOException("HTTP $code for $url")

/** Tiny blocking HTTP helper. Call off the main thread. */
object Http {
    // Museums ask API clients to identify themselves; AIC reads its own header.
    private const val UA = "fArtSaver/0.1 (Android TV screensaver)"

    /**
     * Hard ceiling for one image. Far above any real one (NASA originals, the
     * largest we fetch, run to about 40 MB), so it only ever stops a broken or
     * hostile server from filling the disk.
     */
    const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024

    fun getString(url: String): String {
        val c = open(url)
        try {
            check(c, url)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Save an image to [dest]. Refuses non-image Content-Types and anything over
     * [MAX_DOWNLOAD_BYTES]; on any failure [dest] is deleted, never left partial.
     */
    fun download(url: String, dest: File) {
        val c = open(url)
        try {
            check(c, url)
            val type = c.contentType
            if (type != null && !type.trim().startsWith("image/", ignoreCase = true)) {
                throw IOException("not an image ($type)")
            }
            if (c.contentLengthLong > MAX_DOWNLOAD_BYTES) throw IOException("image too large (${c.contentLengthLong} bytes)")
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) throw IOException("image too large (over $MAX_DOWNLOAD_BYTES bytes)")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun check(c: HttpURLConnection, url: String) {
        val code = c.responseCode
        if (code !in 200..299) throw HttpStatusException(code, c.getHeaderField("Retry-After"), url)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("AIC-User-Agent", UA)
        }
}
