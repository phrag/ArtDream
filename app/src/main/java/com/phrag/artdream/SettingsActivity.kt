package com.phrag.artdream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.phrag.artdream.source.ArtSource
import com.phrag.artdream.ui.ArtworkDialog
import com.phrag.artdream.ui.ContentPane
import com.phrag.artdream.ui.DownloadController
import com.phrag.artdream.ui.DiagnosticsPane
import com.phrag.artdream.ui.DownloadPane
import com.phrag.artdream.ui.LibraryPane
import com.phrag.artdream.ui.SettingRow
import com.phrag.artdream.ui.ThumbLoader
import com.phrag.artdream.ui.Ui
import com.phrag.artdream.ui.dp
import com.phrag.artdream.ui.focusScale
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Launcher entry + the screensaver's settings gear.
 *
 * Two-column 10-foot layout: a side nav (Test screensaver, then sections) and
 * a content pane that follows nav focus, like the system TV settings. Right
 * from the nav enters the pane (at the last-focused item); Left or Back from
 * the pane returns to its nav item. Plain framework Views throughout.
 *
 * Threading: nothing here touches disk or network on the main thread. Cache
 * reads/removes go through [io]; thumbnails through [ThumbLoader]; the
 * download itself is owned by [DownloadController], which outlives this
 * activity and is only observed between onStart and onStop.
 */
class SettingsActivity : Activity(), DownloadController.Observer {

    private class Section(val nav: TextView, val pane: View, val entry: () -> View?, val onShown: (Boolean) -> Unit) {
        var lastFocus: View? = null
    }

