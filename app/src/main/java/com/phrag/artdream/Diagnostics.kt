package com.phrag.artdream

import android.content.Context

/**
 * Outcome of the most recent refresh attempt for one source. [skipUntil]: the
 * source is rate limited and skipped until then (see [Backoff]); 0 if not.
 */
class SourceResult(val at: Long, val ok: Boolean, val added: Int, val error: String?, val skipUntil: Long = 0L)

/** Per-source last result, rate-limit backoff and last-download time, persisted so they survive restarts. */
class Diagnostics(ctx: Context) {
    private val sp = ctx.getSharedPreferences("artdream_diag", Context.MODE_PRIVATE)

    fun result(sourceId: String): SourceResult? {
        val at = sp.getLong("at_$sourceId", 0L)
        if (at == 0L) return null
        return SourceResult(at, sp.getBoolean("ok_$sourceId", false), sp.getInt("added_$sourceId", 0),
            sp.getString("err_$sourceId", null), skipUntil(sourceId))
    }

    fun record(sourceId: String, r: SourceResult) {
        sp.edit().putLong("at_$sourceId", r.at).putBoolean("ok_$sourceId", r.ok)
            .putInt("added_$sourceId", r.added).putString("err_$sourceId", r.error).apply()
    }

    /** Until when [sourceId] is skipped after a rate limit, or 0. */
    fun skipUntil(sourceId: String): Long = sp.getLong("skip_until_$sourceId", 0L)

    /** Back [sourceId] off after [e] (a [Backoff.isRateLimit] error); returns the wait in ms. */
    fun backOff(sourceId: String, e: HttpStatusException, now: Long): Long {
        val wait = Backoff.delayFor(e, sp.getLong("backoff_ms_$sourceId", 0L), now)
        sp.edit().putLong("skip_until_$sourceId", now + wait).putLong("backoff_ms_$sourceId", wait).apply()
        return wait
    }

    /** A successful request: the next rate limit starts again at [Backoff.FIRST_MS]. */
    fun clearBackoff(sourceId: String) {
        if (!sp.contains("skip_until_$sourceId") && !sp.contains("backoff_ms_$sourceId")) return
        sp.edit().remove("skip_until_$sourceId").remove("backoff_ms_$sourceId").apply()
    }

    /** Time the last refresh that actually saved at least one image finished, or 0. */
    var lastDownloadAt: Long
        get() = sp.getLong("last_download_at", 0L)
        set(v) = sp.edit().putLong("last_download_at", v).apply()

    companion object {
        /** One-line error without URLs or noise, e.g. "HTTP 503" or "Timed out". */
        fun shortError(e: Exception): String {
            val m = e.message.orEmpty()
            val http = Regex("HTTP (\\d{3})").find(m)
            val s = when {
                http != null -> "HTTP ${http.groupValues[1]}"
                e is java.net.SocketTimeoutException -> "Timed out"
                e is java.net.UnknownHostException -> "No connection"
                m.isBlank() -> e.javaClass.simpleName
                else -> m.replace(Regex("https?://\\S+"), "").trim().ifEmpty { e.javaClass.simpleName }
            }
            return if (s.length > 80) s.take(79) + "…" else s
        }
    }
}
