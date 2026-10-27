package com.phrag.artdream

import kotlin.math.roundToInt

/**
 * The text of the optional video info overlay (Prefs.videoInfo): what is playing, in a few short
 * lines. Pure (no Android types) so the wording is unit-tested; the slideshow reads the values
 * from ExoPlayer's video format, MediaExtractor (own files) or the YouTube web page.
 */
object VideoInfo {

    /** Colour transfer ids; the same numbers in Media3's C and android.media.MediaFormat. */
    const val TRANSFER_SDR = 3
    const val TRANSFER_PQ = 6
    const val TRANSFER_HLG = 7

    /**
     * The HDR kind from the transfer the decoder got, or Dolby Vision from its mime/codec string.
     * Null [transfer] (or any value not listed) means the stream didn't say; that plays as SDR.
     */
    fun hdrName(transfer: Int?, mime: String?, codecs: String?): String {
        val c = codecs.orEmpty().lowercase()
        if (mime.orEmpty().contains("dolby-vision") || c.startsWith("dvh") || c.startsWith("dva") || c.startsWith("dav1"))
            return "Dolby Vision"
        return when (transfer) {
            TRANSFER_PQ -> "HDR10 (PQ)"
            TRANSFER_HLG -> "HLG"
            TRANSFER_SDR -> "SDR"
            else -> "SDR (not signalled)"
        }
    }

    /**
     * Codec and profile from an RFC 6381 codec string ("vp09.02.51.10…" → "VP9 profile 2, 10-bit"),
     * else from the mime type and an android.media.MediaCodecInfo.CodecProfileLevel [profile].
     */
    fun codecName(mime: String?, codecs: String?, profile: Int? = null): String {
        val f = codecs.orEmpty().trim().lowercase().split('.')
        when (f.firstOrNull()) {
            "vp09" -> return listOfNotNull("VP9 profile ${f.getOrNull(1)?.toIntOrNull() ?: "?"}",
                f.getOrNull(3)?.toIntOrNull()?.let { "$it-bit" }).joinToString(", ")
            "vp9" -> return "VP9"
            "av01" -> return listOfNotNull("AV1 " + when (f.getOrNull(1)) { "0" -> "Main"; "1" -> "High"; "2" -> "Professional"; else -> "?" },
                f.getOrNull(3)?.toIntOrNull()?.let { "$it-bit" }).joinToString(", ")
            "avc1", "avc3" -> return "H.264" + (f.getOrNull(1)?.take(2)?.toIntOrNull(16)?.let { avcProfile(it) }?.let { " $it" } ?: "")
            "hvc1", "hev1" -> return "HEVC" + when (f.getOrNull(1)?.trimStart('a', 'b', 'c')) {
                "1" -> " Main"; "2" -> " Main 10"; else -> ""
            }
            "dvh1", "dvhe", "dav1", "dva1", "dvav" -> return "Dolby Vision" + (f.getOrNull(1)?.toIntOrNull()?.let { " profile $it" } ?: "")
        }
        val m = mime.orEmpty().lowercase()
        val name = when {
            m.endsWith("/avc") -> "H.264"
            m.endsWith("/hevc") -> "HEVC"
            m.endsWith("vp9") -> "VP9"
            m.endsWith("vp8") -> "VP8"
            m.endsWith("/av01") -> "AV1"
            m.endsWith("/dolby-vision") -> "Dolby Vision"
            m.endsWith("mp4v-es") -> "MPEG-4"
            m.isEmpty() -> return codecs.orEmpty().ifEmpty { "unknown codec" }
            else -> m.substringAfter('/')
        }
        val p = profile?.let { profileName(name, it) }
        return if (p != null) "$name $p" else name
    }

    /** H.264 profile_idc (the first byte after "avc1."). */
    private fun avcProfile(idc: Int) = when (idc) {
        66 -> "Baseline"; 77 -> "Main"; 88 -> "Extended"; 100 -> "High"; 110 -> "High 10"
        122 -> "High 4:2:2"; 244 -> "High 4:4:4"; else -> null
    }

    /** The common CodecProfileLevel values per codec (the constants, not their names, so this stays pure). */
    private fun profileName(codec: String, p: Int): String? = when (codec) {
        "H.264" -> when (p) { 0x1 -> "Baseline"; 0x2 -> "Main"; 0x4 -> "Extended"; 0x8 -> "High"; 0x10 -> "High 10"; 0x10000 -> "Constrained Baseline"; 0x80000 -> "Constrained High"; else -> null }
        "HEVC" -> when (p) { 0x1 -> "Main"; 0x2 -> "Main 10"; 0x4 -> "Main Still"; 0x1000 -> "Main 10 HDR10"; 0x2000 -> "Main 10 HDR10+"; else -> null }
        "VP9" -> when (p) { 0x1 -> "profile 0"; 0x2 -> "profile 1"; 0x4 -> "profile 2"; 0x8 -> "profile 3"; 0x1000 -> "profile 2 HDR"; 0x2000 -> "profile 3 HDR"; 0x4000 -> "profile 2 HDR10+"; 0x8000 -> "profile 3 HDR10+"; else -> null }
        "AV1" -> when (p) { 0x1 -> "Main 8-bit"; 0x2 -> "Main 10-bit"; 0x1000 -> "Main 10 HDR10"; 0x2000 -> "Main 10 HDR10+"; else -> null }
        // DolbyVisionProfileDvavPer (0x1) is profile 0, each next bit the next profile.
        "Dolby Vision" -> if (p > 0 && p and (p - 1) == 0) "profile ${Integer.numberOfTrailingZeros(p)}" else null
        else -> null
    }

    /** "3840x2160 · 59.94 fps"; size or rate left out when unknown (<= 0). */
    fun picture(width: Int, height: Int, fps: Float): String = listOfNotNull(
        if (width > 0 && height > 0) "${width}x$height" else null,
        if (fps > 0f) fpsText(fps) + " fps" else null,
    ).joinToString(" · ").ifEmpty { "size unknown" }

    private fun fpsText(fps: Float): String {
        val whole = fps.roundToInt()
        return if (kotlin.math.abs(fps - whole) < 0.01f) "$whole" else "%.2f".format(java.util.Locale.ROOT, fps)
    }

    /** "18.4 Mbps", "850 kbps"; null when unknown (<= 0). */
    fun bitrate(bps: Long): String? = when {
        bps <= 0 -> null
        bps >= 1_000_000 -> "%.1f Mbps".format(java.util.Locale.ROOT, bps / 1_000_000.0)
        else -> "${(bps / 1000.0).roundToInt()} kbps"
    }

    /** The TV's mode and HDR kinds (Display.HdrCapabilities ids), for telling what it could show. */
    fun screen(width: Int, height: Int, refreshHz: Float, hdrTypes: List<Int>): String {
        val hdr = hdrTypes.mapNotNull { when (it) { 1 -> "DV"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> null } }
        return "TV " + listOfNotNull(
            if (width > 0 && height > 0) "${width}x$height" else null,
            if (refreshHz > 0f) "${fpsText(refreshHz)} Hz" else null,
        ).joinToString(" ") + " · " + (if (hdr.isEmpty()) "no HDR" else hdr.joinToString("/"))
    }
}
