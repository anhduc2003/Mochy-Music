package com.mochyy.desktop.crossmix

/**
 * The little of `LyricsUtils` the crossmix planner actually needs: "is this text time-stamped, and
 * where does each line start".
 *
 * The Android build calls `LyricsUtils.parseLyrics`, which is 82k of tokenising, transliteration,
 * ruby and translation handling behind one entry point. The planner uses it for one thing — to
 * know where the voice is, so it can avoid overlapping two sung lines — and that only needs the
 * `[mm:ss.xx]` stamps. Copying 82k of text processing to get them would drag kuromoji, pinyin and
 * a Room entity into the PC build for no gain, so this reads the stamps directly.
 *
 * The output shape matches what the planner expects: `time` in milliseconds.
 */
internal object LyricsStamp {

    data class Line(val time: Long)

    /** `[01:23]`, `[01:23.45]`, `[1:23.456]` — the forms LRC files and providers actually use */
    private val Stamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    fun hasStamps(lyrics: String?): Boolean =
        !lyrics.isNullOrBlank() && Stamp.containsMatchIn(lyrics)

    /**
     * One entry per stamped line, in file order. A line with several stamps (a repeated chorus)
     * yields one entry per stamp, because that is how many times it is sung.
     */
    fun parse(lyrics: String?): List<Line> {
        if (lyrics.isNullOrBlank()) return emptyList()
        val out = ArrayList<Line>()
        for (raw in lyrics.lineSequence()) {
            val stamps = Stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            // only a line that says something after its stamps is a lyric line, not metadata
            if (raw.substringAfterLast(']').isBlank()) continue
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
                out += Line(minutes * 60_000 + seconds * 1_000 + millis)
            }
        }
        return out
    }
}
