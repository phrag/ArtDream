package com.phrag.artdream

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Joins a picture-only stream and a sound-only one into one file without re-encoding anything:
 * MP4 (H.264 + AAC), or WebM (VP9 + Opus) when [join]'s webm is set.
 */
object Remux {

    /** Throws if either file lacks the expected track or the muxer can't take it. */
    fun join(video: File, audio: File, out: File, webm: Boolean = false) {
        val vx = MediaExtractor()
        val ax = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vx.setDataSource(video.path)
            ax.setDataSource(audio.path)
            val vTrack = firstTrack(vx, "video/") ?: error("no video track in the download")
            val aTrack = firstTrack(ax, "audio/") ?: error("no audio track in the download")
            vx.selectTrack(vTrack)
            ax.selectTrack(aTrack)

            muxer = MediaMuxer(out.path, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val vOut = muxer.addTrack(vx.getTrackFormat(vTrack))
            val aOut = muxer.addTrack(ax.getTrackFormat(aTrack))
            muxer.start()
            started = true

            // Interleave by timestamp so playback never has to hunt for the next audio chunk.
            val buf = ByteBuffer.allocate(BUFFER_BYTES)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val vt = vx.sampleTime
                val at = ax.sampleTime
                if (vt < 0 && at < 0) break
                val takeVideo = at < 0 || (vt in 0..at)
                val (x, track) = if (takeVideo) vx to vOut else ax to aOut
                buf.clear()
                val size = x.readSampleData(buf, 0)
                if (size < 0) { x.advance(); continue }
                info.set(0, size, x.sampleTime, if (x.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, buf, info)
                x.advance()
            }
        } finally {
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { vx.release() }
            runCatching { ax.release() }
        }
    }

    private fun firstTrack(x: MediaExtractor, mimePrefix: String): Int? =
        (0 until x.trackCount).firstOrNull { x.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true }

    /** Big enough for a 4K key frame. */
    private const val BUFFER_BYTES = 8 * 1024 * 1024
}
