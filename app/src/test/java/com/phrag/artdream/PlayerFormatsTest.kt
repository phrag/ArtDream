package com.phrag.artdream

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerFormatsTest {

    private val request = """{"context":{"client":{"clientName":"IOS","clientVersion":"21.03.2"}},"videoId":"abc"}"""

    private fun format(itag: Int, mime: String) =
        """{"itag": $itag, "url": "https://rr1---sn.googlevideo.com/videoplayback?itag=$itag&x=1", "mimeType": "$mime", "bitrate": 1000}"""

    @Test fun listsFormatsAndTheHdrOnes() {
        val response = """{"streamingData":{"adaptiveFormats":[""" + listOf(
            format(315, """video/webm; codecs=\"vp9\""""),
            format(337, """video/webm; codecs=\"vp09.02.51.10.01.09.16.09.00\""""),
            format(701, """video/mp4; codecs=\"av01.0.13M.10.0.110.09.16.09.0\""""),
            format(140, """audio/mp4; codecs=\"mp4a.40.2\""""),
        ).joinToString(",") + "]}}"
        assertEquals(
            "IOS: 4 formats (315 vp9 337 vp09.02.51.10.01.09.16.09.00 701 av01.0.13M.10.0.110.09.16.09.0 140 mp4a.40.2); HDR: 337,701",
            PlayerFormats.summary(request, response),
        )
    }

    @Test fun saysNoneWithoutHdrAndCopesWithNoRequest() {
        val response = """{"streamingData":{"adaptiveFormats":[""" + format(313, """video/webm; codecs=\"vp9\"""") + "]}}"
        assertEquals("?: 1 formats (313 vp9); HDR: none", PlayerFormats.summary(null, response))
        assertEquals("?: 0 formats (); HDR: none", PlayerFormats.summary(null, "{}"))
    }

    @Test fun playerRequests() {
        assertEquals(true, PlayerFormats.isPlayerRequest("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false"))
        assertEquals(true, PlayerFormats.isPlayerRequest("https://youtubei.googleapis.com/youtubei/v1/reel/reel_item_watch?x"))
        assertEquals(false, PlayerFormats.isPlayerRequest("https://www.youtube.com/youtubei/v1/next"))
    }
}
