package com.phrag.artdream.source

import com.phrag.artdream.Artwork
import com.phrag.artdream.Http
import com.phrag.artdream.str
import org.json.JSONObject
import kotlin.random.Random

/**
 * SMK (Statens Museum for Kunst, Copenhagen) Open — paintings flagged
 * public_domain (CC0 / Public Domain Mark), IIIF images. No key.
 */
object SmkSource : ArtSource {
    override val id = "smk"
    override val label = "SMK (Denmark)"
    override val category = Category.PAINTINGS

    // [public_domain:true],[has_image:true],[object_names:maleri]  ("maleri" = painting)
    private const val BASE = "https://api.smk.dk/api/v1/art/search/" +
        "?keys=*&filters=%5Bpublic_domain%3Atrue%5D,%5Bhas_image%3Atrue%5D,%5Bobject_names%3Amaleri%5D" +
        "&fields=object_number,titles,production,production_date,public_domain," +
        "image_iiif_id,image_thumbnail,image_width,image_height"

    @Volatile private var total = 0

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> {
        if (total == 0) total = JSONObject(Http.getString("$BASE&offset=0&rows=1")).optInt("found")
        val offset = if (total > count) rnd.nextInt(total - count) else 0
        return parse(Http.getString("$BASE&offset=$offset&rows=$count"))
    }

    internal fun parse(json: String): List<Artwork> {
        val items = JSONObject(json).optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val o = items.getJSONObject(i)
            if (!o.optBoolean("public_domain")) return@mapNotNull null
            val iiif = o.str("image_iiif_id")
            if (iiif.isEmpty()) return@mapNotNull null
            val number = o.str("object_number").ifEmpty { return@mapNotNull null }
            val thumb = o.str("image_thumbnail")
            val urls = listOf(
                "$iiif/full/!3000,3000/0/default.jpg",
                // iip-thumb is the CDN copy of the same IIIF image; use it if the origin fails.
                thumb.substringBefore("/full/", "").takeIf { it.isNotEmpty() }
                    ?.let { "$it/full/!3000,3000/0/default.jpg" },
            ).filterNotNull()
            val titles = o.optJSONArray("titles")
            Artwork(
                source = id, id = number,
                title = titles?.optJSONObject(0)?.str("title").orEmpty(),
                artist = o.optJSONArray("production")?.optJSONObject(0)?.str("creator").orEmpty()
                    .split(", ").reversed().joinToString(" "),   // "Ballin, Mogens" -> "Mogens Ballin"
                date = o.optJSONArray("production_date")?.optJSONObject(0)?.str("period").orEmpty(),
                imageUrls = urls,
                width = o.optInt("image_width"), height = o.optInt("image_height"),
            )
        }
    }
}
