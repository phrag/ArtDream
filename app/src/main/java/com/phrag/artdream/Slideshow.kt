package com.phrag.artdream

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.phrag.artdream.source.ArtSource
import java.io.File
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
 *
 * With [only] (a My media item's key) it plays just that item, on demand: a video or YouTube
 * link from the start and on repeat, a picture held, with sound and no night dimming.
 *
 * With "HDR only" on (Prefs.hdrOnly) it plays only the user's own HDR pictures and videos, filling
 * the screen, and asks for the window's HDR (or wide gamut) colour mode while it runs.
 */
class Slideshow(private val context: Context, private val only: String? = null) {

    /**
     * One slide's decoded content: a picture ([image]) or a video ([video], played by [player]
     * on a surface once its view is laid out). The backdrop is the blurred picture/poster frame.
     */
    private class Slide(
        val art: Artwork,
        val image: Bitmap?,
        val backdrop: Bitmap,
        val video: File? = null,
        val videoW: Int = 0,
        val videoH: Int = 0,
        val durationMs: Long = 0,
        /** Set for a YouTube link: shown live, by [exo] when [live] was found, else in [web]. */
        val youtubeId: String? = null,
        val live: YouTubeLive.Streams? = null,
        /** What an own video file's container says about its picture, for the info overlay. */
        val file: FileVideo? = null,
        /** The HDR kind found by [HdrProbe], in HDR only mode. */
        val hdr: String? = null,
    ) {
        var player: MediaPlayer? = null
        var exo: ExoPlayer? = null
        var surface: Surface? = null
        var web: WebView? = null
        /** Hands [web] back to the slideshow's pool (WebViews are reused, not destroyed per slide). */
        var releaseWeb: ((WebView) -> Unit)? = null
        var disposed = false
        /** The video info overlay, when it's switched on; [webQuality] etc. feed it. */
        var info: TextView? = null
        var webQuality: String? = null
        var decoderName: String? = null
        var nativeFailed: String? = null

        fun recycle() {
            disposed = true
            web?.let { releaseWeb?.invoke(it) }
            web = null
            exo?.release()
            exo = null
            player?.let { runCatching { it.stop() }; it.release() }
            player = null
            surface?.release()
            surface = null
            image?.recycle()
            backdrop.recycle()
        }
    }

    /** An own video's track format (unknown values null), read once on the loader thread. */
    private class FileVideo(val mime: String?, val profile: Int?, val fps: Float, val transfer: Int?, val bitrate: Long, val averaged: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()   // decodes the next slide
    private val network: ExecutorService = Executors.newSingleThreadExecutor()  // tops up the cache
    private val cache = ArtCache(context.applicationContext)
    private val own = OwnMedia(context.applicationContext)
    private val prefs = Prefs(context.applicationContext)
    private val hdrProbe = HdrProbe(context.applicationContext)
    /** HDR only mode, fixed for a run (from [start] to [stop]); never when playing one item. */
    private var hdrMode = false
    /** Own items' HDR kinds from the last playlist build (key -> kind), for the slides. */
    @Volatile private var hdrKinds: Map<String, String> = emptyMap()

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
    private val brokenLinks = HashMap<String, Long>()   // YouTube id -> when to try it again
    private val nativeBroken = HashMap<String, Long>()  // YouTube id -> when to try the native player again
    private var lastYouTubeLoadAt = 0L
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
        val hdr = only == null && prefs.hdrOnly
        if (hdr != hdrMode) queue.clear()   // switched since the last run: an HDR only round and a normal one don't mix
        hdrMode = hdr
        if (hdrMode) {
            status.text = "Looking for HDR pictures and videos…"
            setHdrWindow(true)
        } else {
            status.text = "Fetching pictures…"
        }
        if (only != null) {
            advance()
            return
        }
        // Top up the cache in the background (one refresh at a time) — unless only
        // the user's own media is wanted, which needs no downloads.
        if (prefs.ownMode != Prefs.OWN_ONLY && !hdrMode && refreshing.compareAndSet(false, true)) {
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
        // Files left in the app's media folder (adb push) join the user's own media first, so
        // the very first slide can already be one of them.
        if (own.hasPending()) {
            loader.execute {
                runCatching { own.importPending() }
                main.post { if (running) advance() }
            }
        } else {
            advance()
        }
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
        destroyIdleWebs()   // nothing is loading now, so this is the safe moment
        current = null
        status.visibility = View.GONE
        clock?.visibility = View.GONE
        if (hdrMode) setHdrWindow(false)
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
        // Playing one item, nothing moves on by itself: an advance once it's up means it failed.
        if (only != null && current != null) {
            cantPlay()
            return
        }
        val gen = ++generation
        main.removeCallbacksAndMessages(null)
        if (queue.isEmpty() && hdrMode) {
            // Finding the HDR files can read every file once, so it runs on the loader thread.
            loader.execute {
                val round = try { hdrQueue() } catch (e: Throwable) { Log.w(TAG, "hdr playlist: $e"); emptyList() }
                main.post {
                    if (!running || gen != generation) return@post
                    if (round.isEmpty()) noHdr() else { queue.addAll(round); advance() }
                }
            }
            return
        }
        if (queue.isEmpty()) queue.addAll(buildQueue())
        val next = takeNext()
        if (next == null && only != null) {
            cantPlay()
            return
        }
        if (next == null) {
            status.visibility = View.VISIBLE
            main.postDelayed(::advance, 5_000)
            return
        }
        loader.execute {
            val mine = next.art.source == OwnMedia.SOURCE
            val slide = try {
                when {
                    mine && OwnMedia.isVideo(next) -> decodeVideo(next)
                    mine && OwnMedia.isYouTube(next) -> decodeYouTube(next)
                    else -> decode(next)
                }
            } catch (e: Throwable) {   // incl. OOM on a pathological file
                Log.w(TAG, "decode ${next.image}: $e")
                null
            }
            main.post {
                if (!running || gen != generation) {   // stopped or skipped meanwhile
                    slide?.recycle()
                    return@post
                }
                if (slide == null && only != null) {
                    cantPlay()
                } else if (slide == null && mine) {
                    // Never delete the user's own file on a failed decode; skip it, and pause
                    // briefly so a playlist of nothing but bad files can't spin.
                    Log.w(TAG, "skipping unreadable ${next.image.name}")
                    main.postDelayed(::advance, 1_000)
                } else if (slide == null) {
                    cache.remove(next.art)   // corrupt; drop it so we don't spin on it
                    advance()
                } else {
                    show(slide)
                }
            }
        }
    }

    /** The one item asked for is gone or won't play: say so instead of retrying it forever. */
    private fun cantPlay() {
        generation++
        main.removeCallbacksAndMessages(null)
        current?.let(::discard)
        current = null
        status.text = "Couldn't play this. Press Back to return to My media."
        status.visibility = View.VISIBLE
    }

    /** HDR only, with nothing HDR to show: say so (not a blank screen) and look again in a while. */
    private fun noHdr() {
        current?.let(::discard)
        current = null
        status.text = "HDR only is on, but none of your media is HDR.\n" +
            "Add HDR10, HLG or Dolby Vision videos or HDR photos in My media, or switch HDR only off."
        status.visibility = View.VISIBLE
        main.postDelayed(::advance, NO_HDR_RETRY_MS)
    }

    /**
     * One round of HDR only: the user's own pictures and videos that are HDR and play as HDR here,
     * shuffled (museum art and YouTube links are SDR). Favourites only applies as usual.
     */
    private fun hdrQueue(): List<ArtCache.Entry> {
        if (own.hasPending()) runCatching { own.importPending() }
        val items = own.items()
        hdrProbe.prune(items)
        val kinds = HashMap<String, String>()
        for (item in items) {
            val r = hdrProbe.probe(item)
            if (r.hdr) kinds[item.key] = r.kind!!
        }
        hdrKinds = kinds
        val hdr = items.filter { it.key in kinds }.map { own.entry(it) }
        val favs = if (prefs.favouritesOnly) hdr.filter { it.art.key in prefs.favourites } else hdr
        Log.i(TAG, "hdr only: ${hdr.size} of ${items.size} own items are HDR")
        return favs.ifEmpty { hdr }.shuffled()
    }

    /** Sound on the user's own videos: always when one was picked to play, else the setting. */
    private val sound get() = only != null || prefs.videoSound

    /**
     * One pass through the playlist. In Mixed mode the user's own items get a guaranteed share, one
     * after every [OWN_EVERY] museum slides: with a hundred museum images a lone uploaded video would
     * otherwise come up about once in two hours and look like it hadn't worked. Own items take turns
     * (shuffled, repeating when there are fewer than slots).
     */
    private fun buildQueue(): List<ArtCache.Entry> {
        val all = playlist()
        val mine = all.filter { it.art.source == OwnMedia.SOURCE }
        val art = all.filter { it.art.source != OwnMedia.SOURCE }
        if (mine.isEmpty() || art.isEmpty() || prefs.ownMode != Prefs.OWN_MIX) return order(all)

        val turns = ArrayDeque<ArtCache.Entry>()
        fun nextOwn(): ArtCache.Entry {
            if (turns.isEmpty()) turns.addAll(mine.shuffled())
            return turns.removeFirst()
        }
        val out = ArrayList<ArtCache.Entry>()
        order(art).forEachIndexed { i, e ->
            out.add(e)
            if ((i + 1) % OWN_EVERY == 0) out.add(nextOwn())
        }
        if (out.none { it.art.source == OwnMedia.SOURCE }) out.add(nextOwn())   // fewer museum slides than a round
        return out
    }

    /**
     * The next entry from the queue. On the test TV a YouTube page loaded within ~30 s of another
     * sat dead for ~30 s before its player started, so right after one, show a non-YouTube slide
     * first if one is queued.
     */
    private fun takeNext(): ArtCache.Entry? {
        val first = queue.removeFirstOrNull() ?: return null
        if (!OwnMedia.isYouTube(first) || System.currentTimeMillis() - lastYouTubeLoadAt >= YOUTUBE_GAP_MS) return first
        val other = queue.firstOrNull { !OwnMedia.isYouTube(it) } ?: return first
        queue.remove(other)
        queue.addFirst(first)   // its turn comes next
        return other
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
        val hdr = if (hdrMode) hdrKinds[entry.art.id] else null
        val image = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            // A PQ/HLG picture is 10 bits or more; keep them (a gain map photo's base is 8-bit anyway).
            if (hdr != null && hdr != HdrMedia.GAIN_MAP) inPreferredConfig = Bitmap.Config.RGBA_F16
        }) ?: return null
        // HDR only: black around the picture, never a dimmed SDR backdrop.
        if (hdrMode) return Slide(entry.art, image, Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888), hdr = hdr)

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
        Log.d(TAG, "show ${slide.art.source}: ${slide.art.key}")
        status.visibility = View.GONE
        val intervalMs = prefs.intervalSec * 1000L

        val backdrop = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(slide.backdrop)
            setColorFilter(Color.argb(150, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
        }
        val isVideo = slide.video != null
        val isYouTube = slide.youtubeId != null
        val painting: View = if (isYouTube && slide.live != null) nativeYouTubeView(slide) else if (isYouTube) youtubeView(slide) else if (isVideo) videoView(slide, intervalMs) else ImageView(context).apply {
            val image = slide.image!!
            val dm = context.resources.displayMetrics
            scaleType = if (hdrMode && HdrMedia.fillsScreen(image.width, image.height, dm.widthPixels, dm.heightPixels))
                ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            setImageBitmap(image)
            if (isNight()) colorFilter = NIGHT_FILTER
        }
        if (hdrMode) logHdr(slide)
        val slideView = FrameLayout(context).apply {
            alpha = 0f
            tag = slide   // so discard() can recycle its bitmaps
            if (!hdrMode) addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(painting, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // A video can't take the bitmap colour filter, so dim and warm it with a veil.
            if ((isVideo || isYouTube) && isNight()) addView(View(context).apply { setBackgroundColor(NIGHT_VEIL) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // CC BY / BY-SA images must stay attributed, so they keep a one-line
            // credit even when captions are switched off.
            val needsCredit = slide.art.license.startsWith("CC BY")
            if (prefs.showCaption || needsCredit) addView(caption(slide.art, full = prefs.showCaption),
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            if ((isVideo || isYouTube || hdrMode) && prefs.videoInfo) addView(infoOverlay().also { slide.info = it },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                    setMargins(dp(48), dp(48), dp(48), dp(48))
                })
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
        // (Videos already move, so they stay put.)
        if (!isVideo && !isYouTube && only == null) {
            val drift = context.resources.displayMetrics.widthPixels * 0.02f
            painting.animate()
                .scaleX(1.06f).scaleY(1.06f)
                .translationX(if (Random.nextBoolean()) drift else -drift)
                .translationY(if (Random.nextBoolean()) drift / 2 else -drift / 2)
                .setInterpolator(LinearInterpolator())
                .setDuration(intervalMs + FADE_MS)
        }

        if (only == null) main.postDelayed(::advance, if (isVideo) videoShowMs(slide, intervalMs) else intervalMs)
        tickInfo(slide)
    }

    /** The video info box: small monospace text on a dark plate, top left (the clock is top right). */
    private fun infoOverlay() = TextView(context).apply {
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(Color.argb(230, 255, 255, 255))
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    /** Refreshes a slide's info overlay now and every [INFO_TICK_MS] while it's shown (dropped frames move). */
    private fun tickInfo(slide: Slide) {
        val view = slide.info ?: return
        if (slide.disposed) return
        view.text = infoLines(slide).joinToString("\n")
        main.postDelayed({ tickInfo(slide) }, INFO_TICK_MS)
    }

    /**
     * What the overlay says. The native player's values are the format the decoder was handed
     * (so "HDR10" means the decoder really got a PQ stream); an own file's too, unless it fell back
     * to MediaPlayer: then they come from its container, and a TextureView shows HDR as SDR.
     */
    @OptIn(UnstableApi::class)
    private fun infoLines(slide: Slide): List<String> {
        val out = ArrayList<String>()
        val exo = slide.exo
        val live = slide.live
        when {
            exo != null && live != null -> {
                out += "Native player (ExoPlayer)"
                val f = exo.videoFormat
                if (f == null) {
                    out += "starting… ${live.summary}"
                } else {
                    out += VideoInfo.picture(f.width, f.height, if (f.frameRate > 0f) f.frameRate else live.fps.toFloat())
                    out += VideoInfo.codecName(f.sampleMimeType, f.codecs ?: live.codec)
                    out += "HDR: " + VideoInfo.hdrName(f.colorInfo?.colorTransfer, f.sampleMimeType, f.codecs ?: live.codec)
                    val bps = listOf(f.averageBitrate, f.bitrate, live.bitrate).firstOrNull { it > 0 } ?: 0
                    VideoInfo.bitrate(bps.toLong())?.let { out += it }
                }
                val dropped = exo.videoDecoderCounters?.let { it.ensureUpdated(); it.droppedBufferCount }
                out += listOfNotNull(slide.decoderName, dropped?.let { "dropped $it" }).joinToString(" · ").ifEmpty { "decoder starting…" }
            }
            slide.web != null -> {
                out += "YouTube web player" + if (slide.nativeFailed != null) " (fallback)" else ""
                slide.nativeFailed?.let { out += "native: $it" }
                out += "quality " + (slide.webQuality ?: "starting…")
                out += "HDR: none (the web player is SDR only)"
            }
            exo != null && slide.video != null -> {
                val v = slide.file
                out += "Own file (ExoPlayer)"
                val f = exo.videoFormat
                if (f == null) {
                    out += "starting…"
                } else {
                    out += VideoInfo.picture(f.width, f.height, if (f.frameRate > 0f) f.frameRate else v?.fps ?: 0f)
                    out += VideoInfo.codecName(f.sampleMimeType, f.codecs, v?.profile)
                    out += "HDR: " + VideoInfo.hdrName(f.colorInfo?.colorTransfer, f.sampleMimeType, f.codecs)
                    val bps = listOf(f.averageBitrate.toLong(), f.bitrate.toLong(), v?.bitrate ?: 0L).firstOrNull { it > 0 } ?: 0L
                    VideoInfo.bitrate(bps)?.let { out += if (bps == v?.bitrate && v.averaged) "$it (average)" else it }
                }
                val dropped = exo.videoDecoderCounters?.let { it.ensureUpdated(); it.droppedBufferCount }
                out += listOfNotNull(slide.decoderName, dropped?.let { "dropped $it" }).joinToString(" · ").ifEmpty { "decoder starting…" }
            }
            slide.video != null -> {
                val v = slide.file
                out += "Own file (MediaPlayer" + if (slide.nativeFailed != null) ", fallback)" else ")"
                slide.nativeFailed?.let { out += "native: $it" }
                out += VideoInfo.picture(slide.videoW, slide.videoH, v?.fps ?: 0f)
                out += VideoInfo.codecName(v?.mime, null, v?.profile)
                val hdr = VideoInfo.hdrName(v?.transfer, v?.mime, null)
                out += "HDR: " + if (hdr.startsWith("SDR")) hdr else "$hdr in file, shown as SDR"
                VideoInfo.bitrate(v?.bitrate ?: 0)?.let { out += if (v?.averaged == true) "$it (average)" else it }
                val metrics = runCatching { slide.player?.metrics }.getOrNull()
                val codec = metrics?.getString(MediaPlayer.MetricsConstants.CODEC_VIDEO)
                val dropped = metrics?.takeIf { it.containsKey(MediaPlayer.MetricsConstants.FRAMES_DROPPED) }
                    ?.getInt(MediaPlayer.MetricsConstants.FRAMES_DROPPED)
                listOfNotNull(codec, dropped?.let { "dropped $it" }).joinToString(" · ").takeIf { it.isNotEmpty() }?.let { out += it }
            }
            slide.image != null -> {
                val b = slide.image
                out += "Own picture" + (slide.hdr?.let { ": $it" } ?: "")
                out += VideoInfo.picture(b.width, b.height, 0f)
                out += "bitmap ${b.config} · ${b.colorSpace?.name ?: "no colour space"}"
                if (Build.VERSION.SDK_INT >= 34) out += if (b.hasGainmap()) "gain map: yes" else "gain map: no"
                out += "window: ${colorModeName(window?.colorMode)}"
            }
            else -> out += "starting…"
        }
        val mode = displayMode
        out += VideoInfo.screen(displayW, displayH, mode?.refreshRate ?: 0f, hdrTypes)
        return out
    }

    /**
     * How long a video holds the screen: a clip shorter than the slide interval loops until the
     * interval is up (so it doesn't flash by), a longer one plays through, up to [VIDEO_CAP_MS].
     */
    private fun videoShowMs(slide: Slide, intervalMs: Long): Long =
        if (slide.durationMs < intervalMs) intervalMs else minOf(slide.durationMs, VIDEO_CAP_MS) + 500

    /**
     * An own video, fitted to the screen and played by Media3 on a SurfaceView, like a native
     * YouTube link: a SurfaceView hands the decoder's output straight to the display, so an HDR10,
     * HLG or Dolby Vision file reaches the TV as HDR (a TextureView composites it as SDR app
     * content). It doesn't fade with the slide. If ExoPlayer can't play the file, or it never
     * starts, the slide switches to Android's MediaPlayer ([mediaPlayerView]).
     */
    @OptIn(UnstableApi::class)
    private fun videoView(slide: Slide, intervalMs: Long): View {
        val file = slide.video!!
        val vw = slide.videoW.coerceAtLeast(1)
        val vh = slide.videoH.coerceAtLeast(1)
        val fit = fitScale(vw, vh)
        val holder = FrameLayout(context)
        val surface = SurfaceView(context)
        holder.addView(surface, FrameLayout.LayoutParams((vw * fit).toInt(), (vh * fit).toInt(), Gravity.CENTER))

        var started = false
        fun fallBack(why: String) = main.post {
            if (slide.disposed || slide.exo == null) return@post
            Log.w(TAG, "video ${file.name} native: $why; using MediaPlayer")
            slide.nativeFailed = why
            slide.exo?.release()
            slide.exo = null
            slide.decoderName = null
            holder.removeAllViews()
            holder.addView(mediaPlayerView(slide, intervalMs), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val exo = ExoPlayer.Builder(context).build()
        slide.exo = exo
        exo.setVideoSurfaceView(surface)
        exo.volume = if (sound) 1f else 0f
        exo.repeatMode = if (only != null || slide.durationMs < intervalMs) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!started) {
                    // What the decoder really got, to tell "the file is SDR" from "HDR lost on the way".
                    val f = exo.videoFormat
                    Log.i(TAG, "video ${file.name} native, decoder: ${slide.decoderName} ${f?.sampleMimeType} ${f?.codecs} ${f?.width}x${f?.height}" +
                        " transfer=${transferName(f?.colorInfo?.colorTransfer)} screen HDR types=$hdrTypes")
                }
                started = true
            }
            override fun onPlayerError(error: PlaybackException) {
                fallBack("error ${error.errorCodeName} ${error.message}")
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                slide.decoderName = decoderName
            }
        })
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.playWhenReady = true
        exo.prepare()
        main.postDelayed({ if (!started) fallBack("never started") }, NATIVE_START_MS)
        return holder
    }

    /**
     * How much to scale a [vw]x[vh] video to the screen: fitted, or in HDR only mode filling it
     * when that crops little (see [HdrMedia.fillsScreen]).
     */
    private fun fitScale(vw: Int, vh: Int): Float {
        val dm = context.resources.displayMetrics
        val sx = dm.widthPixels.toFloat() / vw
        val sy = dm.heightPixels.toFloat() / vh
        return if (hdrMode && HdrMedia.fillsScreen(vw, vh, dm.widthPixels, dm.heightPixels)) maxOf(sx, sy) else minOf(sx, sy)
    }

    /** The fallback: Android's MediaPlayer on a [TextureView] (it shows HDR as SDR). */
    private fun mediaPlayerView(slide: Slide, intervalMs: Long): View {
        val vw = slide.videoW.coerceAtLeast(1)
        val vh = slide.videoH.coerceAtLeast(1)
        val fit = fitScale(vw, vh)
        val tv = TextureView(context)
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                if (slide.disposed) return
                try {
                    val surface = Surface(st)
                    val mp = MediaPlayer()
                    slide.surface = surface
                    slide.player = mp
                    mp.setSurface(surface)
                    mp.setDataSource(slide.video!!.path)
                    mp.isLooping = only != null || slide.durationMs < intervalMs
                    val vol = if (sound) 1f else 0f
                    mp.setVolume(vol, vol)
                    mp.setOnPreparedListener { if (!slide.disposed) it.start() }
                    mp.setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "video ${slide.video?.name}: error $what/$extra")
                        main.post { if (running && !slide.disposed && current?.tag === slide) advance() }
                        true
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    Log.w(TAG, "video ${slide.video?.name}: $e")
                    main.post { if (running && !slide.disposed && current?.tag === slide) advance() }
                }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        return FrameLayout(context).apply {
            addView(tv, FrameLayout.LayoutParams((vw * fit).toInt(), (vh * fit).toInt(), Gravity.CENTER))
        }
    }

    /**
     * A YouTube link, played live in a WebView (muted unless Sound is on). A short video repeats
     * until the slide interval is up; a long one is cut then. If the player reports an error
     * (embedding disabled, video gone) or never starts, the slideshow just moves on.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun youtubeView(slide: Slide): View {
        fun skip() = main.post {
            if (running && !slide.disposed && current?.tag === slide) {
                slide.youtubeId?.let { brokenLinks[it] = System.currentTimeMillis() + BROKEN_LINK_MS }
                advance()
            }
        }
        var ready = false
        val web = acquireWeb().apply {
            webViewClient = object : WebViewClient() {
                // Only the player page itself; its frames load normally, the page can't navigate away.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        Log.w(TAG, "youtube ${slide.youtubeId}: page error ${error.errorCode} ${error.description}")
                        skip()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val msg = m.message()
                    if (msg.startsWith("artdream:")) {
                        Log.i(TAG, "youtube ${slide.youtubeId}: ${msg.removePrefix("artdream:")}")
                        if (msg == "artdream:ready") ready = true
                        if (msg.startsWith("artdream:quality:")) slide.webQuality = msg.removePrefix("artdream:quality:")
                        if (msg.startsWith("artdream:error:")) main.postDelayed({ skip() }, 1_500)
                    }
                    return true
                }
            }
        }
        slide.web = web
        slide.releaseWeb = ::releaseWeb
        lastYouTubeLoadAt = System.currentTimeMillis()
        web.loadDataWithBaseURL(YouTube.PLAYER_BASE_URL, YouTube.playerHtml(slide.youtubeId!!, sound, displayW, displayH, fromStart = only != null), "text/html", "utf-8", null)
        main.postDelayed({
            if (!ready && !slide.disposed) {
                Log.w(TAG, "youtube ${slide.youtubeId}: player never started")
                skip()
            }
        }, YOUTUBE_START_MS)
        return web
    }

    /**
     * The display's own resolution. On a 4K TV the app (and its WebViews) render at 1920x1080
     * while the TV shows 3840x2160; the YouTube page sizes its player to this so YouTube picks 4K.
     */
    @Suppress("DEPRECATION")
    private val displayMode by lazy {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.mode
        } catch (e: Exception) {
            null
        }
    }
    private val displayW get() = displayMode?.let { maxOf(it.physicalWidth, it.physicalHeight) } ?: 0
    private val displayH get() = displayMode?.let { minOf(it.physicalWidth, it.physicalHeight) } ?: 0

    /**
     * A YouTube link played by Media3 from the stream URLs [YouTubeLive] found, on a SurfaceView:
     * it plays at the screen's full resolution, where the WebView player is held to what YouTube
     * picks for its size. (It doesn't fade with the slide the way the other views do.) No text
     * track is loaded, so there are never captions. Starts somewhere inside a long video, loops a
     * short one. If playback fails or never starts, the slide switches to the web player, and that
     * video uses the web player for a while.
     */
    @OptIn(UnstableApi::class)
    private fun nativeYouTubeView(slide: Slide): View {
        val live = slide.live!!
        val id = slide.youtubeId!!
        val holder = FrameLayout(context)
        val dm = context.resources.displayMetrics
        val vw = live.width.takeIf { it > 0 } ?: (live.height * 16 / 9)
        val vh = live.height.coerceAtLeast(1)
        val fit = minOf(dm.widthPixels.toFloat() / vw.coerceAtLeast(1), dm.heightPixels.toFloat() / vh)
        val surface = SurfaceView(context)
        holder.addView(surface, FrameLayout.LayoutParams((vw * fit).toInt(), (vh * fit).toInt(), Gravity.CENTER))

        var started = false
        fun fallBack(why: String) = main.post {
            if (slide.disposed || slide.exo == null) return@post
            Log.w(TAG, "youtube $id native: $why; using the web player")
            slide.nativeFailed = why
            nativeBroken[id] = System.currentTimeMillis() + BROKEN_LINK_MS
            slide.exo?.release()
            slide.exo = null
            holder.removeAllViews()
            holder.addView(youtubeView(slide), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val sources = ProgressiveMediaSource.Factory(http)
        val video = sources.createMediaSource(MediaItem.fromUri(live.videoUrl))
        val withSound = sound && live.audioUrl != null
        val source = if (withSound) MergingMediaSource(video, sources.createMediaSource(MediaItem.fromUri(live.audioUrl!!))) else video
        val exo = ExoPlayer.Builder(context).build()
        slide.exo = exo
        exo.setVideoSurfaceView(surface)
        exo.volume = if (withSound) 1f else 0f
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.addListener(object : Player.Listener {
            @OptIn(UnstableApi::class)
            override fun onRenderedFirstFrame() {
                if (!started) {
                    Log.i(TAG, "youtube $id native: playing ${live.summary}")
                    val f = exo.videoFormat
                    Log.i(TAG, "youtube $id native, decoder: ${slide.decoderName} ${f?.sampleMimeType} ${f?.codecs} ${f?.width}x${f?.height}")
                }
                started = true
            }
            override fun onPlayerError(error: PlaybackException) {
                fallBack("error ${error.errorCodeName} ${error.message}")
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            @OptIn(UnstableApi::class)
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) {
                slide.decoderName = decoderName
            }
        })
        exo.setMediaSource(source)
        // A slide shows a minute or so: start somewhere inside a long video, not always at 0:00.
        if (only == null && live.durationMs > 240_000) exo.seekTo(Random.nextLong(live.durationMs - 150_000))
        exo.playWhenReady = true
        exo.prepare()
        main.postDelayed({ if (!started) fallBack("never started") }, NATIVE_START_MS)
        return holder
    }

    /** The HDR kinds the TV takes (1 Dolby Vision, 2 HDR10, 3 HLG, 4 HDR10+); empty for an SDR TV. */
    @Suppress("DEPRECATION")
    private val hdrTypes: List<Int> by lazy {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                .hdrCapabilities?.supportedHdrTypes?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The window the slideshow is in: the screensaver's or the preview activity's. */
    private val window: Window? get() = when (context) {
        is Activity -> context.window
        is DreamService -> context.window
        else -> null
    }

    /**
     * HDR only asks for the window's HDR colour mode, so an HDR picture is drawn as HDR rather
     * than squeezed into SDR. Android draws app content in HDR from Android 14; before that
     * (the SHIELD runs Android 11) the most an app can ask for is wide gamut, and pictures reach
     * the TV as SDR. Videos don't depend on this: they go to the TV on their own surface.
     */
    @Suppress("DEPRECATION")
    private fun setHdrWindow(on: Boolean) {
        val w = window ?: return
        val display = runCatching { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay }.getOrNull()
        val mode = when {
            !on -> ActivityInfo.COLOR_MODE_DEFAULT
            Build.VERSION.SDK_INT >= 34 && display?.isHdr == true -> ActivityInfo.COLOR_MODE_HDR
            display?.isWideColorGamut == true -> ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT
            else -> ActivityInfo.COLOR_MODE_DEFAULT
        }
        runCatching { w.colorMode = mode }.onFailure { Log.w(TAG, "window colour mode: $it") }
        if (on) Log.i(TAG, "hdr only: Android ${Build.VERSION.SDK_INT}, screen isHdr=${display?.isHdr} wideGamut=${display?.isWideColorGamut}" +
            " HDR types=$hdrTypes, asked ${colorModeName(mode)}, window has ${colorModeName(w.colorMode)}")
    }

    private fun colorModeName(m: Int?) = when (m) {
        ActivityInfo.COLOR_MODE_DEFAULT -> "default (SDR)"
        ActivityInfo.COLOR_MODE_WIDE_COLOR_GAMUT -> "wide colour gamut"
        ActivityInfo.COLOR_MODE_HDR -> "HDR"
        null -> "unknown"
        else -> "$m"
    }

    /** What an HDR only slide is, and what the window and screen take, for checking on the TV with logcat. */
    private fun logHdr(slide: Slide) {
        val b = slide.image
        val what = if (b != null) {
            "picture ${slide.art.id} ${slide.hdr}: ${b.width}x${b.height} ${b.config} ${b.colorSpace?.name}" +
                (if (Build.VERSION.SDK_INT >= 34) " gainmap=${b.hasGainmap()}" else "")
        } else {
            "video ${slide.art.id} ${slide.hdr}"
        }
        Log.i(TAG, "hdr only: $what; window ${colorModeName(window?.colorMode)}, screen HDR types=$hdrTypes")
    }

    @OptIn(UnstableApi::class)
    private fun transferName(t: Int?) = when (t) {
        C.COLOR_TRANSFER_ST2084 -> "PQ (HDR10)"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR"
        null, Format.NO_VALUE -> "not signalled"
        else -> "$t"
    }

    /**
     * WebViews are kept and reused between slides. Destroying one while the next slide's page
     * was still loading left the new page without a network connection for ~25 s on the TV, so
     * they are only destroyed when the slideshow stops.
     */
    private val idleWebs = ArrayDeque<WebView>()

    @SuppressLint("SetJavaScriptEnabled")
    private fun acquireWeb(): WebView = idleWebs.removeFirstOrNull() ?: WebView(context).apply {
        setBackgroundColor(Color.BLACK)
        isFocusable = false
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
    }

    private fun releaseWeb(w: WebView) {
        (w.parent as? ViewGroup)?.removeView(w)
        w.webViewClient = WebViewClient()      // stop reporting to the slide it served
        w.webChromeClient = WebChromeClient()
        w.loadUrl("about:blank")
        idleWebs.addLast(w)
    }

    private fun destroyIdleWebs() {
        while (idleWebs.isNotEmpty()) idleWebs.removeFirst().let { it.stopLoading(); it.destroy() }
    }

    /** Runs on the loader thread, so the stream lookup (a few seconds) happens off the main one. */
    private fun decodeYouTube(entry: ArtCache.Entry): Slide? {
        val id = OwnMedia.youtubeId(entry.image) ?: return null
        val nativeOk = (nativeBroken[id] ?: 0L) < System.currentTimeMillis()
        val live = if (nativeOk) YouTubeLive.resolve(id, displayH) else null
        return Slide(entry.art, null, Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888), youtubeId = id, live = live)
    }

    /** Poster frame (for the blurred backdrop), size and length of a video; null if it can't be read. */
    private fun decodeVideo(entry: ArtCache.Entry): Slide? {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(entry.image.path)
            fun meta(key: Int) = r.extractMetadata(key)?.toIntOrNull() ?: 0
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            var h = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            if (durationMs <= 0 || w <= 0 || h <= 0) return null
            val frame = r.getFrameAtTime(minOf(1_000_000L, durationMs * 500), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val backdrop = if (frame != null) {
                val bh = (24f * frame.height / frame.width).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(frame, 24, bh, true).also { if (it !== frame) frame.recycle() }
            } else {
                Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888)   // black
            }
            return Slide(entry.art, null, backdrop, entry.image, w, h, durationMs,
                file = if (prefs.videoInfo) fileVideo(entry.image, durationMs) else null,
                hdr = if (hdrMode) hdrKinds[entry.art.id] else null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** The first video track's format, for the info overlay; null if the file can't be read that way. */
    private fun fileVideo(file: File, durationMs: Long): FileVideo? {
        val x = MediaExtractor()
        return try {
            x.setDataSource(file.path)
            val f = (0 until x.trackCount).map { x.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            fun int(key: String) = if (f.containsKey(key)) runCatching { f.getInteger(key) }.getOrNull() else null
            // The frame rate is stored as an int or a float, depending on the extractor.
            val fps = if (!f.containsKey(MediaFormat.KEY_FRAME_RATE)) 0f else
                runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.recoverCatching { f.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(0f)
            val declared = int(MediaFormat.KEY_BIT_RATE)?.toLong() ?: 0L
            FileVideo(f.getString(MediaFormat.KEY_MIME), int(MediaFormat.KEY_PROFILE), fps, int(MediaFormat.KEY_COLOR_TRANSFER),
                if (declared > 0) declared else file.length() * 8_000 / durationMs.coerceAtLeast(1), averaged = declared <= 0)
        } catch (e: Exception) {
            Log.w(TAG, "video info ${file.name}: $e")
            null
        } finally {
            runCatching { x.release() }
        }
    }

    private fun isNight(): Boolean {
        if (only != null || prefs.nightMode != Prefs.NIGHT_AUTO) return false
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
        if (only != null) return own.entries().filter { it.art.id == only }
        // A YouTube link that just failed (embedding off, video gone, no network) sits out a while,
        // so a playlist of one bad link doesn't keep reloading a web page.
        val now = System.currentTimeMillis()
        val mine = (if (prefs.ownMode == Prefs.OWN_OFF) emptyList() else own.entries()).filter { e ->
            !OwnMedia.isYouTube(e) || (brokenLinks[OwnMedia.youtubeId(e.image)] ?: 0L) <= now
        }
        // "Only mine" with nothing added yet falls back to the museum art, never a blank screen.
        val all = if (prefs.ownMode == Prefs.OWN_ONLY && mine.isNotEmpty()) mine else cache.entries() + mine
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
        const val TAG = "fArtSaver"
        const val FADE_MS = 2_000L
        const val RUN = 8
        /** In Mixed mode, one of the user's own slides after this many museum slides. */
        const val OWN_EVERY = 3
        /** A video longer than this is cut here and the slideshow moves on. */
        const val VIDEO_CAP_MS = 5 * 60_000L
        /**
         * A YouTube slide that hasn't reported "ready" by now is skipped (no network, blocked, …).
         * Generous on purpose: on the test TV a page loaded soon after another YouTube page stalled
         * for ~30 s before the player started.
         */
        const val YOUTUBE_START_MS = 45_000L
        /** A natively played YouTube link that shows no picture by now switches to the web player. */
        const val NATIVE_START_MS = 20_000L
        /** Minimum spacing wanted between two YouTube page loads (see [advance]). */
        const val YOUTUBE_GAP_MS = 35_000L
        /** How long a failed YouTube link is left out of the playlist. */
        const val BROKEN_LINK_MS = 15 * 60_000L
        /** HDR only with nothing HDR: how soon to look again (files may have been added). */
        const val NO_HDR_RETRY_MS = 60_000L
        /** How often the video info overlay is refreshed. */
        const val INFO_TICK_MS = 2_000L
        /** Warm dim veil over videos at night (the bitmap [NIGHT_FILTER] can't apply to them). */
        val NIGHT_VEIL = Color.argb(90, 40, 15, 0)
        /** Dim to ~75% and shift toward warm (less blue, a little less green). */
        val NIGHT_FILTER = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.6f, 0f, 0f, 0f,
            0f, 0f, 0.4f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)))
    }
}
