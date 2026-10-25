package com.phrag.artdream.ui

import android.content.Context
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import com.phrag.artdream.Prefs
import com.phrag.artdream.source.ArtSource
import com.phrag.artdream.source.Category

/**
 * One toggle per [Category] (iterated generically so new categories appear
 * automatically) with that category's sources as indented secondary toggles.
 * Any combination that would leave the downloader with nothing to do is
 * called out in an amber hint instead of being silently accepted.
 */
class ContentPane(
    private val ctx: Context,
    private val prefs: Prefs,
    private val onChanged: () -> Unit,
) {
    val view = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }

    private class Group(
        val category: Category,
        val sources: List<ArtSource>,
        val row: SettingRow,
        val sourceRows: List<SettingRow>,
        val hint: TextView,
    )

    private val groups = ArrayList<Group>()
    private val globalWarning = Ui.warning(ctx)
    private var libraryCount = -1
    private var firstRow: View? = null

    init {
        val col = Ui.column(ctx).apply {
            setPadding(ctx.dp(LibraryPane.PAD), ctx.dp(36), ctx.dp(LibraryPane.PAD), ctx.dp(48))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(Ui.header(ctx, "Content"))
        col.addView(Ui.body(ctx, "Choose which kinds of images to show. The screensaver only shows images " +
            "from the types switched on here, and new downloads only use these types and sources."))
        col.addView(Ui.spacer(ctx, 8))
        col.addView(globalWarning, Ui.lp().apply { topMargin = ctx.dp(8) })

        for (cat in Category.values()) {
            val sources = ArtSource.ALL.filter { it.category == cat }
            col.addView(Ui.spacer(ctx, 14))
            val catRow = SettingRow(ctx, cat.label, categorySub(sources))
            col.addView(catRow)
            if (firstRow == null) firstRow = catRow
            val srcRows = sources.map { src ->
                SettingRow.toggle(ctx, src.label, null, prefs.isSourceOn(src.id), indent = true) { on ->
                    prefs.setSourceOn(src.id, on)
                    refresh()
                    onChanged()
                }.also { col.addView(it) }
            }
            val hint = Ui.warning(ctx)
            col.addView(hint, Ui.lp().apply { topMargin = ctx.dp(8); marginStart = ctx.dp(24) })
            val g = Group(cat, sources, catRow, srcRows, hint)
            groups.add(g)
            catRow.setOnOff(prefs.isCategoryOn(cat))
            catRow.setOnClickListener {
                prefs.setCategoryOn(cat, !prefs.isCategoryOn(cat))
                refresh()
                onChanged()
            }
        }
        view.addView(col)
        refresh()
    }

    fun firstFocusable(): View? = firstRow

    /** Library size feeds the "nothing to show at all" wording. */
    fun setLibraryCount(n: Int) {
        if (n == libraryCount) return
        libraryCount = n
        refresh()
    }

    private fun categorySub(sources: List<ArtSource>) = when (sources.size) {
        0 -> "No sources for this type yet"
        1 -> "From ${sources[0].label}"
        else -> "From ${sources.size} sources"
    }

    private fun refresh() {
        var activeSources = 0
        for (g in groups) {
            val catOn = prefs.isCategoryOn(g.category)
            g.row.setOnOff(catOn)
            g.sourceRows.forEach { it.isEnabled = catOn }
            val onCount = g.sources.count { prefs.isSourceOn(it.id) }
            if (catOn) activeSources += onCount
            g.hint.text = when {
                !catOn -> ""
                g.sources.isEmpty() -> "There are no sources for ${g.category.label} yet, so this type adds nothing for now."
                onCount == 0 -> "All ${g.category.label} sources are off, so no ${g.category.label.lowercase()} " +
                    "will be downloaded. Turn on at least one source above."
                else -> ""
            }
            g.hint.visibility = if (g.hint.text.isEmpty()) View.GONE else View.VISIBLE
        }
        globalWarning.text = when {
            activeSources > 0 -> ""
            libraryCount == 0 -> "Nothing to show: no content type has an active source and your library is " +
                "empty, so the screensaver will have no images. Turn on a type and at least one of its sources."
            else -> "No new images will be downloaded: no content type has an active source. The screensaver " +
                "will keep showing what's already in your library."
        }
        globalWarning.visibility = if (globalWarning.text.isEmpty()) View.GONE else View.VISIBLE
    }
}
