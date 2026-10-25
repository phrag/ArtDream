package com.example.artdream.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.artdream.ArtCache
import com.example.artdream.source.ArtSource
import org.json.JSONException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/**
 * Process-wide owner of the "download now" run, so a download keeps going (and
 * its progress is still visible) if the settings activity is left and
 * re-entered. The activity only observes; it never owns the worker thread.
 *
 * Threading: all state below is read and written on the main thread only.
 * The worker posts every update through [post], tagged with the run id, so
 * late callbacks from an old/cancelled run are dropped.
 */
object DownloadController {

    enum class Phase { IDLE, RUNNING, CANCELLING, BACKGROUND, DONE }

    class Failure(val sourceLabel: String, val reason: String)

    interface Observer {
        fun onDownloadState()
        fun onDownloadAdded(entry: ArtCache.Entry) {}
    }

    var phase = Phase.IDLE; private set
    var added = 0; private set
    var wanted = 0; private set
    var status = ""; private set
    /** Newest first; capped so a long session can't hold unbounded entries. */
    val recent = ArrayList<ArtCache.Entry>()
    val failures = LinkedHashMap<String, Failure>()
    var cancelled = false; private set
    var noSources = false; private set
    var error: String? = null; private set
    /** Library size after the run (or as last polled while the screensaver downloads). */
    var libraryCount = -1; private set
    var backgroundStartCount = -1; private set
    var fromBackground = false; private set
    /** How many sources the current/last run asked. */
    var sourceCount = 0; private set
    /** Incremented every time a run (ours or the screensaver's) finishes. */
    var finishedRuns = 0; private set

    private const val TAG = "ArtDream"
    private const val RECENT_CAP = 40
    private const val POLL_MS = 1500L

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "artdream-download").apply { isDaemon = true }
    }
    private val observers = LinkedHashSet<Observer>()
    private var runId = 0

    @Volatile private var workerThread: Thread? = null
    @Volatile private var cancelRequested = false

    val isBusy: Boolean get() = phase == Phase.RUNNING || phase == Phase.CANCELLING || phase == Phase.BACKGROUND

    fun observe(o: Observer) { observers.add(o) }
    fun unobserve(o: Observer) { observers.remove(o) }

    fun start(ctx: Context) {
        if (isBusy) return
        val id = ++runId
        phase = Phase.RUNNING
        added = 0; wanted = 0
        status = "Starting…"
        failures.clear()
        cancelled = false; noSources = false; error = null; fromBackground = false
        backgroundStartCount = -1
        cancelRequested = false
        sourceCount = 0
        notifyState()

        val appCtx = ctx.applicationContext
        worker.execute {
            Thread.interrupted()   // clear any stale flag from an earlier cancel
            workerThread = Thread.currentThread()
            try {
                if (cancelRequested) {
                    post(id) { finish(cancelledRun = true) }
                    return@execute
                }
                val cache = ArtCache(appCtx)
                val sources = cache.enabledSources().size
                if (sources == 0) {
                    post(id) { noSources = true; finish(false) }
                    return@execute
                }
                post(id) { sourceCount = sources }
                val before = cache.count()
                val estimate = cache.wantedNow()
                post(id) { if (wanted == 0) wanted = estimate; notifyState() }

                val result = try {
                    cache.refresh(object : ArtCache.Listener {
                        // HttpURLConnection/okio swallow the interrupt flag when they abort a
                        // request, so re-assert it on every callback until refresh() notices.
                        private fun keepInterrupted() {
                            if (cancelRequested) Thread.currentThread().interrupt()
                        }
                        override fun onStatus(message: String) {
                            keepInterrupted()
                            if (!cancelRequested) post(id) { status = message; notifyState() }
                        }
                        override fun onAdded(entry: ArtCache.Entry, added: Int, wanted: Int) {
                            keepInterrupted()
                            post(id) { onAddedMain(entry, added, wanted) }
                        }
                        override fun onSourceFailed(source: ArtSource, error: Exception) {
                            keepInterrupted()
                            if (cancelRequested) return   // failures caused by our own cancel
                            post(id) {
                                failures[source.id] = Failure(source.label, shortReason(error))
                                notifyState()
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.w(TAG, "download now failed", e)
                    post(id) { error = shortReason(e); finish(false) }
                    return@execute
                }

                if (result == ArtCache.ALREADY_RUNNING) {
                    post(id) {
                        phase = Phase.BACKGROUND
                        fromBackground = true
                        backgroundStartCount = before
                        libraryCount = before
                        status = "The screensaver is downloading in the background"
                        notifyState()
                        main.postDelayed({ poll(id, appCtx) }, POLL_MS)
                    }
                    return@execute
                }
                val after = cache.count()
                post(id) {
                    added = result
                    libraryCount = after
                    finish(cancelledRun = cancelRequested)
                }
            } finally {
                workerThread = null
                Thread.interrupted()
            }
        }
    }

    /** Stops our own run (the refresh loop honours interruption). Can't stop the screensaver's. */
    fun cancel() {
        if (phase != Phase.RUNNING) return
        cancelRequested = true
        phase = Phase.CANCELLING
        status = "Stopping after the current request…"
        workerThread?.interrupt()
        notifyState()
    }

    /** Back to idle after the user has seen the summary. */
    fun dismissSummary() {
        if (phase == Phase.DONE) { phase = Phase.IDLE; notifyState() }
    }

    fun forget(entry: ArtCache.Entry) {
        if (recent.removeAll { it.art.key == entry.art.key }) notifyState()
    }

    private fun onAddedMain(entry: ArtCache.Entry, count: Int, want: Int) {
        added = count
        wanted = want
        recent.add(0, entry)
        while (recent.size > RECENT_CAP) recent.removeAt(recent.size - 1)
        observers.toList().forEach { it.onDownloadAdded(entry) }
        notifyState()
    }

    private fun poll(id: Int, appCtx: Context) {
        if (id != runId || phase != Phase.BACKGROUND) return
        worker.execute {
            val cache = ArtCache(appCtx)
            val running = cache.isRefreshing
            val count = cache.count()
            post(id) {
                libraryCount = count
                if (running) {
                    notifyState()
                    main.postDelayed({ poll(id, appCtx) }, POLL_MS)
                } else {
                    added = maxOf(0, count - backgroundStartCount)
                    finish(false)
                }
            }
        }
    }

    private fun finish(cancelledRun: Boolean) {
        cancelled = cancelledRun
        phase = Phase.DONE
        status = ""
        finishedRuns++
        notifyState()
    }

    private fun post(id: Int, block: () -> Unit) {
        main.post { if (id == runId) block() }
    }

    private fun notifyState() {
        observers.toList().forEach { it.onDownloadState() }
    }

    /** A few words a person can act on, instead of an exception class name. */
    fun shortReason(e: Throwable): String = when (e) {
        is UnknownHostException -> "no internet connection"
        is ConnectException -> "couldn't connect"
        is SocketTimeoutException -> "timed out"
        is SSLException -> "secure connection failed"
        is FileNotFoundException -> "not found"
        is JSONException -> "unexpected reply"
        is InterruptedException, is java.io.InterruptedIOException -> "cancelled"
        is IOException -> e.message?.let { m ->
            Regex("HTTP (\\d{3})").find(m)?.let { "server error ${it.groupValues[1]}" }
        } ?: "network error"
        else -> e.javaClass.simpleName.ifEmpty { "error" }
    }
}
