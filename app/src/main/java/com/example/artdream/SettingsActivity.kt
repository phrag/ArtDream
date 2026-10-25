package com.example.artdream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.artdream.source.ArtSource
import java.util.concurrent.Executors

/**
 * Launcher entry + the screensaver's settings gear. Plain Views on purpose:
 * D-pad focus traversal works out of the box.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var cache: ArtCache
    private lateinit var cacheInfo: TextView
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        cache = ArtCache(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }

        col.addView(header("ArtDream"))
        col.addView(note("Public-domain paintings from museum open-access collections."))

        col.addView(sectionLabel("Display"))
        col.addView(Button(this).apply {
            fun label() = "Time per painting: ${formatInterval(prefs.intervalSec)}"
            text = label()
            setOnClickListener {
                val all = Prefs.INTERVALS
                prefs.intervalSec = all[(all.indexOf(prefs.intervalSec) + 1) % all.size]
                text = label()
            }
        })
        col.addView(switchRow("Show title & artist", prefs.showCaption) { prefs.showCaption = it })
        col.addView(switchRow("Landscape paintings only", prefs.landscapeOnly) { prefs.landscapeOnly = it })

        col.addView(sectionLabel("Collections"))
        for (src in ArtSource.ALL) {
            col.addView(switchRow(src.label, prefs.isSourceOn(src.id)) { prefs.setSourceOn(src.id, it) })
        }

        col.addView(sectionLabel("Library"))
        cacheInfo = note("")
        col.addView(cacheInfo)
        col.addView(Button(this).apply {
            text = "Download more paintings now"
            setOnClickListener {
                isEnabled = false
                cacheInfo.text = "Downloading…"
                io.execute {
                    val added = cache.refresh()
                    runOnUiThread {
                        isEnabled = true
                        updateCacheInfo(if (added <= 0) "No new paintings (offline or all sources off?)" else "Added $added.")
                    }
                }
            }
        })
        col.addView(Button(this).apply {
            text = "Open screensaver settings"
            setOnClickListener { openDreamSettings() }
        })
        col.addView(note(
            "Pick “ArtDream” under Screen saver. On Google TV, where the menu only offers " +
                "Ambient mode, set it from a computer:\n" +
                "adb shell settings put secure screensaver_components $packageName/.ArtDream"
        ))

        setContentView(ScrollView(this).apply { addView(col) })
        updateCacheInfo(null)
        col.getChildAt(3).requestFocus()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun updateCacheInfo(extra: String?) {
        cacheInfo.text = "${cache.count()} paintings stored on this device." + (extra?.let { " $it" } ?: "")
    }

    private fun openDreamSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun formatInterval(sec: Int) = if (sec < 60) "$sec s" else "${sec / 60} min"

    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            textSize = 18f
            isChecked = initial
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 32f
        setTextColor(Color.WHITE)
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.LTGRAY)
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.GRAY)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
