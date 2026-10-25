package com.example.artdream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import com.example.artdream.source.ArtSource
import java.util.Calendar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The art slideshow, shared by the real screensaver ([ArtDream]) and the
 * in-app preview ([PreviewActivity]): the painting fitted to the screen over a
 * blurred, dimmed copy of itself (paintings are rarely 16:9), a slow Ken Burns
 * drift (also spares OLED panels), crossfades and a caption.
 *
 * Everything must be called on the main thread. Lifecycle: [start] / [stop]
 * may alternate any number of times; [release] is final. After [stop] nothing
 * is shown, posted or animated until the next [start].
 */
class Slideshow(private val context: Context) {

    private class Slide(val art: Artwork, val image: Bitmap, val backdrop: Bitmap) {
        fun recycle() {
            image.recycle()
            backdrop.recycle()
        }
    }


    private val main = Handler(Looper.getMainLooper())
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()   // decodes the next slide
    private val network: ExecutorService = Executors.newSingleThreadExecutor()  // tops up the cache
    private val cache = ArtCache(context.applicationContext)
    private val prefs = Prefs(context.applicationContext)

    private val status = TextView(context).apply {
        setTextColor(Color.GRAY)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        text = "Fetching pictures…"
        visibility = View.GONE
    }
    private val root = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    private var clock: TextClock? = null
    private var clockShift = 0

    /** The slideshow's root view; put it in a window once and leave it there. */
    val view: View get() = root

    private val queue = ArrayDeque<ArtCache.Entry>()
    private val refreshing = AtomicBoolean(false)
    private var current: View? = null
    private var running = false
    private var released = false
    private var generation = 0   // bumped per advance(); stale decode results are dropped

    /** The artwork currently on screen, or null while nothing is shown. */
    val currentArtwork: Artwork? get() = (current?.tag as? Slide)?.art

    fun start() {
        if (running || released) return
        running = true
        // Top up the cache in the background (one refresh at a time).
        if (refreshing.compareAndSet(false, true)) {
            network.execute {
                val added = try {
                    cache.refresh()
                } catch (e: Throwable) {
                    Log.w(TAG, "refresh: $e")
                    0
                } finally {
                    refreshing.set(false)
                }
                // First run with an empty cache: start showing as soon as something lands.
                if (added > 0) main.post { if (running && current == null) advance() }
            }
        }
        advance()
    }

