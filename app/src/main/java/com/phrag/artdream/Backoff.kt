package com.phrag.artdream

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Per-source backoff after a rate limit. Shared IPs (VPN exits, CGNAT) often
 * get HTTP 429 from Wikimedia, AIC and others; hammering them again on every
 * refresh only extends the ban. A source that is rate limited is skipped until
 * the time the server asked for (Retry-After), or else for 15 min, doubling on
 * each further limit up to a day. A successful batch resets it.
 * Pure functions; [Diagnostics] stores the state.
 */
object Backoff {
    const val FIRST_MS = 15 * 60 * 1000L
    const val MAX_MS = 24 * 60 * 60 * 1000L

    /** 429, or 503 with a Retry-After (a plain 503 is usually a passing outage, retried as before). */
    fun isRateLimit(e: Throwable): Boolean =
        e is HttpStatusException && (e.code == 429 || (e.code == 503 && e.retryAfter != null))

    /**
     * Milliseconds the server asks us to wait, from a Retry-After header in
     * either form: delta-seconds ("120") or an HTTP-date ("Wed, 21 Oct 2026
     * 07:28:00 GMT"), relative to [now]. Null when absent or unparseable.
     */
    fun parseRetryAfter(header: String?, now: Long): Long? {
        val s = header?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
        return try {
            (ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now)
                .coerceAtLeast(0)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * How long to skip a source after [e], given the delay used last time
     * ([previousMs], 0 if none since the last success). Retry-After wins when
     * present; otherwise the previous delay doubles. Always within [FIRST_MS]..[MAX_MS]
     * for the exponential case, and capped at [MAX_MS] so a bogus header can't
     * disable a source for good.
     */
    fun delayFor(e: HttpStatusException, previousMs: Long, now: Long): Long {
        parseRetryAfter(e.retryAfter, now)?.let { return it.coerceAtMost(MAX_MS) }
        return nextDelay(previousMs)
    }

    fun nextDelay(previousMs: Long): Long =
        if (previousMs <= 0) FIRST_MS else (previousMs * 2).coerceIn(FIRST_MS, MAX_MS)

    /** "40 min", "3 h", "less than a minute": for "Rate limited, retrying in …". */
    fun formatWait(ms: Long): String {
        val min = (ms + 59_999) / 60_000
        return when {
            ms < 60_000 -> "less than a minute"
            min < 60 -> "$min min"
            else -> "${(min + 30) / 60} h"
        }
    }
}
