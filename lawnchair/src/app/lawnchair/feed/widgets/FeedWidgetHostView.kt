package app.lawnchair.feed.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView

/**
 * Host view of a widget in the built-in feed. Tells the widget how big it is drawn, and lets a
 * list or stack inside the widget keep the gesture that starts on it instead of the feed.
 */
class FeedWidgetHostView(context: Context) : AppWidgetHostView(context) {

    private var reportedWidth = 0
    private var reportedHeight = 0

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && hasScrollableAt(this, ev.x, ev.y)) {
            // Otherwise the feed (vertical) or its close swipe (horizontal) would win the drag.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return super.dispatchTouchEvent(ev)
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
