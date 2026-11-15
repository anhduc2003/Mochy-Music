package com.mochyy.desktop

import com.mochyy.desktop.model.Track
import org.jaudiotagger.audio.AudioFileIO
import java.io.File

/**
 * Reads what a file says about itself.
 *
 * Tags first, because they are what the user named the song. When a file has none, the file name
 * is the fallback and is split on the "Artist - Title" shape that scene releases and most
 * downloaded Vietnamese tracks use, rather than showing a bare filename.
 */
object TrackReader {

    fun read(file: File): Track? {
        val tags = runCatching { AudioFileIO.read(file) }.getOrNull()
        val audio = tags?.audioHeader
        val durationMs = runCatching { (audio?.trackLength ?: 0) * 1000L }.getOrDefault(0L)

        val title = tags?.tag?.getFirst("TITLE")?.trim()?.takeIf { it.isNotEmpty() }
        val artist = tags?.tag?.getFirst("ARTIST")?.trim()?.takeIf { it.isNotEmpty() }
        val album = tags?.tag?.getFirst("ALBUM")?.trim()?.takeIf { it.isNotEmpty() }

        val baseName = file.nameWithoutExtension
        val fallback = splitFileName(baseName)

        return Track(
            id = file.absolutePath,
            title = title ?: fallback.second,
            artist = artist ?: fallback.first,
            album = album,
            durationMs = durationMs,
            uri = file.absolutePath,
        )
    }

    /** "Artist - Title" when a file carries no tags; otherwise the whole name as the title */
    internal fun splitFileName(name: String): Pair<String, String> {
        val dash = name.indexOf(" - ")
        if (dash <= 0) return "" to name.trim()
        val artist = name.substring(0, dash).trim()
        val title = name.substring(dash + 3).trim()
        return if (artist.isEmpty() || title.isEmpty()) "" to name.trim() else artist to title
    }
}
