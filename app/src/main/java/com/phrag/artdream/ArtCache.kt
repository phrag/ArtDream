package com.phrag.artdream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.phrag.artdream.source.ArtSource
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * On-disk playlist: <key>.jpg + <key>.json per painting in filesDir/art.
 * The screensaver only ever displays from here, so a slow or dead network
 * never stalls it — [refresh] tops the cache up in the background.
 */
class ArtCache(ctx: Context) {

    class Entry(val art: Artwork, val image: File) {
        val addedAt: Long get() = image.lastModified()
    }

    /**
     * Progress callbacks for [refresh]. Called on the refreshing thread — post
     * to the main thread before touching views.
     */
    interface Listener {
        /** A source call started, e.g. "Asking The Met…". */
        fun onStatus(message: String) {}
        /** One image saved; [added] of [wanted] so far. */
        fun onAdded(entry: Entry, added: Int, wanted: Int) {}
        /** A source's batch request failed (network, HTTP error, bad JSON). */
        fun onSourceFailed(source: ArtSource, error: Exception) {}
    }

    private val dir = File(ctx.filesDir, "art").apply { mkdirs() }
    private val prefs = Prefs(ctx)
    private val diag = Diagnostics(ctx)

    /** Last recorded result per source id (for the Diagnostics pane). Reads prefs; call off the main thread. */
    fun lastResult(sourceId: String): SourceResult? = diag.result(sourceId)
    fun lastDownloadAt(): Long = diag.lastDownloadAt

    /** All cached images, newest first. */
    fun entries(): List<Entry> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { meta ->
            val img = File(dir, meta.nameWithoutExtension + ".jpg")
            if (!img.exists()) return@mapNotNull null
            runCatching { Entry(Artwork.fromJson(JSONObject(meta.readText())), img) }.getOrNull()
        }.filter { it.art.key !in prefs.blocked }.sortedByDescending { it.addedAt }

    val isRefreshing: Boolean get() = running.get()

    fun count(): Int = dir.list { _, name -> name.endsWith(".jpg") }?.size ?: 0

    fun remove(art: Artwork) {
        File(dir, art.key + ".jpg").delete()
        File(dir, art.key + ".json").delete()
    }

    /** Never show [art] again: remembered, deleted from disk and skipped by [refresh]. */
    fun block(art: Artwork) {
        prefs.block(art.key)
        remove(art)
    }

    /**
     * Fetch [Prefs.perRun] new images (more if the cache is short of [TARGET]),
     * then trim the oldest down to [Prefs.maxStored] so the rotation keeps moving.
     * Returns how many were added, or [ALREADY_RUNNING] if another refresh (e.g.
     * the screensaver's) is in progress. Only one run happens at a time.
     * Only sources whose category and own toggle are both on are used, minus
     * any still backed off after a rate limit ([Backoff]).
     */
    fun refresh(listener: Listener? = null): Int {
        if (!running.compareAndSet(false, true)) return ALREADY_RUNNING
        try {
            dir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
            val now = System.currentTimeMillis()
            val sources = enabledSources().filter { src ->
                val until = diag.skipUntil(src.id)
                (until <= now).also { if (!it) Log.i(TAG, "${src.id}: rate limited, skipped for ${(until - now) / 1000}s") }
            }
            if (sources.isEmpty()) return 0

            val want = wantedNow()
            var added = 0
            var rounds = 0
            val okSrc = HashSet<String>(); val addedBy = HashMap<String, Int>(); val errBy = HashMap<String, String>()
            val limited = HashSet<String>()   // rate limited during this run: leave them alone
            fun rateLimited(src: ArtSource, e: HttpStatusException) {
                val wait = diag.backOff(src.id, e, System.currentTimeMillis())
                Log.w(TAG, "${src.id} rate limited (HTTP ${e.code}), backing off ${wait / 1000}s")
                limited.add(src.id)
                okSrc.remove(src.id)
                errBy[src.id] = RATE_LIMITED
                listener?.onSourceFailed(src, e)
            }
            // Larger runs need more passes: each pass yields at most BATCH per source,
            // and some candidates are rejected (portrait, wrong licence, failed download).
            val maxRounds = 5 + want / BATCH
            while (added < want && rounds++ < maxRounds && !Thread.currentThread().isInterrupted) {
                for (src in sources.shuffled()) {
                    if (added >= want || Thread.currentThread().isInterrupted) break
                    if (src.id in limited) continue
                    listener?.onStatus("Asking ${src.label}…")
                    val batch = try {
                        withRetry { src.fetchBatch(BATCH, Random.Default) }.also {
                            okSrc.add(src.id)
                            errBy.remove(src.id)
                            diag.clearBackoff(src.id)
                        }
                    } catch (e: Exception) {
                        if (Backoff.isRateLimit(e)) { rateLimited(src, e as HttpStatusException); continue }
                        Log.w(TAG, "${src.id} batch failed: $e")
                        errBy[src.id] = Diagnostics.shortError(e)
                        listener?.onSourceFailed(src, e)
                        continue
                    }
                    Log.i(TAG, "${src.id}: batch of ${batch.size}")
                    val blocked = prefs.blocked
                    try {
                        for (art in batch) {
                            if (art.key in blocked) continue
                            if (added >= want || Thread.currentThread().isInterrupted) break
                            if (File(dir, art.key + ".json").exists()) continue
                            if (prefs.landscapeOnly && art.width > 0 && art.width < art.height) continue
                            listener?.onStatus("Downloading “${art.title.ifEmpty { "untitled" }}”")
                            if (download(art)) {
                                added++
                                addedBy[src.id] = (addedBy[src.id] ?: 0) + 1
                                Log.i(TAG, "saved ${art.key} ($added/$want)")
                                listener?.onAdded(Entry(art, File(dir, art.key + ".jpg")), added, want)
                            }
                        }
                    } catch (e: HttpStatusException) {
                        rateLimited(src, e)   // the image host limited us: same operator, same backoff
                    }
                }
            }
            trim()
            recordResults(sources, okSrc, addedBy, errBy, added)
            Log.i(TAG, "refresh done: added $added of $want, ${count()} cached")
            return added
        } finally {
            running.set(false)
        }
    }

