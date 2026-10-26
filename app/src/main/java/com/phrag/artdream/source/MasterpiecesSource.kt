package com.phrag.artdream.source

import android.util.Log
import com.phrag.artdream.Artwork
import com.phrag.artdream.Backoff
import kotlin.random.Random

/**
 * A hand-picked list of famous paintings that are public domain / CC0 in the
 * museum that holds them. Every id below was checked against the live API
 * (title, artist, public-domain flag and image present) on 2026-10-25.
 * Each work is resolved through its museum's own object endpoint, so the
 * title/artist/date come from the museum and a work that is withdrawn or loses
 * its image is simply skipped.
 */
object MasterpiecesSource : ArtSource {
    override val id = "masterpieces"
    override val label = "Masterpieces (curated)"
    override val category = Category.PAINTINGS

    enum class Museum { MET, CMA, AIC }

    data class Pick(val museum: Museum, val id: Int, val note: String)

    /** [note] is only for humans reading this list. */
    internal val PICKS: List<Pick> = listOf(
        // The Met (objects/{id}, isPublicDomain = true)
        Pick(Museum.MET, 437394, "Rembrandt, Aristotle with a Bust of Homer"),
        Pick(Museum.MET, 436535, "van Gogh, Wheat Field with Cypresses"),
        Pick(Museum.MET, 437980, "van Gogh, Cypresses"),
        Pick(Museum.MET, 436524, "van Gogh, Sunflowers"),
        Pick(Museum.MET, 436528, "van Gogh, Irises"),
        Pick(Museum.MET, 436532, "van Gogh, Self-Portrait with a Straw Hat"),
        Pick(Museum.MET, 437984, "van Gogh, La Berceuse"),
        Pick(Museum.MET, 437881, "Vermeer, Young Woman with a Water Pitcher"),
        Pick(Museum.MET, 437878, "Vermeer, A Maid Asleep"),
        Pick(Museum.MET, 438817, "Degas, The Dance Class"),
        Pick(Museum.MET, 436575, "El Greco, View of Toledo"),
        Pick(Museum.MET, 11122, "Homer, The Gulf Stream"),
        Pick(Museum.MET, 12127, "Sargent, Madame X"),
        Pick(Museum.MET, 436105, "David, The Death of Socrates"),
        Pick(Museum.MET, 435809, "Bruegel, The Harvesters"),
        Pick(Museum.MET, 436947, "Manet, Boating"),
        Pick(Museum.MET, 435878, "Cezanne, Mont Sainte-Victoire"),
        Pick(Museum.MET, 435868, "Cezanne, The Card Players"),
        Pick(Museum.MET, 435844, "Caravaggio, The Musicians"),
        Pick(Museum.MET, 438815, "Renoir, Madame Georges Charpentier and Her Children"),
        Pick(Museum.MET, 10497, "Cole, View from Mount Holyoke (The Oxbow)"),
        Pick(Museum.MET, 437869, "Velazquez, Juan de Pareja"),
        Pick(Museum.MET, 436838, "La Tour, The Fortune-Teller"),
        // Cleveland Museum of Art (artworks/{id}, share_license_status = CC0)
        Pick(Museum.CMA, 148758, "Caravaggio, The Crucifixion of Saint Andrew"),
        Pick(Museum.CMA, 136510, "Monet, Water Lilies (Agapanthus)"),
        Pick(Museum.CMA, 122351, "Turner, The Burning of the Houses of Lords and Commons"),
        Pick(Museum.CMA, 108555, "Poussin, Nymphs and a Satyr (Amor Vincit Omnia)"),
        // Art Institute of Chicago (artworks/{id}, is_public_domain = true)
        Pick(Museum.AIC, 27992, "Seurat, A Sunday on La Grande Jatte"),
        Pick(Museum.AIC, 28560, "van Gogh, The Bedroom"),
        Pick(Museum.AIC, 80607, "van Gogh, Self-Portrait"),
        Pick(Museum.AIC, 16568, "Monet, Water Lilies"),
        Pick(Museum.AIC, 64818, "Monet, Stacks of Wheat (End of Summer)"),
        Pick(Museum.AIC, 14598, "Monet, The Beach at Sainte-Adresse"),
        Pick(Museum.AIC, 20684, "Caillebotte, Paris Street; Rainy Day"),
        Pick(Museum.AIC, 16487, "Cezanne, The Bay of Marseille, Seen from L'Estaque"),
    )

    override fun fetchBatch(count: Int, rnd: Random): List<Artwork> =
        PICKS.shuffled(rnd).take(count).mapNotNull { p ->
            try {
                val a = when (p.museum) {
                    Museum.MET -> MetSource.fetchOne(p.id)
                    Museum.CMA -> ClevelandSource.fetchOne(p.id)
                    Museum.AIC -> ArticSource.fetchOne(p.id)
                }
                // Same work as the museum's own source, but its own cache key.
                a?.copy(source = id, id = "${p.museum.name.lowercase()}-${p.id}")
            } catch (e: Exception) {
                if (Backoff.isRateLimit(e)) throw e   // let ArtCache back the whole source off
                Log.w("fArtSaver", "masterpiece ${p.museum} ${p.id}: $e")
                null
            }
        }
}
