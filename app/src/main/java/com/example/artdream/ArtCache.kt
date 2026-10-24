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

    class Entry(val art: Artwork, val image: File)

    private val dir = File(ctx.filesDir, "art").apply { mkdirs() }
    private val prefs = Prefs(ctx)

    fun entries(): List<Entry> =
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray()).mapNotNull { meta ->
            val img = File(dir, meta.nameWithoutExtension + ".jpg")
            if (!img.exists()) return@mapNotNull null
            runCatching { Entry(Artwork.fromJson(JSONObject(meta.readText())), img) }.getOrNull()
        }

    fun count(): Int = dir.list { _, name -> name.endsWith(".jpg") }?.size ?: 0

    fun remove(art: Artwork) {
        File(dir, art.key + ".jpg").delete()
        File(dir, art.key + ".json").delete()
    }

    /**
     * Fetch up to [FRESH_PER_RUN] new paintings (more if the cache is short of
     * [TARGET]), then trim the oldest down to [MAX] so the rotation keeps moving.
     * Returns how many were added. Safe to call from several places; only one
     * run happens at a time.
     */
    fun refresh(): Int {
        if (!running.compareAndSet(false, true)) return 0
        try {
            dir.listFiles { f -> f.extension == "part" }?.forEach { it.delete() }
            val sources = ArtSource.ALL.filter { prefs.isSourceOn(it.id) }
            if (sources.isEmpty()) return 0

            val want = maxOf(FRESH_PER_RUN, TARGET - count())
            var added = 0
            var rounds = 0
            while (added < want && rounds++ < 5 && !Thread.currentThread().isInterrupted) {
                for (src in sources.shuffled()) {
                    val batch = try {
                        src.fetchBatch(BATCH, Random.Default)
                    } catch (e: Exception) {
                        Log.w(TAG, "${src.id} batch failed: $e")
                        continue
                    }
                    for (art in batch) {
                        if (added >= want || Thread.currentThread().isInterrupted) break
                        if (File(dir, art.key + ".json").exists()) continue
                        if (prefs.landscapeOnly && art.width > 0 && art.width < art.height) continue
                        if (download(art)) added++
                    }
                }
            }
            trim()
            return added
        } finally {
            running.set(false)
        }
    }

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
        if (imgs.size <= MAX) return
        imgs.sortedBy { it.lastModified() }.take(imgs.size - MAX).forEach { img ->
            img.delete()
            File(dir, img.nameWithoutExtension + ".json").delete()
        }
    }

    companion object {
        private const val TAG = "ArtDream"
        const val TARGET = 30          // minimum playlist before we stop topping up eagerly
        const val MAX = 120            // ~1.5MB each after downscale → well under 200MB
        const val FRESH_PER_RUN = 8
        const val BATCH = 10
        const val MAX_EDGE = 4096
        private val running = AtomicBoolean(false)
    }
}
