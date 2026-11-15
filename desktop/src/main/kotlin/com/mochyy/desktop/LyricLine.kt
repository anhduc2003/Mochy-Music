package com.mochyy.desktop

/**
 * One line of lyrics and when it starts.
 *
 * The Android build's `LyricsEntry` also carries word timings, ruby tokens, translation and
 * romanisation flows — the machinery for the karaoke renderer. This is the line-level shape the
 * PC window draws: text, start, end. The richer entry type is already copied into
 * `com.mochyy.desktop.lyrics` for when the karaoke view needs it.
 */
data class LyricLine(
    val text: String,
    val startMs: Long,
    val endMs: Long,
) {
    /** which line is being sung at [positionMs], or -1 before the first */
    companion object {
        private val Stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

        fun parse(text: String?): List<LyricLine> {
            if (text.isNullOrBlank()) return emptyList()
            val lines = ArrayList<LyricLine>()
            for (raw in text.lineSequence()) {
                val stamps = Stamp.findAll(raw).toList()
                if (stamps.isEmpty()) continue
                val words = raw.substringAfterLast(']').trim()
                // a stamp with nothing after it is metadata, not a lyric
                if (words.isEmpty()) continue
                for (stamp in stamps) {
                    val minutes = stamp.groupValues[1].toLongOrNull() ?: continue
                    val seconds = stamp.groupValues[2].toLongOrNull() ?: continue
                    val fraction = stamp.groupValues[3]
                    val millis = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        else -> fraction.take(3).toLong()
                    }
                    lines += LyricLine(words, minutes * 60_000 + seconds * 1_000 + millis, -1L)
                }
            }
            // sort by time, then give each line an end from the next one it has
            val sorted = lines.sortedBy { it.startMs }
            return sorted.mapIndexed { index, line ->
                val next = sorted.getOrNull(index + 1)?.startMs ?: (line.startMs + 4_000L)
                line.copy(endMs = next)
            }
        }

        fun indexAt(lines: List<LyricLine>, positionMs: Long): Int =
            lines.indexOfLast { it.startMs <= positionMs }
    }
}
