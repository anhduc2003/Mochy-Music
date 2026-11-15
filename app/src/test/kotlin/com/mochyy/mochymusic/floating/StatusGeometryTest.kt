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
