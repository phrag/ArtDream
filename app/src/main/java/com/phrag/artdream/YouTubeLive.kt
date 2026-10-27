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

    /** What to play: one video-only stream, plus an audio stream when there is one. */
    class Streams(
        val videoUrl: String,
        val audioUrl: String?,
        val width: Int,
        val height: Int,
        val hdr: Boolean,
        val durationMs: Long,
        val summary: String,
    )

    /**
     * Looks the video up and picks streams for a screen [screenH] pixels tall (0 = no limit).
     * Blocking (a few seconds of network); call off the main thread. Null when the extractor
     * fails or nothing offered decodes here, and the caller falls back to the web player.
     */
    fun resolve(id: String, screenH: Int, hdrScreen: Boolean): Streams? = try {
        YouTubeDownloads.init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTube.watchUrl(id))
        fun summary(s: VideoStream) = "${s.height}p${s.fps} ${s.codec}${if (StreamPicker.isHdr(s.codec.orEmpty())) " HDR" else ""}"
        Log.i(TAG, "youtube $id native, offered: " + info.videoOnlyStreams.joinToString { summary(it) })
        val maxHeight = if (screenH > 0) maxOf(screenH, 1080) else Int.MAX_VALUE
        val choice = StreamPicker.chooseLive(
            videoOnly = info.videoOnlyStreams.map(::describe),
            audio = info.audioStreams.map(::describe),
            maxHeight = maxHeight,
            hdrScreen = hdrScreen,
            canPlay = { canDecode(it.codec, (it.ref as VideoStream).width, it.height, it.fps, StreamPicker.isHdr(it.codec)) },
        )
        if (choice == null) {
            Log.w(TAG, "youtube $id native: nothing offered decodes here")
            null
        } else {
            val v = choice.video.ref as VideoStream
            val a = choice.audio?.ref as AudioStream?
            val picked = summary(v)
            Log.i(TAG, "youtube $id native, playing: $picked" + (a?.let { " + ${it.averageBitrate}k ${it.codec}" } ?: ""))
            Streams(v.content, a?.content, v.width, v.height, StreamPicker.isHdr(v.codec.orEmpty()),
                info.duration.coerceAtLeast(0) * 1000, picked)
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

    /** Whether a hardware decoder here plays [codec] at this size and rate (and as HDR, if [hdr]). */
    private fun canDecode(codec: String, width: Int, height: Int, fps: Int, hdr: Boolean): Boolean {
        val c = codec.lowercase()
        val mime = when {
            c.startsWith("avc1") -> "video/avc"
            c.startsWith("vp09") || c == "vp9" -> "video/x-vnd.on2.vp9"
            c.startsWith("av01") -> "video/av01"
            else -> return false
        }
        val hdrProfiles = when (mime) {
            "video/x-vnd.on2.vp9" -> setOf(CodecProfileLevel.VP9Profile2HDR, CodecProfileLevel.VP9Profile2HDR10Plus)
            "video/av01" -> setOf(CodecProfileLevel.AV1ProfileMain10HDR10, CodecProfileLevel.AV1ProfileMain10HDR10Plus)
            else -> emptySet()
        }
        val w = if (width > 0) width else height * 16 / 9
        return codecs.any { info ->
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return@any false
            val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            if (!hardware) return@any false
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@any false
            val video = caps.videoCapabilities ?: return@any false
            if (!video.areSizeAndRateSupported(w, height, fps.coerceAtLeast(1).toDouble())) return@any false
            !hdr || caps.profileLevels.any { it.profile in hdrProfiles }
        }
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
