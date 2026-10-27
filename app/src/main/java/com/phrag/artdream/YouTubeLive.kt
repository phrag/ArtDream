package com.phrag.artdream

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Stream URLs for playing a YouTube link in the app's own player instead of YouTube's web
 * player. The web player in a WebView never gets HDR (a WebView draws as plain SDR app content,
 * and YouTube only offers HDR to pages on an HDR screen); played on a SurfaceView by Media3, the
 * TV's own decoders show YouTube's HDR10/HLG streams as HDR. YouTube has no Dolby Vision streams:
 * a Dolby Vision upload plays as its HDR10 or HLG version.
 */
object YouTubeLive {

    private const val TAG = "fArtSaver"
    private const val TRYING = "trying anyway (experiment):"

    /** What to play: one video-only stream, plus an audio stream when there is one. */
    class Streams(
        val videoUrl: String,
        val audioUrl: String?,
        val width: Int,
        val height: Int,
        val hdr: Boolean,
        val durationMs: Long,
        val summary: String,
        /** What YouTube lists for the video stream (0 when it doesn't say), for the info overlay. */
        val fps: Int = 0,
        val bitrate: Int = 0,
        val codec: String = "",
        /**
         * An HDR stream tried on a hardware decoder that doesn't list a 10-bit profile (the
         * SHIELD's VP9 decoder lists only Profile 0); [sdr] is what to play if that fails.
         */
        val experimental: Boolean = false,
        val sdr: Streams? = null,
    )

