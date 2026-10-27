package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test fun hdrCodecStrings() {
        assertTrue(StreamPicker.isHdr("vp09.02.51.10.01.09.16.09.00"))
        assertTrue(StreamPicker.isHdr("av01.0.12M.10.0.110.09.16.09.0"))
        assertTrue(StreamPicker.isHdr("av01.0.12M.10.0.110.09.18.09.0"))   // HLG
        assertFalse(StreamPicker.isHdr("vp9"))
        assertFalse(StreamPicker.isHdr("vp09.00.51.08.01.01.01.01.00"))
        assertFalse(StreamPicker.isHdr("av01.0.12M.08"))
        assertFalse(StreamPicker.isHdr("av01.0.12M.10.0.110.01.01.01.0"))  // 10-bit SDR
        assertFalse(StreamPicker.isHdr("avc1.640033"))
    }

    private val hdr4k = v("2160hdr", 2160, fps = 60, codec = "vp09.02.51.10.01.09.16.09.00", mp4 = false)
    private val sdr4k = v("2160sdr", 2160, fps = 60, codec = "vp9", mp4 = false)
    private val av1 = v("2160av1", 2160, fps = 60, codec = "av01.0.12M.08")
    private val avc = v("1080avc", 1080)

    @Test fun liveTakesTheTallestSdrStreamAndBestAudio() {
        val c = StreamPicker.chooseLive(listOf(avc, sdr4k, hdr4k), listOf(a("128", 128), a("160o", 160, "opus", m4a = false)),
            maxHeight = 2160) { true }!!
        assertEquals("2160sdr", c.video.ref)
        assertEquals("160o", c.audio!!.ref)
    }

    @Test fun liveSkipsWhatTheTvCantDecodeAndWhatsTooTall() {
        val c = StreamPicker.chooseLive(listOf(sdr4k, av1, avc), emptyList(), maxHeight = 2160) {
            !it.codec.startsWith("av01") && !it.codec.startsWith("vp9")
        }!!
        assertEquals("1080avc", c.video.ref)
        assertNull(c.audio)
        assertEquals("1080avc", StreamPicker.chooseLive(listOf(sdr4k, avc), emptyList(), maxHeight = 1080) { true }!!.video.ref)
        assertNull(StreamPicker.chooseLive(listOf(hdr4k), emptyList(), maxHeight = 2160) { true })
    }

    @Test fun codecDescriptions() {
        assertEquals("VP9 profile 2, 10-bit, PQ (HDR10)", StreamPicker.describeCodec("vp09.02.51.10.01.09.16.09.00"))
        assertEquals("VP9 profile 0, 8-bit, SDR (transfer 01)", StreamPicker.describeCodec("vp09.00.51.08.01.01.01.01.00"))
        assertEquals("AV1 main, 10-bit, HLG", StreamPicker.describeCodec("av01.0.13M.10.0.110.09.18.09.0"))
        assertEquals("AV1 main, 8-bit", StreamPicker.describeCodec("av01.0.12M.08"))
        assertEquals("VP9 (SDR)", StreamPicker.describeCodec("vp9"))
        assertEquals("H.264 (SDR)", StreamPicker.describeCodec("avc1.640033"))
    }

    @Test fun youTubesShortVp9HdrLabelIsHdr() {
        assertTrue(StreamPicker.isHdr("vp9.2"))
        assertTrue(StreamPicker.isHdr("vp09.03.51.10.01.09.16.09.00"))
        assertFalse(StreamPicker.isHdr("vp9.0"))
        assertEquals("VP9 profile 2, 10-bit HDR", StreamPicker.describeCodec("vp9.2"))
        assertEquals("VP9 (SDR)", StreamPicker.describeCodec("vp9.0"))
        // As offered on the TV: VP9 HDR and SDR at 2160p60; the SHIELD plays only the SDR one.
        val c = StreamPicker.chooseLive(listOf(
            v("315", 2160, fps = 60, codec = "vp9", mp4 = false),
            v("337", 2160, fps = 60, codec = "vp9.2", mp4 = false),
        ), emptyList(), maxHeight = 2160) { true }!!
        assertEquals("315", c.video.ref)
    }

    private fun webm(ref: String, h: Int, fps: Int = 30, kbps: Int = 1000, codec: String = "vp9") =
        StreamPicker.Video(ref, h, fps, kbps * 1000, codec, mp4 = false, progressive = true, hasAudio = false, webm = true)

    private fun opus(ref: String, kbps: Int) = StreamPicker.Audio(ref, kbps * 1000, "opus", m4a = false, progressive = true, webm = true)

    @Test fun saveTakes4kVp9WithOpusOverH264At1080() {
        val c = StreamPicker.chooseSave(
            videoOnly = listOf(v("1080avc", 1080), webm("2160vp9", 2160), webm("1080vp9", 1080)),
            audio = listOf(a("aac", 128), opus("opus", 160)),
            maxHeight = 2160,
        ) { true }!!
        assertEquals("2160vp9", c.video.ref)
        assertEquals("opus", c.audio!!.ref)
        assertTrue(c.webm)
    }

    @Test fun saveLeavesOutHdrAndWhatTheTvCantDecode() {
        val c = StreamPicker.chooseSave(
            videoOnly = listOf(
                webm("337", 2160, fps = 60, codec = "vp9.2"),
                v("701", 2160, fps = 60, codec = "av01.0.13M.10.0.110.09.16.09.0"),
                v("401", 2160, codec = "av01.0.12M.08"),
                webm("313", 2160),
                v("1080avc", 1080),
            ),
            audio = listOf(a("aac", 128), opus("opus", 160)),
            maxHeight = 2160,
        ) { !it.codec.startsWith("av01") }!!
        assertEquals("313", c.video.ref)
    }

    @Test fun saveKeepsH264AtTheSameHeightAndRespectsTheLimit() {
        val both = listOf(v("1080avc", 1080), webm("1080vp9", 1080, kbps = 3000), webm("2160vp9", 2160))
        val audio = listOf(a("aac", 128), opus("opus", 160))
        val c = StreamPicker.chooseSave(both, audio, maxHeight = 1080) { true }!!
        assertEquals("1080avc", c.video.ref)
        assertEquals("aac", c.audio!!.ref)
        assertFalse(c.webm)
    }

    @Test fun saveNeedsAMatchingAudioStream() {
        // VP9 without Opus can't be joined (MediaMuxer won't put AAC in WebM): H.264 + AAC instead.
        val c = StreamPicker.chooseSave(listOf(webm("2160vp9", 2160), v("1080avc", 1080)), listOf(a("aac", 128)), 2160) { true }!!
        assertEquals("1080avc", c.video.ref)
        assertNull(StreamPicker.chooseSave(listOf(webm("2160vp9", 2160)), listOf(a("aac", 128)), 2160) { true })
    }
}
