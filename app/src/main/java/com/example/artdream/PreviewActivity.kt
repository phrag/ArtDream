package com.example.artdream

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Runs the same [Slideshow] as the screensaver in an ordinary full-screen
 * activity, since apps can't reliably start a DreamService themselves.
 * Like a real dream, almost any key exits; D-pad right skips to the next image.
 */
class PreviewActivity : Activity() {

    private lateinit var slideshow: Slideshow
    private lateinit var hint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))

        slideshow = Slideshow(this)
        hint = TextView(this).apply {
            text = "Preview · → next image · any other button exits"
            setTextColor(Color.argb(220, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            val pad = (32 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(slideshow.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(hint, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        })
        // Show the hint briefly, then fade it out.
        hint.animate().alpha(0f).setStartDelay(HINT_MS).setDuration(800)
    }

    override fun onStart() {
        super.onStart()
        slideshow.start()
    }

    override fun onStop() {
        slideshow.stop()
        super.onStop()
    }

    override fun onDestroy() {
        hint.animate().cancel()
        slideshow.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()   // bars can reappear after dialogs/focus loss
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> slideshow.next()
            else -> finish()   // back, center, anything else: like a real screensaver
        }
        return true
    }

    private companion object {
        const val HINT_MS = 3_000L
    }
}
