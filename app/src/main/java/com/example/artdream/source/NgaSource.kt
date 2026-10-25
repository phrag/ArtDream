package com.example.artdream.source

import com.example.artdream.Artwork
import kotlin.random.Random

/**
 * National Gallery of Art, Washington — Open Access (CC0) paintings, served as
 * IIIF from api.nga.gov. No key.
 *
 * NGA has no search API; its open-data release is two CSV files of 80+ MB each
 * (github.com/NationalGalleryOfArt/opendata). So the list of open-access
 * paintings with a large primary image (2906 of them, at least 1500 px on the
 * short side) was extracted from `objects.csv` + `published_images.csv`
 * (openaccess=1, classification=Painting, viewtype=primary) into the bundled
 * resource `nga_paintings.tsv`: uuid, title, artist, date, width, height.
 * Only the image is fetched at runtime.
 */
object NgaSource : ArtSource {
    override val id = "nga"
    override val label = "National Gallery of Art"
    override val category = Category.PAINTINGS

    private const val IIIF = "https://api.nga.gov/iiif"

    private val entries: List<Artwork> by lazy {
        val stream = NgaSource::class.java.classLoader?.getResourceAsStream("nga_paintings.tsv")
        stream?.bufferedReader(Charsets.UTF_8)?.use { parseTsv(it.readText()) } ?: emptyList()
    }

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> = entries.shuffled(rnd).take(count)

    /** One line per work: uuid TAB title TAB artist TAB date TAB width TAB height. */
    internal fun parseTsv(text: String): List<Artwork> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 6 || f[0].isBlank()) return@mapNotNull null
        Artwork(
            source = id, id = f[0], title = f[1], artist = f[2], date = f[3],
            imageUrls = listOf(3000, 1500).map { w -> "$IIIF/${f[0]}/full/!$w,$w/0/default.jpg" },
            width = f[4].toIntOrNull() ?: 0, height = f[5].toIntOrNull() ?: 0,
        )
    }.toList()
}
