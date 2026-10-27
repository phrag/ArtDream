package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrMediaTest {

    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) {
            is String -> p.toByteArray(Charsets.ISO_8859_1).toList()
            is Int -> listOf(p.toByte())
            is ByteArray -> p.toList()
            else -> error("bad part $p")
        }
    }.toByteArray()

    private val noise = ByteArray(300) { (it * 37 + 11).toByte() }

    @Test fun ultraHdrJpeg() {
        val xmp = """<x:xmpmeta><rdf:Description xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"/></x:xmpmeta>"""
        assertEquals(HdrMedia.GAIN_MAP, HdrMedia.imageKind(bytes(0xFF, 0xD8, 0xFF, 0xE1, noise, xmp, noise)))
        assertEquals(HdrMedia.GAIN_MAP, HdrMedia.imageKind(bytes(0xFF, 0xD8, noise, "urn:iso:std:iso:ts:21496:-1", noise)))
    }

    @Test fun plainJpegIsSdr() {
        assertNull(HdrMedia.imageKind(bytes(0xFF, 0xD8, 0xFF, 0xE0, "JFIF", 0, noise, "ICC_PROFILE", noise)))
    }

    @Test fun pngCicp() {
        val pq = bytes(0x89, "PNG\r\n", 0x1A, "\n", 0, 0, 0, 4, "cICP", 9, 16, 0, 1, noise)
        val hlg = bytes(0x89, "PNG\r\n", 0x1A, "\n", 0, 0, 0, 4, "cICP", 9, 18, 0, 1, noise)
        val p3 = bytes(0x89, "PNG\r\n", 0x1A, "\n", 0, 0, 0, 4, "cICP", 12, 13, 0, 1, noise)
        assertEquals(HdrMedia.HDR10, HdrMedia.imageKind(pq))
        assertEquals(HdrMedia.HLG, HdrMedia.imageKind(hlg))
        assertNull(HdrMedia.imageKind(p3))   // Display P3 with the sRGB curve: wide gamut, not HDR
    }

    @Test fun iccCicpTagAndProfileName() {
        assertEquals(HdrMedia.HDR10, HdrMedia.imageKind(bytes(noise, "cicp", 0, 0, 0, 0, 9, 16, 0, 1, noise)))
        // "cicp" in data that isn't the tag (no zero padding) isn't read as one.
        assertNull(HdrMedia.imageKind(bytes(noise, "cicp", 1, 2, 3, 4, 9, 16, 0, 1, noise)))
        val utf16 = "Rec. ITU-R BT.2100 PQ".flatMap { listOf(0, it.code) }.map { it.toByte() }.toByteArray()
        assertEquals(HdrMedia.HDR10, HdrMedia.imageKind(bytes(noise, "mluc", utf16, noise)))
        assertEquals(HdrMedia.HLG, HdrMedia.imageKind(bytes(noise, "desc", "ITUR_2100_HLG", noise)))
    }

    @Test fun heifNclxAndAvifGainMap() {
        val ftyp = bytes(0, 0, 0, 24, "ftypavif")
        assertEquals(HdrMedia.HDR10, HdrMedia.imageKind(bytes(ftyp, noise, 0, 0, 0, 19, "colr", "nclx", 0, 9, 0, 16, 0, 9, 0x80, noise)))
        assertNull(HdrMedia.imageKind(bytes(ftyp, noise, 0, 0, 0, 19, "colr", "nclx", 0, 1, 0, 13, 0, 1, 0x80, noise)))
        // infe version 2: version/flags, u16 item id, u16 protection index, item type.
        assertEquals(HdrMedia.GAIN_MAP, HdrMedia.imageKind(bytes(ftyp, noise, 0, 0, 0, 21, "infe", 2, 0, 0, 0, 0, 3, 0, 0, "tmap", noise)))
        assertNull(HdrMedia.imageKind(bytes(ftyp, noise, 0, 0, 0, 21, "infe", 2, 0, 0, 0, 0, 3, 0, 0, "av01", noise, "tmap")))
    }

    @Test fun videoKinds() {
        assertEquals(HdrMedia.HDR10, HdrMedia.videoKind("video/hevc", VideoInfo.TRANSFER_PQ, 2))
        assertEquals(HdrMedia.HLG, HdrMedia.videoKind("video/hevc", VideoInfo.TRANSFER_HLG, 2))
        assertEquals(HdrMedia.DOLBY_VISION, HdrMedia.videoKind("video/dolby-vision", null, 256))
        assertEquals(HdrMedia.HDR10, HdrMedia.videoKind("video/hevc", null, 0x1000))
        assertEquals(HdrMedia.HDR10, HdrMedia.videoKind("video/x-vnd.on2.vp9", null, 0x1000))
        assertNull(HdrMedia.videoKind("video/hevc", VideoInfo.TRANSFER_SDR, 2))   // Main 10 alone isn't HDR
        assertNull(HdrMedia.videoKind("video/avc", null, 8))
        assertNull(HdrMedia.videoKind(null, null, null))
    }

    @Test fun fillOnlyWhenLittleIsCut() {
        assertTrue(HdrMedia.fillsScreen(3840, 2160, 1920, 1080))
        assertTrue(HdrMedia.fillsScreen(4032, 2268, 1920, 1080))
        assertTrue(HdrMedia.fillsScreen(3840, 1920, 1920, 1080))   // 2:1, about 11% cut
        assertFalse(HdrMedia.fillsScreen(4032, 3024, 1920, 1080))  // 4:3 phone photo
        assertFalse(HdrMedia.fillsScreen(3024, 4032, 1920, 1080))  // portrait
        assertFalse(HdrMedia.fillsScreen(0, 0, 1920, 1080))
    }
}
