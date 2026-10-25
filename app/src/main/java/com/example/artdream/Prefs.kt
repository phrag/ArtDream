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

    /** New images fetched per download run (manual button or screensaver start). */
    var perRun: Int
        get() = sp.getInt("per_run", 20)
        set(v) = sp.edit().putInt("per_run", v).apply()

    /** Most images kept on the TV; the oldest are cleared out above this. */
    var maxStored: Int
        get() = sp.getInt("max_stored", 120)
        set(v) = sp.edit().putInt("max_stored", v).apply()

    /** Playlist limited to favourites (falls back to everything while there are none). */
    var favouritesOnly: Boolean
        get() = sp.getBoolean("favourites_only", false)
        set(v) = sp.edit().putBoolean("favourites_only", v).apply()

    /** Keys of favourite artworks; these are never trimmed from the cache. */
    val favourites: Set<String> get() = sp.getStringSet("favourites", emptySet()).orEmpty().toSet()
    fun isFavourite(key: String) = key in favourites

    /** Flips the favourite flag; returns the new state. */
    fun toggleFavourite(key: String): Boolean {
        val set = favourites.toMutableSet()
        val now = set.add(key)
        if (!now) set.remove(key)
        sp.edit().putStringSet("favourites", set).apply()
        return now
    }

    /** Keys of artworks the user never wants to see again. */
    val blocked: Set<String> get() = sp.getStringSet("blocked", emptySet()).orEmpty().toSet()
    fun isBlocked(key: String) = key in blocked

    /** Blocks [key] (and drops it from favourites). */
    fun block(key: String) {
        sp.edit().putStringSet("blocked", blocked + key)
            .putStringSet("favourites", favourites - key).apply()
    }

    fun isSourceOn(id: String): Boolean = sp.getBoolean("src_$id", true)
    fun setSourceOn(id: String, on: Boolean) = sp.edit().putBoolean("src_$id", on).apply()

    companion object {
        val INTERVALS = intArrayOf(30, 60, 120, 300, 600)
        val PER_RUN = intArrayOf(8, 20, 50, 100)
        val MAX_STORED = intArrayOf(60, 120, 250, 500)
    }
}
