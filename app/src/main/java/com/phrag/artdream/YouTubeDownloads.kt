package com.phrag.artdream

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Saves a YouTube video to "My media" so it plays offline. One download at a time, in the
 * background. NewPipeExtractor finds the stream URLs (YouTube changes this often, so expect
 * updates); the rest is plain HTTP, MediaMuxer and [OwnMedia].
 *
 * Downloading from YouTube is against its terms of service unless YouTube offers it; that is
 * the user's call and is said so where the button is offered.
 */
object YouTubeDownloads {

    enum class State { QUEUED, LOOKING_UP, DOWNLOADING, JOINING, DONE, FAILED }

    class Job(val id: String) {
        @Volatile var title = ""
        @Volatile var state = State.QUEUED
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var error: String? = null
        val active: Boolean get() = state != State.DONE && state != State.FAILED
        val percent: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0

        /** One line for the TV and the web page. */
        fun describe(): String = when (state) {
            State.QUEUED -> "Waiting…"
            State.LOOKING_UP -> "Looking up the video…"
            State.DOWNLOADING -> "Downloading ${title.ifBlank { id }}… $percent%"
            State.JOINING -> "Finishing ${title.ifBlank { id }}…"
            State.DONE -> "Saved ${title.ifBlank { id }}"
            State.FAILED -> "Couldn't save ${title.ifBlank { id }}: ${error ?: "unknown error"}"
        }
    }

