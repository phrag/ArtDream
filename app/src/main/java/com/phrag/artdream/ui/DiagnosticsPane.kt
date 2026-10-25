package com.phrag.artdream.ui

import android.content.Context
import android.view.View
import android.widget.ScrollView
import com.phrag.artdream.Backoff
import com.phrag.artdream.SourceResult
import com.phrag.artdream.source.ArtSource

/**
 * Read-only status list: one focusable row per source with the outcome of its
 * last refresh, plus "Last downloaded X ago". Rows are focusable so the D-pad
 * can scroll the list. Feed it with [setData] (load the data off the main thread).
 */
class DiagnosticsPane(private val ctx: Context) {
    val view = ScrollView(ctx).apply { isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false }

    private val lastDownload = Ui.body(ctx, "")
    private val rows = ArtSource.ALL.associate { it.id to SettingRow(ctx, it.label, "Not run yet") }
    private var firstRow: View? = null

    init {
        val col = Ui.column(ctx).apply {
            setPadding(ctx.dp(LibraryPane.PAD), ctx.dp(36), ctx.dp(LibraryPane.PAD), ctx.dp(48))
            clipChildren = false
            clipToPadding = false
        }
        col.addView(Ui.header(ctx, "Diagnostics"))
        col.addView(Ui.body(ctx, "The result of each source's most recent download attempt."))
        col.addView(lastDownload)
        col.addView(Ui.spacer(ctx, 10))
        for (src in ArtSource.ALL) {
            val row = rows.getValue(src.id)
            row.setValue("Never")
            col.addView(row)
            if (firstRow == null) firstRow = row
        }
        view.addView(col)
        setData(emptyMap(), 0L)
    }

    fun firstFocusable(): View? = firstRow

    fun setData(results: Map<String, SourceResult?>, lastDownloadAt: Long) {
        lastDownload.text = if (lastDownloadAt > 0) "Last downloaded ${ago(lastDownloadAt)}" else "Nothing downloaded yet"
        for (src in ArtSource.ALL) {
            val row = rows.getValue(src.id)
            val r = results[src.id]
            if (r == null) {
                row.setValue("Never")
                row.setSub("Not run yet")
            } else if (r.ok) {
                row.setValue("OK")
                row.setSub("${ago(r.at)} · ${if (r.added == 1) "1 image added" else "${r.added} images added"}")
            } else if (r.skipUntil > System.currentTimeMillis()) {
                // Backed off after HTTP 429/503: say when it will be asked again.
                row.setValue("Waiting")
                row.setSub("${ago(r.at)} · Rate limited, retrying in ${Backoff.formatWait(r.skipUntil - System.currentTimeMillis())}")
            } else {
                row.setValue("Failed")
                row.setSub("${ago(r.at)} · ${r.error ?: "Unknown error"}")
            }
        }
    }

    private fun ago(t: Long): String {
        val s = (System.currentTimeMillis() - t).coerceAtLeast(0) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }
}
