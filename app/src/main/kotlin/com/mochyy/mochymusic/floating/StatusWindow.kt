// StatusWindow.kt

package com.mochyy.mochymusic.floating

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
    private val onDiscTap: () -> Unit = {},
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density get() = context.resources.displayMetrics.density
    private val owner = StatusOwner()
    private val root = DragFrame()
    private val compose = ComposeView(context)

    // WindowMetrics may cross into the system server. Keep one coherent snapshot for
    // each gesture/animation instead of asking for screen and insets on every frame.
    private data class Geometry(val screen: Point, val safe: Rect, val placement: Rect)
    private var geometry: Geometry? = null
    private fun geometry(): Geometry = geometry ?: readGeometry().also { geometry = it }
    private fun refreshGeometry() { geometry = readGeometry() }

    private data class SubmittedLayout(
        val width: Int, val height: Int, val x: Int, val y: Int,
        val displayMode: Int, val refreshRate: Float,
    )
    private var submittedLayout: SubmittedLayout? = null
    private var updatePending = false
    private val frameUpdate = Runnable {
        updatePending = false
        update()
    }

    // Only user resizing changes this preference. Rotation and automatic sizing merely
    // constrain its current presentation, so returning to a wider screen restores it.
    private var maximumWidthDp = (savedWidthDp?.toFloat() ?: defaultWidthDp())
        .coerceAtLeast(StatusGeometry.MinCapsuleWidthDp)
    var fittingWidthDp by mutableFloatStateOf(StatusGeometry.width(maximumWidthDp, maxWidthDp()))
        private set
    private var widthAnimator: ValueAnimator? = null
    private var touching = false
    private var removed = false
    private var targetWidthPx = 0
    private val heightDp = StatusGeometry.CapsuleHeightDp
    var expanded by mutableStateOf(false)
        private set
    // Tapping the cover reduces the capsule to a single disc; the saved width is untouched.
    var discOnly by mutableStateOf(false)
        private set

    fun applyDiscOnly(value: Boolean) {
        if (discOnly == value) return
        discOnly = value
        if (value && expanded) expanded = false
        adaptWidth()
    }

    private var anchor = StatusGeometry.Anchor.entries.firstOrNull { it.name == savedAnchor }
        ?: StatusGeometry.Anchor.CENTER
    private var verticalFraction = savedVerticalFraction?.coerceIn(0, 1000)
    private var targetHeightPx = 0

    // Only the temporary host is a native window. The capsule remains an exact-size
    // child, so lyric animation never resizes and moves an Android surface per frame.
    private var localResize = false
    private var visualWidthPx = 0
    private var visualLeftPx = 0
    private var resizeGeneration = 0
    private var settlingLocalResize = false

    private fun windowLeft(width: Int = params.width, x: Int = params.x): Int =
        (screenSize().x - width) / 2 + x

    private fun settleLocalResize() {
        if (!localResize) return
        params.width = visualWidthPx
        params.x = visualLeftPx - (screenSize().x - visualWidthPx) / 2
        settlingLocalResize = true
        flushUpdate()
        root.requestLayout()
    }

    fun changeExpanded(value: Boolean) {
        if (expanded == value || discOnly) return
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
        refreshGeometry()
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
            .onFailure {
                owner.destroy()
                runCatching { compose.disposeComposition() }
                runCatching { root.removeAllViews() }
            }
            .isSuccess
    }

    fun remove() {
        if (removed) return
        removed = true
        cancelPendingUpdate()
        cancelWidthAnimation()
        owner.destroy()
        runCatching { compose.disposeComposition() }
        runCatching {
            if (root.isAttachedToWindow || root.parent != null) {
                windowManager.removeViewImmediate(root)
            }
        }.onFailure {
            runCatching { windowManager.removeView(root) }
        }
        runCatching { root.removeAllViews() }
    }

    private fun cancelWidthAnimation() {
        resizeGeneration++
        widthAnimator?.removeAllListeners()
        widthAnimator?.removeAllUpdateListeners()
        widthAnimator?.cancel()
        widthAnimator = null
        targetWidthPx = 0
        targetHeightPx = 0
    }

    private fun animateLocalWidth(target: Int, targetHeight: Int) {
        val generation = resizeGeneration
        val bounds = placementBounds()
        val screen = screenSize().x
        val startWidth = if (localResize) visualWidthPx else params.width
        val startLeft = if (localResize) visualLeftPx else windowLeft()
        val endLeft = (screen - target) / 2 + StatusGeometry.anchorX(anchor, target, screen, bounds.left, bounds.right)
        // Include the existing host when replacing an animation: do not shrink its
        // surface until the last local frame has been drawn.
        val hostLeft = minOf(windowLeft(), startLeft, endLeft)
        val hostRight = maxOf(windowLeft() + params.width, startLeft + startWidth, endLeft + target)
        localResize = true
        settlingLocalResize = false
        visualWidthPx = startWidth
        visualLeftPx = startLeft
        params.width = hostRight - hostLeft
        params.x = hostLeft - (screen - params.width) / 2
        targetWidthPx = target
        targetHeightPx = targetHeight
        flushUpdate()
        root.requestLayout()
        // A submitted WindowManager request is not a layout acknowledgement. Wait
        // until the actual host matches before beginning child-only animation.
        var layoutWaitFrames = 0
        fun beginWhenLaidOut() {
            if (generation != resizeGeneration || removed || touching) return
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            if (root.width != params.width || location[0] != hostLeft || root.isLayoutRequested) {
                // An OEM may clamp the overlay differently. Never keep an oversized
                // input window alive indefinitely while waiting for impossible bounds.
                if (++layoutWaitFrames >= 60) {
                    cancelWidthAnimation()
                    params.width = target
                    params.x = endLeft - (screen - target) / 2
                    localResize = false
                    settlingLocalResize = false
                    flushUpdate()
                    root.requestLayout()
                    return
                }
                root.postOnAnimation { beginWhenLaidOut() }
                return
            }
            widthAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ResizeMs
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    if (generation != resizeGeneration) return@addUpdateListener
                    val fraction = it.animatedValue as Float
                    visualWidthPx = (startWidth + (target - startWidth) * fraction).roundToInt()
                    // Interpolate the rectangle in one coordinate space. Centered
                    // start/end rectangles have the same centre (apart from 1 px).
                    visualLeftPx = (startLeft + (endLeft - startLeft) * fraction).roundToInt()
                    root.requestLayout()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (generation != resizeGeneration) return
                        visualWidthPx = target
                        visualLeftPx = endLeft
                        // Let the final child layout draw inside the old host before
                        // reducing the input/surface bounds to the capsule rectangle.
                        root.requestLayout()
                        root.postOnAnimation {
                            if (generation == resizeGeneration && !removed) settleLocalResize()
                        }
                    }
                })
                start()
            }
        }
        root.postOnAnimation { beginWhenLaidOut() }
    }

    private fun adaptWidth(animate: Boolean = true) {
        if (touching || removed) return
        if (widthAnimator?.isRunning != true) refreshGeometry()
        fittingWidthDp = StatusGeometry.width(maximumWidthDp, maxWidthDp())
        // The pill's width is whatever the user set; lyrics no longer resize the window.
        val target = px(
            when {
                discOnly -> StatusGeometry.DiscOnlySizeDp
                expanded -> minOf(280f, maxWidthDp())
                else -> StatusGeometry.width(maximumWidthDp, maxWidthDp())
            },
        ).coerceAtLeast(1)
        // Room for the capsule plus one or two rows of transport buttons.
        val panelHeight = heightDp + if (maxWidthDp() < 240f) 96f else 48f
        val targetHeight = minOf(
            px(if (discOnly) StatusGeometry.DiscOnlySizeDp else if (expanded) panelHeight else heightDp),
            placementBounds().height().coerceAtLeast(1),
        )
        if (target == targetWidthPx && targetHeight == targetHeightPx && (localResize || widthAnimator?.isRunning == true)) return
        cancelWidthAnimation()
        if (animate && root.isAttachedToWindow && !expanded && !discOnly && params.height == targetHeight &&
            (localResize || params.width != target)) {
            animateLocalWidth(target, targetHeight)
            return
        }
        if (localResize) {
            // Expanded controls and direct manipulation use the original exact-size
            // native layout; carry the current visual rectangle into that path.
            params.width = visualWidthPx
            params.x = visualLeftPx - (screenSize().x - visualWidthPx) / 2
            settlingLocalResize = true
            root.requestLayout()
        }
        val startWidth = params.width
        val startHeight = params.height
        val startY = params.y
        val bounds = placementBounds()
        val endY = StatusGeometry.anchoredY(verticalFraction ?: 0, targetHeight, bounds.top, bounds.bottom)
        fun applySize(fraction: Float) {
            params.width = (startWidth + (target - startWidth) * fraction).roundToInt()
            params.height = (startHeight + (targetHeight - startHeight) * fraction).roundToInt()
            params.x = StatusGeometry.anchorX(anchor, params.width, screenSize().x, bounds.left, bounds.right)
            params.y = (startY + (endY - startY) * fraction).roundToInt()
            clampInto(params)
            // One relayout per frame no matter how many animator ticks land in it.
            scheduleUpdate()
        }
        if (!animate || !root.isAttachedToWindow) {
            applySize(1f)
            flushUpdate()
            return
        }
        if (target == startWidth && targetHeight == startHeight && startY == endY) {
            applySize(1f)
            flushUpdate()
            return
        }
        targetWidthPx = target
        targetHeightPx = targetHeight
        widthAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ResizeMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { applySize(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = flushUpdate()
                override fun onAnimationCancel(animation: Animator) = flushUpdate()
            })
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

    private fun scheduleUpdate() {
        if (removed || updatePending || !root.isAttachedToWindow) return
        updatePending = true
        root.postOnAnimation(frameUpdate)
    }

    private fun cancelPendingUpdate() {
        root.removeCallbacks(frameUpdate)
        updatePending = false
    }

    private fun flushUpdate() {
        cancelPendingUpdate()
        update()
    }

    private fun update() {
        if (removed || !root.isAttachedToWindow) return
        if (localResize && settlingLocalResize) {
            visualWidthPx = params.width
            visualLeftPx = windowLeft()
            root.requestLayout()
        }
        val next = SubmittedLayout(params.width, params.height, params.x, params.y,
            params.preferredDisplayModeId, params.preferredRefreshRate)
        if (next == submittedLayout) return
        runCatching { windowManager.updateViewLayout(root, params) }
            .onSuccess { submittedLayout = next }
            .onFailure {
                if (it is IllegalArgumentException || it is WindowManager.BadTokenException) {
                    remove()
                }
            }
    }

    private fun persistPosition() {
        listener.onMoved(params.x, params.y, anchor, verticalFraction ?: 0)
    }

    private fun placementBounds(): Rect = geometry().placement

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

    private fun screenSize(): Point = geometry().screen
    private fun safeBounds(): Rect = geometry().safe

    private fun readGeometry(): Geometry {
        val screen: Point
        val safe: Rect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            screen = Point(metrics.bounds.width(), metrics.bounds.height())
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            safe = Rect(insets.left, insets.top, screen.x - insets.right, screen.y - insets.bottom)
        } else {
            screen = Point().also {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealSize(it)
            }
            safe = legacySafeBounds(screen)
        }
        val placement = Rect(safe)
        val margin = minOf(px(EdgeRoomDp), (safe.width() / 2 - 1).coerceAtLeast(0), (safe.height() / 2 - 1).coerceAtLeast(0))
        placement.inset(margin, margin)
        return Geometry(screen, safe, placement)
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun legacySafeBounds(screen: Point): Rect {
        fun inset(name: String, fallback: Int): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) context.resources.getDimensionPixelSize(id) else fallback
        }
        val top = inset("status_bar_height", px(24f))
        val nav = inset("navigation_bar_height", px(48f))
        // Legacy devices may place navigation on either side in landscape. Reserve both.
        val landscape = screen.x > screen.y
        // the cutout is Android 9's; reading its edges has to be inside the same check, not only the
        // lookup of the cutout itself
        val cutoutLeft: Int
        val cutoutTop: Int
        val cutoutRight: Int
        val cutoutBottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = root.rootWindowInsets?.displayCutout
            cutoutLeft = cutout?.safeInsetLeft ?: 0
            cutoutTop = cutout?.safeInsetTop ?: 0
            cutoutRight = cutout?.safeInsetRight ?: 0
            cutoutBottom = cutout?.safeInsetBottom ?: 0
        } else {
            cutoutLeft = 0
            cutoutTop = 0
            cutoutRight = 0
            cutoutBottom = 0
        }
        return Rect(
            maxOf(if (landscape) nav else 0, cutoutLeft),
            maxOf(top, cutoutTop),
            screen.x - maxOf(if (landscape) nav else 0, cutoutRight),
            screen.y - maxOf(if (landscape) 0 else nav, cutoutBottom),
        )
    }

    private fun px(value: Float): Int = (value * density).roundToInt()

    // The entire stream is consumed even over noninteractive lyrics. Track IDs, not indices:
    // lifting either pinch finger rebases onto the survivors without a jump.
    private inner class DragFrame : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (!localResize) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val hostWidth = MeasureSpec.getSize(widthMeasureSpec)
            val hostHeight = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(hostWidth, hostHeight)
            compose.measure(
                MeasureSpec.makeMeasureSpec(visualWidthPx.coerceAtLeast(1), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(hostHeight, MeasureSpec.EXACTLY),
            )
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            if (!localResize) {
                super.onLayout(changed, left, top, right, bottom)
                return
            }
            val location = IntArray(2)
            getLocationOnScreen(location)
            val offset = visualLeftPx - location[0]
            compose.layout(offset, 0, offset + visualWidthPx, height)
            if (settlingLocalResize && width == visualWidthPx) {
                localResize = false
                settlingLocalResize = false
            }
        }

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
        private var ignoredMarginTouch = false
        private var downLocalX = 0f

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                changeExpanded(false)
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) ignoredMarginTouch = false
            if (ignoredMarginTouch) return true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (localResize) {
                        val x = rawX(event, 0)
                        if (x < visualLeftPx || x >= visualLeftPx + visualWidthPx) {
                            // The temporary union has a transparent margin for at most
                            // one transition. It must never toggle or drag the pill.
                            ignoredMarginTouch = true
                            return true
                        }
                    }
                    touching = true
                    downLocalX = event.x
                    cancelWidthAnimation()
                    cancelPendingUpdate()
                    refreshGeometry()
                    // Reconcile the rendered size after interruption; subsequent rebases
                    // use the latest requested size even if traversal has not run yet.
                    if (localResize) {
                        settleLocalResize()
                    } else {
                        if (width > 0) params.width = width
                        if (height > 0) params.height = height
                    }
                    changedWidth = false
                    changedPosition = false
                    firstId = event.getPointerId(0)
                    secondId = -1
                    grip = !expanded && !discOnly && rawX(event, 0) >= windowLeft() + params.width - px(StatusGeometry.HandleWidthDp)
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
                        if (!expanded && !discOnly && pinchStart > 1f && kotlin.math.abs(span - pinchStart) > slop) resize(widthStart * span / pinchStart, anchoredLeft = false)
                    } else if (grip) {
                        if (dragging || kotlin.math.abs(dx) > slop) {
                            dragging = true
                            resize(widthStart + dx / density, anchoredLeft = true)
                        }
                    } else if (dragging || hypot(dx, dy) > slop) {
                        endLocalGestureLayout()
                        cancelChild(event)
                        dragging = true
                        params.x = fromX + dx.roundToInt()
                        params.y = fromY + dy.roundToInt()
                        clampInto(params)
                        changedPosition = true
                        scheduleUpdate()
                    } else if (childStream) super.dispatchTouchEvent(event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val tap = event.actionMasked == MotionEvent.ACTION_UP && !changedPosition && !changedWidth && !multiTouch && !grip && !childStream
                    if (childStream) super.dispatchTouchEvent(event)
                    finish()
                    if (tap) {
                        if (discOnly || downLocalX <= px(64f)) onDiscTap()
                        else changeExpanded(!expanded)
                    }
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
            if (discOnly) onDiscTap() else changeExpanded(!expanded)
            return true
        }

        private fun endLocalGestureLayout() {
            if (!localResize) return
            // Keep the child exact-sized until the asynchronously resized host
            // catches up; update() follows the gesture's requested visual rectangle.
            settlingLocalResize = true
            requestLayout()
        }

        private fun resize(requested: Float, anchoredLeft: Boolean) {
            endLocalGestureLayout()
            maximumWidthDp = StatusGeometry.width(requested, maxWidthDp())
            fittingWidthDp = maximumWidthDp
            params.width = px(maximumWidthDp).coerceAtLeast(1)
            if (anchoredLeft) params.x = StatusGeometry.anchoredX(fromX, pixelWidthStart, params.width, screenSize().x)
            clampInto(params)
            changedWidth = true
            changedPosition = true
            scheduleUpdate()
        }

        private fun rebase(event: MotionEvent) {
            val first = event.findPointerIndex(firstId)
            if (first < 0) return
            downX = rawX(event, first)
            downY = rawY(event, first)
            fromX = params.x
            fromY = params.y
            pixelWidthStart = params.width
            widthStart = pixelWidthStart / density
            dragging = false
            val second = event.findPointerIndex(secondId)
            pinchStart = if (second >= 0) span(event, first, second) else 0f
        }

        private fun finish(resume: Boolean = true) {
            // Ensure the final pointer position is submitted before persisting it.
            flushUpdate()
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
        private fun rawX(event: MotionEvent, index: Int): Float =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && index in 0 until event.pointerCount) {
                event.getRawX(index)
            } else {
                event.rawX + (if (index in 0 until event.pointerCount) event.getX(index) else 0f) - event.getX(0)
            }

        private fun rawY(event: MotionEvent, index: Int): Float =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && index in 0 until event.pointerCount) {
                event.getRawY(index)
            } else {
                event.rawY + (if (index in 0 until event.pointerCount) event.getY(index) else 0f) - event.getY(0)
            }

        private fun span(event: MotionEvent, first: Int, second: Int): Float {
            if (first !in 0 until event.pointerCount || second !in 0 until event.pointerCount) return 0f
            return hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
        }

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            cancelWidthAnimation()
            finish(resume = false)
            refreshGeometry()
            params.height = minOf(px(heightDp), safeBounds().height().coerceAtLeast(1))
            adaptWidth(animate = false)
            clampInto(params)
            update()
            persistPosition()
        }

        // Insets can change without a configuration change (navigation bar hiding, cutout
        // moving on rotation for some OEMs); the cached safe area must follow, but nothing
        // else in the frame loop needs to re-read window metrics.
        override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
            refreshGeometry()
            return super.onApplyWindowInsets(insets)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            refreshGeometry()
        }
    }
}

// How long the window takes to reach a new size. The capsule and the disc alone are laid out
// by different content, so both sides of the swap wait for this before switching.
internal const val ResizeMs = 260L

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