    private val jobs = LinkedHashMap<String, Job>()
    private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "fartsaver-yt-download").apply { isDaemon = true } }
    private var initialised = false

    fun jobs(): List<Job> = synchronized(jobs) { jobs.values.toList() }

    /** Starts (or returns the running) download of [id]. [onChange] is called from the worker thread. */
    fun submit(own: OwnMedia, id: String, maxHeight: Int = 1080, onChange: () -> Unit): Job = synchronized(jobs) {
        jobs[id]?.takeIf { it.active }?.let { return it }
        val job = Job(id)
        jobs.remove(id)
        jobs[id] = job
        while (jobs.size > KEEP_JOBS) {
            val oldest = jobs.keys.firstOrNull { !jobs.getValue(it).active } ?: break   // only finished ones go
            jobs.remove(oldest)
        }
        pool.execute { run(job, own, maxHeight, onChange) }
        job
    }

    private fun run(job: Job, own: OwnMedia, maxHeight: Int, onChange: () -> Unit) {
        val parts = ArrayList<File>()
        try {
            job.state = State.LOOKING_UP; onChange()
            // A cheap first look that tells "no such video" apart from "the extractor broke". Videos
            // with embedding switched off answer 401/403 here and can still be downloaded.
            try {
                job.title = YouTube.oEmbed(job.id).title
                onChange()
            } catch (e: HttpStatusException) {
                if (e.code == 404 || e.code == 400) throw IOException("that video wasn't found (removed, or the link is wrong)")
            } catch (_: IOException) {
                // No answer from oEmbed; the extractor will say if the network is really down.
            }
            init()
            val info = StreamInfo.getInfo(ServiceList.YouTube, YouTube.watchUrl(job.id))
            job.title = info.name.orEmpty()
            onChange()

            // What YouTube offered, so a surprising choice can be explained from the log.
            fun summary(s: VideoStream) = "${s.height}p${s.fps} ${s.codec} ${s.format?.suffix} ${s.deliveryMethod}"
            Log.i(TAG, "youtube ${job.id} video-only: " + info.videoOnlyStreams.joinToString { summary(it) })
            Log.i(TAG, "youtube ${job.id} muxed: " + info.videoStreams.joinToString { summary(it) })
            Log.i(TAG, "youtube ${job.id} audio: " + info.audioStreams.joinToString { "${it.averageBitrate}k ${it.codec} ${it.format?.suffix}" })

            val choice = StreamPicker.choose(
                videoOnly = info.videoOnlyStreams.map(::describe),
                muxed = info.videoStreams.map(::describe),
                audio = info.audioStreams.map(::describe),
                maxHeight = maxHeight,
            ) ?: throw IOException("no H.264 MP4 stream was offered (only formats this TV may not play)")
            val video = choice.video.ref as VideoStream
            val audio = choice.audio?.ref as AudioStream?

            val vUrl = video.content
            val aUrl = audio?.content
            val vSize = size(vUrl, video.itagItem?.contentLength ?: -1)
            val aSize = if (audio != null) size(audio.content, audio.itagItem?.contentLength ?: -1) else 0L
            job.total = vSize + aSize
            if (job.total > OwnMedia.MAX_FILE_BYTES) throw IOException("it's ${job.total / (1024 * 1024)} MB, over the ${OwnMedia.MAX_FILE_BYTES / (1024 * 1024 * 1024)} GB limit")
            if (!own.hasRoomFor(job.total)) throw IOException("not enough room left on the TV")

            job.state = State.DOWNLOADING; onChange()
            val vPart = own.newPart().also { parts += it }
            fetch(vUrl, vPart, vSize, job, 0L, onChange)
            val result: File
            if (aUrl != null) {
                val aPart = own.newPart().also { parts += it }
                fetch(aUrl, aPart, aSize, job, vSize, onChange)
                job.state = State.JOINING; onChange()
                result = own.newPart().also { parts += it }
                Remux.join(vPart, aPart, result)
            } else {
                result = vPart
            }
            val name = OwnMedia.sanitizeName(job.title.ifBlank { "YouTube ${job.id}" } + ".mp4") ?: "YouTube ${job.id}.mp4"
            own.add(result, name)
            job.state = State.DONE
        } catch (e: Exception) {
            Log.w(TAG, "youtube download ${job.id}: $e")
            job.error = friendly(e)
            job.state = State.FAILED
        } finally {
            parts.forEach { it.delete() }
            onChange()
        }
    }

    @Synchronized private fun init() {
        if (initialised) return
        NewPipe.init(NewPipeDownloader(), Localization.DEFAULT)
        initialised = true
    }

    private fun describe(s: VideoStream) = StreamPicker.Video(
        ref = s,
        height = s.height,
        fps = s.fps,
        bitrate = s.bitrate,
        codec = s.codec.orEmpty(),
        mp4 = s.format?.suffix.equals("mp4", ignoreCase = true),
        progressive = s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.isUrl,
        hasAudio = !s.isVideoOnly,
    )

    private fun describe(s: AudioStream) = StreamPicker.Audio(
        ref = s,
        bitrate = s.averageBitrate.takeIf { it > 0 } ?: s.bitrate,
        codec = s.codec.orEmpty(),
        m4a = s.format?.suffix.equals("m4a", ignoreCase = true),
        progressive = s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.isUrl,
    )

    /** The stream's length: from the extractor if it knows, else asked of the server. */
    private fun size(url: String, known: Long): Long {
        if (known > 0) return known
        val c = open(url, "bytes=0-0")
        try {
            val range = c.getHeaderField("Content-Range")   // "bytes 0-0/12345"
            return range?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong.takeIf { it > 0 } ?: -1L
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads [url] to [dest] in 4 MB ranged requests (YouTube slows plain full-file
     * downloads down). A chunk that fails is retried; the file only ever grows by whole chunks.
     */
    private fun fetch(url: String, dest: File, expected: Long, job: Job, base: Long, onChange: () -> Unit) {
        var pos = 0L
        var lastTell = 0L
        dest.outputStream().buffered(256 * 1024).use { out ->
            while (expected <= 0 || pos < expected) {
                if (Thread.currentThread().isInterrupted) throw IOException("stopped")
                val end = if (expected > 0) minOf(pos + CHUNK - 1, expected - 1) else -1L
                val chunk = retrying { readChunk(url, pos, end) }
                if (chunk.isEmpty()) break
                out.write(chunk)
                pos += chunk.size
                job.done = base + pos
                val now = System.currentTimeMillis()
                if (now - lastTell > 500) { lastTell = now; onChange() }
                if (expected <= 0) break   // unknown length: the one request read it all
            }
        }
        if (expected > 0 && pos != expected) throw IOException("download ended early ($pos of $expected bytes)")
    }

    private fun readChunk(url: String, from: Long, to: Long): ByteArray {
        val c = open(url, if (to >= 0) "bytes=$from-$to" else "bytes=$from-")
        try {
            val code = c.responseCode
            if (code != 206 && !(code == 200 && from == 0L)) throw HttpStatusException(code, null, "video stream")
            val out = ByteArrayOutputStream(if (to >= 0) (to - from + 1).toInt() else 1 shl 20)
            c.inputStream.use { it.copyTo(out) }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    private fun <T> retrying(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                if (++attempt >= 3 || (e as? HttpStatusException)?.code in 400..499) throw e
                Thread.sleep(1000L * attempt)
            }
        }
    }

    private fun open(url: String, range: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", NewPipeDownloader.USER_AGENT)
            setRequestProperty("Range", range)
        }

    /** What a person can act on, instead of an exception's class name. */
    private fun friendly(e: Exception): String = when (e.javaClass.simpleName) {
        "AgeRestrictedContentException" -> "it's age-restricted"
        "PrivateContentException" -> "it's private"
        "GeographicRestrictionException" -> "it isn't available in this country"
        "PaidContentException", "YoutubeMusicPremiumContentException" -> "it needs a paid subscription"
        "SignInConfirmNotBotException" -> "YouTube wants a sign-in to confirm it's not a bot (try again later, or use your computer)"
        "ReCaptchaException" -> "YouTube is asking for a captcha (try again later, or use your computer)"
        "ContentNotAvailableException" -> e.message?.take(120) ?: "it isn't available"
        "ParsingException", "ExtractionException", "JsonParserException" ->
            "YouTube's answer couldn't be understood; the download library may need an update"
        else -> when (e) {
            is HttpStatusException -> "YouTube refused the download (HTTP ${e.code}); the extractor may need an update"
            is java.net.UnknownHostException -> "no internet connection"
            is java.net.SocketTimeoutException -> "timed out"
            is OwnMedia.Rejected -> e.message ?: "the TV couldn't use the file"
            else -> (e.message ?: e.javaClass.simpleName).take(160)
        }
    }

    private const val TAG = "fArtSaver"
    private const val CHUNK = 4L * 1024 * 1024
    private const val KEEP_JOBS = 8
}
