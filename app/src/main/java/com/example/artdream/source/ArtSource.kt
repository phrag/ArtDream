package com.example.artdream.source

import com.example.artdream.Artwork
import kotlin.random.Random

/**
 * A museum open-access API. Every source must only return works its museum has
 * released as public domain / CC0 — that's what makes redistribution on a TV OK.
 * Implementations block on network I/O; call off the main thread.
 */
interface ArtSource {
    val id: String
    val label: String
    fun fetchBatch(count: Int, rnd: Random): List<Artwork>

    companion object {
        val ALL: List<ArtSource> = listOf(ClevelandSource, MetSource, ArticSource)
    }
}
