package com.mochyy.mochymusic.floating

import com.mochyy.mochymusic.lyrics.LyricsEntry
import com.mochyy.mochymusic.lyrics.WordTimestamp
import com.mochyy.mochymusic.ui.player.LineReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PillScriptTest {
    private val words = listOf(WordTimestamp("hello", 1.0, 2.0))
    private val syllables = listOf(WordTimestamp("hel", 1.0, 1.5), WordTimestamp("lo", 1.5, 2.0))
    private val original = LyricsEntry(1_000L, "hello", words = words, syllables = syllables)

    @Test fun `original lyrics keep syllable timings with readings enabled`() {
        val script = PillScript.build(
            listOf(original), "Title", "Artist", false,
            PillReadings(listOf(LineReading("reading", null, null)), listOf("translation"), false),
        )
        assertEquals(listOf(PillRowKind.LEAD, PillRowKind.READING, PillRowKind.TRANSLATION), script.rows.take(3).map { it.kind })
        assertEquals(syllables, script.rows.first().words)
        assertEquals(1, script.rows.take(3).map { it.line }.distinct().size)
        assertEquals("hello", script.rows[script.stops[script.stopAt(1_100L)].top].text)
    }

    @Test fun `romanized main keeps original syllables in its reading row`() {
        val readingWords = listOf(WordTimestamp("reading", 1.0, 2.0))
        val script = PillScript.build(
            listOf(original), "Title", "Artist", false,
            PillReadings(listOf(LineReading("reading", readingWords, null)), emptyList(), true),
        )
        assertEquals("reading", script.rows.first().text)
        assertEquals(readingWords, script.rows.first().words)
        assertEquals("hello", script.rows[1].text)
        assertEquals(syllables, script.rows[1].words)
    }

    @Test fun `background syllables survive a following translation row`() {
        val background = original.copy(time = 1_100L, isBackground = true)
        val script = PillScript.build(
            listOf(original, background), "Title", "Artist", false,
            PillReadings(emptyList(), listOf("translation", null), false),
        )
        val backing = script.rows.single { it.kind == PillRowKind.BACKING }
        assertEquals(syllables, backing.words)
        assertEquals(script.rows.first().side, backing.side)
        assertEquals(script.rows.first().line, backing.line)
    }

    @Test fun `translation hosts break without becoming a sung line`() {
        val next = LyricsEntry(20_000L, "next", words = listOf(WordTimestamp("next", 20.0, 21.0)))
        val script = PillScript.build(
            listOf(original, next), "Title", "Artist", false,
            PillReadings(emptyList(), listOf("translation", null), false),
        )
        val stop = script.stopAt(10_000L)
        assertTrue(script.stops[stop].isBreak)
        assertTrue(script.dotsLit(stop, 10_000L) in 1..PillDots)
        assertEquals(PillRowKind.TRANSLATION, script.rows[script.stops[stop].top].kind)
        assertTrue(script.rows[script.stops[stop].top].hostsBreak)
        val nextStop = script.stopAt(20_000L)
        assertFalse(script.stops[nextStop].isBreak)
        assertEquals("next", script.rows[script.stops[nextStop].top].text)
    }

    @Test fun `co-timed bilingual lines preserve primary lead line`() {
        val primary = LyricsEntry(5_000L, "Primary line")
        val secondary = LyricsEntry(5_000L, "Translated line")
        val secondPrimary = LyricsEntry(8_000L, "Second line")
        val script = PillScript.build(listOf(primary, secondary, secondPrimary), "Title", "Artist", false)
        assertEquals("Primary line", script.rows[script.stops[script.stopAt(5_500L)].top].text)
        assertEquals("Second line", script.rows[script.stops[script.stopAt(8_500L)].top].text)
    }

    @Test fun `out-of-order timestamps do not wipe earlier stops`() {
        val first = LyricsEntry(4_000L, "First")
        val second = LyricsEntry(8_000L, "Second")
        val lateZero = LyricsEntry(1_000L, "Intro credit")
        val script = PillScript.build(listOf(first, second, lateZero), "Title", "Artist", false)
        assertEquals("Intro credit", script.rows[script.stops[script.stopAt(1_500L)].top].text)
        assertEquals("First", script.rows[script.stops[script.stopAt(4_500L)].top].text)
        assertEquals("Second", script.rows[script.stops[script.stopAt(8_500L)].top].text)
    }
}
