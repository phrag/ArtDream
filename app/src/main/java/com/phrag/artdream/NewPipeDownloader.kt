package com.phrag.artdream

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/**
 * What NewPipeExtractor needs to talk to the network: one blocking request, answered with the
 * status, headers and body. Only used by [YouTubeDownloads].
 */
class NewPipeDownloader : Downloader() {

    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.httpMethod()
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            // The extractor sets its own headers (language, content type, client hints); they win.
            for ((name, values) in request.headers()) {
                conn.setRequestProperty(name, null)
                values.forEach { conn.addRequestProperty(name, it) }
            }
            request.dataToSend()?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == 429) throw ReCaptchaException("YouTube is asking for a captcha (HTTP 429)", request.url())
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = conn.headerFields.filterKeys { it != null }
            return Response(code, conn.responseMessage.orEmpty(), headers, body, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** The same desktop-browser identity NewPipe itself uses. */
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