    /**
     * How many images the next [refresh] will try to add: the per-run setting,
     * or enough to reach [TARGET] when the library is small, but never more
     * than the library keeps (the trim would delete the new ones).
     */
    fun wantedNow(): Int = minOf(maxOf(prefs.perRun, TARGET - count()), prefs.maxStored)

    fun enabledSources(): List<ArtSource> =
        ArtSource.ALL.filter { prefs.isCategoryOn(it.category) && prefs.isSourceOn(it.id) }

    private fun recordResults(
        sources: List<ArtSource>, ok: Set<String>, added: Map<String, Int>, err: Map<String, String>, total: Int,
    ) {
        val now = System.currentTimeMillis()
        for (src in sources) {
            // Only record sources we actually asked (a run can finish before reaching all of them).
            if (src.id !in ok && src.id !in err) continue
            val failed = src.id !in ok
            diag.record(src.id, SourceResult(now, !failed, added[src.id] ?: 0, if (failed) err[src.id] else null))
        }
        if (total > 0) diag.lastDownloadAt = now
    }

    /**
     * Run [block], retrying transient failures (HTTP 429/5xx, timeouts, dropped connections)
     * with 1s, 2s backoff. Gives up after [RETRY_BUDGET_MS] in total or when interrupted,
     * or at once when a Retry-After asks for longer than the next wait.
     */
    private fun <T> withRetry(block: () -> T): T {
        val start = System.currentTimeMillis()
        var delay = 1000L
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                attempt++
                val left = RETRY_BUDGET_MS - (System.currentTimeMillis() - start)
                if (!isTransient(e) || attempt >= 3 || left <= delay || Thread.currentThread().isInterrupted) throw e
                val asked = (e as? HttpStatusException)?.let { Backoff.parseRetryAfter(it.retryAfter, System.currentTimeMillis()) }
                if (asked != null && asked > delay) throw e
                Log.i(TAG, "transient failure (${Diagnostics.shortError(e)}), retry in ${delay}ms")
                try { Thread.sleep(delay) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt(); throw e
                }
                delay *= 2
            }
        }
    }

    private fun isTransient(e: Exception): Boolean {
        if (e is java.net.SocketTimeoutException) return true
        if (e is java.net.UnknownHostException) return false
        if (e is HttpStatusException) return e.code == 429 || e.code in 500..599
        return e is java.io.IOException   // reset / dropped connection
    }

    /**
     * Try [art]'s image URLs in turn. Never leaves the .part file behind. A rate
     * limit is rethrown (as [HttpStatusException]) so [refresh] backs the source off.
     */
    private fun download(art: Artwork): Boolean {
        val part = File(dir, art.key + ".part")
        for (url in art.imageUrls) {
            try {
                withRetry { Http.download(url, part) }
                if (accept(part)) {
                    if (!part.renameTo(File(dir, art.key + ".jpg"))) throw java.io.IOException("rename failed")
                    File(dir, art.key + ".json").writeText(art.toJson().toString())
                    return true
                }
                Log.i(TAG, "rejected ${art.key} (not landscape or undecodable)")
                part.delete()
                return false   // decoded fine but rejected (e.g. portrait); smaller copy won't help
            } catch (e: Exception) {
                Log.w(TAG, "download $url: $e")
                part.delete()
                if (Backoff.isRateLimit(e)) throw e
            }
        }
        return false
    }

    /** Validate, apply the aspect filter, and shrink anything bigger than 4K. */
    private fun accept(file: File): Boolean {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, b)
        if (b.outWidth <= 0 || b.outHeight <= 0) return false
        if (prefs.landscapeOnly && b.outWidth < b.outHeight) return false

        if (maxOf(b.outWidth, b.outHeight) > MAX_EDGE) {
            var sample = 2
            while (maxOf(b.outWidth, b.outHeight) / sample > MAX_EDGE) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return false
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
        }
        return true
    }

    private fun trim() {
        val imgs = dir.listFiles { f -> f.extension == "jpg" } ?: return
        val max = prefs.maxStored
        if (imgs.size <= max) return
        val keep = prefs.favourites
        imgs.filter { it.nameWithoutExtension !in keep }.sortedBy { it.lastModified() }
            .take(imgs.size - max).forEach { img ->
            img.delete()
            File(dir, img.nameWithoutExtension + ".json").delete()
        }
    }

    companion object {
        private const val TAG = "fArtSaver"
        const val TARGET = 30          // minimum playlist before we stop topping up eagerly
        const val BATCH = 10
        private const val RETRY_BUDGET_MS = 8_000L
        const val MAX_EDGE = 4096
        const val ALREADY_RUNNING = -1
        /** Diagnostics error for a backed-off source; [DiagnosticsPane] adds the time left. */
        const val RATE_LIMITED = "Rate limited"
        private val running = AtomicBoolean(false)
    }
}
