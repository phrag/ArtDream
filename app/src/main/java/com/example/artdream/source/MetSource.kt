package com.example.artdream.source

import android.util.Log
import com.example.artdream.Artwork
import com.example.artdream.Http
import com.example.artdream.str
import org.json.JSONObject
import kotlin.random.Random

/**
 * The Met Open Access — CC0 originals (often 4000px+, the cache downsamples).
 * Searches European Paintings (department 11). No key.
 * Note: /v1/search was retired 2026-10-01; /v1.1/search is paginated.
 */
object MetSource : ArtSource {
    override val id = "met"
    override val label = "The Met"

    private const val BASE = "https://collectionapi.metmuseum.org/public/collection"
    private const val SEARCH = "$BASE/v1.1/search?departmentId=11&hasImages=true&isPublicDomain=true&q=painting"

    @Volatile private var total = 0

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> {
        if (total == 0) total = JSONObject(Http.getString("$SEARCH&limit=1")).optInt("total")
        val offset = if (total > count) rnd.nextInt(total - count) else 0
        val ids = JSONObject(Http.getString("$SEARCH&limit=$count&offset=$offset"))
            .optJSONArray("objectIDs") ?: return emptyList()

        return (0 until ids.length()).mapNotNull { i ->
            try {
                val o = JSONObject(Http.getString("$BASE/v1/objects/${ids.get(i)}"))
                val img = o.str("primaryImage")
                if (!o.optBoolean("isPublicDomain") || img.isEmpty()) return@mapNotNull null
                Artwork(
                    source = id, id = o.get("objectID").toString(),
                    title = o.str("title"), artist = o.str("artistDisplayName"), date = o.str("objectDate"),
                    imageUrls = listOf(img),   // Met doesn't report pixel size; checked after download
                )
            } catch (e: Exception) {
                Log.w("ArtDream", "met object ${ids.get(i)}: $e")
                null
            }
        }
    }
}
