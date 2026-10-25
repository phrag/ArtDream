package com.example.artdream.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.artdream.ArtCache
import com.example.artdream.R

/**
 * D-pad grid of everything in the on-disk cache. Only rows near the viewport
 * hold a bitmap reference; scrolled-away rows are unbound so memory stays at
 * "what fits in the LRU", not "everything in the library".
 */
class LibraryPane(
    private val ctx: Context,
    private val thumbs: ThumbLoader,
    private val columns: Int,
    private val tileW: Int,
    private val tileH: Int,
    private val onOpen: (ArtCache.Entry) -> Unit,
    private val onDownload: () -> Unit,
) {
    val view = ScrollView(ctx).apply {
        isFillViewport = true
        clipToPadding = false
        isVerticalScrollBarEnabled = false
    }

    private val content = Ui.column(ctx).apply {
        setPadding(ctx.dp(PAD), ctx.dp(36), ctx.dp(PAD), ctx.dp(48))
        clipChildren = false
        clipToPadding = false
    }
    private val countView: TextView
    private val infoTitle: TextView
    private val infoSub: TextView
    private val info: LinearLayout
    private val grid = Ui.column(ctx).apply { clipChildren = false; clipToPadding = false }
    private val empty: LinearLayout
    private val loading = Ui.body(ctx, "Loading your library…")
    private val tiles = ArrayList<FrameLayout>()
    private var keys: List<String>? = null
    private var shown = false

    init {
        view.addView(content)
        val head = Ui.row(ctx)
        head.addView(Ui.header(ctx, "Library"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        countView = Ui.text(ctx, "", Ui.BODY_SP, R.color.ad_text2)
        head.addView(countView)
        content.addView(head)

        // Caption for whichever tile has focus: titles are unreadable inside a tile at 10 ft.
        info = Ui.column(ctx).apply { minimumHeight = ctx.dp(64); setPadding(0, ctx.dp(4), 0, ctx.dp(14)) }
        infoTitle = Ui.text(ctx, "", Ui.LABEL_SP).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        infoSub = Ui.text(ctx, "", Ui.SMALL_SP, R.color.ad_text2).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(infoTitle)
        info.addView(infoSub)
        content.addView(info)
        content.addView(loading)
        content.addView(grid)

        empty = Ui.column(ctx).apply {
            visibility = View.GONE
            setBackgroundResource(R.drawable.ad_card_bg)
            setPadding(ctx.dp(28), ctx.dp(24), ctx.dp(28), ctx.dp(28))
            addView(Ui.text(ctx, "Your library is empty", Ui.LABEL_SP))
            addView(Ui.body(ctx, "The screensaver shows images stored on this device. Download some now — it " +
                "takes a minute or so — or just wait: the screensaver fetches new ones each time it starts."))
            addView(Ui.spacer(ctx, 12))
            addView(Ui.button(ctx, "Download images now", primary = true) { onDownload() },
                Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        content.addView(empty)
        showInfo(null)

        view.viewTreeObserver.addOnScrollChangedListener(ViewTreeObserver.OnScrollChangedListener { bindVisible() })
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bindVisible() }
    }

    fun setShown(on: Boolean) {
        shown = on
        if (on) view.post { bindVisible() } else tiles.forEach { unbindTile(it) }
    }

    /** Replace the grid contents, keeping focus on the same image (or its neighbour) if it had it. */
    fun setEntries(entries: List<ArtCache.Entry>) {
        loading.visibility = View.GONE
        countView.text = when (entries.size) { 1 -> "1 image"; else -> "${entries.size} images" }
        val newKeys = entries.map { it.art.key }
        if (newKeys == keys) return
        keys = newKeys

        val focused = grid.findFocus()
        val focusedIndex = focused?.let { tiles.indexOf(it) } ?: -1
        val focusedKey = (focused?.getTag(R.id.ad_entry) as? ArtCache.Entry)?.art?.key

        // Move focus somewhere stable before the focused tile is detached, or the
        // framework would hand it to the first focusable in the window.
        if (focused != null) {
            view.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            view.requestFocus()
        }
        tiles.forEach { unbindTile(it) }
        tiles.clear()
        grid.removeAllViews()

        var row: LinearLayout? = null
        entries.forEachIndexed { i, e ->
            if (i % columns == 0) {
                row = Ui.row(ctx).apply { clipChildren = false; clipToPadding = false }
                grid.addView(row, Ui.lp().apply { if (i > 0) topMargin = ctx.dp(GAP) })
            }
            val tile = tile(e)
            tiles.add(tile)
            row!!.addView(tile, LinearLayout.LayoutParams(tileW, tileH).apply { if (i % columns != 0) marginStart = ctx.dp(GAP) })
        }
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        info.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE

        if (focused != null) {
            view.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            val target = tiles.firstOrNull { (it.getTag(R.id.ad_entry) as ArtCache.Entry).art.key == focusedKey }
                ?: tiles.getOrNull(focusedIndex.coerceIn(0, maxOf(0, tiles.size - 1)))
            (target ?: firstFocusable())?.requestFocus()
        }
        if (shown) view.post { bindVisible() }
    }

    /** Where focus should land when entering this pane from the side nav. */
    fun firstFocusable(): View? = tiles.firstOrNull() ?: empty.takeIf { it.visibility == View.VISIBLE }?.getChildAt(3)

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
        focusScale(1.08f) { has -> if (has) showInfo(e) }
    }

    private fun showInfo(e: ArtCache.Entry?) {
        if (e == null) {
            infoTitle.text = "Select an image to see it full screen"
            infoSub.text = ""
            return
        }
        infoTitle.text = e.art.title.ifEmpty { "Untitled" }
        infoSub.text = listOf(e.art.artist, e.art.date).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun bindVisible() {
        if (!shown || view.height == 0) return
        val top = view.scrollY - view.height / 2
        val bottom = view.scrollY + view.height * 3 / 2
        for (i in 0 until grid.childCount) {
            val row = grid.getChildAt(i) as ViewGroup
            val y = content.top + grid.top + row.top
            val near = y + row.height >= top && y <= bottom
            for (j in 0 until row.childCount) {
                val tile = row.getChildAt(j) as FrameLayout
                if (near) {
                    val e = tile.getTag(R.id.ad_entry) as ArtCache.Entry
                    thumbs.load(e.image, tile.getChildAt(0) as ImageView)
                } else {
                    unbindTile(tile)
                }
            }
        }
    }

    private fun unbindTile(tile: FrameLayout) = thumbs.unbind(tile.getChildAt(0) as ImageView)

    companion object {
        const val PAD = 40
        const val GAP = 18
    }
}
