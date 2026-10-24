package com.example.artdream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.artdream.source.ArtSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The screensaver. Shows cached paintings full-screen: the painting fitted to
 * the screen over a blurred, dimmed copy of itself (paintings are rarely 16:9),
 * a slow Ken Burns drift (also spares OLED panels), crossfades and a caption.
 */
class ArtDream : DreamService() {

    private class Slide(val art: Artwork, val image: Bitmap, val backdrop: Bitmap)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var loader: ExecutorService   // decodes the next slide
    private lateinit var network: ExecutorService  // tops up the cache
    private lateinit var cache: ArtCache
    private lateinit var prefs: Prefs
    private lateinit var root: FrameLayout
    private lateinit var status: TextView

    private val queue = ArrayDeque<ArtCache.Entry>()
    private var current: View? = null
    private var dreaming = false

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true

        cache = ArtCache(this)
        prefs = Prefs(this)
        loader = Executors.newSingleThreadExecutor()
        network = Executors.newSingleThreadExecutor()

        status = TextView(this).apply {
            setTextColor(Color.GRAY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            text = "Fetching paintings…"
            visibility = View.GONE
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        network.execute {
            val added = cache.refresh()
            // First run with an empty cache: start showing as soon as something lands.
            if (added > 0) main.post { if (current == null) advance() }
        }
        advance()
    }

    override fun onDreamingStopped() {
        dreaming = false
        main.removeCallbacksAndMessages(null)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        loader.shutdownNow()
        network.shutdownNow()
        super.onDetachedFromWindow()
    }

    private fun advance() {
        if (!dreaming) return
        main.removeCallbacksAndMessages(null)
        if (queue.isEmpty()) queue.addAll(cache.entries().shuffled())
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
                Log.w("ArtDream", "decode ${next.image}: $e")
                null
            }
            main.post {
                if (!dreaming) return@post
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
        val screenW = resources.displayMetrics.widthPixels.coerceAtLeast(1280)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= screenW) sample *= 2
        val image = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null

        // Backdrop: shrink to a few dozen pixels; upscaling with filtering = a cheap blur
        // that works on API 26 (RenderEffect is 31+).
        var bgSample = 1
        while (bounds.outWidth / (bgSample * 2) >= 256) bgSample *= 2
        val small = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = bgSample })
            ?: return null
        val h = (24f * small.height / small.width).toInt().coerceAtLeast(1)
        val backdrop = Bitmap.createScaledBitmap(small, 24, h, true)
        if (backdrop !== small) small.recycle()

        return Slide(entry.art, image, backdrop)
    }

    private fun show(slide: Slide) {
        status.visibility = View.GONE
        val intervalMs = prefs.intervalSec * 1000L

        val backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(slide.backdrop)
            setColorFilter(Color.argb(150, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        }
        val painting = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(slide.image)
        }
        val view = FrameLayout(this).apply {
            alpha = 0f
            addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(painting, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (prefs.showCaption) addView(caption(slide.art), FrameLayout.LayoutParams(
                WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        }
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val old = current
        current = view
        view.animate().alpha(1f).setDuration(FADE_MS).withEndAction { old?.let(root::removeView) }

        // Ken Burns: slow zoom + drift in a random direction across the whole interval.
        val drift = resources.displayMetrics.widthPixels * 0.02f
        painting.animate()
            .scaleX(1.06f).scaleY(1.06f)
            .translationX(if (Random.nextBoolean()) drift else -drift)
            .translationY(if (Random.nextBoolean()) drift / 2 else -drift / 2)
            .setInterpolator(LinearInterpolator())
            .setDuration(intervalMs + FADE_MS)

        main.postDelayed(::advance, intervalMs)
    }

    private fun caption(art: Artwork): View {
        val pad = dp(48)
        fun line(text: String, sp: Float, style: Int) = TextView(this).apply {
            this.text = text
            setTextColor(Color.argb(220, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTypeface(Typeface.SERIF, style)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        val credit = ArtSource.ALL.firstOrNull { it.id == art.source }?.label.orEmpty()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            if (art.title.isNotEmpty()) addView(line(art.title, 22f, Typeface.ITALIC))
            val byline = listOf(art.artist, art.date).filter { it.isNotEmpty() }.joinToString(", ")
            if (byline.isNotEmpty()) addView(line(byline, 16f, Typeface.NORMAL))
            if (credit.isNotEmpty()) addView(line(credit, 12f, Typeface.NORMAL))
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val FADE_MS = 2_000L
    }
}
