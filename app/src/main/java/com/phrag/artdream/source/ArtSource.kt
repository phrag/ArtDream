package com.phrag.artdream.source

import com.phrag.artdream.Artwork
import kotlin.random.Random

/**
 * What kind of picture a source provides. The settings screen shows one toggle
 * per category; a source is only used when its category is switched on.
 * Add new categories here (label is what the picker shows).
 */
enum class Category(val label: String) {
    PAINTINGS("Paintings"),
    LANDSCAPES("Landscapes & nature"),
    SPACE("Space"),
    CITIES("Cities & architecture"),
}

/**
 * An open-access image API. Every source must only return images that are public
 * domain / CC0 (or, if a source needs attribution, carry it in the Artwork so
 * the caption shows it) — that's what makes redistribution on a TV OK.
 * Implementations block on network I/O; call off the main thread.
 */
interface ArtSource {
    val id: String
    val label: String
    val category: Category
    fun fetchBatch(count: Int, rnd: Random): List<Artwork>

    companion object {
        val ALL: List<ArtSource> = listOf(
            ClevelandSource, MetSource, ArticSource,
            RijksSource, SmkSource, NgaSource, MasterpiecesSource,
            NasaSpaceSource, NasaEarthSource,
            WikimediaLandscapesSource, WikimediaSpaceSource, WikimediaCitiesSource,
        )
    }
}
