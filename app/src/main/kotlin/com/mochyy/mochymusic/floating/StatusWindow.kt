// StatusWindow.kt

package com.mochyy.mochymusic.floating

import android.annotation.SuppressLint
import android.content.Context
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
private const val MinWidthDp = 120f
private const val EdgeRoomDp = 12f

// what the window tells the service about, so where it was left and how wide it was come back next time
internal interface StatusWindowListener {
    fun onMoved(x: Int, y: Int)
    fun onResized(widthDp: Int)
}

// the window the line lives in: a capsule in the status bar's own row to begin with, which can be dragged anywhere and pinched wider or
// narrower. it takes its own touches from the raw screen position, so moving the window under the finger never feeds back into the drag
internal class StatusWindow(
    private val context: Context,
    savedX: Int?,
    savedY: Int?,
    savedWidthDp: Int?,
    private val listener: StatusWindowListener,
) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val owner = StatusOwner()
    private val root = DragFrame()
    private val compose = ComposeView(context)

    private var widthDp = (savedWidthDp?.toFloat() ?: defaultWidthDp()).coerceIn(MinWidthDp, maxWidthDp())
    private val heightDp = (topInset() / density).coerceIn(26f, 38f)

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
        // first time: the middle of the status bar's row, where the phone puts its island
        x = savedX ?: 0
        y = savedY ?: 0
        title = "Lyrics in the status bar"
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

    // keeps the capsule on screen; its own width is what tells how far it may go
    private fun clampInto(lp: WindowManager.LayoutParams) {
        val screen = screenSize()
        val half = ((screen.x - lp.width) / 2f).coerceAtLeast(0f).roundToInt()
        lp.x = lp.x.coerceIn(-half, half)
        lp.y = lp.y.coerceIn(0, (screen.y - lp.height).coerceAtLeast(0))
    }

    private fun defaultWidthDp(): Float = min((screenSize().x / density) - 40f, 300f)

    private fun maxWidthDp(): Float = (screenSize().x / density) - EdgeRoomDp * 2f

    private fun screenSize(): Point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = windowManager.currentWindowMetrics.bounds
        Point(bounds.width(), bounds.height())
    } else {
        Point().also {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(it)
        }
    }

    // the status bar's height, which is the row the capsule takes over to begin with
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun topInset(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
            return insets.top
        }
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else px(24f)
    }

    private fun px(value: Float): Int = (value * density).roundToInt()

    // one finger moves the capsule, two pinch it wider or narrower; the compose inside never sees a touch
    private inner class DragFrame : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var fromX = 0
        private var fromY = 0
        private var dragging = false
        private var resizing = false
        private var pinchStart = 0f
        private var widthStart = 0f

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    fromX = params.x
                    fromY = params.y
                    dragging = false
                    resizing = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                    pinchStart = span(event)
                    widthStart = widthDp
                    resizing = true
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizing && event.pointerCount >= 2) {
                        val span = span(event)
                        if (pinchStart > 1f) {
                            widthDp = (widthStart * (span / pinchStart)).coerceIn(MinWidthDp, maxWidthDp())
                            params.width = px(widthDp)
                            update()
                        }
                    } else if (!resizing && hypot(event.rawX - downX, event.rawY - downY) > slop) {
                        dragging = true
                        params.x = fromX + (event.rawX - downX).roundToInt()
                        params.y = fromY + (event.rawY - downY).roundToInt()
                        clampInto(params)
                        update()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (resizing) listener.onResized(widthDp.roundToInt()) else if (dragging) listener.onMoved(params.x, params.y)
                    dragging = false
                    resizing = false
                }
            }
            return true
        }

        private fun span(event: MotionEvent): Float =
            hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
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
