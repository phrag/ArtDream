package com.phrag.artdream

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The parts of an HTTP request this server looks at. Pure, so it can be unit-tested. */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
) {
    /** -1 when absent or not a number. */
    val contentLength: Long get() = headers["content-length"]?.trim()?.toLongOrNull() ?: -1L

    companion object {
        const val MAX_HEAD = 8 * 1024

        /** "GET /a/b?x=1 HTTP/1.1" plus header lines, without the blank line; null if malformed. */
        fun parse(head: String): HttpRequest? {
            val lines = head.split("\r\n")
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
            val target = parts[1]
            if (!target.startsWith("/")) return null
            val path = target.substringBefore('?')
            val query = LinkedHashMap<String, String>()
            target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach { kv ->
                val k = kv.substringBefore('=')
                val v = kv.substringAfter('=', "")
                try {
                    query[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
                } catch (_: IllegalArgumentException) {
                    return null   // broken %-escape
                }
            }
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            return HttpRequest(parts[0].uppercase(), path, query, headers)
        }

        /** Reads up to the blank line that ends the headers; null on EOF or an oversized head. */
        fun readHead(input: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                sb.append(b.toChar())
                if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) return sb.substring(0, sb.length - 4)
            }
            return null
        }
    }
}

/**
 * A tiny HTTP server so a phone or computer on the same Wi-Fi can add pictures and videos from
 * a browser. Runs only while the "My media" screen is open. Every URL starts with a random
 * token that is shown on the TV, and only private-network peers are served. No dependencies.
 */
