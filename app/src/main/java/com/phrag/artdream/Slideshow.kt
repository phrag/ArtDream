package com.phrag.artdream

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
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
 */
class Slideshow(private val context: Context) {

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
    ) {
        var player: MediaPlayer? = null
        var exo: ExoPlayer? = null
        var surface: Surface? = null
        var web: WebView? = null
        /** Hands [web] back to the slideshow's pool (WebViews are reused, not destroyed per slide). */
        var releaseWeb: ((WebView) -> Unit)? = null
        var disposed = false

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


    private val main = Handler(Looper.getMainLooper())
    private val loader: ExecutorService = Executors.newSingleThreadExecutor()   // decodes the next slide
    private val network: ExecutorService = Executors.newSingleThreadExecutor()  // tops up the cache
    private val cache = ArtCache(context.applicationContext)
    private val own = OwnMedia(context.applicationContext)
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
        // Top up the cache in the background (one refresh at a time) — unless only
        // the user's own media is wanted, which needs no downloads.
        if (prefs.ownMode != Prefs.OWN_ONLY && refreshing.compareAndSet(false, true)) {
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
        if (queue.isEmpty()) queue.addAll(buildQueue())
        val next = takeNext()
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
                if (slide == null && mine) {
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
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(slide.image)
            if (isNight()) colorFilter = NIGHT_FILTER
        }
        val slideView = FrameLayout(context).apply {
            alpha = 0f
            tag = slide   // so discard() can recycle its bitmaps
            addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(painting, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            // A video can't take the bitmap colour filter, so dim and warm it with a veil.
            if ((isVideo || isYouTube) && isNight()) addView(View(context).apply { setBackgroundColor(NIGHT_VEIL) },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
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
        // (Videos already move, so they stay put.)
        if (!isVideo && !isYouTube) {
            val drift = context.resources.displayMetrics.widthPixels * 0.02f
            painting.animate()
                .scaleX(1.06f).scaleY(1.06f)
                .translationX(if (Random.nextBoolean()) drift else -drift)
                .translationY(if (Random.nextBoolean()) drift / 2 else -drift / 2)
                .setInterpolator(LinearInterpolator())
                .setDuration(intervalMs + FADE_MS)
        }

        main.postDelayed(::advance, if (isVideo) videoShowMs(slide, intervalMs) else intervalMs)
    }

    /**
     * How long a video holds the screen: a clip shorter than the slide interval loops until the
     * interval is up (so it doesn't flash by), a longer one plays through, up to [VIDEO_CAP_MS].
     */
    private fun videoShowMs(slide: Slide, intervalMs: Long): Long =
        if (slide.durationMs < intervalMs) intervalMs else minOf(slide.durationMs, VIDEO_CAP_MS) + 500

    /** The video, fitted to the screen on a [TextureView] (unlike a SurfaceView it fades with the slide). */
    private fun videoView(slide: Slide, intervalMs: Long): View {
        val dm = context.resources.displayMetrics
        val vw = slide.videoW.coerceAtLeast(1)
        val vh = slide.videoH.coerceAtLeast(1)
        val fit = minOf(dm.widthPixels.toFloat() / vw, dm.heightPixels.toFloat() / vh)
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
                    mp.isLooping = slide.durationMs < intervalMs
                    val vol = if (prefs.videoSound) 1f else 0f
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
                        if (msg.startsWith("artdream:error:")) main.postDelayed({ skip() }, 1_500)
                    }
                    return true
                }
            }
        }
        slide.web = web
        slide.releaseWeb = ::releaseWeb
        lastYouTubeLoadAt = System.currentTimeMillis()
        web.loadDataWithBaseURL(YouTube.PLAYER_BASE_URL, YouTube.playerHtml(slide.youtubeId!!, prefs.videoSound, displayW, displayH), "text/html", "utf-8", null)
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
     * unlike the WebView or a TextureView, a SurfaceView hands the decoder's output straight to the
     * display, so HDR streams show as HDR. (It doesn't fade with the slide the way the other views
     * do.) No text track is loaded, so there are never captions. Starts somewhere inside a long
     * video, loops a short one. If playback fails or never starts, the slide switches to the web
     * player, and that video uses the web player for a while.
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
            nativeBroken[id] = System.currentTimeMillis() + BROKEN_LINK_MS
            slide.exo?.release()
            slide.exo = null
            holder.removeAllViews()
            holder.addView(youtubeView(slide), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }

        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val sources = ProgressiveMediaSource.Factory(http)
        val video = sources.createMediaSource(MediaItem.fromUri(live.videoUrl))
        val sound = prefs.videoSound && live.audioUrl != null
        val source = if (sound) MergingMediaSource(video, sources.createMediaSource(MediaItem.fromUri(live.audioUrl!!))) else video
        val exo = ExoPlayer.Builder(context).build()
        slide.exo = exo
        exo.setVideoSurfaceView(surface)
        exo.volume = if (sound) 1f else 0f
        exo.repeatMode = Player.REPEAT_MODE_ONE
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!started) Log.i(TAG, "youtube $id native: playing ${live.summary}")
                started = true
            }
            override fun onPlayerError(error: PlaybackException) {
                fallBack("error ${error.errorCodeName} ${error.message}")
            }
        })
        exo.setMediaSource(source)
        // A slide shows a minute or so: start somewhere inside a long video, not always at 0:00.
        if (live.durationMs > 240_000) exo.seekTo(Random.nextLong(live.durationMs - 150_000))
        exo.playWhenReady = true
        exo.prepare()
        main.postDelayed({ if (!started) fallBack("never started") }, NATIVE_START_MS)
        return holder
    }

    /** Whether the TV takes HDR (HDR10, HLG or Dolby Vision) at all; if not, SDR streams are picked. */
    @Suppress("DEPRECATION")
    private val hdrScreen by lazy {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
                .hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true
        } catch (e: Exception) {
            false
        }
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
        val live = if (nativeOk) YouTubeLive.resolve(id, displayH, hdrScreen) else null
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
            return Slide(entry.art, null, backdrop, entry.image, w, h, durationMs)
        } finally {
            runCatching { r.release() }
        }
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
