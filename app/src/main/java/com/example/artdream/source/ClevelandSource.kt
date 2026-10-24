package com.example.artdream.source

import com.example.artdream.Artwork
import com.example.artdream.Http
import com.example.artdream.str
import org.json.JSONObject
import kotlin.random.Random

/** Cleveland Museum of Art Open Access — CC0, ~3400px "print" JPEGs. No key. */
object ClevelandSource : ArtSource {
    override val id = "cma"
    override val label = "Cleveland Museum of Art"

    private const val BASE = "https://openaccess-api.clevelandart.org/api/artworks/?cc0=1&has_image=1&type=Painting"

    @Volatile private var total = 0

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> {
        if (total == 0) {
            total = JSONObject(Http.getString("$BASE&limit=1")).getJSONObject("info").optInt("total")
        }
        val skip = if (total > count) rnd.nextInt(total - count) else 0
        val data = JSONObject(Http.getString("$BASE&limit=$count&skip=$skip")).getJSONArray("data")
        return (0 until data.length()).mapNotNull { i ->
            val o = data.getJSONObject(i)
            val images = o.optJSONObject("images") ?: return@mapNotNull null
            // Skip "full": it's a 100MB+ TIFF.
            val print = images.optJSONObject("print")
            val web = images.optJSONObject("web")
            val urls = listOfNotNull(print?.str("url"), web?.str("url")).filter { it.isNotEmpty() }
            if (urls.isEmpty()) return@mapNotNull null
            val dims = print ?: web
            val creators = o.optJSONArray("creators")
            val artist = creators?.optJSONObject(0)?.str("description").orEmpty()
            Artwork(
                source = id, id = o.get("id").toString(),
                title = o.str("title"), artist = artist, date = o.str("creation_date"),
                imageUrls = urls,
                width = dims?.str("width")?.toIntOrNull() ?: 0,
                height = dims?.str("height")?.toIntOrNull() ?: 0,
            )
        }
    }
}
