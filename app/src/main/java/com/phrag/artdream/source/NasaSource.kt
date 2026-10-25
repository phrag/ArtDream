package com.phrag.artdream.source

import android.net.Uri
import android.util.Log
import com.phrag.artdream.Artwork
import com.phrag.artdream.Http
import com.phrag.artdream.str
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * NASA Image and Video Library (images-api.nasa.gov) — no key, NASA imagery is
 * public domain unless an item says otherwise (those are skipped).
 * Each item lists links: ~orig.jpg (often 3000-8000 px), ~large (1920), ~medium (1280).
 * The API caps paging at 10 000 results (page * page_size).
 */
abstract class NasaSource(
    override val id: String,
    override val label: String,
    override val category: Category,
    private val queries: List<String>,
) : ArtSource {
    private val totals = ConcurrentHashMap<String, Int>()

    private fun url(q: String, page: Int, size: Int) =
        "https://images-api.nasa.gov/search?media_type=image&q=${Uri.encode(q)}&page=$page&page_size=$size"

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> {
        val q = queries[rnd.nextInt(queries.size)]
        val total = totals[q] ?: JSONObject(Http.getString(url(q, 1, 1)))
            .getJSONObject("collection").getJSONObject("metadata").optInt("total_hits")
            .also { totals[q] = it }
        if (total == 0) return emptyList()
        val pages = (minOf(total, 10_000) / count).coerceAtLeast(1)
        val page = 1 + rnd.nextInt(pages)
        val items = JSONObject(Http.getString(url(q, page, count)))
            .getJSONObject("collection").getJSONArray("items")

        return (0 until items.length()).mapNotNull { i ->
            try {
                parse(items.getJSONObject(i))
            } catch (e: Exception) {
                Log.w("ArtDream", "$id item $i: $e")
                null
            }
        }
    }

    private fun parse(item: JSONObject): Artwork? {
        val d = item.getJSONArray("data").getJSONObject(0)
        if (d.str("media_type") != "image") return null
        val text = d.str("description") + " " + d.str("secondary_creator")
        // A few items carry a non-PD licence in the credit line (e.g. "CC BY-NC-SA 4.0").
        if (Regex("copyright|©|creative commons|\\bCC[ -]", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        val links = item.optJSONArray("links") ?: return null

        var orig: String? = null
        var large: String? = null
        var medium: String? = null
        var bestW = 0; var bestH = 0
        for (i in 0 until links.length()) {
            val l = links.getJSONObject(i)
            if (l.str("render") != "image") continue
            val href = l.str("href")
            val w = l.optInt("width"); val h = l.optInt("height")
            val bytes = l.optLong("size")
            if (w > bestW && h > 0) { bestW = w; bestH = h }
            when {
                href.endsWith("~orig.jpg") || href.endsWith("~orig.jpeg") || href.endsWith("~orig.png") ->
                    if (bytes <= 30_000_000L) orig = href
                href.endsWith("~large.jpg") -> large = href
                href.endsWith("~medium.jpg") -> medium = href
            }
        }
        if (bestW in 1..1919) return null          // only low-res variants exist
        if (bestW > 0 && bestW < bestH) return null // portrait
        val urls = listOfNotNull(orig, large, medium)
        if (urls.isEmpty()) return null

        val title = d.str("title").take(120)
        if (title.isEmpty()) return null
        val credit = d.str("secondary_creator").ifEmpty { d.str("photographer") }
            .ifEmpty { d.str("center").ifEmpty { "NASA" } }
        return Artwork(
            source = id, id = d.str("nasa_id"),
            title = title, artist = "", date = d.str("date_created").take(4),
            imageUrls = urls, width = bestW, height = bestH,
            credit = credit, license = "Public domain (NASA)",
        )
    }
}

object NasaSpaceSource : NasaSource(
    id = "nasa_space", label = "NASA (space)", category = Category.SPACE,
    queries = listOf(
        "nebula", "galaxy", "hubble", "james webb", "supernova remnant", "star cluster",
        "milky way", "mars surface", "jupiter", "saturn", "comet", "solar eclipse",
        "spiral galaxy", "planetary nebula", "lunar surface",
    ),
)

object NasaEarthSource : NasaSource(
    id = "nasa_earth", label = "NASA (Earth)", category = Category.LANDSCAPES,
    queries = listOf(
        "earth landscape", "earth from space", "mountains from space", "glacier",
        "desert from orbit", "volcano", "coastline", "river delta", "aurora earth",
        "national park", "island from space", "lake from space",
    ),
)
