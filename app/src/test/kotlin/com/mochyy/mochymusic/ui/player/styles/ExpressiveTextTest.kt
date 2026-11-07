package com.mochyy.mochymusic.ui.player.styles

import com.mochyy.mochymusic.constants.LyricsMotionStyle
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpressiveTextTest {
    @Test
    fun `neon and mask sweep with bright white while only neon glows`() {
        val base = Color(0xFFF1ECEC)
        assertTrue(LyricsMotionStyle.NEON.accentFill)
        assertTrue(LyricsMotionStyle.NEON.shaded)
        assertTrue(LyricsMotionStyle.MASK.accentFill)
        assertFalse(LyricsMotionStyle.MASK.shaded)
        assertEquals(Color.White, lyricFillColor(LyricsMotionStyle.NEON, base))
        assertEquals(Color.White, lyricFillColor(LyricsMotionStyle.MASK, base))
        assertEquals(base, lyricFillColor(LyricsMotionStyle.MOTION, base))
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
