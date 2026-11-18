package com.mochyy.mochymusic.lyrics

import com.mochyy.mochymusic.constants.LyricsMotionStyle
import com.mochyy.mochymusic.extensions.toEnum
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsMotionStyleTest {
    @Test
    fun `only the four supported lyrics styles are available`() {
        assertEquals(listOf("MOTION", "LEGACY", "BORING", "PLAIN"), LyricsMotionStyle.entries.map { it.name })
    }

    @Test
    fun `removed and unrecognized saved styles fall back to motion`() {
        for (stored in listOf("NEON", "MASK", "UNKNOWN", "", null)) {
            assertEquals(LyricsMotionStyle.MOTION, stored.toEnum(LyricsMotionStyle.MOTION))
        }
    }

    @Test
    fun `saved supported styles retain their selection`() {
        for (style in LyricsMotionStyle.entries) {
            assertEquals(style, style.name.toEnum(LyricsMotionStyle.MOTION))
        }
    }
}
