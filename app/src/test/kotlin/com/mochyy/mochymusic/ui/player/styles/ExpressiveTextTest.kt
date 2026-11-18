package com.mochyy.mochymusic.ui.player.styles

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ExpressiveTextTest {
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
