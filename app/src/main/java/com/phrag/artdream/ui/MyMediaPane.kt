package com.phrag.artdream.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.phrag.artdream.HdrProbe
import com.phrag.artdream.OwnMedia
import com.phrag.artdream.PreviewActivity
import com.phrag.artdream.Prefs
import com.phrag.artdream.R
import com.phrag.artdream.UploadServer
import com.phrag.artdream.YouTubeDownloads
import java.util.concurrent.Executors

/**
 * "My media": the user's own pictures and videos. Shows how they mix with the museum art, the
 * address to open on a phone or computer to add files (the upload server only runs while this
 * screen is showing), and what's on the TV, where OK plays an item full screen and the Remove
 * button beside it deletes it.
 */
class MyMediaPane(private val ctx: Context, private val prefs: Prefs, private val own: OwnMedia) {

    val view = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "fartsaver-mymedia").apply { isDaemon = true } }

    private val modeRow = SettingRow(ctx, "Show my media")
    private val soundRow = SettingRow.toggle(ctx, "Sound on videos", "Off keeps the screensaver quiet", prefs.videoSound) {
        prefs.videoSound = it
    }
    private val infoRow = SettingRow.toggle(ctx, "Video info overlay",
        "Shows resolution, frame rate, codec, HDR and bitrate in a corner while a video plays", prefs.videoInfo) {
        prefs.videoInfo = it
    }
    private val hdrRow = SettingRow.toggle(ctx, "HDR only", null, prefs.hdrOnly) {
        prefs.hdrOnly = it
        renderMode()
        renderHdr()
    }
    private val hdrProbe = HdrProbe(ctx.applicationContext)
    /** How many own items are HDR (and play as HDR here); null until the list has been checked. */
    private var hdrCount: Int? = null
    private val addressView = Ui.text(ctx, "", Ui.HEADER_SP).apply {
        typeface = Typeface.MONOSPACE
        setPadding(0, ctx.dp(6), 0, ctx.dp(6))
    }
    private val qrView = QrView(ctx)
    private val statusView = Ui.text(ctx, "", Ui.BODY_SP, R.color.ad_text2)
    private val usedView = Ui.body(ctx, "")
    private val listBox = Ui.column(ctx).apply { clipChildren = false; clipToPadding = false }
    private val adbView = Ui.text(ctx, "", Ui.SMALL_SP, R.color.ad_text3).apply { typeface = Typeface.MONOSPACE }

    private var server: UploadServer? = null
    private val token = UploadServer.newToken()   // stable while the app is open
    private var shown = false
    private var started = false
    /** The item last played, so its row gets focus back when the list is rebuilt on return. */
    private var playedKey: String? = null

    init {
        val col = Ui.column(ctx).apply {
            setPadding(ctx.dp(LibraryPane.PAD), ctx.dp(36), ctx.dp(LibraryPane.PAD), ctx.dp(48))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(Ui.header(ctx, "My media"))
        col.addView(Ui.body(ctx, "Your own pictures and videos can play in the screensaver, mixed in with the art or on their own."))
        col.addView(Ui.spacer(ctx, 6))
        renderMode()
        modeRow.setOnClickListener {
            prefs.ownMode = when (prefs.ownMode) {
                Prefs.OWN_MIX -> Prefs.OWN_ONLY
                Prefs.OWN_ONLY -> Prefs.OWN_OFF
                else -> Prefs.OWN_MIX
            }
            renderMode()
        }
        col.addView(modeRow)
        col.addView(soundRow)
        col.addView(infoRow)
        col.addView(hdrRow)
        renderHdr()

        col.addView(Ui.groupLabel(ctx, "Add from your phone or computer"))
        // Address and instructions on the left, a QR code of the same address on the right.
        val addRow = Ui.row(ctx).apply { gravity = Gravity.TOP }
        val addText = Ui.column(ctx)
        addText.addView(Ui.body(ctx, "On a device on the same Wi-Fi, scan the code with the camera or open this address in a browser:"))
        addText.addView(addressView)
        addText.addView(statusView)
        addRow.addView(addText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addRow.addView(qrView, LinearLayout.LayoutParams(ctx.dp(QR_DP), ctx.dp(QR_DP)).apply { marginStart = ctx.dp(24) })
        col.addView(addRow)

        col.addView(Ui.groupLabel(ctx, "On this TV"))
        col.addView(usedView)
        col.addView(listBox)

        col.addView(Ui.groupLabel(ctx, "Or copy files with adb"))
        col.addView(adbView)
        view.addView(col)
        render(emptyList())
    }

    /** The row to focus when the pane is entered with the D-pad. */
    fun firstFocusable(): View = modeRow

    /** The pane became visible or hidden in the settings. */
    fun setShown(on: Boolean) {
        shown = on
        sync()
    }

    /** The activity is in the foreground (between onStart and onStop). */
    fun setStarted(on: Boolean) {
        started = on
        sync()
    }

    private fun sync() {
        if (shown && started) startServer() else stopServer()
    }

    /** The display's own height (a 4K TV runs apps at 1080p but shows 2160 lines); 1080 if unknown. */
    @Suppress("DEPRECATION")
    private fun screenHeight(): Int = runCatching {
        val mode = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.mode
        minOf(mode.physicalWidth, mode.physicalHeight)
    }.getOrDefault(0).takeIf { it > 0 } ?: 1080

    private fun startServer() {
        if (server != null) return
        val s = UploadServer(own, object : UploadServer.Events {
            override fun onUploaded(name: String) {
                main.post { statusView.text = "Added $name"; statusView.setTextColor(ctx.getColor(R.color.ad_text)); reload() }
            }
            override fun onRejected(name: String, reason: String) {
                main.post { statusView.text = "Not added: ${name.ifEmpty { "file" }} — $reason"; statusView.setTextColor(ctx.getColor(R.color.ad_warn)) }
            }
            override fun onChanged() { main.post { reload() } }
            override fun onJobs() {
                main.post {
                    val job = YouTubeDownloads.jobs().lastOrNull() ?: return@post
                    statusView.text = job.describe()
                    val failed = job.state == YouTubeDownloads.State.FAILED
                    statusView.setTextColor(ctx.getColor(if (failed) R.color.ad_warn else R.color.ad_text))
                    if (!job.active) reload()
                }
            }
        }, token, screenHeight())
        if (!s.start()) {
            addressView.text = ""
            qrView.setText(null)
            statusView.text = "Couldn't start the upload page (no free port)."
            reload()
            return
        }
        server = s
        val urls = s.urls()
        addressView.text = urls.joinToString("\n") { it.removePrefix("http://") }
        qrView.setText(urls.firstOrNull())   // the camera needs the scheme, so keep "http://"
        statusView.setTextColor(ctx.getColor(R.color.ad_text2))
        statusView.text = if (urls.isEmpty()) "No network address found. Connect the TV to Wi-Fi or Ethernet."
            else "Waiting for files…  (Only works while this screen is open. The address changes when the app restarts.)"
        reload()
    }

    private fun stopServer() {
        server?.stop()
        server = null
        qrView.setText(null)
    }

    private fun reload() {
        io.execute {
            own.importPending()
            val items = own.items()
            val used = own.usedBytes()
            hdrProbe.prune(items)
            val hdr = items.associate { it.key to hdrProbe.probe(it) }
            main.post { render(items, used, hdr) }
        }
    }

    private fun renderMode() {
        modeRow.isEnabled = !prefs.hdrOnly   // HDR only decides what plays while it's on
        modeRow.setValue(when (prefs.ownMode) { Prefs.OWN_ONLY -> "Only mine"; Prefs.OWN_OFF -> "Off"; else -> "Mixed" })
        modeRow.setSub(if (prefs.hdrOnly) "Not used while HDR only is on" else when (prefs.ownMode) {
            Prefs.OWN_ONLY -> "Only your pictures and videos; nothing is downloaded (art is used if you have none)"
            Prefs.OWN_OFF -> "Your pictures and videos are kept but not shown"
            else -> "One of yours after every three art slides"
        })
    }

    private fun renderHdr() {
        val n = hdrCount
        hdrRow.setSub(when {
            n == null || !prefs.hdrOnly && n > 0 ->
                "Plays only your HDR pictures and videos, full screen. Museum art, YouTube links and SDR files are skipped" +
                    (n?.let { " · $it HDR item${if (it == 1) "" else "s"} on this TV" } ?: "")
            n == 0 && prefs.hdrOnly -> "None of your media is HDR yet, so the screensaver shows a message. Add HDR10, HLG or Dolby Vision videos or HDR photos below"
            n == 0 -> "None of your media is HDR yet. Add HDR10, HLG or Dolby Vision videos or HDR photos to use this"
            else -> "Showing only your $n HDR item${if (n == 1) "" else "s"}, full screen"
        })
    }

    private fun render(items: List<OwnMedia.Item>, used: Long = 0L, hdr: Map<String, HdrProbe.Result>? = null) {
        if (hdr != null) {
            hdrCount = hdr.values.count { it.hdr }
            renderHdr()
        }
        usedView.text = if (items.isEmpty()) "Nothing added yet." else
            "${items.size} item${if (items.size == 1) "" else "s"} · ${mb(used)} of ${mb(OwnMedia.QUOTA_BYTES)}"
        listBox.removeAllViews()
        for (item in items) {
            val tag = hdr?.get(item.key)?.takeIf { it.kind != null }?.let { r ->
                " · " + r.kind + if (r.playable) "" else " (no HDR decoder on this TV)"
            }.orEmpty()
            val sub = when {
                item.youtube -> "YouTube link · streams from YouTube"
                item.video -> "Video · ${mb(item.bytes)}$tag"
                else -> "Picture · ${mb(item.bytes)}$tag"
            }
            val row = SettingRow(ctx, item.label, sub)
            row.setValue("Play")
            row.setOnClickListener {
                playedKey = item.key
                PreviewActivity.play(ctx, item.key)
            }
            val remove = Ui.button(ctx, "Remove") { confirmRemove(item) }
            val line = Ui.row(ctx).apply { clipChildren = false; clipToPadding = false }
            line.addView(row, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = ctx.dp(6)
            })
            line.addView(remove, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ctx.dp(6)
                marginStart = ctx.dp(12)
            })
            listBox.addView(line)
            if (item.key == playedKey && row.requestFocus()) playedKey = null
        }
        adbView.text = "adb push <file> ${own.importDir?.path ?: "(no storage)"}/"
    }

    private fun confirmRemove(item: OwnMedia.Item) {
        AlertDialog.Builder(ctx)
            .setTitle("Remove “${item.label}”?")
            .setMessage("It's deleted from this TV.")
            .setPositiveButton("Remove") { _, _ ->
                io.execute { own.remove(item.key); main.post { reload(); modeRow.requestFocus() } }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private companion object {
        const val QR_DP = 180
    }

    private fun mb(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        else -> "%d MB".format((bytes / (1L shl 20)).coerceAtLeast(if (bytes > 0) 1 else 0))
    }
}
