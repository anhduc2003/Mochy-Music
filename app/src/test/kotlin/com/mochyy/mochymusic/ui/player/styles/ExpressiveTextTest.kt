package com.mochyy.mochymusic.ui.player.styles

import com.mochyy.mochymusic.constants.LyricsMotionStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveTextTest {
    @Test
    fun `neon glows and mask does not while both use accent fill`() {
        assertTrue(LyricsMotionStyle.NEON.accentFill)
        assertTrue(LyricsMotionStyle.NEON.shaded)
        assertTrue(LyricsMotionStyle.MASK.accentFill)
        assertFalse(LyricsMotionStyle.MASK.shaded)
    }

    @Test
    fun `a joined word uses its local origin rather than the RTL caret at its right edge`() {
        // A single Arabic word is kept intact so its glyphs remain joined. The measured caret at
        // logical offset zero is at the right edge; it must not become the word's drawing origin.
        assertArrayEquals(floatArrayOf(0f), wordLetterOffsets(pieceCount = 1) { 48f }, 0f)
    }

    @Test
    fun `split letters keep their measured positions within the word`() {
        val measured = floatArrayOf(0f, 12f, 27f)
        assertArrayEquals(
            measured,
            wordLetterOffsets(pieceCount = measured.size) { measured[it] },
            0f,
        )
    }
}
