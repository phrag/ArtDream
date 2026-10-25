package com.phrag.artdream.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.phrag.artdream.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Off-main-thread thumbnail decoding with a bounded LRU memory cache.
 *
 * Cached images can be up to 4096px, so each decode first reads bounds, picks a
 * power-of-two `inSampleSize`, then scales the result to just cover the tile
 * (RGB_565, ~150KB per tile). Views are tagged with the key they want; a result
 * only lands in a view whose tag still matches, so recycled/unbound tiles never
 * show stale images. All public methods are main-thread only.
 */
class ThumbLoader(private val targetW: Int, private val targetH: Int) {

    private val main = Handler(Looper.getMainLooper())
    private val pool: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "artdream-thumbs").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }

    // Size in KB; at most 1/8 of the heap and never more than 32MB.
    private val cache = object : LruCache<String, Bitmap>(
        min(32 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt())
    ) {
        override fun sizeOf(key: String, value: Bitmap) = max(1, value.allocationByteCount / 1024)
    }

    /** Keys some view is still waiting for; checked by workers so stale queued work is skipped. */
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val waiting = HashMap<String, MutableList<ImageView>>()
    private val failed = HashSet<String>()   // undecodable/removed files: don't retry every bind
    private var closed = false

    fun load(file: File, into: ImageView) {
        if (closed) return
        val key = file.path
        if (into.getTag(R.id.ad_thumb_key) == key &&
            (into.drawable != null || waiting[key]?.contains(into) == true)) return   // shown or on its way
        unbind(into)
        into.setTag(R.id.ad_thumb_key, key)
        cache.get(key)?.let { into.setImageBitmap(it); return }
        if (key in failed) return

        val list = waiting.getOrPut(key) { ArrayList(2) }
        list.add(into)
        if (list.size > 1) return              // decode already queued
        wanted.add(key)
        pool.execute {
            if (key !in wanted) return@execute
            val bmp = runCatching { decode(file, targetW, targetH, cover = true, Bitmap.Config.RGB_565) }.getOrNull()
            main.post { deliver(key, bmp) }
        }
    }

    private fun deliver(key: String, bmp: Bitmap?) {
        wanted.remove(key)
        val views = waiting.remove(key)
        if (closed) return
        if (bmp == null) { failed.add(key); return }
        cache.put(key, bmp)
        views?.forEach { if (it.getTag(R.id.ad_thumb_key) == key) it.setImageBitmap(bmp) }
    }

    /** Drop the view's bitmap reference (the LRU decides what stays in memory). */
    fun unbind(view: ImageView) {
        val key = view.getTag(R.id.ad_thumb_key) as? String
        if (key != null) {
            waiting[key]?.let { list ->
                list.remove(view)
                if (list.isEmpty()) { waiting.remove(key); wanted.remove(key) }
            }
        }
        view.setTag(R.id.ad_thumb_key, null)
        view.setImageDrawable(null)
    }

    fun forget(file: File) { cache.remove(file.path); failed.remove(file.path) }

    fun close() {
        closed = true
        pool.shutdownNow()
        wanted.clear()
        waiting.clear()
        failed.clear()
        cache.evictAll()   // views are torn down with the activity; no recycle() needed
    }

    companion object {
        /**
         * Decode [file] so it either covers ([cover]) or fits inside [w]x[h]
         * without ever allocating the full-resolution bitmap. Call off-main.
         */
        fun decode(file: File, w: Int, h: Int, cover: Boolean, config: Bitmap.Config): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val sw = bounds.outWidth
            val sh = bounds.outHeight
            if (sw <= 0 || sh <= 0) return null
            val fx = w.toFloat() / sw
            val fy = h.toFloat() / sh
            val scale = if (cover) max(fx, fy) else min(fx, fy)
            var sample = 1
            while (scale > 0f && 1f / (sample * 2) >= scale) sample *= 2
            val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = config
            }) ?: return null
            val f = scale * sample   // remaining scale after sampling, in (0.5, 1] when shrinking
            if (f >= 0.9f) return bmp
            val out = Bitmap.createScaledBitmap(
                bmp, max(1, (bmp.width * f).roundToInt()), max(1, (bmp.height * f).roundToInt()), true
            )
            if (out !== bmp) bmp.recycle()
            return out
        }
    }
}
