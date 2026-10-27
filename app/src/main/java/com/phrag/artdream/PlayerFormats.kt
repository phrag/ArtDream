package com.phrag.artdream

/**
 * A one-line summary of a YouTube player response, logged by [NewPipeDownloader] for every client
 * NewPipe asks, so the log shows which formats YouTube offered before NewPipe filtered them
 * (it drops itags it doesn't know). Pure, so it is unit-tested.
 */
object PlayerFormats {

    private val CLIENT = Regex("\"clientName\"\\s*:\\s*\"([A-Z0-9_]+)\"")
    private val FORMAT = Regex("\"itag\"\\s*:\\s*(\\d+)[^{}]*?\"mimeType\"\\s*:\\s*\"(?:video|audio)/[a-z0-9]+;\\s*codecs=\\\\\"([^\\\\\"]+)\\\\\"\"")

    /** YouTube's HDR itags: VP9 profile 2 (330–337) and AV1 HDR (694–702). */
    fun isHdrItag(itag: Int) = itag in 330..337 || itag in 694..702

    /** True for the requests that return a player response (the ones with formats). */
    fun isPlayerRequest(url: String) = "/youtubei/v1/player" in url || "/reel/reel_item_watch" in url

    /**
     * "ANDROID: 24 formats (313 vp9, 337 vp09.02.51.10.01.09.16.09.00 …); HDR: 337,336" or with
     * "HDR: none". [request] is the JSON sent (for the client name), [response] the JSON answer.
     */
    fun summary(request: String?, response: String): String {
        val client = request?.let { CLIENT.find(it)?.groupValues?.get(1) } ?: "?"
        val formats = FORMAT.findAll(response).map { it.groupValues[1].toInt() to it.groupValues[2] }
            .distinctBy { it.first }.toList()
        val hdr = formats.map { it.first }.filter(::isHdrItag)
        return "$client: ${formats.size} formats (" + formats.joinToString(" ") { "${it.first} ${it.second}" } +
            "); HDR: " + (if (hdr.isEmpty()) "none" else hdr.joinToString(","))
    }
}
