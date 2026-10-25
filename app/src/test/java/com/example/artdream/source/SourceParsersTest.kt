package com.example.artdream.source

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers run against real API responses saved in src/test/resources (captured 2026-10-25). */
class SourceParsersTest {
    private fun res(name: String) =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }

    @Test fun smk() {
        val list = SmkSource.parse(res("smk_search.json"))
        assertEquals(2, list.size)  // 3rd sample item has no IIIF image and is skipped
        val a = list[0]
        assertEquals("smk", a.source)
        assertTrue(a.id.startsWith("KMS"))
        assertTrue(a.title.isNotEmpty())
        assertTrue(a.artist.isNotEmpty() && !a.artist.contains(","))
        assertTrue(a.imageUrls[0].startsWith("https://iip.smk.dk/iiif/"))
        assertTrue(a.imageUrls[0].endsWith("/full/!3000,3000/0/default.jpg"))
        assertTrue(a.width > 0 && a.height > 0)
    }

    @Test fun rijks() {
        val a = RijksSource.parseObject(res("rijks_object.json"))
        assertNotNull(a)
        a!!
        assertEquals("200100988", a.id)
        assertEquals("Misty Sea", a.title)
        assertEquals("Jan Toorop", a.artist)
        assertEquals("1899", a.date)
        assertEquals("https://iiif.micr.io/mPymb/full/3000,/0/default.jpg", a.imageUrls[0])
    }

    @Test fun rijksWithoutPublicDomainImageIsSkipped() {
        val o = JSONObject(res("rijks_object.json"))
        val stripped = o.toString().replace("publicdomain/mark/1.0", "licenses/by-nc/4.0")
        assertEquals(null, RijksSource.parseObject(stripped))
    }

    @Test fun nga() {
        val list = NgaSource.parseTsv(res("nga_sample.tsv"))
        assertEquals(5, list.size)
        assertEquals("Saint James Major", list[0].title)
        assertEquals("Grifo di Tancredi", list[0].artist)
        assertEquals(2846, list[0].width)
        assertEquals(
            "https://api.nga.gov/iiif/7b170a4c-9d44-475c-b294-cee6f43d88af/full/!3000,3000/0/default.jpg",
            list[0].imageUrls[0],
        )
    }

    @Test fun ngaBundledListIsLoadable() {
        val n = NgaSource.fetchBatch(10, kotlin.random.Random(1)).size
        assertEquals(10, n)
    }

    @Test fun clevelandParseAndLicense() {
        val o = JSONObject(res("cleveland_object.json")).getJSONObject("data")
        val a = ClevelandSource.parse(o)!!
        assertEquals("148758", a.id)
        assertEquals("The Crucifixion of Saint Andrew", a.title)
        assertEquals(2563, a.width)
    }

    @Test fun metParse() {
        val a = MetSource.parse(JSONObject(res("met_object.json")))!!
        assertEquals("437394", a.id)
        assertEquals("Aristotle with a Bust of Homer", a.title)
    }

    @Test fun masterpiecesListHasNoDuplicates() {
        val keys = MasterpiecesSource.PICKS.map { it.museum to it.id }
        assertEquals(keys.size, keys.toSet().size)
    }
}
