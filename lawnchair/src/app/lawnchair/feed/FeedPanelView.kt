package app.lawnchair.feed

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import app.lawnchair.ui.theme.LawnchairTheme
import app.lawnchair.util.ProvideLifecycleState
import com.android.launcher3.Insettable
import com.android.launcher3.Utilities
import kotlin.math.abs

/**
 * Full-screen container of the built-in feed.
 *
 * It sits in the launcher's root view, beside the drag layer, because the drag layer itself
 * slides away and fades while a feed is open. The panel only handles the swipe that closes it;
 * [Controller] owns the open/close progress.
 */
@SuppressLint("ViewConstructor")
class FeedPanelView(
    context: Context,
    private val controller: Controller,
) : FrameLayout(context),
    Insettable {

    interface Controller {
        /** A closing swipe started on the panel. Returns the progress to continue from. */
        fun onPanelDragStart(): Float

        fun onPanelDrag(progress: Float)

        /** @param velocity progress per second, positive towards open. */
        fun onPanelDragEnd(velocity: Float)

        fun onPanelBackgroundResolved()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val isRtl = Utilities.isRtl(resources)

    private var systemInsets by mutableStateOf(Rect())

    /** Whether the feed background is light, i.e. needs dark system bar icons. */
    var isBackgroundLight = false
        private set

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartProgress = 0f
    private var velocityTracker: VelocityTracker? = null

    init {
        // Nothing behind the feed may be touched through its empty areas.
        isClickable = true
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    LawnchairTheme {
                        ProvideLifecycleState {
                            val light = MaterialTheme.colorScheme.surface.luminance() > 0.5f
                            SideEffect {
                                if (isBackgroundLight != light) {
                                    isBackgroundLight = light
                                    controller.onPanelBackgroundResolved()
                                }
                            }
                            val padding = with(LocalDensity.current) {
                                PaddingValues(
                                    start = (if (isRtl) systemInsets.right else systemInsets.left).toDp(),
                                    top = systemInsets.top.toDp(),
                                    end = (if (isRtl) systemInsets.left else systemInsets.right).toDp(),
                                    bottom = systemInsets.bottom.toDp(),
                                )
                            }
                            FeedContent(contentPadding = padding)
                        }
                    }
                }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    // The background runs edge to edge; the content is padded instead of the view.
    override fun setInsets(insets: Rect) {
        systemInsets = Rect(insets)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                endDrag(cancelled = true)
            }

            MotionEvent.ACTION_MOVE -> maybeStartDrag(ev)
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                maybeStartDrag(ev)
                if (dragging) {
                    track(ev)
                    controller.onPanelDrag(dragStartProgress + towardsOpen(ev.rawX - dragStartX) / dragWidth())
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) track(ev)
                endDrag(cancelled = false)
            }

            MotionEvent.ACTION_CANCEL -> endDrag(cancelled = true)
        }
        return true
    }

    private fun maybeStartDrag(ev: MotionEvent) {
        if (dragging) return
        val dx = ev.rawX - downX
        val dy = ev.rawY - downY
        val closing = towardsOpen(dx) < 0
        if (closing && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
            dragging = true
            dragStartX = ev.rawX
            dragStartProgress = controller.onPanelDragStart()
            velocityTracker = VelocityTracker.obtain()
            track(ev)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private fun endDrag(cancelled: Boolean) {
        val tracker = velocityTracker
        velocityTracker = null
        if (dragging) {
            dragging = false
            var velocity = 0f
            if (!cancelled && tracker != null) {
                tracker.computeCurrentVelocity(1000)
                velocity = towardsOpen(tracker.xVelocity) / dragWidth()
            }
            controller.onPanelDragEnd(velocity)
        }
        tracker?.recycle()
    }

    /** The panel moves under the finger, so velocity is tracked in screen coordinates. */
    private fun track(ev: MotionEvent) {
        val copy = MotionEvent.obtain(ev)
        copy.setLocation(ev.rawX, ev.rawY)
        velocityTracker?.addMovement(copy)
        copy.recycle()
    }

    private fun towardsOpen(dx: Float) = if (isRtl) -dx else dx

    private fun dragWidth() = width.coerceAtLeast(1).toFloat()
}
