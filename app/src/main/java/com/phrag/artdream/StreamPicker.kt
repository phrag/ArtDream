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
    )

    class Audio(
        val ref: Any?,
        val bitrate: Int,
        val codec: String,
        val m4a: Boolean,
        val progressive: Boolean,
    )

    class Choice(val video: Video, val audio: Audio?)

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
}
