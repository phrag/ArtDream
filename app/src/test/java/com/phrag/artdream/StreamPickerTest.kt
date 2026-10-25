package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StreamPickerTest {

    private fun v(
        ref: String, h: Int, fps: Int = 30, kbps: Int = 1000, codec: String = "avc1.640028",
        mp4: Boolean = true, progressive: Boolean = true, audio: Boolean = false,
    ) = StreamPicker.Video(ref, h, fps, kbps * 1000, codec, mp4, progressive, audio)

    private fun a(ref: String, kbps: Int, codec: String = "mp4a.40.2", m4a: Boolean = true, progressive: Boolean = true) =
        StreamPicker.Audio(ref, kbps * 1000, codec, m4a, progressive)

    @Test fun tallestH264UpToTheLimitPlusBestAac() {
        val c = StreamPicker.choose(
            videoOnly = listOf(v("720", 720), v("1080", 1080), v("2160", 2160), v("1440", 1440)),
            muxed = listOf(v("360m", 360, audio = true)),
            audio = listOf(a("48", 48), a("128", 128), a("160", 160)),
            maxHeight = 1080,
        )!!
        assertEquals("1080", c.video.ref)
        assertEquals("160", c.audio!!.ref)
    }

    @Test fun thirtyFpsBeatsSixtyAtTheSameHeight() {
        val c = StreamPicker.choose(
            videoOnly = listOf(v("1080p60", 1080, fps = 60, kbps = 4000), v("1080p30", 1080, fps = 30, kbps = 2000)),
            muxed = emptyList(), audio = listOf(a("a", 128)), maxHeight = 1080,
        )!!
        assertEquals("1080p30", c.video.ref)
    }

    @Test fun ignoresCodecsAndContainersTheTvMightNotPlay() {
        val c = StreamPicker.choose(
            videoOnly = listOf(
                v("vp9", 1080, codec = "vp09.00.40.08", mp4 = false),
                v("av1", 1080, codec = "av01.0.08M.08"),
                v("avc720", 720),
            ),
            muxed = emptyList(),
            audio = listOf(a("opus", 160, codec = "opus", m4a = false), a("aac", 128)),
            maxHeight = 1080,
        )!!
        assertEquals("avc720", c.video.ref)
        assertEquals("aac", c.audio!!.ref)
    }

    @Test fun ignoresStreamsThatAreNotPlainHttp() {
        val c = StreamPicker.choose(
            videoOnly = listOf(v("dash", 1080, progressive = false), v("ok", 480)),
            muxed = emptyList(), audio = listOf(a("hls", 256, progressive = false), a("ok", 96)), maxHeight = 1080,
        )!!
        assertEquals("ok", c.video.ref)
        assertEquals("ok", c.audio!!.ref)
    }

    @Test fun fallsBackToAMuxedStreamWithoutUsableAudio() {
        val c = StreamPicker.choose(
            videoOnly = listOf(v("1080", 1080)),
            muxed = listOf(v("360m", 360, audio = true), v("720m", 720, audio = true)),
            audio = listOf(a("opus", 160, codec = "opus", m4a = false)),
            maxHeight = 1080,
        )!!
        // A usable pair isn't possible, so it takes the best muxed stream rather than silence.
        assertEquals("720m", c.video.ref)
        assertNull(c.audio)
    }

    @Test fun pictureOnlyIsBetterThanNothing() {
        val c = StreamPicker.choose(
            videoOnly = listOf(v("1080", 1080)), muxed = emptyList(), audio = emptyList(), maxHeight = 1080,
        )!!
        assertEquals("1080", c.video.ref)
        assertNull(c.audio)
    }

    @Test fun respectsTheHeightLimit() {
        val low = StreamPicker.choose(
            videoOnly = listOf(v("1080", 1080), v("480", 480)), muxed = emptyList(), audio = listOf(a("a", 128)), maxHeight = 720,
        )!!
        assertEquals("480", low.video.ref)
        assertNull(StreamPicker.choose(listOf(v("1080", 1080)), emptyList(), listOf(a("a", 128)), maxHeight = 720))
    }

    @Test fun nothingUsableMeansNull() {
        assertNull(StreamPicker.choose(emptyList(), emptyList(), emptyList(), 1080))
        assertNull(StreamPicker.choose(listOf(v("av1", 1080, codec = "av01.0")), emptyList(), listOf(a("a", 128)), 1080))
    }

    @Test fun choiceKeepsTheOriginalReference() {
        val marker = Any()
        val c = StreamPicker.choose(
            listOf(StreamPicker.Video(marker, 720, 30, 1, "avc1.4d401f", true, true, false)),
            emptyList(), listOf(a("a", 128)), 1080,
        )!!
        assertSame(marker, c.video.ref)
        assertNotNull(c.audio)
    }
}
