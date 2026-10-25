package com.example.artdream.source

import android.text.Html
import android.util.Log
import com.example.artdream.Artwork
import com.example.artdream.Http
import com.example.artdream.str
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random

/**
 * Wikimedia Commons "Featured pictures" categories via the MediaWiki API — no key.
 * Featured pictures are the community's best photos, mostly 4000-8000 px.
 * Licences vary, so each item is checked: only public domain, CC0, CC BY and
 * CC BY-SA (no NC/ND) are kept, and author + licence travel in the Artwork so the
 * caption can attribute them. The API gives a scaled 3000 px JPEG (thumburl).
 */
abstract class WikimediaSource(
    override val id: String,
    override val label: String,
    override val category: Category,
    private val categories: List<String>,
) : ArtSource {

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> {
        var out = emptyList<Artwork>()
        repeat(2) {   // a random start key near the end of a category can return little
            if (out.size >= count / 2) return out
            out = query(categories[rnd.nextInt(categories.size)], count, rnd)
        }
        return out
    }

    private fun query(cat: String, count: Int, rnd: Random): List<Artwork> {
        val startKey = "%016X".format(rnd.nextLong())
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2" +
            "&generator=categorymembers&gcmtitle=" + URLEncoder.encode("Category:$cat", "UTF-8") +
            "&gcmtype=file&gcmsort=sortkey&gcmstarthexsortkey=$startKey&gcmlimit=${minOf(50, count * 2)}" +
            "&prop=imageinfo&iiprop=url%7Csize%7Cmime%7Cextmetadata&iiurlwidth=3000" +
            "&iiextmetadatafilter=LicenseShortName%7CArtist%7CObjectName%7CDateTimeOriginal"
        val pages = JSONObject(Http.getString(url)).optJSONObject("query")?.optJSONArray("pages")
            ?: return emptyList()
        return (0 until pages.length()).mapNotNull { i ->
            try {
                parse(pages.getJSONObject(i))
            } catch (e: Exception) {
                Log.w("ArtDream", "$id page $i: $e")
                null
            }
        }
    }

    private fun parse(p: JSONObject): Artwork? {
        val info = p.getJSONArray("imageinfo").getJSONObject(0)
        val mime = info.str("mime")
        if (mime != "image/jpeg" && mime != "image/png") return null
        val w = info.optInt("width"); val h = info.optInt("height")
        if (w < 1920 || w < h) return null
        val meta = info.optJSONObject("extmetadata") ?: return null
        val license = meta.optJSONObject("LicenseShortName")?.str("value").orEmpty()
        if (!freeLicense(license)) return null

        val thumb = info.str("thumburl")
        val orig = info.str("url")
        val urls = listOfNotNull(
            thumb.ifEmpty { null },
            orig.takeIf { it.isNotEmpty() && info.optLong("size") <= 30_000_000L && it != thumb },
        )
        if (urls.isEmpty()) return null

        val file = p.str("title").removePrefix("File:").substringBeforeLast('.')
        val title = plain(meta.optJSONObject("ObjectName")?.str("value").orEmpty()).ifEmpty { file }
        val author = plain(meta.optJSONObject("Artist")?.str("value").orEmpty()).take(80)
        val year = Regex("\\d{4}").find(meta.optJSONObject("DateTimeOriginal")?.str("value").orEmpty())?.value.orEmpty()
        return Artwork(
            source = id, id = p.get("pageid").toString(),
            title = title.take(120), artist = author, date = year,
            imageUrls = urls, width = w, height = h,
            credit = author, license = license,
        )
    }

    private fun freeLicense(l: String): Boolean {
        val s = l.lowercase().trim()
        if (s.isEmpty() || Regex("\\b(nc|nd)\\b").containsMatchIn(s)) return false
        return s.startsWith("cc0") || s.startsWith("public domain") || s.startsWith("pd") ||
            Regex("^cc[ -]by([ -]sa)?[ -]\\d").containsMatchIn(s)
    }

    private fun plain(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
}

object WikimediaLandscapesSource : WikimediaSource(
    id = "wm_landscapes", label = "Wikimedia Commons (nature)", category = Category.LANDSCAPES,
    categories = listOf(
        "Featured_pictures_of_landscapes", "Featured_pictures_of_mountains",
        "Featured_pictures_of_sunsets", "Featured_pictures_of_lakes",
        "Featured_pictures_of_waterfalls", "Featured_pictures_of_beaches",
        "Featured_pictures_of_forests", "Featured_pictures_of_trees",
    ),
)

object WikimediaSpaceSource : WikimediaSource(
    id = "wm_space", label = "Wikimedia Commons (astronomy)", category = Category.SPACE,
    categories = listOf("Featured_pictures_of_astronomy", "Featured_pictures_of_the_Moon"),
)

object WikimediaCitiesSource : WikimediaSource(
    id = "wm_cities", label = "Wikimedia Commons (cities)", category = Category.CITIES,
    categories = listOf("Featured_pictures_of_cityscapes", "Featured_pictures_of_architecture"),
)
