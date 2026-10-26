package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    private fun rows(q: QrCode) = (0 until q.size).map { y -> (0 until q.size).joinToString("") { x -> if (q[x, y]) "1" else "0" } }

    // Checked by decoding with ZXing when written; any change here is a real change to the output.
    @Test fun knownSymbol() {
        val expected = """
            111111101001101111111
            100000100110101000001
            101110100101101011101
            101110101011101011101
            101110100000001011101
            100000100101001000001
            111111101010101111111
            000000000010000000000
            001011101001010001001
            001100010100010000110
            011010101101000010001
            100101000110101000100
            110010101100001010101
            000000001010110101011
            111111100010011101111
            100000101111011111000
            101110101100001101101
            101110100010111000110
            101110101011010010001
            100000100010111000110
            111111100000111010111
        """.trimIndent().lines()
        assertEquals(expected, rows(QrCode.encode("a")))
    }

    @Test fun uploadAddressFitsASmallCode() {
        val q = QrCode.encode("http://192.168.1.23:41234/abcdefgh/")
        assertEquals("version 3", 29, q.size)
        assertFinder(q, 0, 0); assertFinder(q, q.size - 7, 0); assertFinder(q, 0, q.size - 7)
        for (i in 8 until q.size - 8) assertEquals("timing $i", i % 2 == 0, q[i, 6])
        assertTrue("dark module", q[8, q.size - 8])
    }

    @Test fun capacity() {
        // Version 40-L holds 2953 bytes; version 1-M holds 14.
        assertEquals(177, QrCode.encode("q".repeat(2953), QrCode.Ecc.L).size)
        assertEquals(21, QrCode.encode("q".repeat(14)).size)
        assertEquals(25, QrCode.encode("q".repeat(15)).size)
        assertEquals(QrCode.dataCodewords(40, QrCode.Ecc.L), 2956)
    }

    @Test(expected = IllegalArgumentException::class) fun tooLong() {
        QrCode.encode("q".repeat(2954), QrCode.Ecc.L)
    }

    @Test fun alignmentPositions() {
        assertEquals(listOf(6, 18), QrCode.alignmentPositions(2).toList())
        assertEquals(listOf(6, 22, 38), QrCode.alignmentPositions(7).toList())
        assertEquals(listOf(6, 30, 58, 86, 114, 142, 170), QrCode.alignmentPositions(40).toList())
    }

    private fun assertFinder(q: QrCode, x0: Int, y0: Int) {
        for (dy in 0 until 7) for (dx in 0 until 7) {
            val d = maxOf(kotlin.math.abs(dx - 3), kotlin.math.abs(dy - 3))
            assertEquals("finder at $x0,$y0 +$dx,$dy", d != 2, q[x0 + dx, y0 + dy])
        }
    }
}
