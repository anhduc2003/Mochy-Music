// StatusWindow.kt

package com.mochyy.mochymusic.floating

import android.animation.ValueAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

// the narrowest the capsule may get, and the room kept around it when it grows
private const val EdgeRoomDp = 12f

// what the window tells the service about, so where it was left and how wide it was come back next time
internal interface StatusWindowListener {
    fun onMoved(x: Int, y: Int, anchor: StatusGeometry.Anchor, verticalFraction: Int)
    fun onResized(widthDp: Int)
}

// The capsule starts below system UI so Android can deliver its touches. Stored x is still
// center-relative; drag and resize use screen coordinates so window movement cannot feed back.
internal class StatusWindow(
    private val context: Context,
    savedX: Int?,
    savedY: Int?,
    savedWidthDp: Int?,
    private val listener: StatusWindowListener,
    private val savedAnchor: String? = null,
    savedVerticalFraction: Int? = null,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density get() = context.resources.displayMetrics.density
    private val owner = StatusOwner()
    private val root = DragFrame()
    private val compose = ComposeView(context)

    // Only user resizing changes this preference. Rotation and automatic sizing merely
    // constrain its current presentation, so returning to a wider screen restores it.
    private var maximumWidthDp = (savedWidthDp?.toFloat() ?: defaultWidthDp()).coerceAtLeast(120f)
    var fittingWidthDp by mutableFloatStateOf(StatusGeometry.width(maximumWidthDp, maxWidthDp()))
        private set
    private var contentWidthDp: Float? = null
    private var widthAnimator: ValueAnimator? = null
    private var touching = false
    private var removed = false
    private var targetWidthPx = 0
    private val heightDp = 38f
    var expanded by mutableStateOf(false)
        private set
    private var anchor = StatusGeometry.Anchor.entries.firstOrNull { it.name == savedAnchor }
        ?: StatusGeometry.Anchor.CENTER
    private var verticalFraction = savedVerticalFraction?.coerceIn(0, 1000)
    private var targetHeightPx = 0

    fun changeExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        adaptWidth()
    }

    private val params = WindowManager.LayoutParams(
        px(StatusGeometry.width(maximumWidthDp, maxWidthDp())).coerceAtLeast(1),
        px(heightDp),
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // Old y=0 preferences are clamped below the status bar when shown.
        x = savedX ?: 0
        y = savedY ?: safeBounds().top
        title = "Floating lyrics"
    }

    // false when the system refused the window, the permission was taken back most likely
    fun show(content: @Composable () -> Unit): Boolean {
        val bounds = placementBounds()
        // Migrate the old free position to the nearest stable anchor once.
        if (savedAnchor == null) anchor = StatusGeometry.snapAnchor(params.x, params.width, screenSize().x, bounds.left, bounds.right, px(36f))
        if (verticalFraction == null) verticalFraction = StatusGeometry.verticalFraction(params.y, params.height, bounds.top, bounds.bottom)
        applyAnchor()
        clampInto(params)
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        compose.setContent(content)
        root.addView(compose, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        owner.start()
        return runCatching { windowManager.addView(root, params) }
            .onFailure { owner.destroy() }
            .isSuccess
    }

    fun remove() {
        removed = true
        cancelWidthAnimation()
        owner.destroy()
        runCatching { windowManager.removeView(root) }
    }

    fun setContentWidth(widthDp: Float) {
        if (!widthDp.isFinite() || removed) return
        contentWidthDp = widthDp
        adaptWidth()
    }

    private fun cancelWidthAnimation() {
        widthAnimator?.cancel()
        widthAnimator?.removeAllUpdateListeners()
        widthAnimator = null
        targetWidthPx = 0
    }

    private fun adaptWidth(animate: Boolean = true) {
        if (touching || removed) return
        fittingWidthDp = StatusGeometry.width(maximumWidthDp, maxWidthDp())
        val target = px(if (expanded) minOf(280f, maxWidthDp()) else StatusGeometry.adaptiveWidth(
            contentWidthDp ?: maximumWidthDp, maximumWidthDp, maxWidthDp(),
        )).coerceAtLeast(1)
        val panelHeight = if (maxWidthDp() < 240f) 144f else 96f
        val targetHeight = minOf(px(if (expanded) panelHeight else heightDp), placementBounds().height().coerceAtLeast(1))
        if (target == targetWidthPx && targetHeight == targetHeightPx && widthAnimator?.isRunning == true) return
        cancelWidthAnimation()
        val startWidth = params.width
        val startHeight = params.height
        val startX = params.x
        val startY = params.y
        val bounds = placementBounds()
        val endX = StatusGeometry.anchorX(anchor, target, screenSize().x, bounds.left, bounds.right)
        val endY = StatusGeometry.anchoredY(verticalFraction ?: 0, targetHeight, bounds.top, bounds.bottom)
        fun applySize(fraction: Float) {
            params.width = (startWidth + (target - startWidth) * fraction).roundToInt()
            params.height = (startHeight + (targetHeight - startHeight) * fraction).roundToInt()
            params.x = (startX + (endX - startX) * fraction).roundToInt()
            params.y = (startY + (endY - startY) * fraction).roundToInt()
            clampInto(params)
            update()
        }
        if (!animate || !root.isAttachedToWindow) {
            applySize(1f)
            return
        }
        if (target == startWidth && targetHeight == startHeight && startX == endX && startY == endY) return
        targetWidthPx = target
        targetHeightPx = targetHeight
        widthAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { applySize(it.animatedValue as Float) }
            start()
        }
    }

    // Request a matching-resolution high-refresh mode; the system still decides the actual rate.
    @Suppress("DEPRECATION")
    fun setHighRefreshRate(enabled: Boolean) {
        val display = root.display ?: windowManager.defaultDisplay
        val current = display.mode
        val mode = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { it.refreshRate <= 120.5f }
            .maxByOrNull { it.refreshRate }
        params.preferredDisplayModeId = if (enabled) mode?.modeId ?: 0 else 0
        params.preferredRefreshRate = if (enabled) mode?.refreshRate ?: 0f else 0f
        update()
    }

    private fun update() {
        if (root.isAttachedToWindow) runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun persistPosition() {
        listener.onMoved(params.x, params.y, anchor, verticalFraction ?: 0)
    }

    private fun placementBounds(): Rect = safeBounds().apply {
        val margin = minOf(px(EdgeRoomDp), (width() / 2 - 1).coerceAtLeast(0), (height() / 2 - 1).coerceAtLeast(0))
        inset(margin, margin)
    }

    private fun applyAnchor() {
        val bounds = placementBounds()
        params.x = StatusGeometry.anchorX(anchor, params.width, screenSize().x, bounds.left, bounds.right)
        params.y = StatusGeometry.anchoredY(verticalFraction ?: 0, params.height, bounds.top, bounds.bottom)
    }

    private fun clampInto(lp: WindowManager.LayoutParams) {
        val bounds = placementBounds()
        lp.x = StatusGeometry.centeredX(lp.x, lp.width, screenSize().x, bounds.left, bounds.right)
        lp.y = StatusGeometry.y(lp.y, lp.height, bounds.top, bounds.bottom)
    }

    private fun defaultWidthDp(): Float = min((screenSize().x / density) - 40f, 300f)

    private fun maxWidthDp(): Float = safeBounds().width() / density - EdgeRoomDp * 2f

    private fun screenSize(): Point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        Point(bounds.width(), bounds.height())
    } else {
        Point().also {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(it)
        }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun safeBounds(): Rect {
        val screen = screenSize()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(insets.left, insets.top, screen.x - insets.right, screen.y - insets.bottom)
        }
        fun inset(name: String, fallback: Int): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) context.resources.getDimensionPixelSize(id) else fallback
        }
        val top = inset("status_bar_height", px(24f))
        val nav = inset("navigation_bar_height", px(48f))
        // Legacy devices may place navigation on either side in landscape. Reserve both.
        val landscape = screen.x > screen.y
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) root.rootWindowInsets?.displayCutout else null
        return Rect(
            maxOf(if (landscape) nav else 0, cutout?.safeInsetLeft ?: 0),
            maxOf(top, cutout?.safeInsetTop ?: 0),
            screen.x - maxOf(if (landscape) nav else 0, cutout?.safeInsetRight ?: 0),
            screen.y - maxOf(if (landscape) 0 else nav, cutout?.safeInsetBottom ?: 0),
        )
    }

    private fun px(value: Float): Int = (value * density).roundToInt()

    // The entire stream is consumed even over noninteractive lyrics. Track IDs, not indices:
    // lifting either pinch finger rebases onto the survivors without a jump.
    private inner class DragFrame : FrameLayout(context) {
        private val slop get() = ViewConfiguration.get(context).scaledTouchSlop
        private var firstId = -1
        private var secondId = -1
        private var downX = 0f
        private var downY = 0f
        private var fromX = 0
        private var fromY = 0
        private var grip = false
        private var dragging = false
        private var changedWidth = false
        private var changedPosition = false
        private var pinchStart = 0f
        private var widthStart = 0f
        private var pixelWidthStart = 0
        private var childStream = false
        private var multiTouch = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                changeExpanded(false)
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    cancelWidthAnimation()
                    // Layout may still be catching up with the last animation frame.
                    if (width > 0) params.width = width
                    changedWidth = false
                    changedPosition = false
                    firstId = event.getPointerId(0)
                    secondId = -1
                    grip = !expanded && event.x >= width - px(StatusGeometry.HandleWidthDp)
                    multiTouch = false
                    childStream = expanded && event.y >= px(heightDp)
                    rebase(event)
                    if (childStream) super.dispatchTouchEvent(event)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (firstId != -1 && secondId == -1) {
                    multiTouch = true
                    cancelChild(event)
                    secondId = event.getPointerId(event.actionIndex)
                    grip = false
                    rebase(event)
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val lifted = event.getPointerId(event.actionIndex)
                    if (lifted == firstId || lifted == secondId) {
                        val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
                        firstId = remaining.firstOrNull()?.let(event::getPointerId) ?: -1
                        secondId = remaining.getOrNull(1)?.let(event::getPointerId) ?: -1
                        grip = false
                        rebase(event)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val first = event.findPointerIndex(firstId)
                    val second = event.findPointerIndex(secondId)
                    if (first < 0) return true
                    val dx = rawX(event, first) - downX
                    val dy = rawY(event, first) - downY
                    if (second >= 0) {
                        val span = span(event, first, second)
                        if (!expanded && pinchStart > 1f && kotlin.math.abs(span - pinchStart) > slop) resize(widthStart * span / pinchStart, anchoredLeft = false)
                    } else if (grip) {
                        if (dragging || kotlin.math.abs(dx) > slop) {
                            dragging = true
                            resize(widthStart + dx / density, anchoredLeft = true)
                        }
                    } else if (dragging || hypot(dx, dy) > slop) {
                        cancelChild(event)
                        dragging = true
                        params.x = fromX + dx.roundToInt()
                        params.y = fromY + dy.roundToInt()
                        clampInto(params)
                        changedPosition = true
                        update()
                    } else if (childStream) super.dispatchTouchEvent(event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tap = event.actionMasked == MotionEvent.ACTION_UP && !changedPosition && !changedWidth && !multiTouch && !grip && !childStream
                    if (childStream) super.dispatchTouchEvent(event)
                    finish()
                    if (tap) changeExpanded(!expanded)
                }
            }
            return true
        }

        private fun cancelChild(event: MotionEvent) {
            if (!childStream) return
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
            childStream = false
        }

        override fun performClick(): Boolean {
            super.performClick()
            changeExpanded(!expanded)
            return true
        }

        private fun resize(requested: Float, anchoredLeft: Boolean) {
            maximumWidthDp = StatusGeometry.width(requested, maxWidthDp())
            fittingWidthDp = maximumWidthDp
            params.width = px(maximumWidthDp).coerceAtLeast(1)
            if (anchoredLeft) params.x = StatusGeometry.anchoredX(fromX, pixelWidthStart, params.width, screenSize().x)
            clampInto(params)
            changedWidth = true
            changedPosition = true
            update()
        }

        private fun rebase(event: MotionEvent) {
            val first = event.findPointerIndex(firstId)
            if (first < 0) return
            downX = rawX(event, first)
            downY = rawY(event, first)
            fromX = params.x
            fromY = params.y
            pixelWidthStart = width.takeIf { it > 0 } ?: params.width
            widthStart = pixelWidthStart / density
            dragging = false
            val second = event.findPointerIndex(secondId)
            pinchStart = if (second >= 0) span(event, first, second) else 0f
        }

        private fun finish(resume: Boolean = true) {
            if (changedWidth) listener.onResized(maximumWidthDp.roundToInt())
            if (changedPosition) {
                val bounds = placementBounds()
                anchor = StatusGeometry.snapAnchor(params.x, params.width, screenSize().x, bounds.left, bounds.right, px(36f))
                verticalFraction = StatusGeometry.verticalFraction(params.y, params.height, bounds.top, bounds.bottom)
                persistPosition()
            }
            firstId = -1
            secondId = -1
            grip = false
            dragging = false
            changedWidth = false
            changedPosition = false
            touching = false
            childStream = false
            if (resume) adaptWidth()
        }

        // rawX/rawY belong to index zero on older Android. Its raw-local offset maps
        // any pointer into screen space without depending on the asynchronously moved window.
        private fun rawX(event: MotionEvent, index: Int) = event.rawX + event.getX(index) - event.getX(0)
        private fun rawY(event: MotionEvent, index: Int) = event.rawY + event.getY(index) - event.getY(0)
        private fun span(event: MotionEvent, first: Int, second: Int): Float =
            hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            cancelWidthAnimation()
            finish(resume = false)
            params.height = minOf(px(heightDp), safeBounds().height().coerceAtLeast(1))
            adaptWidth(animate = false)
            clampInto(params)
            update()
            persistPosition()
        }
    }
}

// overlays have their own window type from android 8; before that the phone type stood in for it
private fun overlayType(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
} else {
    @Suppress("DEPRECATION")
    WindowManager.LayoutParams.TYPE_PHONE
}

// the lifecycle and saved state compose expects from a window, which an overlay has to bring itself
private class StatusOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun start() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) return
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED || registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
