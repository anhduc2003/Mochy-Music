package com.mochyy.mochymusic.floating

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusGeometryTest {
    @Test fun `content pivot stays on the anchored side while scaling`() {
        assertEquals(0f, StatusGeometry.anchorPivotX(StatusGeometry.Anchor.LEFT), 0f)
        assertEquals(0.5f, StatusGeometry.anchorPivotX(StatusGeometry.Anchor.CENTER), 0f)
        assertEquals(1f, StatusGeometry.anchorPivotX(StatusGeometry.Anchor.RIGHT), 0f)

        // Scaling about the pivot must leave the anchored edge exactly where it was.
        val target = 300f
        for (anchor in StatusGeometry.Anchor.entries) {
            val pivot = StatusGeometry.anchorPivotX(anchor) * target
            val scale = 0.6f
            val scaledLeft = pivot - pivot * scale
            val scaledRight = scaledLeft + target * scale
            when (anchor) {
                StatusGeometry.Anchor.LEFT -> assertEquals(0f, scaledLeft, 0f)
                StatusGeometry.Anchor.RIGHT -> assertEquals(target, scaledRight, 0f)
                StatusGeometry.Anchor.CENTER -> assertEquals(target / 2, (scaledLeft + scaledRight) / 2, 0f)
            }
        }
    }

    @Test fun `fixed content is centred so resize reveals both ends equally`() {
        val target = 300f
        for (presented in listOf(120f, 149f, 150f, 151f, 260f, 300f, 380f)) {
            val offset = StatusGeometry.centeredContentX(presented, target)
            // Positive means spare room, negative means the capsule clips that much.
            val leftRoom = offset
            val rightRoom = presented - (offset + target)
            assertEquals(leftRoom, rightRoom, 0.001f)
        }
    }

    @Test fun `cover scale follows the capsule within safe bounds`() {
        assertEquals(0.6f, StatusGeometry.capsuleScale(120f, 300f), 0f)
        assertEquals(1f, StatusGeometry.capsuleScale(250f, 250f), 0f)
        assertEquals(1.2f, StatusGeometry.capsuleScale(400f, 200f), 0f)
        assertEquals(1f, StatusGeometry.capsuleScale(100f, 0f), 0f)
    }

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

    @Test fun `snap picks center when close and nearest edge otherwise`() {
        val screen = 1000
        val left = 24
        val right = 960
        val width = 300
        // centered: x=0 means the capsule sits in the middle
        assertEquals(StatusGeometry.Anchor.CENTER, StatusGeometry.snapAnchor(0, width, screen, left, right, 60))
        // pushed well to the left of centre
        assertEquals(StatusGeometry.Anchor.LEFT, StatusGeometry.snapAnchor(-300, width, screen, left, right, 60))
        // pushed well to the right of centre
        assertEquals(StatusGeometry.Anchor.RIGHT, StatusGeometry.snapAnchor(300, width, screen, left, right, 60))
    }

    @Test fun `each anchor lands exactly on its edge or the middle`() {
        val screen = 1080
        val left = 24
        val right = 1056
        for (width in listOf(120, 301, 640)) {
            val origin = (screen - width) / 2
            val startLeft = origin + StatusGeometry.anchorX(StatusGeometry.Anchor.LEFT, width, screen, left, right)
            val startRight = origin + StatusGeometry.anchorX(StatusGeometry.Anchor.RIGHT, width, screen, left, right)
            val center = origin + StatusGeometry.anchorX(StatusGeometry.Anchor.CENTER, width, screen, left, right)
            assertEquals(left, startLeft)
            assertEquals(right, startRight + width)
            assertEquals((left + right - width) / 2, center)
        }
    }

    @Test fun `anchor keeps the edge placed as the lyric width changes`() {
        val screen = 1000
        val left = 20
        val right = 980
        for (anchor in StatusGeometry.Anchor.entries) {
            val narrow = StatusGeometry.anchorX(anchor, 140, screen, left, right)
            val wide = StatusGeometry.anchorX(anchor, 620, screen, left, right)
            val narrowLeft = (screen - 140) / 2 + narrow
            val wideLeft = (screen - 620) / 2 + wide
            when (anchor) {
                StatusGeometry.Anchor.LEFT -> assertEquals(left, wideLeft)
                StatusGeometry.Anchor.RIGHT -> assertEquals(right, wideLeft + 620)
                // growing around the middle: the capsule's centre stays put
                StatusGeometry.Anchor.CENTER -> assertEquals(narrowLeft + 70, wideLeft + 310)
            }
        }
    }

    @Test fun `center anchor stays symmetric throughout resizing with asymmetric insets`() {
        val screen = 1081
        val left = 61
        val right = 1040
        // Include every integer width used by animation, in both directions.
        for (width in (120..979).toList() + (979 downTo 120).toList()) {
            val x = StatusGeometry.anchorX(StatusGeometry.Anchor.CENTER, width, screen, left, right)
            val clamped = StatusGeometry.centeredX(x, width, screen, left, right)
            val actualLeft = (screen - width) / 2 + clamped
            val actualRight = actualLeft + width
            // Odd widths can put the centre half a pixel from the safe-area centre.
            assertTrue(kotlin.math.abs(actualLeft + actualRight - left - right) <= 1)
        }
    }

    @Test fun `vertical fraction survives rotation and stays on screen`() {
        val fraction = StatusGeometry.verticalFraction(500, 38, 48, 1000)
        // same share of the new travel, still inside the bounds
        val rotated = StatusGeometry.anchoredY(fraction, 38, 0, 400)
        assertTrue(rotated >= 0)
        assertTrue(rotated + 38 <= 400)
        assertEquals(48, StatusGeometry.anchoredY(0, 38, 48, 1000))
        assertEquals(1000 - 38, StatusGeometry.anchoredY(1000, 38, 48, 1000))
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
