package com.example.artdream

import org.json.JSONArray
import org.json.JSONObject

/**
 * One painting as returned by a museum source. [imageUrls] is in preference
 * order (largest first); the downloader takes the first one that works.
 * [width]/[height] are the source's own dimensions when it reports them (only
 * used for the aspect filter, so 0 = unknown is fine).
 * [credit]/[license] are filled by sources whose images need attribution (CC BY etc.).
 */
data class Artwork(
    val source: String,
    val id: String,
    val title: String,
    val artist: String,
    val date: String,
    val imageUrls: List<String>,
    val width: Int = 0,
    val height: Int = 0,
    /** Photographer / author / agency to show when the licence asks for attribution ("" = none). */
    val credit: String = "",
    /** Short licence name, e.g. "CC BY-SA 4.0" or "Public domain" ("" = unspecified / PD). */
    val license: String = "",
) {
    /** Filesystem-safe cache key, unique across sources. */
    val key: String get() = "$source-$id".replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun toJson(): JSONObject = JSONObject()
        .put("source", source).put("id", id)
        .put("title", title).put("artist", artist).put("date", date)
        .put("imageUrls", JSONArray(imageUrls))
        .put("width", width).put("height", height)
        .put("credit", credit).put("license", license)

    companion object {
        fun fromJson(o: JSONObject): Artwork {
            val urls = o.optJSONArray("imageUrls") ?: JSONArray()
            return Artwork(
                source = o.str("source"), id = o.str("id"),
                title = o.str("title"), artist = o.str("artist"), date = o.str("date"),
                imageUrls = (0 until urls.length()).map { urls.getString(it) },
                width = o.optInt("width"), height = o.optInt("height"),
                credit = o.str("credit"), license = o.str("license"),
            )
        }
    }
}

/** optString() returns the literal "null" for JSON nulls; this returns "". */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key).trim()
