package app.lawnchair.feed

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.window.BackEvent
import androidx.annotation.RequiresApi
import app.lawnchair.LawnchairLauncher
import app.lawnchair.feed.rss.RssRepository
import app.lawnchair.feed.widgets.FeedWidgetHost
import com.android.app.animation.Interpolators
import com.android.launcher3.InsettableFrameLayout
import com.android.launcher3.LauncherState
import com.android.launcher3.Utilities
import com.android.launcher3.statemanager.StateManager
import com.android.launcher3.util.BackPressHandler
import com.android.launcher3.util.SystemUiController
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import kotlin.math.abs

/**
 * The feed that is built into the launcher. It is driven by the same [LauncherOverlay] hooks as
 * an external feed app, but draws its page in the launcher's own window.
 *
 * Progress runs from 0 (home screen) to 1 (feed). The workspace reports the opening swipe; the
 * feed page reports the closing one. Every change is passed back through
 * [LauncherOverlayCallbacks], which is what moves the home screen out of the way.
 */
class BuiltInFeedOverlay(private val launcher: LawnchairLauncher) :
    LauncherOverlay,
    FeedPanelView.Controller {

    private val isRtl = Utilities.isRtl(launcher.resources)
    private val flingThreshold = FLING_THRESHOLD_DP * launcher.resources.displayMetrics.density

    private var callbacks: LauncherOverlayCallbacks? = null
    private var panel: FeedPanelView? = null
    private var settleAnimator: ValueAnimator? = null

    private var progress = 0f

    // State of the swipe that is in flight, whoever reports it.
    private var dragStartProgress = 0f
    private var lastDragProgress = 0f
    private var lastDragTime = 0L

    /** Progress per second, positive towards open. */
    private var dragVelocity = 0f

    /** Pixels per second from the workspace, positive towards open; NaN when not reported. */
    private var flingVelocity = Float.NaN

    private val stateListener = object : StateManager.StateListener<LauncherState> {
        override fun onStateTransitionStart(toState: LauncherState) {
            // Every other launcher state draws in the drag layer, which is off screen while the
            // feed is open.
            if (toState != LauncherState.NORMAL) close(animate = false)
        }
    }

    private val backHandler by lazy(LazyThreadSafetyMode.NONE) { FeedBackHandler() }
    private val widgetHost by lazy(LazyThreadSafetyMode.NONE) { FeedWidgetHost.getInstance(launcher) }

    fun attach() {
        if (panel != null) return
        val root = launcher.rootView
        val view = FeedPanelView(launcher, launcher, this)
        view.visibility = View.INVISIBLE
        root.addView(view, InsettableFrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.setInsets(root.insets)
        panel = view
        launcher.stateManager.addStateListener(stateListener)
        if (Utilities.ATLEAST_U) launcher.addBackHandler(backHandler)
        applyProgress(0f)
        widgetHost.startListening()
    }

    fun detach() {
        val view = panel ?: return
        close(animate = false)
        launcher.stateManager.removeStateListener(stateListener)
        if (Utilities.ATLEAST_U) launcher.removeBackHandler(backHandler)
        launcher.rootView.removeView(view)
        panel = null
        widgetHost.stopListening()
        widgetHost.releaseViews(launcher)
    }

    /** Widgets only need updates while the launcher can be seen. */
    fun onActivityStarted() {
        if (panel != null) widgetHost.startListening()
    }

    fun onActivityStopped() {
        if (panel != null) widgetHost.stopListening()
    }

    fun open(animate: Boolean) = settle(1f, animate)

    fun close(animate: Boolean, duration: Long = -1) = settle(0f, animate, duration)

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        this.callbacks = callbacks
    }

    // The opening swipe, reported by the workspace as it is pulled past its first page.

    override fun onScrollInteractionBegin() = beginDrag()

    override fun onScrollChange(progress: Float, rtl: Boolean) = drag(dragStartProgress + progress)

    override fun onFlingVelocity(velocity: Float) {
        flingVelocity = if (isRtl) -velocity else velocity
    }

    override fun onScrollInteractionEnd() = endDrag()

    // The closing swipe, reported by the feed page.

    override fun onPanelDragStart(): Float {
        beginDrag()
        return progress
    }

    override fun onPanelDrag(progress: Float) = drag(progress)

    override fun onPanelDragEnd(velocity: Float) {
        dragVelocity = velocity
        endDrag()
    }

    override fun onPanelBackgroundResolved() = updateSystemBars()

    private fun beginDrag() {
        settleAnimator?.cancel()
        dragStartProgress = progress
        lastDragProgress = progress
        lastDragTime = SystemClock.uptimeMillis()
        dragVelocity = 0f
        flingVelocity = Float.NaN
    }

    private fun drag(newProgress: Float) {
        val now = SystemClock.uptimeMillis()
        val bounded = newProgress.coerceIn(0f, 1f)
        val elapsed = now - lastDragTime
        if (elapsed > 0) {
            val sample = (bounded - lastDragProgress) * 1000f / elapsed
            dragVelocity = dragVelocity * 0.4f + sample * 0.6f
            lastDragProgress = bounded
            lastDragTime = now
        }
        applyProgress(bounded)
    }

    private fun endDrag() {
        val width = launcher.rootView.width.coerceAtLeast(1)
        val velocity = if (flingVelocity.isNaN()) dragVelocity * width else flingVelocity
        val target = when {
            abs(velocity) > flingThreshold -> if (velocity > 0) 1f else 0f
            dragStartProgress < 0.5f -> if (progress > COMMIT_DISTANCE) 1f else 0f
            else -> if (progress < 1f - COMMIT_DISTANCE) 0f else 1f
        }
        settle(target, animate = true)
    }

    private fun settle(target: Float, animate: Boolean, duration: Long = -1) {
        settleAnimator?.cancel()
        if (panel == null || progress == target) return
        if (!animate || duration == 0L) {
            applyProgress(target)
            return
        }
        val distance = abs(target - progress)
        settleAnimator = ValueAnimator.ofFloat(progress, target).apply {
            this.duration = if (duration > 0) duration else (SETTLE_MIN_MS + SETTLE_SPAN_MS * distance).toLong()
            interpolator = Interpolators.DECELERATE_2
            addUpdateListener { applyProgress(it.animatedValue as Float) }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (settleAnimator === animation) settleAnimator = null
                    }
                },
            )
            start()
        }
    }

    private fun applyProgress(newProgress: Float) {
        if (progress == 0f && newProgress > 0f && panel != null) {
            RssRepository.getInstance(launcher).refresh(force = false)
        }
        progress = newProgress
        if (newProgress == 0f) panel?.leaveMode()
        panel?.let { view ->
            view.visibility = if (newProgress > 0f) View.VISIBLE else View.INVISIBLE
            // The feed page enters from the side the home screen leaves towards.
            val offScreen = launcher.rootView.width * (1f - newProgress)
            view.translationX = if (isRtl) offScreen else -offScreen
        }
        callbacks?.onOverlayScrollChanged(newProgress)
        updateSystemBars()
    }

    private fun updateSystemBars() {
        val view = panel
        val flags = when {
            view == null || progress < 0.5f -> 0

            view.isBackgroundLight ->
                SystemUiController.FLAG_LIGHT_NAV or SystemUiController.FLAG_LIGHT_STATUS

            else -> SystemUiController.FLAG_DARK_NAV or SystemUiController.FLAG_DARK_STATUS
        }
        launcher.systemUiController?.updateUiState(SystemUiController.UI_STATE_WIDGET_BOTTOM_SHEET, flags)
    }

    @RequiresApi(34)
    private inner class FeedBackHandler : BackPressHandler {
        override fun canHandleBack() = progress > 0f

        override fun onBackStarted(backEvent: BackEvent) = Unit

        override fun onBackProgressed(backEvent: BackEvent) = Unit

        override fun onBackCancelled() = Unit

        override fun onBackInvoked() {
            if (panel?.leaveMode() != true) close(animate = true)
        }
    }

    private companion object {
        const val FLING_THRESHOLD_DP = 300f

        /** How far a slow swipe must travel from where it started before it commits. */
        const val COMMIT_DISTANCE = 0.25f

        const val SETTLE_MIN_MS = 120f
        const val SETTLE_SPAN_MS = 230f
    }
}
