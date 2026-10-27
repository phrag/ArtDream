package com.phrag.artdream

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Finds out which of the user's own files are HDR, for "HDR only" (Prefs.hdrOnly), and remembers
 * it per file (name, size and date) so the slideshow doesn't open every file again on each round.
 * Slow (reads files): call it off the main thread.
 */
class HdrProbe(ctx: Context) {

    /** A file's verdict: [kind] is the HDR kind, or null for SDR; [playable] false when no decoder here takes it. */
    class Result(val kind: String?, val playable: Boolean) {
        /** HDR and this device can play it as HDR. */
        val hdr: Boolean get() = kind != null && playable
    }

    private val sp = ctx.getSharedPreferences("hdr_probe", Context.MODE_PRIVATE)

    fun probe(item: OwnMedia.Item): Result {
        if (item.youtube) return Result(null, true)   // YouTube HDR is VP9.2/AV1: no decoder on the SHIELD
        val stamp = "${item.file.length()}:${item.file.lastModified()}:"
        sp.getString(item.key, null)?.takeIf { it.startsWith(stamp) }?.let { return decode(it.removePrefix(stamp)) }
        val r = try {
            if (item.video) probeVideo(item.file) else Result(HdrMedia.imageKind(readEnds(item.file)), true)
        } catch (e: Exception) {
            Log.w(TAG, "hdr probe ${item.key}: $e")
            Result(null, true)
        }
        Log.i(TAG, "hdr probe ${item.key}: ${r.kind ?: "SDR"}${if (r.playable) "" else " (no HDR decoder here)"}")
        sp.edit().putString(item.key, stamp + encode(r)).apply()
        return r
    }

    /** Drops remembered results for files that are gone. */
    fun prune(items: List<OwnMedia.Item>) {
        val keys = items.mapTo(HashSet()) { it.key }
        val stale = sp.all.keys.filter { it !in keys }
        if (stale.isNotEmpty()) sp.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    /** Video: any HDR video track, and whether a decoder here can take that track as it is. */
    private fun probeVideo(file: File): Result {
        val x = MediaExtractor()
        try {
            x.setDataSource(file.path)
            var sdr: Result? = null
            for (t in 0 until x.trackCount) {
                val f = x.getTrackFormat(t)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                fun int(key: String) = if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrNull() else null
                val profile = int(MediaFormat.KEY_PROFILE)
                val kind = HdrMedia.videoKind(mime, int(MediaFormat.KEY_COLOR_TRANSFER), profile)
                if (kind == null) {
                    sdr = sdr ?: Result(null, true)
                    continue
                }
                return Result(kind, hasDecoder(mime, f, profile))
            }
            return sdr ?: Result(null, true)
        } finally {
            runCatching { x.release() }
        }
    }

    /**
     * A plain decoder for [mime] at this size and [profile]. Without one (VP9 profile 2 or AV1 on
     * the SHIELD) the file would play as SDR, or not at all.
     */
    private fun hasDecoder(mime: String, f: MediaFormat, profile: Int?): Boolean {
        val w = runCatching { f.getInteger(MediaFormat.KEY_WIDTH) }.getOrDefault(1920)
        val h = runCatching { f.getInteger(MediaFormat.KEY_HEIGHT) }.getOrDefault(1080)
        val want = MediaFormat.createVideoFormat(mime, w, h).apply { profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) } }
        return runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(want) != null }.getOrDefault(true)
    }

    /**
     * The start of the file and, for a big one, its end too: HEIF/AVIF may keep their item info
     * after the picture data.
     */
    private fun readEnds(file: File): ByteArray = RandomAccessFile(file, "r").use { f ->
        val len = f.length()
        if (len <= HEAD + TAIL) return ByteArray(len.toInt()).also { f.readFully(it) }
        val out = ByteArray(HEAD + TAIL)
        f.readFully(out, 0, HEAD)
        f.seek(len - TAIL)
        f.readFully(out, HEAD, TAIL)
        out
    }

    private fun encode(r: Result) = when {
        r.kind == null -> "SDR"
        r.playable -> r.kind
        else -> "!" + r.kind
    }

    private fun decode(s: String) = when {
        s == "SDR" -> Result(null, true)
        s.startsWith("!") -> Result(s.drop(1), false)
        else -> Result(s, true)
    }

    private companion object {
        const val TAG = "fArtSaver"
        const val HEAD = 512 * 1024
        const val TAIL = 64 * 1024
    }
}
