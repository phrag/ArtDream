package com.phrag.artdream.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.phrag.artdream.ArtCache
import com.phrag.artdream.Prefs
import com.phrag.artdream.R
import com.phrag.artdream.source.ArtSource
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Full-screen view of one cached image with its caption and a
 * "Remove from library" action (press twice to confirm — easy to hit OK by
 * accident on a remote). The image is decoded at screen size off the main
 * thread and released when the dialog closes.
 */
class ArtworkDialog(
    private val activity: Activity,
    private val entry: ArtCache.Entry,
    private val io: ExecutorService,
    private val onRemove: (ArtCache.Entry) -> Unit,
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private lateinit var image: ImageView
    private lateinit var spinner: ProgressBar
    private var bitmap: Bitmap? = null
    private var armed = false
    private val prefs = Prefs(activity.applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context
        val root = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }

        image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = entry.art.title
        }
        root.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        spinner = ProgressBar(ctx)
        root.addView(spinner, FrameLayout.LayoutParams(ctx.dp(56), ctx.dp(56), Gravity.CENTER))

        val bar = Ui.row(ctx).apply {
            setBackgroundResource(R.drawable.ad_scrim_bottom)
            gravity = Gravity.BOTTOM
            setPadding(ctx.dp(56), ctx.dp(96), ctx.dp(56), ctx.dp(36))
            clipChildren = false
            clipToPadding = false
        }
        val texts = Ui.column(ctx)
        val art = entry.art
        texts.addView(Ui.text(ctx, art.title.ifEmpty { "Untitled" }, Ui.TITLE_SP).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        })
        val byline = listOf(art.artist, art.date).filter { it.isNotBlank() }.joinToString(" · ")
        if (byline.isNotEmpty()) texts.addView(Ui.text(ctx, byline, Ui.LABEL_SP, R.color.ad_text2).apply { maxLines = 2 })
        val sourceLabel = ArtSource.ALL.firstOrNull { it.id == art.source }?.label ?: art.source
        val author = art.credit.takeIf { it.isNotBlank() && it != art.artist }.orEmpty()
        val creditLine = listOf(author, art.license, sourceLabel).filter { it.isNotBlank() }.joinToString(" · ")
        if (creditLine.isNotBlank()) {
            texts.addView(Ui.text(ctx, creditLine, Ui.SMALL_SP, R.color.ad_text3).apply {
                setPadding(0, ctx.dp(6), 0, 0)
            })
        }
        bar.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val close = Ui.button(ctx, "Close") { dismiss() }
        val remove = Ui.button(ctx, "Remove from library") { v -> onRemoveClicked(v as TextView) }
        remove.focusScale(1.06f) { has -> if (!has) disarm(remove) }
        fun favLabel() = if (prefs.isFavourite(art.key)) "Unfavourite" else "Favourite"
        val fav = Ui.button(ctx, favLabel()) { v -> prefs.toggleFavourite(art.key); (v as TextView).text = favLabel() }
        val never = Ui.button(ctx, "Never show again") {
            prefs.block(art.key)   // also drops it from favourites
            dismiss()
            onRemove(entry)
        }
        bar.addView(fav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dp(24)
        })
        bar.addView(never, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dp(16)
        })
        bar.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dp(16)
        })
        bar.addView(remove, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = ctx.dp(16)
        })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        close.requestFocus()
        loadImage()
    }

    private fun loadImage() {
        val dm = activity.resources.displayMetrics
        val w = dm.widthPixels.coerceAtMost(1920)
        val h = dm.heightPixels.coerceAtMost(1080)
        val file = entry.image
        try {
            io.execute {
                val bmp = runCatching { ThumbLoader.decode(file, w, h, cover = false, Bitmap.Config.ARGB_8888) }.getOrNull()
                activity.runOnUiThread {
                    if (!isShowing || activity.isDestroyed) { bmp?.recycle(); return@runOnUiThread }
                    spinner.visibility = View.GONE
                    if (bmp == null) {
                        image.setImageDrawable(null)
                        image.contentDescription = "Image unavailable"
                    } else {
                        bitmap = bmp
                        image.setImageBitmap(bmp)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // Activity is shutting down.
        }
    }

    private fun onRemoveClicked(button: TextView) {
        if (!armed) {
            armed = true
            button.text = "Press OK again to remove"
            return
        }
        dismiss()
        onRemove(entry)
    }

    private fun disarm(button: TextView) {
        armed = false
        button.text = "Remove from library"
    }

    override fun onStop() {
        super.onStop()
        image.setImageDrawable(null)
        bitmap?.recycle()   // only this dialog ever referenced it
        bitmap = null
    }
}
