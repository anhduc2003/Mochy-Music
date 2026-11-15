package com.mochyy.mochymusic.floating

/** Pure pixel geometry. Stored x remains an offset from the screen's horizontal center. */
internal object StatusGeometry {
    const val HandleWidthDp = 32f

    fun width(requested: Float, available: Float): Float {
        val maximum = available.coerceAtLeast(1f)
        return requested.coerceIn(minOf(120f, maximum), maximum)
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
