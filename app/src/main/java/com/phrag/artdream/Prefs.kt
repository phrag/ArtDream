package com.phrag.artdream

import android.content.Context
import com.phrag.artdream.source.Category

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

    /** "auto" dims and warms the painting between 21:00 and 07:00; "off" never does. */
    var nightMode: String
        get() = sp.getString("night_mode", NIGHT_AUTO) ?: NIGHT_AUTO
        set(v) = sp.edit().putString("night_mode", v).apply()

    /** Small clock in the top-right corner (nudged each slide against burn-in). */
    var showClock: Boolean
        get() = sp.getBoolean("show_clock", false)
        set(v) = sp.edit().putBoolean("show_clock", v).apply()

    /** [GROUP_RANDOM] or [GROUP_BY_TYPE] (runs of ~8 slides from one category). */
    var grouping: String
        get() = sp.getString("grouping", GROUP_RANDOM) ?: GROUP_RANDOM
        set(v) = sp.edit().putString("grouping", v).apply()

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

    /** Top the cache up once a day in the background (JobScheduler), not just at screensaver start. */
    var backgroundRefresh: Boolean
        get() = sp.getBoolean("background_refresh", true)
        set(v) = sp.edit().putBoolean("background_refresh", v).apply()

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

    /** How the user's own pictures and videos mix with the museum art: [OWN_MIX], [OWN_ONLY] or [OWN_OFF]. */
    var ownMode: String
        get() = sp.getString("own_mode", OWN_MIX) ?: OWN_MIX
        set(v) = sp.edit().putString("own_mode", v).apply()

    /** Sound on own videos. Off by default: a screensaver shouldn't start talking in a quiet room. */
    var videoSound: Boolean
        get() = sp.getBoolean("video_sound", false)
        set(v) = sp.edit().putBoolean("video_sound", v).apply()

    fun isSourceOn(id: String): Boolean = sp.getBoolean("src_$id", true)
    fun setSourceOn(id: String, on: Boolean) = sp.edit().putBoolean("src_$id", on).apply()

    companion object {
        const val NIGHT_OFF = "off"
        const val NIGHT_AUTO = "auto"
        const val OWN_MIX = "mix"
        const val OWN_ONLY = "only"
        const val OWN_OFF = "off"
        const val GROUP_RANDOM = "random"
        const val GROUP_BY_TYPE = "type"
        val INTERVALS = intArrayOf(30, 60, 120, 300, 600)
        val PER_RUN = intArrayOf(8, 20, 50, 100)
        val MAX_STORED = intArrayOf(60, 120, 250, 500)
    }
}
