package com.example.artdream.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.example.artdream.ArtCache
import com.example.artdream.Prefs
import com.example.artdream.R
import com.example.artdream.ui.DownloadController.Phase

/**
 * "Download now" panel: one action button whose meaning follows the state
 * (Download → Cancel → Download more), a determinate progress bar, the live
 * status line from [ArtCache.Listener.onStatus], a strip of the images that
 * just arrived, and a plain-language summary at the end.
 *
 * State lives in [DownloadController]; this view only renders it, so it can be
 * rebuilt at any time (e.g. after the activity is recreated).
 */
class DownloadPane(
    private val ctx: Context,
    private val thumbs: ThumbLoader,
    private val stripTileW: Int,
    private val stripTileH: Int,
    private val onStart: () -> Unit,
    private val onOpen: (ArtCache.Entry) -> Unit,
) {
    val view = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }

    private val noSourcesWarning = Ui.warning(ctx)
    private val title = Ui.text(ctx, "", Ui.LABEL_SP + 2)
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        progressTintList = ColorStateList.valueOf(ctx.getColor(R.color.ad_accent))
        progressBackgroundTintList = ColorStateList.valueOf(ctx.getColor(R.color.ad_surface2))
        indeterminateTintList = ColorStateList.valueOf(ctx.getColor(R.color.ad_accent))
    }
    private val statusLine = Ui.text(ctx, "", Ui.BODY_SP, R.color.ad_text2).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val summary = Ui.text(ctx, "", Ui.BODY_SP)
    private val failuresView = Ui.text(ctx, "", Ui.BODY_SP, R.color.ad_error)
    val action: TextView = Ui.button(ctx, "Download images now", primary = true) { onAction() }
    private val stripLabel = Ui.groupLabel(ctx, "Just downloaded")
    private val strip = Ui.row(ctx).apply { clipChildren = false; clipToPadding = false; setPadding(ctx.dp(8), ctx.dp(10), ctx.dp(8), ctx.dp(10)) }
    private val stripScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        clipChildren = false
        addView(strip)
    }
    private val prefs = Prefs(ctx)
    private val intro = Ui.body(ctx, "")
    private val perRunRow = SettingRow(ctx, "Images per download").apply {
        setOnClickListener {
            prefs.perRun = cycle(Prefs.PER_RUN, prefs.perRun)
            // Keep "fetch N" and "keep at most M" consistent: never keep fewer than one run.
            if (prefs.maxStored < prefs.perRun) prefs.maxStored = Prefs.MAX_STORED.first { it >= prefs.perRun }
            refreshSettings()
        }
    }
    private val maxRow = SettingRow(ctx, "Library size").apply {
        setOnClickListener {
            prefs.maxStored = cycle(Prefs.MAX_STORED, prefs.maxStored)
            if (prefs.perRun > prefs.maxStored) prefs.perRun = Prefs.PER_RUN.last { it <= prefs.maxStored }
            refreshSettings()
        }
    }
    private var shown = false
    private var stripKeys: List<String> = emptyList()

    init {
        val col = Ui.column(ctx).apply {
            setPadding(ctx.dp(LibraryPane.PAD), ctx.dp(36), ctx.dp(LibraryPane.PAD), ctx.dp(48))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(Ui.header(ctx, "Download"))
        col.addView(intro)
        col.addView(perRunRow)
        col.addView(maxRow)
        col.addView(Ui.spacer(ctx, 12))
        col.addView(noSourcesWarning, Ui.lp().apply { bottomMargin = ctx.dp(12) })

        val card = Ui.column(ctx).apply {
            setBackgroundResource(R.drawable.ad_card_bg)
            setPadding(ctx.dp(28), ctx.dp(22), ctx.dp(28), ctx.dp(24))
            clipChildren = false
            clipToPadding = false
        }
        card.addView(title)
        card.addView(progress, Ui.lp(h = ctx.dp(10)).apply { topMargin = ctx.dp(14); bottomMargin = ctx.dp(10) })
        card.addView(statusLine)
        card.addView(summary, Ui.lp().apply { topMargin = ctx.dp(6) })
        card.addView(failuresView, Ui.lp().apply { topMargin = ctx.dp(6) })
        card.addView(action, Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(18) })
        col.addView(card)
        col.addView(stripLabel)
        col.addView(stripScroll, Ui.lp())
        view.addView(col)
        refreshSettings()
        render()
    }

    /** Next option above [current], wrapping to the first. */
    private fun cycle(options: IntArray, current: Int) = options.firstOrNull { it > current } ?: options.first()

    private fun refreshSettings() {
        intro.text = "Fetch new images now from the sources switched on under Content. The screensaver " +
            "also tops up on its own whenever it starts, using the same settings."
        perRunRow.setSub("How many new images each download adds (the first download fills a small library to ${ArtCache.TARGET})")
        perRunRow.setValue("${prefs.perRun}")
        maxRow.setSub("The oldest images are cleared out above this. Roughly ${approxSize(prefs.maxStored)} of storage.")
        maxRow.setValue("${prefs.maxStored}")
    }

    /** ~3 MB per image after downscaling to 4K. */
    private fun approxSize(count: Int): String {
        val mb = count * 3
        return if (mb >= 1000) "%.1f GB".format(mb / 1000f) else "$mb MB"
    }

    fun setShown(on: Boolean) {
        shown = on
        bindStrip()
    }

    fun setNoSources(none: Boolean) {
        noSourcesWarning.text = "No image source is switched on, so there is nothing to download. " +
            "Turn on at least one content type and source under Content."
        noSourcesWarning.visibility = if (none) View.VISIBLE else View.GONE
    }

    fun firstFocusable(): View = action

    private fun onAction() {
        when (DownloadController.phase) {
            Phase.IDLE, Phase.DONE -> onStart()
            Phase.RUNNING -> DownloadController.cancel()
            Phase.CANCELLING, Phase.BACKGROUND -> Unit   // nothing to do; label explains
        }
    }

    /** Re-read everything from the controller. Cheap; called on every state change. */
    fun render() {
        val c = DownloadController
        val busy = c.isBusy
        action.alpha = if (c.phase == Phase.CANCELLING || c.phase == Phase.BACKGROUND) 0.6f else 1f
        action.text = when (c.phase) {
            Phase.IDLE -> "Download images now"
            Phase.RUNNING -> "Cancel"
            Phase.CANCELLING -> "Stopping…"
            Phase.BACKGROUND -> "Please wait…"
            Phase.DONE -> "Download more"
        }

        progress.visibility = if (busy) View.VISIBLE else View.GONE
        when (c.phase) {
            Phase.RUNNING, Phase.CANCELLING -> {
                val known = c.wanted > 0
                progress.isIndeterminate = !known
                if (known) { progress.max = c.wanted; progress.progress = c.added }
            }
            Phase.BACKGROUND -> progress.isIndeterminate = true
            else -> Unit
        }

        title.text = when (c.phase) {
            Phase.IDLE -> "Ready to download"
            Phase.RUNNING -> if (c.wanted > 0) "Downloading — ${c.added} of ${c.wanted} images" else "Downloading…"
            Phase.CANCELLING -> "Cancelling — ${c.added} saved so far"
            Phase.BACKGROUND -> "The screensaver is downloading in the background"
            Phase.DONE -> when {
                c.noSources -> "Nothing to download"
                c.error != null -> "Download stopped"
                c.cancelled -> "Download cancelled"
                c.added > 0 -> "Download finished"
                else -> "No new images"
            }
        }

        statusLine.text = when (c.phase) {
            Phase.RUNNING, Phase.CANCELLING -> c.status
            Phase.BACKGROUND -> if (c.libraryCount >= 0) "Library: ${c.libraryCount} images. This page updates when it's done." else ""
            else -> ""
        }
        statusLine.visibility = if (statusLine.text.isEmpty()) View.GONE else View.VISIBLE

        summary.text = if (c.phase == Phase.DONE) summaryText() else if (c.phase == Phase.IDLE)
            "Downloads use your TV's internet connection and usually take a minute or two." else ""
        summary.visibility = if (summary.text.isEmpty()) View.GONE else View.VISIBLE

        val fails = c.failures.values
        failuresView.text = if (fails.isEmpty() || c.phase == Phase.IDLE) "" else
            "Couldn't get images from: " + fails.joinToString("; ") { "${it.sourceLabel} (${it.reason})" }
        failuresView.visibility = if (failuresView.text.isEmpty()) View.GONE else View.VISIBLE

        bindStrip()
    }

    private fun summaryText(): String {
        val c = DownloadController
        val lib = if (c.libraryCount >= 0) " Your library now has ${c.libraryCount}." else ""
        val n = plural(c.added)
        return when {
            c.noSources -> "All content types or sources are switched off. Turn some on under Content."
            c.error != null -> "Something went wrong (${c.error}). $n were saved before it stopped."
            c.cancelled -> "Stopped early. $n were saved.$lib"
            c.fromBackground -> "The screensaver's download finished: ${if (c.added > 0) "$n added." else "no change in size."}$lib"
            c.added > 0 -> "Added $n.$lib"
            c.failures.isNotEmpty() && c.failures.size >= c.sourceCount ->
                "Every source failed, so nothing was downloaded. Check the TV's internet connection and try again."
            else -> "The sources only offered images you already have, or ones skipped by your filters " +
                "(such as Landscape images only). Try again later.$lib"
        }
    }

    private fun plural(n: Int) = if (n == 1) "1 new image" else "$n new images"

    private fun bindStrip() {
        val entries = DownloadController.recent
        val show = entries.isNotEmpty()
        stripLabel.visibility = if (show) View.VISIBLE else View.GONE
        stripScroll.visibility = if (show) View.VISIBLE else View.GONE
        val keys = entries.map { it.art.key }
        if (keys != stripKeys) {
            val fresh = keys.size - stripKeys.size
            if (fresh > 0 && keys.subList(fresh, keys.size) == stripKeys) {
                // Common case: new arrivals at the front. Prepend so a focused tile keeps focus.
                for (i in fresh - 1 downTo 0) strip.addView(tile(entries[i]), 0, stripLp(true))
                for (j in 1 until minOf(fresh + 1, strip.childCount)) strip.getChildAt(j).layoutParams = stripLp(false)
            } else {
                val focusedIndex = (0 until strip.childCount).firstOrNull { strip.getChildAt(it).hasFocus() } ?: -1
                if (focusedIndex >= 0) action.requestFocus()   // park focus in this pane while tiles are swapped
                for (i in 0 until strip.childCount) thumbs.unbind((strip.getChildAt(i) as FrameLayout).getChildAt(0) as ImageView)
                strip.removeAllViews()
                entries.forEachIndexed { i, e -> strip.addView(tile(e), stripLp(i == 0)) }
                if (focusedIndex >= 0) strip.getChildAt(focusedIndex.coerceAtMost(strip.childCount - 1))?.requestFocus()
            }
            stripKeys = keys
        }
        for (i in 0 until strip.childCount) {
            val tile = strip.getChildAt(i) as FrameLayout
            val img = tile.getChildAt(0) as ImageView
            if (shown) thumbs.load((tile.getTag(R.id.ad_entry) as ArtCache.Entry).image, img) else thumbs.unbind(img)
        }
    }

    private fun stripLp(first: Boolean) = LinearLayout.LayoutParams(stripTileW, stripTileH).apply {
        if (!first) marginStart = ctx.dp(14)
    }

    private fun tile(e: ArtCache.Entry) = FrameLayout(ctx).apply {
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.ad_tile_bg)
        foreground = ctx.getDrawable(R.drawable.ad_tile_fg)
        clipToOutline = true
        contentDescription = e.art.title
        setTag(R.id.ad_entry, e)
        addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setOnClickListener { onOpen(e) }
        focusScale(1.08f)
    }
}
