package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoInfoTest {

    @Test fun hdrFromTransferOrDolbyVision() {
        assertEquals("HDR10 (PQ)", VideoInfo.hdrName(VideoInfo.TRANSFER_PQ, "video/x-vnd.on2.vp9", "vp09.02.51.10.01.09.16.09.00"))
        assertEquals("HLG", VideoInfo.hdrName(VideoInfo.TRANSFER_HLG, "video/hevc", null))
        assertEquals("SDR", VideoInfo.hdrName(VideoInfo.TRANSFER_SDR, "video/avc", "avc1.640028"))
        assertEquals("SDR (not signalled)", VideoInfo.hdrName(null, "video/x-vnd.on2.vp9", "vp9"))
        assertEquals("SDR (not signalled)", VideoInfo.hdrName(-1, null, null))
        assertEquals("Dolby Vision", VideoInfo.hdrName(VideoInfo.TRANSFER_PQ, "video/dolby-vision", null))
        assertEquals("Dolby Vision", VideoInfo.hdrName(null, "video/hevc", "dvh1.08.06"))
    }

    @Test fun codecFromCodecString() {
        assertEquals("VP9 profile 2, 10-bit", VideoInfo.codecName("video/x-vnd.on2.vp9", "vp09.02.51.10.01.09.16.09.00"))
        assertEquals("VP9", VideoInfo.codecName("video/x-vnd.on2.vp9", "vp9"))
        assertEquals("VP9 profile 2, 10-bit", VideoInfo.codecName("video/x-vnd.on2.vp9", "vp9.2"))
        assertEquals("AV1 Main, 10-bit", VideoInfo.codecName("video/av01", "av01.0.12M.10.0.110.09.16.09.0"))
        assertEquals("H.264 High", VideoInfo.codecName("video/avc", "avc1.640028"))
        assertEquals("HEVC Main 10", VideoInfo.codecName("video/hevc", "hvc1.2.4.L153.B0"))
        assertEquals("Dolby Vision profile 8", VideoInfo.codecName("video/dolby-vision", "dvh1.08.06"))
    }

    @Test fun codecFromMimeAndProfile() {
        assertEquals("HEVC Main 10 HDR10", VideoInfo.codecName("video/hevc", null, 0x1000))
        assertEquals("H.264 High", VideoInfo.codecName("video/avc", null, 0x8))
        assertEquals("VP9 profile 2", VideoInfo.codecName("video/x-vnd.on2.vp9", null, 0x4))
        assertEquals("Dolby Vision profile 5", VideoInfo.codecName("video/dolby-vision", null, 0x20))
        assertEquals("HEVC", VideoInfo.codecName("video/hevc", null, null))
        assertEquals("unknown codec", VideoInfo.codecName(null, null))
    }

    @Test fun pictureBitrateAndScreen() {
        assertEquals("3840x2160 · 60 fps", VideoInfo.picture(3840, 2160, 60f))
        assertEquals("1920x1080 · 23.98 fps", VideoInfo.picture(1920, 1080, 23.976f))
        assertEquals("1280x720", VideoInfo.picture(1280, 720, -1f))
        assertEquals("size unknown", VideoInfo.picture(0, 0, 0f))
        assertEquals("18.4 Mbps", VideoInfo.bitrate(18_400_000))
        assertEquals("850 kbps", VideoInfo.bitrate(850_000))
        assertNull(VideoInfo.bitrate(-1))
        assertEquals("TV 3840x2160 60 Hz · DV/HDR10/HLG", VideoInfo.screen(3840, 2160, 60f, listOf(1, 2, 3)))
        assertEquals("TV 1920x1080 50 Hz · no HDR", VideoInfo.screen(1920, 1080, 50f, emptyList()))
    }
}
