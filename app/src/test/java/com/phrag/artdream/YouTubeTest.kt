package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeTest {

    private val id = "YE7VzlLtp-4"

    @Test fun watchLinks() {
        assertEquals(id, YouTube.parseVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTube.parseVideoId("https://youtube.com/watch?v=$id&t=42s&list=PLabc"))
        assertEquals(id, YouTube.parseVideoId("https://m.youtube.com/watch?feature=share&v=$id"))
        assertEquals(id, YouTube.parseVideoId("https://music.youtube.com/watch?v=$id&si=xyz"))
        assertEquals(id, YouTube.parseVideoId("http://www.youtube.com/watch?v=$id"))
    }

    @Test fun shortLinkAndOtherPathForms() {
        assertEquals(id, YouTube.parseVideoId("https://youtu.be/$id"))
        assertEquals(id, YouTube.parseVideoId("https://youtu.be/$id?si=abc&t=3"))
        assertEquals(id, YouTube.parseVideoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, YouTube.parseVideoId("https://www.youtube.com/embed/$id?autoplay=1"))
        assertEquals(id, YouTube.parseVideoId("https://www.youtube-nocookie.com/embed/$id"))
        assertEquals(id, YouTube.parseVideoId("https://www.youtube.com/live/$id"))
        assertEquals(id, YouTube.parseVideoId("https://www.youtube.com/v/$id"))
    }

    @Test fun lenientAboutWhitespaceSchemeAndBareIds() {
        assertEquals(id, YouTube.parseVideoId("  https://youtu.be/$id \n"))
        assertEquals(id, YouTube.parseVideoId("youtube.com/watch?v=$id"))
        assertEquals(id, YouTube.parseVideoId("youtu.be/$id"))
        assertEquals(id, YouTube.parseVideoId(id))
    }

    @Test fun refusesWhatIsNotAVideoLink() {
        assertNull(YouTube.parseVideoId(""))
        assertNull(YouTube.parseVideoId("https://www.youtube.com/"))
        assertNull(YouTube.parseVideoId("https://www.youtube.com/@someChannel"))
        assertNull(YouTube.parseVideoId("https://www.youtube.com/playlist?list=PLabcdefghijk"))
        assertNull(YouTube.parseVideoId("https://www.youtube.com/watch?v=short"))
        assertNull(YouTube.parseVideoId("https://www.youtube.com/watch?v=" + "a".repeat(12)))
        assertNull(YouTube.parseVideoId("https://vimeo.com/$id"))
        assertNull(YouTube.parseVideoId("https://evil.example/watch?v=$id"))
        assertNull(YouTube.parseVideoId("https://youtube.com.evil.example/watch?v=$id"))
        assertNull(YouTube.parseVideoId("https://notyoutu.be/$id"))
        assertNull(YouTube.parseVideoId("ftp://youtu.be/$id"))
        assertNull(YouTube.parseVideoId("javascript:alert(1)"))
        assertNull(YouTube.parseVideoId("not a link at all"))
    }

    @Test fun linkFileRoundTrip() {
        val link = YouTube.Link(id, "Big Buck Bunny", "Blender")
        val back = YouTube.readLink(link.toJson())!!
        assertEquals(id, back.id)
        assertEquals("Big Buck Bunny", back.title)
        assertEquals("Blender", back.channel)
    }

    @Test fun linkFileMustHoldAValidId() {
        assertNull(YouTube.readLink(""))
        assertNull(YouTube.readLink("{}"))
        assertNull(YouTube.readLink("""{"id":"short"}"""))
        assertNull(YouTube.readLink("""{"id":"x'); alert(1);//"}"""))
        assertNull(YouTube.readLink("not json"))
    }

    @Test fun fileNamesAreSafeAndKeepTheId() {
        val name = YouTube.fileName(YouTube.Link(id, "../../Big: Buck/Bunny?*", "c"))
        assertTrue(name, name.endsWith(" [$id].yt"))
        assertTrue(name, '/' !in name && '\\' !in name && ".." !in name)
        assertNotNull(OwnMedia.kindOf(name))
        assertEquals("YouTube video [$id].yt", YouTube.fileName(YouTube.Link(id, "", "")))
    }

    @Test fun playerPageCarriesOnlyAValidatedIdAndTheSoundSetting() {
        val muted = YouTube.playerHtml(id, sound = false)
        assertTrue("videoId:'$id'" in muted)
        assertTrue("mute:1" in muted)
        assertTrue("e.target.mute();" in muted)
        val loud = YouTube.playerHtml(id, sound = true)
        assertTrue("mute:0" in loud)
        assertTrue("e.target.mute();" !in loud)
    }

    @Test fun playerPageTurnsCaptionsOffAndSizesThePlayerInScreenPixels() {
        for (page in listOf(YouTube.playerHtml(id, sound = false), YouTube.playerHtml(id, sound = true))) {
            assertTrue("cc_load_policy:0" in page)
            assertTrue("unloadModule('captions')" in page)
            assertTrue("devicePixelRatio" in page)
            assertTrue("scale('+(1/dpr)+')" in page)
            // Sized before the player exists, and again on resize (a 0x0 window left it at 480p).
            assertTrue(page.indexOf("function onYouTubeIframeAPIReady") < page.indexOf("iframe_api\""))
            assertTrue("addEventListener('resize'" in page)
            assertTrue("player.setSize(W,H);best(player);" in page)
        }
    }
}