    /**
     * Looks the video up and picks streams for a screen [screenH] pixels tall (0 = no limit).
     * Blocking (a few seconds of network); call off the main thread. Null when the extractor
     * fails or nothing offered decodes here, and the caller falls back to the web player.
     */
    fun resolve(id: String, screenH: Int, hdrScreen: Boolean): Streams? = try {
        YouTubeDownloads.init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTube.watchUrl(id))
        val experimental = HashSet<VideoStream>()
        fun summary(s: VideoStream) = "${s.height}p${s.fps} ${s.codec}${if (StreamPicker.isHdr(s.codec.orEmpty())) " HDR" else ""}"
        Log.i(TAG, "youtube $id native, offered: " + info.videoOnlyStreams.joinToString { summary(it) })
        logDecoders("video/x-vnd.on2.vp9")
        logDecoders("video/av01")
        val maxHeight = if (screenH > 0) maxOf(screenH, 1080) else Int.MAX_VALUE
        val choice = StreamPicker.chooseLive(
            videoOnly = info.videoOnlyStreams.map(::describe),
            audio = info.audioStreams.map(::describe),
            maxHeight = maxHeight,
            hdrScreen = hdrScreen,
            canPlay = {
                val hdr = StreamPicker.isHdr(it.codec)
                val (ok, why) = canDecode(it.codec, (it.ref as VideoStream).width, it.height, it.fps, hdr, hdrScreen)
                if (hdr) Log.i(TAG, "youtube $id native, HDR candidate ${it.height}p${it.fps} ${it.codec}: " +
                    (if (ok) "accepted, " else "rejected, ") + why)
                if (ok && why.startsWith(TRYING)) experimental += it.ref as VideoStream
                ok
            },
        )
        fun streams(choice: StreamPicker.Choice, sdr: Streams?, label: String): Streams {
            val v = choice.video.ref as VideoStream
            val a = choice.audio?.ref as AudioStream?
            val picked = summary(v) + " [" + StreamPicker.describeCodec(v.codec.orEmpty()) + "]" +
                " hdrScreen=$hdrScreen" + if (v in experimental) " (HDR on a decoder that doesn't list it: experiment)" else ""
            Log.i(TAG, "youtube $id native, $label: $picked" + (a?.let { " + ${it.averageBitrate}k ${it.codec}" } ?: ""))
            return Streams(v.content, a?.content, v.width, v.height, StreamPicker.isHdr(v.codec.orEmpty()),
                info.duration.coerceAtLeast(0) * 1000, picked, v.fps, v.bitrate, v.codec.orEmpty(),
                experimental = v in experimental, sdr = sdr)
        }
        if (choice == null) {
            Log.w(TAG, "youtube $id native: nothing offered decodes here")
            null
        } else if ((choice.video.ref as VideoStream) in experimental) {
            // The fallback if the experiment fails: the best SDR stream the decoders do list.
            val sdrChoice = StreamPicker.chooseLive(
                videoOnly = info.videoOnlyStreams.map(::describe).filter { !StreamPicker.isHdr(it.codec) },
                audio = info.audioStreams.map(::describe),
                maxHeight = maxHeight,
                hdrScreen = false,
                canPlay = { canDecode(it.codec, (it.ref as VideoStream).width, it.height, it.fps, false, hdrScreen).first },
            )
            streams(choice, sdrChoice?.let { streams(it, null, "SDR fallback") }, "playing")
        } else {
            streams(choice, null, "playing")
        }
    } catch (e: Exception) {
        Log.w(TAG, "youtube $id native: $e")
        null
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

    private val codecs by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }

    /**
     * Whether a hardware decoder here plays [codec] at this size and rate, and the reason, for the
     * log. An HDR stream also needs the decoder to take 10-bit HDR: either it lists an HDR profile
     * (VP9Profile2HDR, AV1ProfileMain10HDR10, …) or, on an HDR screen, the plain 10-bit profile
     * (VP9 Profile 2/3, AV1 Main 10). The SHIELD's VP9 decoder lists only plain Profile 2, yet
     * HDR10 metadata travels in the stream itself and reaches the display either way.
     *
     * Experiment: on the TV the SHIELD's hardware VP9 decoder listed only Profile 0, so on an HDR
     * screen a VP9 HDR stream is still accepted when a hardware VP9 decoder takes its size and
     * rate (reason starting with [TRYING]); the player falls back to SDR if it fails.
     */
    fun canDecode(codec: String, width: Int, height: Int, fps: Int, hdr: Boolean, hdrScreen: Boolean): Pair<Boolean, String> {
        val c = codec.lowercase()
        val mime = when {
            c.startsWith("avc1") -> "video/avc"
            c.startsWith("vp09") || c == "vp9" || c.startsWith("vp9.") -> "video/x-vnd.on2.vp9"
            c.startsWith("av01") -> "video/av01"
            else -> return false to "unknown codec"
        }
        val hdrProfiles = when (mime) {
            "video/x-vnd.on2.vp9" -> setOf(CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus,
                CodecProfileLevel.VP9Profile3HDR, CodecProfileLevel.VP9Profile3HDR10Plus)
            "video/av01" -> setOf(CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)
            else -> emptySet()
        }
        val tenBitProfiles = when (mime) {
            "video/x-vnd.on2.vp9" -> setOf(CodecProfileLevel.VP9Profile2, CodecProfileLevel.VP9Profile3)
            "video/av01" -> setOf(CodecProfileLevel.AV1ProfileMain10)
            else -> emptySet()
        }
        val w = if (width > 0) width else height * 16 / 9
        val why = ArrayList<String>()
        for (info in codecs) {
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
            val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            if (!hardware) { why += "${info.name}: software"; continue }
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
            val video = caps?.videoCapabilities
            if (caps == null || video == null) { why += "${info.name}: no video caps"; continue }
            if (!video.areSizeAndRateSupported(w, height, fps.coerceAtLeast(1).toDouble())) {
                why += "${info.name}: not ${w}x$height@$fps"; continue
            }
            if (!hdr) return true to info.name
            val profiles = caps.profileLevels.map { it.profile }.toSet()
            when {
                profiles.any { it in hdrProfiles } -> return true to "${info.name} lists an HDR profile"
                hdrScreen && profiles.any { it in tenBitProfiles } -> return true to "${info.name} lists the 10-bit profile (HDR screen)"
                hdrScreen && mime == "video/x-vnd.on2.vp9" ->
                    return true to "$TRYING ${info.name} lists no 10-bit profile (profiles ${profiles.sorted().joinToString(",") { "0x" + it.toString(16) }})"
                else -> why += "${info.name}: no 10-bit/HDR profile (profiles ${profiles.sorted().joinToString(",") { "0x" + it.toString(16) }}, hdrScreen=$hdrScreen)"
            }
        }
        return false to (why.joinToString("; ").ifEmpty { "no $mime decoder" })
    }

    /** One log line per decoder type: each hardware decoder's name and the profiles it lists. */
    private val logged = HashSet<String>()
    @Synchronized private fun logDecoders(mime: String) {
        if (!logged.add(mime)) return
        val lines = codecs.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }.map { info ->
            val profiles = runCatching { info.getCapabilitiesForType(mime).profileLevels.map { it.profile }.toSet().sorted() }
                .getOrDefault(emptyList())
            val hw = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.")
            "${info.name}${if (hw) "" else " (software)"} profiles=" + profiles.joinToString(",") { "0x" + it.toString(16) }
        }
        Log.i(TAG, "decoders for $mime: " + lines.joinToString("; ").ifEmpty { "none" })
    }

    /**
     * NewPipeExtractor drops every stream whose itag it doesn't list, and it lists none of
     * YouTube's HDR ones, so they are added to its table here. Its own code reads codec, size and
     * fps from YouTube's answer, so only the id, kind and container matter. Each gets its own
     * resolution label: NewPipe drops a stream whose label and container match one already seen,
     * which would lose an HDR stream behind the SDR one of the same size. Best effort: if the
     * table can't be changed, HDR streams just aren't offered.
     */
    fun registerHdrItags() {
        val hdr = listOf(
            // VP9 profile 2 HDR, WebM
            330 to "144p60 HDR", 331 to "240p60 HDR", 332 to "360p60 HDR", 333 to "480p60 HDR",
            334 to "720p60 HDR", 335 to "1080p60 HDR", 336 to "1440p60 HDR", 337 to "2160p60 HDR",
        ).map { (id, res) -> ItagItem(id, ItagItem.ItagType.VIDEO_ONLY, MediaFormat.WEBM, res, 60) } + listOf(
            // AV1 HDR, MP4
            694 to "144p60 HDR", 695 to "240p60 HDR", 696 to "360p60 HDR", 697 to "480p60 HDR",
            698 to "720p60 HDR", 699 to "1080p60 HDR", 700 to "1440p60 HDR", 701 to "2160p60 HDR",
            702 to "4320p60 HDR",
        ).map { (id, res) -> ItagItem(id, ItagItem.ItagType.VIDEO_ONLY, MediaFormat.MPEG_4, res, 60) }
        try {
            val field = ItagItem::class.java.getDeclaredField("ITAG_LIST")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val known = field.get(null) as Array<ItagItem>
            val added = hdr.filter { h -> known.none { it.id == h.id } }
            if (added.isNotEmpty()) field.set(null, known + added)
            Log.i(TAG, "youtube: HDR itags registered (${added.size} added)")
        } catch (e: Exception) {
            Log.w(TAG, "youtube: couldn't register HDR itags, HDR streams won't be offered: $e")
        }
    }
}
