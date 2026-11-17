package com.mochyy.mochymusic.floating

/** Pure pixel geometry. Stored x remains an offset from the screen's horizontal center. */
internal object StatusGeometry {
    const val HandleWidthDp = 32f
    // A two-line capsule (song + lyric, beside the cover) needs room to stay legible.
    const val CapsuleHeightDp = 64f
    const val MinCapsuleWidthDp = 200f

    enum class Anchor { LEFT, CENTER, RIGHT }

    /**
     * Fraction of a composable's width that stays put while the capsule scales.
     * A left-anchored pill grows and shrinks from its left edge, a right-anchored one
     * from its right edge, and a centred one from its middle.
     */
    fun anchorPivotX(anchor: Anchor): Float = when (anchor) {
        Anchor.LEFT -> 0f
        Anchor.CENTER -> 0.5f
        Anchor.RIGHT -> 1f
    }

    fun snapAnchor(x: Int, width: Int, screenWidth: Int, left: Int, right: Int, threshold: Int): Anchor {
        val center = (screenWidth - width) / 2 + x + width / 2f
        val safeCenter = (left + right) / 2f
        return when {
            kotlin.math.abs(center - safeCenter) <= threshold.coerceAtLeast(0) -> Anchor.CENTER
            center < safeCenter -> Anchor.LEFT
            else -> Anchor.RIGHT
        }
    }

    fun anchorX(anchor: Anchor, width: Int, screenWidth: Int, left: Int, right: Int): Int {
        val origin = (screenWidth - width) / 2
        val target = when (anchor) {
            Anchor.LEFT -> left
            Anchor.CENTER -> left + (right - left - width) / 2
            Anchor.RIGHT -> right - width
        }
        return centeredX(target - origin, width, screenWidth, left, right)
    }

    fun verticalFraction(y: Int, height: Int, top: Int, bottom: Int): Int {
        val travel = (bottom - top - height).coerceAtLeast(0)
        return if (travel == 0) 0 else (((y - top).toLong() * 1000 / travel).toInt()).coerceIn(0, 1000)
    }

    fun anchoredY(fraction: Int, height: Int, top: Int, bottom: Int): Int =
        top + ((bottom - top - height).coerceAtLeast(0).toLong() * fraction.coerceIn(0, 1000) / 1000).toInt()

    fun width(requested: Float, available: Float): Float {
        val maximum = available.coerceAtLeast(1f)
        return requested.coerceIn(minOf(MinCapsuleWidthDp, maximum), maximum)
    }

    /** Window width needed for one measured line, before clamping to the user's maximum. */
    fun contentWidthDp(
        textWidthPx: Float,
        density: Float,
        coverDp: Float,
        edgeDp: Float,
        gapDp: Float,
        handleDp: Float,
        paddingDp: Float = 0f,
    ): Float = (textWidthPx / density.coerceAtLeast(0.1f)) + coverDp + edgeDp + gapDp + handleDp + paddingDp

    fun adaptiveWidth(contentDp: Float, maximumDp: Float, availableDp: Float): Float =
        width(contentDp, minOf(maximumDp, availableDp))

    // Use the actual integer gravity origins, avoiding one-pixel drift at odd widths.
    fun anchoredX(startX: Int, startWidth: Int, width: Int, screenWidth: Int): Int =
        startX + (screenWidth - startWidth) / 2 - (screenWidth - width) / 2

    fun centeredX(x: Int, width: Int, screenWidth: Int, left: Int, right: Int): Int {
        val origin = (screenWidth - width) / 2
        return x.coerceIn(left - origin, (right - width - origin).coerceAtLeast(left - origin))
    }

    fun y(y: Int, height: Int, top: Int, bottom: Int): Int =
        y.coerceIn(top, (bottom - height).coerceAtLeast(top))

    // Keeping the left edge fixed means the centered offset moves by half the width change.
    fun resizedX(startX: Int, startWidth: Int, width: Int): Int = startX + (width - startWidth) / 2
}
