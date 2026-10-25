package com.example.artdream

import android.content.Context
import com.example.artdream.source.Category

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("artdream", Context.MODE_PRIVATE)

    /** Seconds each painting stays on screen. */
    var intervalSec: Int
        get() = sp.getInt("interval_sec", 60)
        set(v) = sp.edit().putInt("interval_sec", v).apply()

    /** Skip portrait paintings; they leave most of a 16:9 screen as backdrop. */
    var landscapeOnly: Boolean
        get() = sp.getBoolean("landscape_only", true)
        set(v) = sp.edit().putBoolean("landscape_only", v).apply()

    var showCaption: Boolean
        get() = sp.getBoolean("show_caption", true)
        set(v) = sp.edit().putBoolean("show_caption", v).apply()

    /** Paintings on by default; other categories are opt-in from the picker. */
    fun isCategoryOn(c: Category): Boolean = sp.getBoolean("cat_${c.name}", c == Category.PAINTINGS)
    fun setCategoryOn(c: Category, on: Boolean) = sp.edit().putBoolean("cat_${c.name}", on).apply()

    fun isSourceOn(id: String): Boolean = sp.getBoolean("src_$id", true)
    fun setSourceOn(id: String, on: Boolean) = sp.edit().putBoolean("src_$id", on).apply()

    companion object {
        val INTERVALS = intArrayOf(30, 60, 120, 300, 600)
    }
}
