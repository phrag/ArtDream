package com.phrag.artdream

import kotlin.math.abs

/**
 * A QR code (ISO/IEC 18004) for a short string, so a phone camera can open the upload address
 * without typing it. Byte mode only, any version 1-40, and the error correction is raised as far
 * as fits in the smallest version. Written out rather than pulling in a barcode library: the app
 * keeps its runtime dependencies to a minimum. Follows the structure of Project Nayuki's
 * reference encoder. Pure Kotlin, tested on the JVM.
 */
class QrCode private constructor(val size: Int, private val dark: Array<BooleanArray>) {

    /** True for a dark module; x is the column, y the row, both from the top left. */
    operator fun get(x: Int, y: Int): Boolean = dark[y][x]

    enum class Ecc(val formatBits: Int) { L(1), M(0), Q(3), H(2) }

    companion object {
        /** Smallest code that holds [text] as UTF-8 with at least [minEcc] error correction. */
        fun encode(text: String, minEcc: Ecc = Ecc.M): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            var ecc = minEcc
            val version = (1..40).firstOrNull { usedBits(data.size, it) <= dataCodewords(it, ecc) * 8 }
                ?: throw IllegalArgumentException("text too long for a QR code: ${data.size} bytes")
            for (e in Ecc.values()) {   // ascending L, M, Q, H
                if (e.ordinal > ecc.ordinal && usedBits(data.size, version) <= dataCodewords(version, e) * 8) ecc = e
            }
            return Builder(version, ecc).build(codewords(data, version, ecc))
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        private fun usedBits(len: Int, version: Int): Int =
            if (len >= 1 shl countBits(version)) Int.MAX_VALUE else 4 + countBits(version) + 8 * len

        /** Mode, length, data, terminator and padding, as the data codewords for [version]. */
        private fun codewords(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
            val capacity = dataCodewords(version, ecc) * 8
            val bits = ArrayList<Boolean>(capacity)
            fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((value ushr i) and 1 != 0) }
            put(0b0100, 4)
            put(data.size, countBits(version))
            for (b in data) put(b.toInt() and 0xFF, 8)
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return ByteArray(capacity / 8) { i ->
                var v = 0
                for (j in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + j]) 1 else 0)
                v.toByte()
            }
        }

        /** Modules left for data and error correction once the function patterns are drawn. */
        internal fun rawDataModules(version: Int): Int {
            var n = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                n -= (25 * align - 10) * align - 55
                if (version >= 7) n -= 36
            }
            return n
        }

        internal fun dataCodewords(version: Int, ecc: Ecc): Int =
            rawDataModules(version) / 8 - ECC_PER_BLOCK[ecc.ordinal][version] * BLOCKS[ecc.ordinal][version]

        internal fun alignmentPositions(version: Int): IntArray {
            if (version == 1) return IntArray(0)
            val n = version / 7 + 2
            val step = (version * 8 + n * 3 + 5) / (n * 4 - 4) * 2
            val result = IntArray(n)
            result[0] = 6
            var pos = version * 4 + 17 - 7
            for (i in n - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        // ---- Reed-Solomon over GF(2^8), polynomial 0x11D ---------------------------------------

        private fun gfMul(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            for (i in 0 until degree) {
                for (j in 0 until degree) {
                    result[j] = gfMul(result[j], root)
                    if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
                }
                root = gfMul(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        /** Splits the data into blocks, appends each block's error correction, and interleaves. */
        private fun withEcc(data: ByteArray, version: Int, ecc: Ecc): ByteArray {
            val blocks = BLOCKS[ecc.ordinal][version]
            val eccLen = ECC_PER_BLOCK[ecc.ordinal][version]
            val raw = rawDataModules(version) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLen = raw / blocks
            val divisor = rsDivisor(eccLen)
            val all = ArrayList<ByteArray>(blocks)
            var k = 0
            for (i in 0 until blocks) {
                val len = shortLen - eccLen + if (i < shortBlocks) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val block = ByteArray(shortLen + 1)   // short blocks keep a gap byte, skipped below
                dat.copyInto(block, 0)
                rsRemainder(dat, divisor).copyInto(block, shortLen + 1 - eccLen)
                all.add(block)
            }
            val out = ByteArray(raw)
            var n = 0
            for (i in 0..shortLen) {
                for (j in 0 until blocks) {
                    if (i != shortLen - eccLen || j >= shortBlocks) out[n++] = all[j][i]
                }
            }
            return out
        }

        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )
        private val BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )
    }

    /** Lays out one symbol: function patterns, codewords, then the mask with the lowest penalty. */
    private class Builder(val version: Int, val ecc: Ecc) {
        val size = version * 4 + 17
        val dark = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun build(data: ByteArray): QrCode {
            drawFunctionPatterns()
            drawCodewords(withEcc(data, version, ecc))
            var best = 0
            var bestPenalty = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask)
                drawFormat(mask)
                val p = penalty()
                if (p < bestPenalty) { best = mask; bestPenalty = p }
                applyMask(mask)   // XOR again to undo
            }
            applyMask(best)
            drawFormat(best)
            return QrCode(size, dark)
        }

        private fun set(x: Int, y: Int, on: Boolean) { dark[y][x] = on; function[y][x] = true }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignmentPositions(version)
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(pos[i] + dx, pos[j] + dy, maxOf(abs(dx), abs(dy)) != 1)
            }
            drawFormat(0)   // reserve the area; redrawn once the mask is chosen
            drawVersion()
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                val d = maxOf(abs(dx), abs(dy))
                if (x in 0 until size && y in 0 until size) set(x, y, d != 2 && d != 4)
            }
        }

        private fun drawFormat(mask: Int) {
            val data = ecc.formatBits shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = version shl 12 or rem
            for (i in 0 until 18) {
                val on = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, on); set(b, a, on)
            }
        }

        /** Zigzag up and down column pairs from the right, skipping the vertical timing line. */
        private fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val y = if ((right + 1) and 2 == 0) size - 1 - vert else vert
                    if (!function[y][x] && i < data.size * 8) {
                        dark[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val flip = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip && !function[y][x]) dark[y][x] = !dark[y][x]
            }
        }

        /** The standard's four penalty rules; only steers the mask choice, any mask decodes. */
        private fun penalty(): Int {
            var p = 0
            fun at(x: Int, y: Int, vertical: Boolean) = if (vertical) dark[x][y] else dark[y][x]
            for (vertical in listOf(false, true)) for (y in 0 until size) {
                var run = 1
                for (x in 1 until size) {
                    if (at(x, y, vertical) == at(x - 1, y, vertical)) {
                        run++
                        if (run == 5) p += 3 else if (run > 5) p++
                    } else run = 1
                }
                // 1:1:3:1:1 finder look-alikes with four light modules on either side
                for (x in 0..size - 11) {
                    val a = FINDER_LIKE.indices.all { at(x + it, y, vertical) == FINDER_LIKE[it] }
                    val b = FINDER_LIKE.indices.all { at(x + it, y, vertical) == FINDER_LIKE[10 - it] }
                    if (a) p += 40
                    if (b) p += 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = dark[y][x]
                if (c == dark[y][x + 1] && c == dark[y + 1][x] && c == dark[y + 1][x + 1]) p += 3
            }
            val total = size * size
            val darkCount = dark.sumOf { row -> row.count { it } }
            p += ((abs(darkCount * 20 - total * 10) + total - 1) / total - 1) * 10
            return p
        }

        private companion object {
            val FINDER_LIKE = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        }
    }
}