class UploadServer(
    private val own: OwnMedia,
    private val events: Events,
    /** Part of every URL. The caller keeps one for as long as the screen lives, so a restart doesn't change the address. */
    val token: String = newToken(),
) {

    /** Called on server threads; post to the main thread before touching views. */
    interface Events {
        fun onUploaded(name: String) {}
        fun onRejected(name: String, reason: String) {}
        fun onChanged() {}
        /** A YouTube download started, moved on or finished. */
        fun onJobs() {}
    }

    @Volatile var port = 0; private set

    private var socket: ServerSocket? = null
    private val pool = ThreadPoolExecutor(
        4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue(8),
        ThreadFactory { r -> Thread(r, "artdream-upload-conn").apply { isDaemon = true } },
    )
    @Volatile private var badTokens = 0
    @Volatile private var lockedUntil = 0L

    @Synchronized fun start(): Boolean {
        if (socket != null) return true
        val s = PORTS.firstNotNullOfOrNull { p ->
            try { ServerSocket(p).also { it.reuseAddress = true } } catch (_: IOException) { null }
        } ?: return false
        socket = s
        port = s.localPort
        Thread({
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: IOException) { break }
                if (!isPrivatePeer(c.inetAddress)) { runCatching { c.close() }; continue }
                try {
                    pool.execute { handle(c) }
                } catch (_: RejectedExecutionException) {
                    runCatching { c.close() }
                }
            }
        }, "artdream-upload").apply { isDaemon = true }.start()
        Log.i(TAG, "upload server on :$port")
        return true
    }

    @Synchronized fun stop() {
        runCatching { socket?.close() }
        socket = null
        port = 0
        own.clearStaleParts()
    }

    /** What to type on the other device, one per usable network address. */
    fun urls(): List<String> = lanAddresses().map { "http://$it:$port/$token/" }

    // ---- one connection -------------------------------------------------------------------

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 20_000
            val input = BufferedInputStream(c.getInputStream())
            val out = c.getOutputStream()
            val head = HttpRequest.readHead(input) ?: return reply(out, 400, "text/plain", "Bad request")
            val req = HttpRequest.parse(head) ?: return reply(out, 400, "text/plain", "Bad request")
            route(req, input, out)
        } catch (e: Exception) {
            Log.w(TAG, "upload connection: $e")
        } finally {
            runCatching { c.close() }
        }
    }

    private fun route(req: HttpRequest, input: InputStream, out: OutputStream) {
        val now = System.currentTimeMillis()
        if (now < lockedUntil) return reply(out, 503, "text/plain", "Try again in a minute")
        val segs = req.path.trim('/').split('/')
        if (segs.firstOrNull().isNullOrEmpty() || !tokenMatches(segs[0])) {
            // Guessing the token: after 30 misses, stop answering for a minute.
            if (++badTokens >= 30) { badTokens = 0; lockedUntil = now + 60_000 }
            return reply(out, 404, "text/plain", "Not found")
        }
        val rest = segs.drop(1).joinToString("/")
        when {
            req.method == "GET" && rest.isEmpty() && !req.path.endsWith("/") ->
                reply(out, 301, "text/plain", "", "Location: ${req.path}/")
            req.method == "GET" && rest.isEmpty() -> reply(out, 200, "text/html; charset=utf-8", UploadPage.HTML)
            req.method == "GET" && rest == "list" -> reply(out, 200, JSON, listJson().toString())
            req.method == "PUT" && rest == "upload" -> upload(req, input, out)
            req.method == "POST" && rest == "youtube" -> youtube(req, out)
            req.method == "POST" && rest == "delete" -> {
                val ok = req.query["key"]?.let { own.remove(it) } ?: false
                if (ok) events.onChanged()
                reply(out, if (ok) 200 else 404, JSON, JSONObject().put("ok", ok).toString())
            }
            else -> reply(out, 404, "text/plain", "Not found")
        }
    }

    private fun upload(req: HttpRequest, input: InputStream, out: OutputStream) {
        val raw = req.query["name"].orEmpty()
        val name = OwnMedia.sanitizeName(raw)
            ?.takeIf { OwnMedia.kindOf(it) != OwnMedia.Kind.YOUTUBE }   // links go through the link box
            ?: return fail(out, 415, raw, "Not a picture or video file (jpg, png, webp, gif, mp4, mkv, webm, mov)")
        val len = req.contentLength
        if (len < 0) return fail(out, 411, name, "No file size sent")
        if (len == 0L) return fail(out, 400, name, "Empty file")
        if (len > OwnMedia.MAX_FILE_BYTES) return fail(out, 413, name, "Larger than ${OwnMedia.MAX_FILE_BYTES / (1024 * 1024)} MB")
        if (!own.hasRoomFor(len)) return fail(out, 507, name, "Not enough room left for your own media")
        if (req.headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); out.flush()
        }
        val part = own.newPart()
        try {
            if (!receive(input, len, part)) return fail(out, 400, name, "Upload was cut short")
            val item = own.add(part, name)
            events.onUploaded(item.file.name)
            reply(out, 200, JSON, JSONObject().put("ok", true).put("name", item.file.name).toString())
        } catch (e: OwnMedia.Rejected) {
            fail(out, 422, name, e.message ?: "Rejected")
        } finally {
            part.delete()   // gone already after a successful add; this only cleans up failures
        }
    }

    /** POST /<token>/youtube?url=…  — adds a YouTube link that streams in the screensaver. */
    private fun youtube(req: HttpRequest, out: OutputStream) {
        val raw = req.query["url"].orEmpty()
        val id = YouTube.parseVideoId(raw)
            ?: return fail(out, 400, raw.take(60), "That doesn't look like a YouTube video link")
        if (req.query["mode"] == "download") {
            // Runs in the background; the page and the TV screen follow it through the job list.
            val job = YouTubeDownloads.submit(own, id) { events.onJobs() }
            return reply(out, 200, JSON, JSONObject().put("ok", true).put("name", job.title.ifBlank { id }).put("mode", "download").toString())
        }
        val link = try {
            YouTube.oEmbed(id)
        } catch (e: HttpStatusException) {
            if (e.code in 400..499) {
                return fail(out, 422, id, "YouTube says this video is private, removed or can't be embedded")
            }
            YouTube.Link(id, "", "")   // YouTube having a bad moment; the link may still play later
        } catch (e: IOException) {
            YouTube.Link(id, "", "")   // couldn't reach YouTube to get a title; still add the link
        }
        try {
            val item = own.addLink(link)
            events.onUploaded(item.label)
            reply(out, 200, JSON, JSONObject().put("ok", true).put("name", item.label).put("mode", "stream").toString())
        } catch (e: OwnMedia.Rejected) {
            fail(out, 422, id, e.message ?: "Rejected")
        }
    }

    private fun receive(input: InputStream, len: Long, part: File): Boolean {
        part.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return false
                o.write(buf, 0, n)
                left -= n
            }
        }
        return true
    }

    private fun fail(out: OutputStream, code: Int, name: String, reason: String) {
        events.onRejected(name, reason)
        reply(out, code, JSON, JSONObject().put("ok", false).put("error", reason).toString())
    }

    private fun listJson(): JSONObject {
        val items = JSONArray()
        own.items().forEach {
            items.put(JSONObject().put("key", it.key).put("video", it.video).put("youtube", it.youtube).put("bytes", it.bytes))
        }
        val jobs = JSONArray()
        YouTubeDownloads.jobs().forEach {
            jobs.put(JSONObject().put("id", it.id).put("active", it.active).put("failed", it.state == YouTubeDownloads.State.FAILED).put("text", it.describe()))
        }
        return JSONObject().put("items", items).put("jobs", jobs).put("canDownload", true)
            .put("usedBytes", own.usedBytes()).put("quotaBytes", OwnMedia.QUOTA_BYTES)
    }

    private fun tokenMatches(given: String): Boolean =
        MessageDigest.isEqual(given.toByteArray(), token.toByteArray())

    private fun reply(out: OutputStream, code: Int, type: String, body: String, extra: String? = null) {
        val bytes = body.toByteArray()
        val reason = when (code) {
            200 -> "OK"; 301 -> "Moved Permanently"; 400 -> "Bad Request"; 404 -> "Not Found"
            411 -> "Length Required"; 413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"
            422 -> "Unprocessable Entity"; 503 -> "Service Unavailable"; 507 -> "Insufficient Storage"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n")
            append("Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n")
            if (type.startsWith("text/html")) append("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'\r\n")
            if (extra != null) append(extra).append("\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray()); out.write(bytes); out.flush()
    }

    companion object {
        private const val TAG = "ArtDream"
        private const val JSON = "application/json"
        private val PORTS = 8765..8774
        private const val TOKEN_CHARS = "abcdefghjkmnpqrstuvwxyz23456789"   // no look-alikes (l, 1, o, 0, i)

        fun newToken(length: Int = 8): String {
            val r = SecureRandom()
            return buildString { repeat(length) { append(TOKEN_CHARS[r.nextInt(TOKEN_CHARS.length)]) } }
        }

        /** Home / office networks only: private IPv4, link-local, loopback, IPv6 unique-local. */
        fun isPrivatePeer(a: InetAddress): Boolean = when {
            a.isLoopbackAddress || a.isLinkLocalAddress -> true
            a is Inet4Address -> a.isSiteLocalAddress
            a is Inet6Address -> (a.address[0].toInt() and 0xFE) == 0xFC
            else -> false
        }

        fun lanAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