    private lateinit var prefs: Prefs
    private val io: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "artdream-settings-io").apply { isDaemon = true }
    }
    /** Only ever touched on [io] threads (its constructor creates the cache dir). */
    private val cache by lazy { ArtCache(applicationContext) }
    private val main = Handler(Looper.getMainLooper())

    private lateinit var thumbs: ThumbLoader
    private lateinit var library: LibraryPane
    private lateinit var download: DownloadPane
    private lateinit var content: ContentPane
    private lateinit var diagnostics: DiagnosticsPane
    private lateinit var navCol: LinearLayout
    private lateinit var paneHost: FrameLayout
    private lateinit var navCount: TextView
    private val sections = ArrayList<Section>()
    private var current = -1
    private var dialog: ArtworkDialog? = null
    private var seenFinishedRuns = -1
    private var libraryCount = -1
    private val reloadLibrary = Runnable { loadLibrary() }
    private val updater by lazy { Updater(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        RefreshJobService.schedule(this)
        window.setBackgroundDrawable(ColorDrawable(getColor(R.color.ad_bg)))

        // Tile geometry from the screen: nav is fixed, the grid takes the rest.
        val dm = resources.displayMetrics
        val availPx = dm.widthPixels - dp(NAV_W) - dp(1) - 2 * dp(LibraryPane.PAD)
        val cols = (availPx / dp(170)).coerceIn(3, 6)
        val tileW = (availPx - (cols - 1) * dp(LibraryPane.GAP)) / cols
        val tileH = tileW * 9 / 16
        thumbs = ThumbLoader(tileW, tileH)

        library = LibraryPane(this, thumbs, cols, tileW, tileH, onOpen = ::openArtwork, onDownload = {
            select(SECTION_DOWNLOAD)
            download.action.requestFocus()
            startDownload()
        })
        download = DownloadPane(this, thumbs, tileW, tileH, onStart = ::startDownload, onOpen = ::openArtwork)
        content = ContentPane(this, prefs, onChanged = ::updateSourceWarnings)

        diagnostics = DiagnosticsPane(this)

        val root = FocusRoot(this)
        navCol = buildNav()
        root.addView(navCol, LinearLayout.LayoutParams(dp(NAV_W), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(View(this).apply { setBackgroundColor(getColor(R.color.ad_divider)) },
            LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        paneHost = FrameLayout(this)
        root.addView(paneHost, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        addSection("Library", library.view, library::firstFocusable, library::setShown)
        addSection("Download", download.view, download::firstFocusable, download::setShown)
        addSection("Content", content.view, content::firstFocusable) {}
        val display = buildDisplayPane()
        addSection("Display", display, { firstFocusableIn(display) }) {}
        addSection("Diagnostics", diagnostics.view, diagnostics::firstFocusable) { if (it) loadDiagnostics() }
        val setup = buildSetupPane()
        addSection("Setup", setup, { firstFocusableIn(setup) }) {}

        setContentView(root)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val s = sections.getOrNull(current) ?: return@addOnGlobalFocusChangeListener
            if (now != null && now.isInside(s.pane)) s.lastFocus = now
        }

        updateSourceWarnings()
        select(SECTION_LIBRARY)
        sections[SECTION_LIBRARY].nav.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        DownloadController.observe(this)
        seenFinishedRuns = DownloadController.finishedRuns
        download.render()
        loadLibrary()   // the screensaver may have changed the cache while we were away
        updater.check()   // offers a newer GitHub build, if any
    }

    override fun onStop() {
        DownloadController.unobserve(this)
        main.removeCallbacks(reloadLibrary)
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        thumbs.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // TV convention: Back from inside a pane goes to its nav item first.
        val focus = currentFocus
        if (focus != null && focus.isInside(paneHost) && current >= 0) {
            sections[current].nav.requestFocus()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---- download -------------------------------------------------------

    private fun startDownload() {
        DownloadController.dismissSummary()
        DownloadController.start(this)
    }

    override fun onDownloadState() {
        download.render()
        if (DownloadController.finishedRuns != seenFinishedRuns) {
            seenFinishedRuns = DownloadController.finishedRuns
            loadLibrary()
        } else if (DownloadController.phase == DownloadController.Phase.BACKGROUND && DownloadController.libraryCount >= 0) {
            setLibraryCount(DownloadController.libraryCount)
        }
    }

    override fun onDownloadAdded(entry: ArtCache.Entry) {
        // Batch grid rebuilds while images stream in.
        main.removeCallbacks(reloadLibrary)
        main.postDelayed(reloadLibrary, 1500)
    }

    // ---- library --------------------------------------------------------

    private fun loadLibrary() {
        runIo({ cache.entries() }) { list ->
            library.setEntries(list)
            setLibraryCount(list.size)
        }
    }

    private fun loadDiagnostics() {
        runIo({ ArtSource.ALL.associate { it.id to cache.lastResult(it.id) } to cache.lastDownloadAt() }) { (r, at) ->
            diagnostics.setData(r, at)
        }
    }

    private fun setLibraryCount(n: Int) {
        libraryCount = n
        navCount.text = when (n) { 0 -> "Library is empty"; 1 -> "1 image on this TV"; else -> "$n images on this TV" }
        content.setLibraryCount(n)
    }

    private fun openArtwork(entry: ArtCache.Entry) {
        if (isFinishing || isDestroyed) return
        dialog?.dismiss()
        dialog = ArtworkDialog(this, entry, io, ::removeArtwork).also {
            it.setOnDismissListener { d -> if (dialog === d) dialog = null }
            it.show()
        }
    }

    private fun removeArtwork(entry: ArtCache.Entry) {
        thumbs.forget(entry.image)
        DownloadController.forget(entry)
        runIo({ cache.remove(entry.art) }) {
            Toast.makeText(this, "Removed “${entry.art.title.ifEmpty { "Untitled" }}”", Toast.LENGTH_SHORT).show()
            loadLibrary()
        }
    }

    /** Run [work] on [io], then [done] on the main thread unless this activity is gone by then. */
    private fun <T> runIo(work: () -> T, done: (T) -> Unit) {
        try {
            io.execute {
                val r = try { work() } catch (e: Exception) { null }
                main.post {
                    @Suppress("UNCHECKED_CAST")
                    if (!isDestroyed && r != null) done(r as T)
                }
            }
        } catch (_: RejectedExecutionException) {
            // shutting down
        }
    }

    // ---- content ----------------------------------------------------------

    private fun updateSourceWarnings() {
        download.setNoSources(ArtSource.ALL.none { prefs.isCategoryOn(it.category) && prefs.isSourceOn(it.id) })
    }

    // ---- nav + panes ------------------------------------------------------

    private fun buildNav(): LinearLayout = Ui.column(this).apply {
        setBackgroundColor(getColor(R.color.ad_nav_bg))
        setPadding(dp(28), dp(32), dp(24), dp(24))
        clipChildren = false
        clipToPadding = false
        addView(Ui.text(context, "ArtDream", Ui.TITLE_SP).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        })
        addView(Ui.text(context, "Art & imagery screensaver", Ui.SMALL_SP, R.color.ad_text3).apply {
            setPadding(dp(12), 0, 0, dp(20))
        })
        addView(Ui.button(context, "▶  Test screensaver", primary = true) { testScreensaver() },
            Ui.lp().apply { bottomMargin = dp(20) })
    }

    private fun addSection(label: String, pane: View, entry: () -> View?, onShown: (Boolean) -> Unit) {
        val index = sections.size
        val nav = TextView(this).apply {
            text = label
            textSize = Ui.LABEL_SP
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundResource(R.drawable.ad_nav_bg)
            setTextColor(getColorStateList(R.color.ad_nav_text))
            focusScale(1.03f) { has -> if (has) select(index) }
            setOnClickListener { enterPane() }
        }
        navCol.addView(nav, Ui.lp().apply { topMargin = dp(4) })
        pane.visibility = View.GONE
        paneHost.addView(pane, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sections.add(Section(nav, pane, entry, onShown))

        if (index == SECTION_SETUP) {
            // Library size, pinned to the bottom of the nav.
            navCol.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
            navCount = Ui.text(this, "", Ui.SMALL_SP, R.color.ad_text3).apply { setPadding(dp(12), 0, 0, 0) }
            navCol.addView(navCount)
        }
    }

    private fun select(index: Int) {
        if (index == current) return
        sections.getOrNull(current)?.let {
            it.nav.isActivated = false
            it.pane.visibility = View.GONE
            it.onShown(false)
        }
        current = index
        val s = sections[index]
        s.nav.isActivated = true
        s.pane.visibility = View.VISIBLE
        s.onShown(true)
    }

    /** Where Right/OK from the nav lands: last-focused item in the pane, else its first one. */
    private fun paneEntryFocus(): View? {
        val s = sections.getOrNull(current) ?: return null
        s.lastFocus?.let { v ->
            if (v.isAttachedToWindow && v.isShown && v.isFocusable && v.isEnabled && v.isInside(s.pane)) return v
        }
        return s.entry()
    }

    private fun enterPane() { paneEntryFocus()?.requestFocus() }

    private fun firstFocusableIn(pane: View): View? =
        FocusFinder.getInstance().findNextFocus(pane as ViewGroup, null, View.FOCUS_DOWN)

    /** Root that steers focus between the nav column and the pane (see class doc). */
    private inner class FocusRoot(ctx: Context) : LinearLayout(ctx) {
        init { orientation = HORIZONTAL }

        override fun focusSearch(focused: View?, direction: Int): View? {
            if (focused == null || current < 0) return super.focusSearch(focused, direction)
            val fromNav = focused.isInside(navCol)
            val fromPane = focused.isInside(paneHost)
            if (direction == FOCUS_RIGHT && fromNav) return paneEntryFocus() ?: focused
            val next = super.focusSearch(focused, direction)
            return when {
                fromPane && direction == FOCUS_LEFT ->
                    if (next != null && next.isInside(paneHost)) next else sections[current].nav
                // Up/Down never jump sideways between the nav and the pane.
                fromNav && next != null && !next.isInside(navCol) -> null
                fromPane && next != null && !next.isInside(paneHost) -> null
                else -> next
            }
        }
    }

    private fun View.isInside(ancestor: View): Boolean {
        var v: View? = this
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    // ---- Display / Setup ---------------------------------------------------

    private fun paneColumn(title: String, intro: String?): Pair<ScrollView, LinearLayout> {
        val col = Ui.column(this).apply {
            setPadding(dp(LibraryPane.PAD), dp(36), dp(LibraryPane.PAD), dp(48))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(Ui.header(this, title))
        if (intro != null) col.addView(Ui.body(this, intro))
        col.addView(Ui.spacer(this, 10))
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }
        scroll.addView(col)
        return scroll to col
    }

    private fun buildDisplayPane(): View {
        val (scroll, col) = paneColumn("Display", "Changes apply the next time the screensaver starts.")
        col.addView(SettingRow(this, "Time per image", "Press OK to change").apply {
            setValue(formatInterval(prefs.intervalSec))
            setOnClickListener {
                val all = Prefs.INTERVALS
                prefs.intervalSec = all[(all.indexOf(prefs.intervalSec) + 1) % all.size]
                setValue(formatInterval(prefs.intervalSec))
            }
        })
        col.addView(SettingRow.toggle(this, "Show title & artist", "A small caption over each image",
            prefs.showCaption) { prefs.showCaption = it })
        col.addView(SettingRow.toggle(this, "Landscape images only",
            "Skip portrait images when downloading; they leave most of a widescreen TV empty",
            prefs.landscapeOnly) { prefs.landscapeOnly = it })
        col.addView(SettingRow.toggle(this, "Favourites only",
            "Only show images you marked as favourite (everything if you have none yet)",
            prefs.favouritesOnly) { prefs.favouritesOnly = it })
        col.addView(SettingRow(this, "Night mode", "Auto dims and warms the picture from 21:00 to 07:00").apply {
            fun label() = if (prefs.nightMode == Prefs.NIGHT_AUTO) "Auto" else "Off"
            setValue(label())
            setOnClickListener {
                prefs.nightMode = if (prefs.nightMode == Prefs.NIGHT_AUTO) Prefs.NIGHT_OFF else Prefs.NIGHT_AUTO
                setValue(label())
            }
        })
        col.addView(SettingRow.toggle(this, "Show clock", "A small clock in the top-right corner",
            prefs.showClock) { prefs.showClock = it })
        col.addView(SettingRow(this, "Image order", "By type keeps about 8 images in a row from one category").apply {
            fun label() = if (prefs.grouping == Prefs.GROUP_BY_TYPE) "By type" else "Random"
            setValue(label())
            setOnClickListener {
                prefs.grouping = if (prefs.grouping == Prefs.GROUP_BY_TYPE) Prefs.GROUP_RANDOM else Prefs.GROUP_BY_TYPE
                setValue(label())
            }
        })
        return scroll
    }

    private fun buildSetupPane(): View {
        val (scroll, col) = paneColumn("Setup",
            "To use ArtDream as your screensaver, open the system screensaver settings and choose “ArtDream” " +
                "under Screen saver. You can also set how long the TV waits before it starts there.")
        col.addView(Ui.button(this, "Open screensaver settings") { openDreamSettings() },
            Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8); bottomMargin = dp(20) })
        col.addView(Ui.column(this).apply {
            setBackgroundResource(R.drawable.ad_card_bg)
            setPadding(dp(24), dp(20), dp(24), dp(22))
            addView(Ui.text(context, "Google TV", Ui.LABEL_SP))
            addView(Ui.body(context, "Google TV's menu only offers Ambient mode. Set ArtDream from a computer " +
                "with adb instead:"))
            addView(Ui.text(context, "adb shell settings put secure screensaver_components $packageName/.ArtDream",
                Ui.SMALL_SP, R.color.ad_accent).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(false)
                setPadding(0, dp(8), 0, 0)
            })
        })
        col.addView(Ui.column(this).apply {
            setBackgroundResource(R.drawable.ad_card_bg)
            setPadding(dp(24), dp(20), dp(24), dp(22))
            clipChildren = false
            clipToPadding = false
            addView(Ui.text(context, "Updates", Ui.LABEL_SP))
            addView(Ui.body(context, "Installed build: ${installedBuild()}. New builds come from the project's " +
                "GitHub release; you're asked before anything is installed."))
            addView(SettingRow.toggle(context, "Automatic update checks", "Asks GitHub at most once a day",
                updater.autoCheck) { updater.autoCheck = it })
            addView(Ui.button(context, "Check for updates now") { updater.check(manual = true) },
                Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        }, Ui.lp().apply { topMargin = dp(20) })
        return scroll
    }

    /** "1a2b3c4 · 25 Oct 2026, 14:02" from the commit this APK was built from. */
    private fun installedBuild(): String {
        val date = if (BuildConfig.GIT_TIME <= 0) "unknown date"
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(BuildConfig.GIT_TIME * 1000))
        return "${BuildConfig.GIT_SHA.take(7)} · $date"
    }

    // ---- actions ----------------------------------------------------------------

    private fun testScreensaver() {
        if (libraryCount == 0) {
            Toast.makeText(this, "Your library is empty. Download some images first.", Toast.LENGTH_LONG).show()
            select(SECTION_DOWNLOAD)
            download.action.requestFocus()
            return
        }
        try {
            // PreviewActivity lands in a parallel branch; switch to a class reference once merged.
            startActivity(Intent(this, PreviewActivity::class.java))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Preview isn't available in this version. Use Setup → Open screensaver settings.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun openDreamSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun formatInterval(sec: Int) = if (sec < 60) "$sec s" else "${sec / 60} min"

    companion object {
        private const val NAV_W = 300
        private const val SECTION_LIBRARY = 0
        private const val SECTION_DOWNLOAD = 1
        private const val SECTION_SETUP = 5
    }
}
