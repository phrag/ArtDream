package com.phrag.artdream.source

import android.util.Log
import com.phrag.artdream.Artwork
import com.phrag.artdream.Backoff
import com.phrag.artdream.Http
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import kotlin.random.Random

/**
 * Rijksmuseum — paintings with a Public Domain Mark image, via the new
 * Linked Art data service (data.rijksmuseum.nl). The old `/api/en/collection`
 * endpoint that needed a key now answers 410 Gone; the new one needs no key.
 *
 * Flow: the search lists 100 object ids per page (any `pageToken` of the form
 * base64({"token": "<object uri>"}) works, so we jump to a random place);
 * each object is one JSON-LD fetch whose `subject_of` carries the IIIF
 * image (micr.io) and its rights statement.
 */
object RijksSource : ArtSource {
    override val id = "rijks"
    override val label = "Rijksmuseum"
    override val category = Category.PAINTINGS

    private const val SEARCH = "https://data.rijksmuseum.nl/search/collection?type=painting&imageAvailable=true"
    private const val EN = "http://vocab.getty.edu/aat/300388277"
    private const val PD_MARK = "publicdomain/mark/1.0"

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> {
        var ids = listIds(pageUrl(rnd))
        if (ids.isEmpty()) ids = listIds(SEARCH)   // random token landed past the end
        return ids.shuffled(rnd).take(count).mapNotNull { uri ->
            try {
                parseObject(Http.getString(uri))
            } catch (e: Exception) {
                if (Backoff.isRateLimit(e)) throw e   // let ArtCache back the whole source off
                Log.w("fArtSaver", "rijks $uri: $e")
                null
            }
        }
    }

    private fun pageUrl(rnd: Random): String {
        // Ids sort as strings: ~2001xxxxx and ~2002xxxxx hold most paintings.
        val token = "https://id.rijksmuseum.nl/200${rnd.nextInt(1, 3)}${rnd.nextInt(0, 100000).toString().padStart(5, '0')}"
        val b64 = Base64.getEncoder().encodeToString("""{"token": "$token"}""".toByteArray())
        return "$SEARCH&pageToken=$b64"
    }

    private fun listIds(url: String): List<String> {
        val items = JSONObject(Http.getString(url)).optJSONArray("orderedItems") ?: return emptyList()
        return (0 until items.length()).map { items.getJSONObject(it).getString("id") }
    }

    /** Parses one Linked Art HumanMadeObject; null if there is no public-domain IIIF image. */
    internal fun parseObject(json: String): Artwork? {
        val o = JSONObject(json)
        val uri = o.getString("id")

        var image: String? = null
        val subjects = o.optJSONArray("subject_of") ?: JSONArray()
        loop@ for (i in 0 until subjects.length()) {
            val carried = subjects.getJSONObject(i).optJSONArray("digitally_carried_by") ?: continue
            for (j in 0 until carried.length()) {
                val d = carried.getJSONObject(j)
                val pd = d.optJSONArray("referred_to_by")?.let { r ->
                    (0 until r.length()).any { r.getJSONObject(it).optString("content").contains(PD_MARK) }
                } ?: false
                val url = d.optJSONArray("access_point")?.optJSONObject(0)?.optString("id").orEmpty()
                if (pd && url.contains("/full/")) { image = url; break@loop }
            }
        }
        // ".../<id>/full/max/0/default.jpg" — swap "max" for a 3000 px width.
        val base = image?.substringBefore("/full/") ?: return null

        val names = o.optJSONArray("identified_by") ?: JSONArray()
        var title = ""
        for (i in 0 until names.length()) {
            val n = names.getJSONObject(i)
            if (n.optString("type") != "Name") continue
            val c = n.optString("content")
            val en = (n.optJSONArray("language")?.optJSONObject(0)?.optString("id") == EN)
            if (title.isEmpty() || en) title = c
            if (en && n.optJSONArray("classified_as")?.toString()?.contains("300417207") == true) break
        }

        val produced = o.optJSONObject("produced_by")
        val artist = produced?.optJSONArray("part")?.let { parts ->
            (0 until parts.length()).firstNotNullOfOrNull { p ->
                parts.getJSONObject(p).optJSONArray("carried_out_by")?.optJSONObject(0)
                    ?.optJSONArray("notation")?.let { nn ->
                        (0 until nn.length()).map { nn.getJSONObject(it) }
                            .let { l -> l.firstOrNull { it.optString("@language") == "en" } ?: l.firstOrNull() }
                            ?.optString("@value")
                    }
            }
        }.orEmpty()
        val date = produced?.optJSONObject("timespan")?.optJSONArray("identified_by")
            ?.optJSONObject(0)?.optString("content").orEmpty()

        return Artwork(
            source = id, id = uri.substringAfterLast('/'),
            title = title, artist = artist, date = date,
            imageUrls = listOf("$base/full/3000,/0/default.jpg", "$base/full/1500,/0/default.jpg", "$base/full/max/0/default.jpg"),
        )
    }
}
