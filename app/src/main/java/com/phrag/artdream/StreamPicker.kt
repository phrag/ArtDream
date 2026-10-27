package com.phrag.artdream

/**
 * Which YouTube streams to fetch for the screensaver. Pure (no Android, no extractor types) so the
 * choice is unit-tested; [YouTubeDownloads] describes the extractor's streams as these.
 *
 * Video: H.264 in MP4 over plain HTTP, because every TV decodes it, the tallest up to the limit,
 * and at the same height 30 fps over 60 (half the size, and a screensaver doesn't need more).
 * Audio: AAC in M4A (so MediaMuxer can join it to the video without re-encoding), highest bitrate.
 */
object StreamPicker {

    class Video(
        val ref: Any?,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val codec: String,
        val mp4: Boolean,
        val progressive: Boolean,
        val hasAudio: Boolean,
        val webm: Boolean = false,
    )

    class Audio(
        val ref: Any?,
        val bitrate: Int,
        val codec: String,
        val m4a: Boolean,
        val progressive: Boolean,
        val webm: Boolean = false,
    )

    class Choice(val video: Video, val audio: Audio?) {
        /** Joined into WebM (VP9 + Opus) rather than MP4 (H.264 + AAC). */
        val webm: Boolean get() = video.webm
    }

    private fun Video.usable(maxHeight: Int) =
        mp4 && progressive && codec.startsWith("avc1", ignoreCase = true) && height in 1..maxHeight

    private fun Audio.usable() = m4a && progressive && codec.startsWith("mp4a", ignoreCase = true)

    private val videoOrder = compareByDescending<Video> { it.height }
        .thenByDescending { it.fps <= 30 }          // 30 fps beats 60 at the same height
        .thenByDescending { if (it.fps <= 30) it.fps else -it.fps }
        .thenByDescending { it.bitrate }

    /**
     * [videoOnly] is the adaptive list (picture without sound), [muxed] the progressive list
     * (picture and sound together, but YouTube only offers those up to 720p). Prefers video-only
     * plus a separate audio stream; falls back to a muxed stream when there's no usable pair.
     */
    fun choose(videoOnly: List<Video>, muxed: List<Video>, audio: List<Audio>, maxHeight: Int): Choice? {
        val bestAudio = audio.filter { it.usable() }.maxByOrNull { it.bitrate }
        val bestVideo = videoOnly.filter { it.usable(maxHeight) }.sortedWith(videoOrder).firstOrNull()
        if (bestVideo != null && bestAudio != null) return Choice(bestVideo, bestAudio)
        val fallback = muxed.filter { it.usable(maxHeight) }.sortedWith(videoOrder).firstOrNull()
        if (fallback != null) return Choice(fallback, null)
        // Picture only is still a fine screensaver (it plays muted by default).
        return bestVideo?.let { Choice(it, null) }
    }

    /**
     * What "Save to TV" fetches: the tallest SDR stream up to [maxHeight] that [canPlay] says this
     * TV decodes in hardware, with an audio stream MediaMuxer can join to it: H.264 MP4 with AAC
     * M4A, or VP9 WebM with Opus WebM. YouTube offers H.264 only up to 1080p, so on a 4K TV this
     * is usually VP9. At the same height H.264 beats VP9 (every player takes it), 30 fps beats 60
     * (half the size), then bitrate. HDR streams are left out: the SHIELD can't decode YouTube's
     * (VP9 Profile 2, AV1). Null when no such pair is offered; [choose] is the fallback then.
     */
    fun chooseSave(videoOnly: List<Video>, audio: List<Audio>, maxHeight: Int, canPlay: (Video) -> Boolean): Choice? {
        val aac = audio.filter { it.usable() }.maxByOrNull { it.bitrate }
        val opus = audio.filter { it.webm && it.progressive && it.codec.equals("opus", ignoreCase = true) }.maxByOrNull { it.bitrate }
        fun audioFor(v: Video): Audio? = when {
            v.mp4 && v.codec.startsWith("avc1", ignoreCase = true) -> aac
            v.webm && isVp9(v.codec) -> opus
            else -> null
        }
        val order = compareByDescending<Video> { it.height }
            .thenByDescending { it.fps <= 30 }
            .thenByDescending { if (it.fps <= 30) it.fps else -it.fps }
            .thenByDescending { it.mp4 }
            .thenByDescending { it.bitrate }
        val video = videoOnly
            .filter { it.progressive && it.height in 1..maxHeight && !isHdr(it.codec) && audioFor(it) != null && canPlay(it) }
            .sortedWith(order).firstOrNull() ?: return null
        return Choice(video, audioFor(video))
    }

