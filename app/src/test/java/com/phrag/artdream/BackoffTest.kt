package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackoffTest {

    // Wed, 21 Oct 2026 07:28:00 GMT
    private val now = 1792567680_000L
    private val min = 60_000L

    @Test fun retryAfterSeconds() {
        assertEquals(120_000L, Backoff.parseRetryAfter("120", now))
        assertEquals(0L, Backoff.parseRetryAfter(" 0 ", now))
    }

    @Test fun retryAfterHttpDate() {
        assertEquals(40 * min, Backoff.parseRetryAfter("Wed, 21 Oct 2026 08:08:00 GMT", now))
        assertEquals("date in the past", 0L, Backoff.parseRetryAfter("Wed, 21 Oct 2026 07:00:00 GMT", now))
    }

    @Test fun retryAfterMissingOrJunk() {
        assertNull(Backoff.parseRetryAfter(null, now))
        assertNull(Backoff.parseRetryAfter("", now))
        assertNull(Backoff.parseRetryAfter("soon", now))
        assertNull(Backoff.parseRetryAfter("-5", now))
    }

    @Test fun exponentialFrom15MinTo24h() {
        assertEquals(15 * min, Backoff.nextDelay(0))
        assertEquals(30 * min, Backoff.nextDelay(15 * min))
        assertEquals(60 * min, Backoff.nextDelay(30 * min))
        assertEquals(Backoff.MAX_MS, Backoff.nextDelay(16 * 60 * min))
        assertEquals(Backoff.MAX_MS, Backoff.nextDelay(Backoff.MAX_MS))
        assertEquals("short Retry-After before", 15 * min, Backoff.nextDelay(30_000))
    }

    @Test fun retryAfterWinsButIsCapped() {
        assertEquals(40 * min, Backoff.delayFor(HttpStatusException(429, "2400", "u"), 60 * min, now))
        assertEquals(30 * min, Backoff.delayFor(HttpStatusException(429, null, "u"), 15 * min, now))
        assertEquals(Backoff.MAX_MS, Backoff.delayFor(HttpStatusException(429, "9999999", "u"), 0, now))
    }

    @Test fun whatCountsAsRateLimit() {
        assertTrue(Backoff.isRateLimit(HttpStatusException(429, null, "u")))
        assertTrue(Backoff.isRateLimit(HttpStatusException(503, "60", "u")))
        assertFalse("plain 503 is retried as before", Backoff.isRateLimit(HttpStatusException(503, null, "u")))
        assertFalse(Backoff.isRateLimit(HttpStatusException(404, null, "u")))
        assertFalse(Backoff.isRateLimit(java.io.IOException("HTTP 429")))
    }

    @Test fun messageKeepsShortErrorForm() {
        assertEquals("HTTP 429", Diagnostics.shortError(HttpStatusException(429, null, "https://example.org/x")))
    }

    @Test fun formatsWait() {
        assertEquals("less than a minute", Backoff.formatWait(20_000))
        assertEquals("40 min", Backoff.formatWait(40 * min))
        assertEquals("2 h", Backoff.formatWait(2 * 60 * min))
    }
}
