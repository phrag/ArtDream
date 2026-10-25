package com.phrag.artdream

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File

/**
 * Pictures and videos the user added themselves (uploaded over the LAN, pushed with adb, or
 * saved from a YouTube link), kept in filesDir/own. Deliberately separate from the museum
 * cache ([ArtCache]): nothing here is trimmed to "Library size", counted as a download, or
 * hit by the Landscape-only filter. Only the user removes these.
 */
class OwnMedia(ctx: Context) {

    class Item(val key: String, val file: File, val kind: Kind) {
        val video: Boolean get() = kind == Kind.VIDEO
        /** A YouTube link (".yt" file) played as a live stream, not a file on the TV. */
        val youtube: Boolean get() = kind == Kind.YOUTUBE
        val addedAt: Long get() = file.lastModified()
        val bytes: Long get() = file.length()
        /** What the list shows: the file name without its extension. */
        val label: String get() = file.nameWithoutExtension
    }

    /** Why a file was refused; the message is shown to the person who added it. */
    class Rejected(message: String) : Exception(message)

    private val dir = File(ctx.filesDir, "own").apply { mkdirs() }

    /** App-specific external folder: `adb push` works here with no storage permission. */
    val importDir: File? = ctx.getExternalFilesDir("media")?.apply { mkdirs() }

    fun items(): List<Item> =
        (dir.listFiles { f -> f.isFile && kindOf(f.name) != null } ?: emptyArray())
            .map { Item(it.name, it, kindOf(it.name)!!) }
            .sortedByDescending { it.addedAt }

    /** The same items as slideshow entries (the slideshow only knows [ArtCache.Entry]). */
    fun entries(): List<ArtCache.Entry> = items().map { entry(it) }

    fun entry(item: Item) = ArtCache.Entry(
        Artwork(source = SOURCE, id = item.key, title = "", artist = "", date = "", imageUrls = emptyList()),
        item.file,
    )

    fun usedBytes(): Long = items().sumOf { it.bytes }

    /** Room for [size] more bytes without crossing our quota or leaving the TV short of space. */
    fun hasRoomFor(size: Long): Boolean =
        usedBytes() + size <= QUOTA_BYTES && dir.usableSpace - size >= MIN_FREE_BYTES

