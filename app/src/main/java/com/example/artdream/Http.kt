package com.example.artdream

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Tiny blocking HTTP helper. Call off the main thread. */
object Http {
    // Museums ask API clients to identify themselves; AIC reads its own header.
    private const val UA = "ArtDream/0.1 (Android TV screensaver)"

    fun getString(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, dest: File) {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode} for $url")
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("AIC-User-Agent", UA)
        }
}
