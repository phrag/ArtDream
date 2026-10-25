package com.example.artdream

import android.service.dreams.DreamService

/**
 * The screensaver. A thin DreamService wrapper around [Slideshow], which does
 * all the work (and is also what [PreviewActivity] runs).
 */
class ArtDream : DreamService() {

    private var slideshow: Slideshow? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        slideshow = Slideshow(this).also { setContentView(it.view) }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        slideshow?.start()
    }

    override fun onDreamingStopped() {
        slideshow?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        slideshow?.release()
        slideshow = null
        super.onDetachedFromWindow()
    }
}
