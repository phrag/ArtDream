package com.example.artdream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.artdream.source.ArtSource
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

    /** All cached images, newest first. */
    fun entries(): List<Entry> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { meta ->
            val img = File(dir, meta.nameWithoutExtension + ".jpg")
            if (!img.exists()) return@mapNotNull null
            runCatching { Entry(Artwork.fromJson(JSONObject(meta.readText())), img) }.getOrNull()
        }.sortedByDescending { it.addedAt }

    val isRefreshing: Boolean get() = running.get()

    fun count(): Int = dir.list { _, name -> name.endsWith(".jpg") }?.size ?: 0

    fun remove(art: Artwork) {
        File(dir, art.key + ".jpg").delete()
        File(dir, art.key + ".json").delete()
    }

    /**
     * Fetch [Prefs.perRun] new images (more if the cache is short of [TARGET]),
     * then trim the oldest down to [Prefs.maxStored] so the rotation keeps moving.
     * Returns how many were added, or [ALREADY_RUNNING] if another refresh (e.g.
     * the screensaver's) is in progress. Only one run happens at a time.
     * Only sources whose category and own toggle are both on are used.
     */
    fun refresh(listener: Listener? = null): Int {
        if (!running.compareAndSet(false, true)) return ALREADY_RUNNING
        try {
            dir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
            val sources = enabledSources()
            if (sources.isEmpty()) return 0

            val want = wantedNow()
            var added = 0
            var rounds = 0
            // Larger runs need more passes: each pass yields at most BATCH per source,
            // and some candidates are rejected (portrait, wrong licence, failed download).
            val maxRounds = 5 + want / BATCH
            while (added < want && rounds++ < maxRounds && !Thread.currentThread().isInterrupted) {
                for (src in sources.shuffled()) {
                    if (added >= want || Thread.currentThread().isInterrupted) break
                    listener?.onStatus("Asking ${src.label}…")
                    val batch = try {
                        src.fetchBatch(BATCH, Random.Default)
                    } catch (e: Exception) {
                        Log.w(TAG, "${src.id} batch failed: $e")
                        listener?.onSourceFailed(src, e)
                        continue
                    }
                    Log.i(TAG, "${src.id}: batch of ${batch.size}")
                    for (art in batch) {
                        if (added >= want || Thread.currentThread().isInterrupted) break
                        if (File(dir, art.key + ".json").exists()) continue
                        if (prefs.landscapeOnly && art.width > 0 && art.width < art.height) continue
                        listener?.onStatus("Downloading “${art.title.ifEmpty { "untitled" }}”")
                        if (download(art)) {
                            added++
                            Log.i(TAG, "saved ${art.key} ($added/$want)")
                            listener?.onAdded(Entry(art, File(dir, art.key + ".jpg")), added, want)
                        }
                    }
                }
            }
            trim()
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

    private fun download(art: Artwork): Boolean {
        val part = File(dir, art.key + ".part")
        for (url in art.imageUrls) {
            try {
                Http.download(url, part)
                if (accept(part)) {
                    part.renameTo(File(dir, art.key + ".jpg"))
                    File(dir, art.key + ".json").writeText(art.toJson().toString())
                    return true
                }
                Log.i(TAG, "rejected ${art.key} (not landscape or undecodable)")
                part.delete()
                return false   // decoded fine but rejected (e.g. portrait); smaller copy won't help
            } catch (e: Exception) {
                Log.w(TAG, "download $url: $e")
                part.delete()
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
        imgs.sortedBy { it.lastModified() }.take(imgs.size - max).forEach { img ->
            img.delete()
            File(dir, img.nameWithoutExtension + ".json").delete()
        }
    }

    companion object {
        private const val TAG = "ArtDream"
        const val TARGET = 30          // minimum playlist before we stop topping up eagerly
        const val BATCH = 10
        const val MAX_EDGE = 4096
        const val ALREADY_RUNNING = -1
        private val running = AtomicBoolean(false)
    }
}
