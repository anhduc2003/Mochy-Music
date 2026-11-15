package com.mochyy.desktop.lyrics

/**
 * Where a song's lyrics can come from on the PC.
 *
 * The Android build's `LyricsProviderRegistry` wires fourteen providers, each of which is a small
 * class over an HTTP API in a module under `providers/`. Those modules genuinely do compile off
 * Android (154 files, 13k lines, only a handful of android imports), so they are the next thing to
 * bring over — but bringing them over needs their ktor engine swapped from OkHttp-for-Android, and
 * that is a separate step from having somewhere to put the result.
 *
 * So: the shape first. A provider hands back LRC text; the parser and the renderer do not care
 * where it came from.
 */
interface LyricsSource {
    val name: String
    /** true when this source can answer for a track at all */
    fun canServe(track: com.mochyy.desktop.model.Track): Boolean
    /** the LRC text, or null when this source has nothing */
    suspend fun lyricsFor(track: com.mochyy.desktop.model.Track): String?
}

/**
 * The lyrics that already sit inside the file, which is the one source that needs no network and
 * no provider: a lot of downloaded tracks carry their own time-stamped words. Reads tags only,
 * never the audio.
 */
class EmbeddedLyricsSource : LyricsSource {
    override val name = "Trong tệp"

    override fun canServe(track: com.mochyy.desktop.model.Track): Boolean =
        !track.uri.startsWith("http://") && !track.uri.startsWith("https://")

    override suspend fun lyricsFor(track: com.mochyy.desktop.model.Track): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val file = java.io.File(track.uri.removePrefix("file:"))
                if (!file.isFile) return@runCatching null
                java.io.RandomAccessFile(file, "r").use { raf ->
                    EmbeddedLyrics.find(object : EmbeddedLyrics.Source {
                        override val size: Long get() = raf.length()

                        override fun read(position: Long, length: Int): ByteArray {
                            if (position >= raf.length()) return ByteArray(0)
                            val want = minOf(length.toLong(), raf.length() - position).toInt()
                            val bytes = ByteArray(want)
                            raf.seek(position)
                            raf.readFully(bytes)
                            return bytes
                        }
                    })
                }
            }.getOrNull()
        }
}
