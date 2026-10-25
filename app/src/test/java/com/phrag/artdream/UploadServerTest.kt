package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress

class UploadServerTest {

    @Test fun parsesRequestLineQueryAndHeaders() {
        val r = HttpRequest.parse(
            "PUT /k3m9x2qp/upload?name=my%20holiday%20%281%29.mp4&x=1 HTTP/1.1\r\n" +
                "Host: 192.168.2.115:8765\r\nContent-Length: 1234\r\nExpect: 100-continue",
        )!!
        assertEquals("PUT", r.method)
        assertEquals("/k3m9x2qp/upload", r.path)
        assertEquals("my holiday (1).mp4", r.query["name"])
        assertEquals("1", r.query["x"])
        assertEquals(1234L, r.contentLength)
        assertEquals("100-continue", r.headers["expect"])
    }

    @Test fun plusIsASpaceAndPercentTwoBIsAPlus() {
        val r = HttpRequest.parse("PUT /t/upload?name=a+b%2Bc.jpg HTTP/1.1")!!
        assertEquals("a b+c.jpg", r.query["name"])
    }

    @Test fun missingContentLengthIsMinusOne() {
        assertEquals(-1L, HttpRequest.parse("PUT /t/upload HTTP/1.1")!!.contentLength)
        assertEquals(-1L, HttpRequest.parse("PUT /t/upload HTTP/1.1\r\nContent-Length: abc")!!.contentLength)
    }

    @Test fun rejectsMalformedRequests() {
        assertNull(HttpRequest.parse(""))
        assertNull(HttpRequest.parse("GET"))
        assertNull(HttpRequest.parse("GET / FTP/1.0"))
        assertNull(HttpRequest.parse("GET http://evil/ HTTP/1.1"))
        assertNull(HttpRequest.parse("GET /t/upload?name=%zz HTTP/1.1"))
    }

    @Test fun readHeadStopsAtTheBlankLineAndLeavesTheBody() {
        val raw = "PUT /t/upload HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc"
        val input = ByteArrayInputStream(raw.toByteArray())
        assertEquals("PUT /t/upload HTTP/1.1\r\nContent-Length: 3", HttpRequest.readHead(input))
        assertEquals("abc", String(input.readBytes()))
    }

    @Test fun readHeadRefusesOversizedOrTruncatedHeads() {
        assertNull(HttpRequest.readHead(ByteArrayInputStream("GET / HTTP/1.1\r\nHost: x".toByteArray())))
        assertNull(HttpRequest.readHead(ByteArrayInputStream("A".repeat(HttpRequest.MAX_HEAD + 10).toByteArray())))
    }

    @Test fun onlyPrivateNetworksAreServed() {
        fun ok(ip: String) = UploadServer.isPrivatePeer(InetAddress.getByName(ip))
        assertTrue(ok("192.168.2.50"))
        assertTrue(ok("10.0.0.7"))
        assertTrue(ok("172.16.5.5"))
        assertTrue(ok("127.0.0.1"))
        assertTrue(ok("169.254.10.10"))
        assertTrue(ok("fd12:3456:789a::1"))     // unique-local
        assertTrue(ok("fe80::1"))               // link-local
        assertFalse(ok("8.8.8.8"))
        assertFalse(ok("159.26.104.113"))
        assertFalse(ok("172.32.0.1"))           // just outside 172.16/12
        assertFalse(ok("2003:fb:ef23::1"))      // a global IPv6 address
    }

    @Test fun tokensAreLongEnoughAndUnlikelyToRepeat() {
        val t = UploadServer.newToken()
        assertEquals(8, t.length)
        assertTrue(t.all { it.isLowerCase() || it.isDigit() })
        assertTrue(t.none { it in "l1oi0" })
        assertNotEquals(t, UploadServer.newToken())
    }
}