    private fun isVp9(codec: String) = codec.lowercase().let { it == "vp9" || it.startsWith("vp9.") || it.startsWith("vp09") }

    /**
     * True for a stream YouTube marks HDR in its codec string: VP9 profile 2 or 3 (10-bit) or
     * 10-bit AV1 with the PQ or HLG transfer (or without colour fields, which YouTube only omits
     * for SDR). YouTube labels its VP9 HDR streams with the short form "vp9.2" (seen on the TV);
     * "vp09.02.51.10.01.09.16.09.00" is the long form. "av01.0.12M.10.0.110.09.16.09.0" is HDR10;
     * "vp9", "vp09.00.51.08…" and "av01.0.08M.08" are not.
     */
    fun isHdr(codec: String): Boolean {
        val f = codec.trim().lowercase().split('.')
        return when (f.firstOrNull()) {
            "vp09" -> f.getOrNull(1) == "02" || f.getOrNull(1) == "03"
            "vp9" -> f.getOrNull(1) == "2" || f.getOrNull(1) == "3"
            "av01" -> f.getOrNull(3) == "10" && (f.size <= 7 || f[7] == "16" || f[7] == "18")
            else -> false
        }
    }

    /**
     * What a codec string says, for the log: "VP9 profile 2, 10-bit, PQ (HDR10)",
     * "AV1 main, 10-bit, HLG", "VP9 (SDR)", "H.264 (SDR)".
     */
    fun describeCodec(codec: String): String {
        val f = codec.trim().lowercase().split('.')
        fun transfer(tc: String?) = when (tc) {
            "16" -> "PQ (HDR10)"
            "18" -> "HLG"
            null -> null
            else -> "SDR (transfer $tc)"
        }
        return when (f.firstOrNull()) {
            "vp09" -> listOfNotNull("VP9 profile ${f.getOrNull(1)?.toIntOrNull() ?: "?"}",
                f.getOrNull(3)?.toIntOrNull()?.let { "$it-bit" }, transfer(f.getOrNull(6))).joinToString(", ")
            "vp9" -> when (f.getOrNull(1)) {
                null, "0" -> "VP9 (SDR)"
                else -> "VP9 profile ${f[1]}" + if (isHdr(codec)) ", 10-bit HDR" else ""
            }
            "av01" -> listOfNotNull("AV1 " + when (f.getOrNull(1)) { "0" -> "main"; "1" -> "high"; "2" -> "professional"; else -> "?" },
                f.getOrNull(3)?.toIntOrNull()?.let { "$it-bit" }, transfer(f.getOrNull(7))).joinToString(", ")
            "avc1" -> "H.264 (SDR)"
            else -> codec.ifBlank { "unknown codec" }
        }
    }

    /**
     * Which streams to play live (not download): the tallest video-only stream up to [maxHeight]
     * that [canPlay] says this TV decodes in hardware, never HDR (the SHIELD can't decode
     * YouTube's), then higher frame rate, then bitrate. Any codec and container is fine here (no
     * muxing), so VP9 and AV1 count too: YouTube has most 4K only in those. Audio: highest
     * bitrate, any codec.
     */
    fun chooseLive(
        videoOnly: List<Video>,
        audio: List<Audio>,
        maxHeight: Int,
        canPlay: (Video) -> Boolean,
    ): Choice? {
        val order = compareByDescending<Video> { it.height }
            .thenByDescending { it.fps }
            .thenByDescending { it.bitrate }
        val video = videoOnly.filter { it.progressive && it.height in 1..maxHeight && !isHdr(it.codec) && canPlay(it) }
            .sortedWith(order).firstOrNull() ?: return null
        return Choice(video, audio.filter { it.progressive }.maxByOrNull { it.bitrate })
    }
}
