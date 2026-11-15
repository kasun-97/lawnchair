package app.lawnchair.feed.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import kotlin.math.abs

/**
 * Host view of a widget in the built-in feed. Tells the widget how big it is drawn, lets a list
 * or stack inside the widget keep the gesture that starts on it instead of the feed, and reports
 * when the widget is held.
 */
class FeedWidgetHostView(context: Context) : AppWidgetHostView(context) {

    private var reportedWidth = 0
    private var reportedHeight = 0

    /** Called when the widget is held without moving; the touch is then taken from the widget. */
    var onLongPress: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val longPress = Runnable {
        val listener = onLongPress ?: return@Runnable
        longPressed = true
        // Whatever inside the widget was tracking this touch must not count it as a tap.
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.x
                downY = ev.y
                removeCallbacks(longPress)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                if (hasScrollableAt(this, ev.x, ev.y)) {
                    // Otherwise the feed (vertical) or its close swipe (horizontal) would win
                    // the drag.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) removeCallbacks(longPress)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (longPressed) return true
        super.dispatchTouchEvent(ev)
        // Always claimed, so the end of the touch is seen here even over a part of the widget
        // that does not react to touches; the feed can still take the drag for scrolling.
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(longPress)
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        // The feed sets the margins, so the widget's background can be clipped to rounded corners.
        setPadding(0, 0, 0, 0)
    }

    /**
     * Widgets are anchored to the top rather than centred, so that a height set lower than the
     * widget's own cuts off its bottom, not its top and bottom.
     */
    override fun prepareView(view: View) {
        super.prepareView(view)
        (view.layoutParams as? LayoutParams)?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    /**
     * The height the feed offers is used in full by widgets that stretch. A widget with a height
     * of its own would be centred in it with empty bands above and below, so then the view
     * takes the widget's height instead.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val offered = MeasureSpec.getSize(heightMeasureSpec)
        val exactHeight = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
            MeasureSpec.makeMeasureSpec(offered, MeasureSpec.EXACTLY)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, exactHeight)
        val content = getChildAt(0) ?: return
        val stretches = content.layoutParams?.height == ViewGroup.LayoutParams.MATCH_PARENT
        if (!stretches && content.measuredHeight in 1 until measuredHeight) {
            setMeasuredDimension(measuredWidth, content.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (width != reportedWidth || height != reportedHeight) {
            reportedWidth = width
            reportedHeight = height
            // Changing the options can re-inflate the widget, which must not happen mid-layout.
            post(::reportSize)
        }
    }

    private fun reportSize() {
        if (reportedWidth <= 0 || reportedHeight <= 0) return
        val density = resources.displayMetrics.density
        val widthDp = reportedWidth / density
        val heightDp = reportedHeight / density
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))
            } else {
                @Suppress("DEPRECATION")
                updateAppWidgetSize(null, widthDp.toInt(), heightDp.toInt(), widthDp.toInt(), heightDp.toInt())
            }
        }
    }

    private fun hasScrollableAt(group: ViewGroup, x: Float, y: Float): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val childX = x + group.scrollX - child.left
            val childY = y + group.scrollY - child.top
            if (childX < 0 || childY < 0 || childX >= child.width || childY >= child.height) continue
            if (isScrollable(child)) return true
            if (child is ViewGroup && hasScrollableAt(child, childX, childY)) return true
        }
        return false
    }

    private fun isScrollable(view: View): Boolean {
        val canScroll = view.canScrollVertically(1) || view.canScrollVertically(-1) ||
            view.canScrollHorizontally(1) || view.canScrollHorizontally(-1)
        // Stacks and flippers are swiped without ever reporting that they can scroll.
        return canScroll || (view is AdapterView<*> && view !is AbsListView)
    }
}
