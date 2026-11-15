package com.mochyy.mochymusic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusGeometryTest {
    @Test fun `width fits even when rotation leaves less than the normal minimum`() {
        assertEquals(80f, StatusGeometry.width(300f, 80f), 0f)
        assertEquals(120f, StatusGeometry.width(10f, 400f), 0f)
        assertEquals(400f, StatusGeometry.width(900f, 400f), 0f)
    }

    @Test fun `content width includes cover gaps grip and padding independently of window width`() {
        assertEquals(132f, StatusGeometry.contentWidthDp(100f, 2f, 30f, 5f, 7f, 32f, 8f), 0f)
    }

    @Test fun `content width handles invalid density without exploding`() {
        assertEquals(82f, StatusGeometry.contentWidthDp(0f, 0f, 30f, 5f, 7f, 32f, 8f), 0f)
    }

    @Test fun `adaptive width grows to content but never exceeds saved maximum or screen`() {
        assertEquals(120f, StatusGeometry.adaptiveWidth(82f, 300f, 400f), 0f)
        assertEquals(172f, StatusGeometry.adaptiveWidth(172f, 300f, 400f), 0f)
        assertEquals(300f, StatusGeometry.adaptiveWidth(900f, 300f, 400f), 0f)
        assertEquals(80f, StatusGeometry.adaptiveWidth(900f, 300f, 80f), 0f)
        // Reusing the same saved maximum after rotation restores the larger limit.
        assertEquals(300f, StatusGeometry.adaptiveWidth(900f, 300f, 400f), 0f)
    }

    @Test fun `animation anchor preserves exact left edge at every odd and even width`() {
        for (screen in listOf(999, 1000)) {
            for (startWidth in listOf(120, 121, 300, 301)) {
                val left = (screen - startWidth) / 2 + 17
                for (width in 120..420) {
                    val x = StatusGeometry.anchoredX(17, startWidth, width, screen)
                    assertEquals(left, (screen - width) / 2 + x)
                    assertEquals(17, StatusGeometry.anchoredX(x, width, startWidth, screen))
                }
            }
        }
    }

    @Test fun `automatic growth near right cutout clamps the whole window`() {
        val anchored = StatusGeometry.anchoredX(360, 120, 300, 1000)
        val clamped = StatusGeometry.centeredX(anchored, 300, 1000, 24, 960)
        val left = (1000 - 300) / 2 + clamped
        assertEquals(960, left + 300)
        assertTrue(left >= 24)
    }

    @Test fun `old zero y stays below system bar and bottom stays above navigation`() {
        assertEquals(48, StatusGeometry.y(0, 38, 48, 750))
        assertEquals(712, StatusGeometry.y(900, 38, 48, 750))
    }

    @Test fun `centered preferences stay centered and respect asymmetric cutouts`() {
        assertEquals(0, StatusGeometry.centeredX(0, 300, 1000, 100, 960))
        assertEquals(-250, StatusGeometry.centeredX(-900, 300, 1000, 100, 960))
        assertEquals(310, StatusGeometry.centeredX(900, 300, 1000, 100, 960))
    }

    @Test fun `grip resize anchors left edge despite centered window gravity`() {
        val screen = 1000
        val startWidth = 300
        val startX = 75
        val width = 420
        val resizedX = StatusGeometry.resizedX(startX, startWidth, width)
        assertEquals((screen - startWidth) / 2 + startX, (screen - width) / 2 + resizedX)
    }

    @Test fun `rotation and resize clamp whole window inside safe bounds`() {
        for (screen in listOf(320, 720, 1080)) {
            val left = 24
            val right = screen - 40
            val width = StatusGeometry.width(900f, (right - left).toFloat()).toInt()
            for (oldX in listOf(-2000, 0, 2000)) {
                val x = StatusGeometry.centeredX(oldX, width, screen, left, right)
                val actualLeft = (screen - width) / 2 + x
                assertTrue(actualLeft >= left)
                assertTrue(actualLeft + width <= right)
            }
        }
    }
}
