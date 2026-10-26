package com.phrag.artdream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.phrag.artdream.QrCode

/**
 * Draws a [QrCode] as crisp squares, dark on white with the standard four-module quiet zone, so
 * phone cameras read it straight off the dark settings screen. Gone while there is no code.
 */
class QrView(ctx: Context) : View(ctx) {

    private val paint = Paint().apply { isAntiAlias = false }
    private var code: QrCode? = null

    init { visibility = GONE }

    fun setText(text: String?) {
        code = text?.let { runCatching { QrCode.encode(it) }.getOrNull() }
        visibility = if (code == null) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val q = code ?: return
        val modules = q.size + 2 * QUIET
        // Whole pixels per module: fractional ones blur edges, which some decoders dislike.
        val unit = minOf(width, height) / modules
        if (unit <= 0) return
        val side = unit * modules
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        paint.color = Color.WHITE
        canvas.drawRect(left, top, left + side, top + side, paint)
        paint.color = Color.BLACK
        for (y in 0 until q.size) for (x in 0 until q.size) {
            if (!q[x, y]) continue
            val l = left + (x + QUIET) * unit
            val t = top + (y + QUIET) * unit
            canvas.drawRect(l, t, l + unit, t + unit, paint)
        }
    }

    private companion object {
        const val QUIET = 4
    }
}
