package com.phrag.artdream

/**
 * What makes a picture or video HDR, for the "HDR only" mode (Prefs.hdrOnly). Pure (no Android
 * types) so it's unit-tested; [HdrProbe] feeds it a file's first bytes or its video track format.
 *
 * HDR here means a PQ or HLG picture, Dolby Vision, or a photo with an HDR gain map (Ultra HDR
 * JPEG, ISO 21496-1, Apple's HDR photos). A wide-gamut (Display P3, BT.2020) picture with an
 * ordinary SDR curve is not HDR.
 */
object HdrMedia {

    const val DOLBY_VISION = "Dolby Vision"
    const val HDR10 = "HDR10"
    const val HLG = "HLG"
    const val GAIN_MAP = "HDR photo (gain map)"

    /** ITU-T H.273 transfer characteristics, as written in PNG cICP, ICC cicp and HEIF/AVIF nclx. */
    private const val CICP_PQ = 16
    private const val CICP_HLG = 18

    /**
     * The HDR kind of a picture from its first bytes (and, for HEIF/AVIF, its last ones), or null
     * if nothing in them says HDR.
     */
    fun imageKind(bytes: ByteArray): String? {
        // Gain map photos: the base picture is SDR, the map lifts it to HDR on an HDR screen.
        if (bytes.find("hdrgm:Version") >= 0 || bytes.find("http://ns.adobe.com/hdr-gain-map/") >= 0 ||
            bytes.find("urn:iso:std:iso:ts:21496:-1") >= 0 || bytes.find("urn:com:apple:photo:2020:aux:hdrgainmap") >= 0
        ) return GAIN_MAP
        // AVIF/HEIF gain map: an item of type "tmap" in the item info.
        if (isIsoBmff(bytes) && hasTmapItem(bytes)) return GAIN_MAP

        transferKind(cicpTransfer(bytes))?.let { return it }
        // ICC profiles that name the BT.2100 curve without a cicp tag (often UTF-16 in v4 profiles).
        for (name in listOf("2100 PQ", "2100_PQ", "2100-PQ", "BT2100PQ", "Rec2100PQ")) {
            if (bytes.find(name) >= 0 || bytes.find(name, utf16 = true) >= 0) return HDR10
        }
        for (name in listOf("2100 HLG", "2100_HLG", "2100-HLG", "BT2100HLG", "Rec2100HLG")) {
            if (bytes.find(name) >= 0 || bytes.find(name, utf16 = true) >= 0) return HLG
        }
        return null
    }

    /** The H.273 transfer from a PNG cICP chunk, an ICC cicp tag or a HEIF/AVIF nclx colour box. */
    internal fun cicpTransfer(b: ByteArray): Int? {
        // PNG: length, "cICP", primaries, transfer, matrix, full range.
        b.find("cICP").takeIf { it >= 0 && it + 5 < b.size }?.let { return b[it + 5].toInt() and 0xFF }
        // ICC v4.4 tag: "cicp", 4 reserved zero bytes, primaries, transfer, matrix, full range.
        var i = b.find("cicp")
        while (i >= 0) {
            if (i + 9 < b.size && (4..7).all { b[i + it].toInt() == 0 }) return b[i + 9].toInt() and 0xFF
            i = b.find("cicp", i + 1)
        }
        // ISOBMFF: "colr", "nclx", u16 primaries, u16 transfer, u16 matrix, u8 flags.
        i = b.find("colr")
        while (i >= 0) {
            if (i + 11 < b.size && b.matches("nclx", i + 4)) return ((b[i + 10].toInt() and 0xFF) shl 8) or (b[i + 11].toInt() and 0xFF)
            i = b.find("colr", i + 1)
        }
        return null
    }

    private fun transferKind(t: Int?) = when (t) {
        CICP_PQ -> HDR10
        CICP_HLG -> HLG
        else -> null
    }

    /** An "infe" box (version 2: u16 item id, version 3: u32) whose item type is "tmap". */
    private fun hasTmapItem(b: ByteArray): Boolean {
        var i = b.find("infe")
        while (i >= 0) {
            val version = b.getOrNull(i + 4)?.toInt()
            if (version == 2 && b.matches("tmap", i + 12) || version == 3 && b.matches("tmap", i + 14)) return true
            i = b.find("infe", i + 1)
        }
        return false
    }

    /** An ISO base media file (HEIF, AVIF): "ftyp" right after the first box size. */
    private fun isIsoBmff(b: ByteArray) = b.matches("ftyp", 4)

    /**
     * The HDR kind of a video track from its format: Dolby Vision by mime type, HDR10/HLG by the
     * colour transfer (android.media.MediaFormat.KEY_COLOR_TRANSFER: 6 PQ, 7 HLG), or an HDR
     * profile (android.media.MediaCodecInfo.CodecProfileLevel) when the transfer isn't signalled.
     */
    fun videoKind(mime: String?, transfer: Int?, profile: Int?): String? {
        val m = mime.orEmpty().lowercase()
        if (m.contains("dolby-vision")) return DOLBY_VISION
        when (transfer) {
            VideoInfo.TRANSFER_PQ -> return HDR10
            VideoInfo.TRANSFER_HLG -> return HLG
        }
        val hdrProfile = when {
            m.endsWith("/hevc") -> profile == 0x1000 || profile == 0x2000          // Main10HDR10, Main10HDR10Plus
            m.endsWith("vp9") -> profile in listOf(0x1000, 0x2000, 0x4000, 0x8000)  // Profile 2/3 HDR, HDR10+
            m.endsWith("/av01") -> profile == 0x1000 || profile == 0x2000          // Main10HDR10, Main10HDR10Plus
            else -> false
        }
        return if (hdrProfile) HDR10 else null
    }

    /**
     * Whether a [w]x[h] picture should fill a [sw]x[sh] screen edge to edge (cropping a little)
     * rather than be fitted with black bars: yes unless filling would cut off more than
     * [MAX_CROP] of it.
     */
    fun fillsScreen(w: Int, h: Int, sw: Int, sh: Int): Boolean {
        if (w <= 0 || h <= 0 || sw <= 0 || sh <= 0) return false
        val a = w.toDouble() / h
        val s = sw.toDouble() / sh
        return 1 - minOf(a / s, s / a) <= MAX_CROP
    }

    /** The most of a picture (as a fraction of its width or height) "full screen" may crop. */
    const val MAX_CROP = 0.15

    private fun ByteArray.matches(s: String, at: Int): Boolean =
        at >= 0 && at + s.length <= size && s.indices.all { this[at + it] == s[it].code.toByte() }

    /** Index of [s] (ASCII, or UTF-16BE when [utf16]) in these bytes from [from], or -1. */
    private fun ByteArray.find(s: String, from: Int = 0, utf16: Boolean = false): Int {
        val pat = if (utf16) ByteArray(s.length * 2) { if (it % 2 == 0) 0 else s[it / 2].code.toByte() }
            else ByteArray(s.length) { s[it].code.toByte() }
        if (pat.isEmpty()) return -1
        val first = pat[0]
        var i = from.coerceAtLeast(0)
        val last = size - pat.size
        outer@ while (i <= last) {
            if (this[i] != first) { i++; continue }
            for (j in 1 until pat.size) if (this[i + j] != pat[j]) { i++; continue@outer }
            return i
        }
        return -1
    }
}