    /**
     * Checks [file] (already fully written, e.g. a ".part") really is the [name] it claims to be
     * and moves it into the store. Throws [Rejected] otherwise; the caller deletes [file] then.
     */
    fun add(file: File, name: String): Item {
        val clean = sanitizeName(name) ?: throw Rejected("Not a picture or video file: $name")
        val kind = kindOf(clean)!!
        // The file is already on disk, so only the quota (not free space) is left to check.
        if (usedBytes() + file.length() > QUOTA_BYTES) {
            throw Rejected("That would go over the ${QUOTA_BYTES / (1024 * 1024 * 1024)} GB limit for your own media")
        }
        when (kind) {
            Kind.IMAGE -> {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, b)
                if (b.outWidth <= 0 || b.outHeight <= 0) throw Rejected("$clean is not a picture the TV can open")
            }
            Kind.VIDEO -> checkVideo(file, clean)
            Kind.YOUTUBE -> if (YouTube.readLink(file.readText()) == null) throw Rejected("$clean is not a YouTube link file")
        }
        val target = uniqueFile(clean)
        if (!file.renameTo(target) && !(file.copyTo(target, overwrite = false).exists() && file.delete())) {
            throw Rejected("Couldn't save $clean")
        }
        return Item(target.name, target, kind)
    }

    /** The item already playing this YouTube video, if any (one link per video). */
    fun findYouTube(id: String): Item? = items().firstOrNull { it.youtube && youtubeId(it.file) == id }

    /** Adds a link to [link]'s video, or returns the one already there. */
    fun addLink(link: YouTube.Link): Item {
        findYouTube(link.id)?.let { return it }
        if (!hasRoomFor(0)) throw Rejected("Out of space for your own media")
        val target = uniqueFile(YouTube.fileName(link))
        target.writeText(link.toJson())
        return Item(target.name, target, Kind.YOUTUBE)
    }

    private fun checkVideo(file: File, name: String) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (!hasVideo || ms <= 0) throw Rejected("$name has no playable video")
        } catch (e: Rejected) {
            throw e
        } catch (e: Exception) {
            throw Rejected("$name is not a video the TV can open")
        } finally {
            runCatching { r.release() }
        }
    }

    private fun uniqueFile(name: String): File {
        var f = File(dir, name)
        var n = 2
        val base = f.nameWithoutExtension
        val ext = f.extension
        while (f.exists()) f = File(dir, "$base ($n).$ext").also { n++ }
        return f
    }

    fun remove(key: String): Boolean = File(dir, key).takeIf { it.parentFile == dir }?.delete() ?: false

    /**
     * Takes anything left in [importDir] (e.g. from `adb push`) into the store; files that
     * aren't usable are left where they are. Returns how many were added.
     */
    fun importPending(): Int {
        var added = 0
        for (f in pendingFiles()) {
            try {
                add(f, f.name)
                added++
            } catch (e: Rejected) {
                Log.w(TAG, "import ${f.name}: ${e.message}")
                // Park it so the next start doesn't check (and complain about) it again.
                val rejected = File(importDir, "rejected").apply { mkdirs() }
                f.renameTo(File(rejected, f.name))
            }
        }
        return added
    }

    /** Cheap check (a directory listing) for whether [importPending] has anything to do. */
    fun hasPending(): Boolean = pendingFiles().isNotEmpty()

    private fun pendingFiles(): List<File> =
        importDir?.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.toList().orEmpty()

    /** A scratch file for an upload in progress; never a playable name, so [items] ignores it. */
    fun newPart(): File = File(dir, ".upload-${System.nanoTime()}.part")

    fun clearStaleParts() {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }

    enum class Kind { IMAGE, VIDEO, YOUTUBE }

    companion object {
        private const val TAG = "ArtDream"
        const val SOURCE = "mine"
        /** Everything the user adds, together. */
        const val QUOTA_BYTES = 3L * 1024 * 1024 * 1024
        /** Never fill the TV's storage: stop accepting below this much free space. */
        const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024
        /** Per file. A 4K clip of a few minutes fits; a feature film doesn't. */
        const val MAX_FILE_BYTES = 1L * 1024 * 1024 * 1024

        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
        private val VIDEO_EXT = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp", "ts")

        fun kindOf(name: String): Kind? = when (name.substringAfterLast('.', "").lowercase()) {
            in IMAGE_EXT -> Kind.IMAGE
            in VIDEO_EXT -> Kind.VIDEO
            "yt" -> Kind.YOUTUBE
            else -> null
        }

        fun isVideo(entry: ArtCache.Entry): Boolean =
            entry.art.source == SOURCE && kindOf(entry.image.name) == Kind.VIDEO

        fun isYouTube(entry: ArtCache.Entry): Boolean =
            entry.art.source == SOURCE && kindOf(entry.image.name) == Kind.YOUTUBE

        /** The video id inside a ".yt" file, or null if the file is unreadable or invalid. */
        fun youtubeId(file: File): String? = try { YouTube.readLink(file.readText())?.id } catch (_: Exception) { null }

        /**
         * A safe file name from whatever the sender called it: no folders or "..", only plain
         * characters, bounded length, lower-case known extension. Null if it isn't a picture
         * or video type we play.
         */
        fun sanitizeName(raw: String): String? {
            val leaf = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            val ext = leaf.substringAfterLast('.', "").lowercase()
            if (kindOf("x.$ext") == null) return null
            var stem = leaf.substringBeforeLast('.', leaf)
                .replace(Regex("[^A-Za-z0-9 _()-]"), "_")
                .replace(Regex("_{2,}"), "_")
                .trim(' ', '_', '.')
            if (stem.isEmpty()) stem = "media"
            return stem.take(80).trimEnd(' ', '_') + "." + ext
        }
    }
}
