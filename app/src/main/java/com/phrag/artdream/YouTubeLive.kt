package com.phrag.artdream

import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Stream URLs for playing a YouTube link in the app's own player instead of YouTube's web
 * player, which gets the full resolution of a 4K screen (the web player in a WebView is held to
 * what YouTube picks for its size). Played in SDR: the SHIELD's hardware VP9 decoder takes only
 * Profile 0 and it has no AV1 decoder, so YouTube's HDR streams (VP9 Profile 2, AV1) don't
 * play on it.
 */
object YouTubeLive {

    private const val TAG = "fArtSaver"

    /** What to play: one video-only stream, plus an audio stream when there is one. */
    class Streams(
        val videoUrl: String,
        val audioUrl: String?,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val summary: String,
        /** What YouTube lists for the video stream (0 when it doesn't say), for the info overlay. */
        val fps: Int = 0,
        val bitrate: Int = 0,
        val codec: String = "",
    )

    /**
     * Looks the video up and picks streams for a screen [screenH] pixels tall (0 = no limit).
     * Blocking (a few seconds of network); call off the main thread. Null when the extractor
     * fails or nothing offered decodes here, and the caller falls back to the web player.
     */
    fun resolve(id: String, screenH: Int): Streams? = try {
        YouTubeDownloads.init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTube.watchUrl(id))
        fun summary(s: VideoStream) = "${s.height}p${s.fps} ${s.codec}"
        Log.i(TAG, "youtube $id native, offered: " + info.videoOnlyStreams.joinToString { summary(it) })
        val choice = StreamPicker.chooseLive(
            videoOnly = info.videoOnlyStreams.map(::describe),
            audio = info.audioStreams.map(::describe),
            maxHeight = if (screenH > 0) maxOf(screenH, 1080) else Int.MAX_VALUE,
            canPlay = { canDecode(it.codec, (it.ref as VideoStream).width, it.height, it.fps) },
        )
        if (choice == null) {
            Log.w(TAG, "youtube $id native: nothing offered decodes here")
            null
        } else {
            val v = choice.video.ref as VideoStream
            val a = choice.audio?.ref as AudioStream?
            val picked = summary(v) + " [" + StreamPicker.describeCodec(v.codec.orEmpty()) + "]"
            Log.i(TAG, "youtube $id native, playing: $picked" + (a?.let { " + ${it.averageBitrate}k ${it.codec}" } ?: ""))
            Streams(v.content, a?.content, v.width, v.height, info.duration.coerceAtLeast(0) * 1000, picked,
                v.fps, v.bitrate, v.codec.orEmpty())
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

    /** Whether a hardware decoder here plays [codec] (8-bit SDR) at this size and rate. */
    fun canDecode(codec: String, width: Int, height: Int, fps: Int): Boolean {
        val c = codec.lowercase()
        val mime = when {
            c.startsWith("avc1") -> "video/avc"
            c.startsWith("vp09") || c == "vp9" || c.startsWith("vp9.") -> "video/x-vnd.on2.vp9"
            c.startsWith("av01") -> "video/av01"
            else -> return false
        }
        val w = if (width > 0) width else height * 16 / 9
        return codecs.any { info ->
            info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                (if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
                    else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")) &&
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
                    ?.areSizeAndRateSupported(w, height, fps.coerceAtLeast(1).toDouble()) == true
        }
    }
}
