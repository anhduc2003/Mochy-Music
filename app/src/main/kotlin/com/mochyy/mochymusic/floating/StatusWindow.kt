// StatusWindow.kt

package com.mochyy.mochymusic.floating

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
    fun onMoved(x: Int, y: Int)
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
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density get() = context.resources.displayMetrics.density
    private val owner = StatusOwner()
    private val root = DragFrame()
    private val compose = ComposeView(context)

    private var widthDp = StatusGeometry.width(savedWidthDp?.toFloat() ?: defaultWidthDp(), maxWidthDp())
    private val heightDp = 38f

    private val params = WindowManager.LayoutParams(
        px(widthDp),
        px(heightDp),
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
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
        owner.destroy()
        runCatching { windowManager.removeView(root) }
    }

    private fun update() {
        if (root.isAttachedToWindow) runCatching { windowManager.updateViewLayout(root, params) }
    }

    private fun clampInto(lp: WindowManager.LayoutParams) {
        val bounds = safeBounds()
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

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    changedWidth = false
                    changedPosition = false
                    firstId = event.getPointerId(0)
                    secondId = -1
                    grip = event.x >= width - px(StatusGeometry.HandleWidthDp)
                    rebase(event)
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (firstId != -1 && secondId == -1) {
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
                        if (pinchStart > 1f) resize(widthStart * span / pinchStart, anchoredLeft = false)
                    } else if (grip) {
                        if (dragging || kotlin.math.abs(dx) > slop) {
                            dragging = true
                            resize(widthStart + dx / density, anchoredLeft = true)
                        }
                    } else if (dragging || hypot(dx, dy) > slop) {
                        dragging = true
                        params.x = fromX + dx.roundToInt()
                        params.y = fromY + dy.roundToInt()
                        clampInto(params)
                        changedPosition = true
                        update()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish()
            }
            return true
        }

        private fun resize(requested: Float, anchoredLeft: Boolean) {
            widthDp = StatusGeometry.width(requested, maxWidthDp())
            params.width = px(widthDp)
            if (anchoredLeft) params.x = StatusGeometry.resizedX(fromX, pixelWidthStart, params.width)
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
            widthStart = widthDp
            pixelWidthStart = params.width
            dragging = false
            val second = event.findPointerIndex(secondId)
            pinchStart = if (second >= 0) span(event, first, second) else 0f
        }

        private fun finish() {
            if (changedWidth) listener.onResized(widthDp.roundToInt())
            if (changedPosition) listener.onMoved(params.x, params.y)
            firstId = -1
            secondId = -1
            grip = false
            dragging = false
            changedWidth = false
            changedPosition = false
        }

        // rawX/rawY belong to index zero on older Android. Its raw-local offset maps
        // any pointer into screen space without depending on the asynchronously moved window.
        private fun rawX(event: MotionEvent, index: Int) = event.rawX + event.getX(index) - event.getX(0)
        private fun rawY(event: MotionEvent, index: Int) = event.rawY + event.getY(index) - event.getY(0)
        private fun span(event: MotionEvent, first: Int, second: Int): Float =
            hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))

        override fun onConfigurationChanged(newConfig: Configuration?) {
            super.onConfigurationChanged(newConfig)
            finish()
            widthDp = StatusGeometry.width(widthDp, maxWidthDp())
            params.width = px(widthDp)
            params.height = minOf(px(heightDp), safeBounds().height().coerceAtLeast(1))
            clampInto(params)
            update()
            listener.onResized(widthDp.roundToInt())
            listener.onMoved(params.x, params.y)
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
