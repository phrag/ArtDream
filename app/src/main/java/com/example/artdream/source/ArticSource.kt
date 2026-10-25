package com.example.artdream.source

import com.example.artdream.Artwork
import com.example.artdream.Http
import com.example.artdream.str
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Art Institute of Chicago — CC0 for works flagged is_public_domain, served
 * over IIIF so we just ask for the width we want. No key.
 *
 * The IIIF image host sits behind Cloudflare and may challenge some networks;
 * the downloader treats a failed URL as "skip this one", so that degrades to
 * the other sources rather than breaking the screensaver.
 */
object ArticSource : ArtSource {
    override val id = "artic"
    override val label = "Art Institute of Chicago"
    override val category = Category.PAINTINGS

    private const val IIIF = "https://www.artic.edu/iiif/2"

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> {
        val must = JSONArray()
            .put(JSONObject().put("term", JSONObject().put("is_public_domain", true)))
            .put(JSONObject().put("match", JSONObject().put("artwork_type_title", "Painting")))
        val params = JSONObject()
            .put("query", JSONObject().put("bool", JSONObject().put("must", must)))
            .put("fields", JSONArray(listOf("id", "title", "artist_title", "date_display", "image_id", "thumbnail")))
            .put("limit", count)
            // ~2200 PD paintings; AIC caps deep pagination, so stay in the first 1000 hits.
            .put("page", rnd.nextInt(1, 1000 / count + 1))
        val url = "https://api.artic.edu/api/v1/artworks/search?params=" +
            URLEncoder.encode(params.toString(), "UTF-8")
        val data = JSONObject(Http.getString(url)).getJSONArray("data")

        return (0 until data.length()).mapNotNull { i ->
            val o = data.getJSONObject(i)
            val img = o.str("image_id").ifEmpty { return@mapNotNull null }
            val thumb = o.optJSONObject("thumbnail")
            Artwork(
                source = id, id = o.get("id").toString(),
                title = o.str("title"), artist = o.str("artist_title"), date = o.str("date_display"),
                // 843 is AIC's documented default; larger sizes are usually allowed for PD works.
                imageUrls = listOf(1686, 843).map { w -> "$IIIF/$img/full/$w,/0/default.jpg" },
                width = thumb?.optInt("width") ?: 0,
                height = thumb?.optInt("height") ?: 0,
            )
        }
    }
}