    fun stop() {
        if (!running) return
        running = false
        generation++
        main.removeCallbacksAndMessages(null)
        // Drop every slide so no bitmaps are held (or animated) while stopped.
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child !== status && child !== clock) discard(child)
        }
        current = null
        status.visibility = View.GONE
        clock?.visibility = View.GONE
    }

    /** Skip to the next image now (no-op unless running). */
    fun next() {
        if (running) advance()
    }

    fun release() {
        stop()
        released = true
        loader.shutdownNow()
        network.shutdownNow()
    }

    private fun advance() {
        if (!running) return
        val gen = ++generation
        main.removeCallbacksAndMessages(null)
        if (queue.isEmpty()) queue.addAll(order(playlist()))
        val next = queue.removeFirstOrNull()
        if (next == null) {
            status.visibility = View.VISIBLE
            main.postDelayed(::advance, 5_000)
            return
        }
        loader.execute {
            val slide = try {
                decode(next)
            } catch (e: Throwable) {   // incl. OOM on a pathological file
                Log.w(TAG, "decode ${next.image}: $e")
                null
            }
            main.post {
                if (!running || gen != generation) {   // stopped or skipped meanwhile
                    slide?.recycle()
                    return@post
                }
                if (slide == null) {
                    cache.remove(next.art)   // corrupt; drop it so we don't spin on it
                    advance()
                } else {
                    show(slide)
                }
            }
        }
    }

    private fun decode(entry: ArtCache.Entry): Slide? {
        val path = entry.image.path
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null

        // Main image: no larger than ~the screen.
        val screenW = context.resources.displayMetrics.widthPixels.coerceAtLeast(1280)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW) sample *= 2
        val image = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        // Backdrop: shrink to a few dozen pixels; upscaling with filtering = a cheap blur
        // that works on API 26 (RenderEffect is 31+).
        var bgSample = 1
        while (bounds.outWidth / (bgSample * 2) >= 256) bgSample *= 2
        val small = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = bgSample })
        if (small == null) {
            image.recycle()
            return null
        }
        val h = (24f * small.height / small.width).toInt().coerceAtLeast(1)
        val backdrop = Bitmap.createScaledBitmap(small, 24, h, true)
        if (backdrop !== small) small.recycle()

        return Slide(entry.art, image, backdrop)
    }

    private fun show(slide: Slide) {
        status.visibility = View.GONE
        val intervalMs = prefs.intervalSec * 1000L

        val backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(slide.backdrop)
            setColorFilter(Color.argb(150, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        }
        val painting = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(slide.image)
            if (isNight()) colorFilter = NIGHT_FILTER
        }
        val slideView = FrameLayout(context).apply {
            alpha = 0f
            tag = slide   // so discard() can recycle its bitmaps
            addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(painting, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // CC BY / BY-SA images must stay attributed, so they keep a one-line
            // credit even when captions are switched off.
            val needsCredit = slide.art.license.startsWith("CC BY")
            if (prefs.showCaption || needsCredit) addView(caption(slide.art, full = prefs.showCaption),
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }
        root.addView(slideView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // Skipping mid-fade can leave an older slide still attached; clear everything
        // except the outgoing one and the new one so views/bitmaps can't pile up.
        val old = current
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child !== status && child !== clock && child !== old && child !== slideView) discard(child)
        }
        current = slideView
        updateClock()
        slideView.animate().alpha(1f).setDuration(FADE_MS).withEndAction { old?.let(::discard) }

        // Ken Burns: slow zoom + drift in a random direction across the whole interval.
        val drift = context.resources.displayMetrics.widthPixels * 0.02f
        painting.animate()
            .scaleX(1.06f).scaleY(1.06f)
            .translationX(if (Random.nextBoolean()) drift else -drift)
            .translationY(if (Random.nextBoolean()) drift / 2 else -drift / 2)
            .setInterpolator(LinearInterpolator())
            .setDuration(intervalMs + FADE_MS)

        main.postDelayed(::advance, intervalMs)
    }

    private fun isNight(): Boolean {
        if (prefs.nightMode != Prefs.NIGHT_AUTO) return false
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 21 || h < 7
    }

    /** Show/hide the clock above the slides and nudge it a few px (burn-in). */
    private fun updateClock() {
        if (!prefs.showClock) {
            clock?.visibility = View.GONE
            return
        }
        val c = clock ?: TextClock(context).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            setTextColor(Color.argb(180, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            val pad = dp(48)
            setPadding(pad, pad, pad, pad)
        }.also {
            clock = it
            root.addView(it, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        }
        clockShift = (clockShift + 1) % 6
        val step = dp(4)
        c.translationX = -(clockShift % 3) * step.toFloat()
        c.translationY = (clockShift / 3) * step.toFloat() * 2
        c.visibility = View.VISIBLE
        c.bringToFront()
    }

    /** Playlist order: shuffled, or in shuffled runs of [RUN] from one category. */
    private fun order(entries: List<ArtCache.Entry>): List<ArtCache.Entry> {
        if (prefs.grouping != Prefs.GROUP_BY_TYPE) return entries.shuffled()
        val categoryOf = ArtSource.ALL.associate { it.id to it.category }
        val pools = entries.shuffled().groupBy { categoryOf[it.art.source] }.values
            .map { ArrayDeque(it.chunked(RUN)) }.toMutableList()
        val out = ArrayList<ArtCache.Entry>(entries.size)
        var last = -1
        while (pools.any { it.isNotEmpty() }) {
            val open = pools.indices.filter { pools[it].isNotEmpty() }
            val pick = (open.filter { it != last }.ifEmpty { open }).random()
            out.addAll(pools[pick].removeFirst())
            last = pick
        }
        return out
    }

    /**
     * Cached images whose category is switched on. Falls back to everything
     * cached so a just-changed picker never leaves a blank screen.
     */
    private fun playlist(): List<ArtCache.Entry> {
        val all = cache.entries()
        val categoryOf = ArtSource.ALL.associate { it.id to it.category }
        val wanted = all.filter { e -> categoryOf[e.art.source]?.let(prefs::isCategoryOn) ?: true }
        val pool = if (prefs.favouritesOnly) wanted.filter { it.art.key in prefs.favourites } else wanted
        return pool.ifEmpty { wanted.ifEmpty { all } }
    }

    /** Stop a slide's animations, detach it and free its bitmaps. Idempotent. */
    private fun discard(slideView: View) {
        slideView.animate().cancel()
        (slideView as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) g.getChildAt(i).animate().cancel()
            for (i in 0 until g.childCount) (g.getChildAt(i) as? ImageView)?.setImageDrawable(null)
        }
        root.removeView(slideView)
        (slideView.tag as? Slide)?.recycle()
        slideView.tag = null
    }

    /** Title/byline/credit; with [full] false only the attribution line. */
    private fun caption(art: Artwork, full: Boolean): View {
        val pad = dp(48)
        fun line(text: String, sp: Float, style: Int) = TextView(context).apply {
            this.text = text
            setTextColor(Color.argb(220, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTypeface(Typeface.SERIF, style)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val sourceLabel = ArtSource.ALL.firstOrNull { it.id == art.source }?.label.orEmpty()
        // Credit only when the byline doesn't already name the same person/agency.
        val author = art.credit.takeIf { it.isNotEmpty() && it != art.artist }.orEmpty()
        val creditLine = listOf(author, art.license, sourceLabel).filter { it.isNotEmpty() }.joinToString(" · ")
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            if (full) {
                if (art.title.isNotEmpty()) addView(line(art.title, 22f, Typeface.ITALIC))
                val byline = listOf(art.artist, art.date).filter { it.isNotEmpty() }.joinToString(", ")
                if (byline.isNotEmpty()) addView(line(byline, 16f, Typeface.NORMAL))
                if (creditLine.isNotEmpty()) addView(line(creditLine, 12f, Typeface.NORMAL))
            } else {
                val attribution = listOf(art.artist.ifEmpty { art.credit }, art.license)
                    .filter { it.isNotEmpty() }.joinToString(" · ")
                addView(line(attribution, 11f, Typeface.NORMAL).apply { alpha = 0.7f })
            }
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "ArtDream"
        const val FADE_MS = 2_000L
        const val RUN = 8
        /** Dim to ~75% and shift toward warm (less blue, a little less green). */
        val NIGHT_FILTER = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.6f, 0f, 0f, 0f,
            0f, 0f, 0.4f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }
}
