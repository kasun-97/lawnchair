package app.lawnchair.feed

import app.lawnchair.FeedBridge
import app.lawnchair.LawnchairLauncher
import app.lawnchair.nexuslauncher.OverlayCallbackImpl
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay

/**
 * Chooses which feed sits to the side of the first home screen: the built-in one or an external
 * feed app reached through [OverlayCallbackImpl].
 *
 * The external client keeps receiving the launcher lifecycle either way, but its feed is switched
 * off while the built-in one is selected, so only one of them is ever given to the workspace.
 */
class FeedOverlayManager(private val launcher: LawnchairLauncher) : LauncherOverlayManager {

    private val prefs = PreferenceManager.getInstance(launcher)

    private var feedEnabled = PreferenceManager2.getInstance(launcher).enableFeed.firstCached()
    private var builtInSelected = FeedBridge.isBuiltIn(prefs.feedProvider.get())
    private var windowAttached = false

    private var externalOverlay: LauncherOverlay? = null
    private var current: LauncherOverlay? = null

    private val builtIn = BuiltInFeedOverlay(launcher)
    private val external = OverlayCallbackImpl(launcher) { overlay ->
        externalOverlay = overlay
        applyOverlay()
    }

    private val useBuiltIn get() = feedEnabled && builtInSelected

    /** The feed provider preference changed. */
    fun reconnect() {
        val wasBuiltIn = builtInSelected
        builtInSelected = FeedBridge.isBuiltIn(prefs.feedProvider.get())
        if (wasBuiltIn != builtInSelected) {
            external.setEnableFeed(feedEnabled && !builtInSelected)
        } else {
            external.reconnect()
        }
        applyOverlay()
    }

    fun setEnableFeed(enable: Boolean) {
        feedEnabled = enable
        external.setEnableFeed(enable && !builtInSelected)
        applyOverlay()
    }

    private fun applyOverlay() {
        // The built-in feed needs the launcher's views; an external one is only reported
        // once its window is attached anyway.
        if (!windowAttached) return
        val target = if (useBuiltIn) builtIn else externalOverlay
        if (target === current) return
        if (current === builtIn) builtIn.detach()
        current = target
        if (target === builtIn) builtIn.attach()
        launcher.setLauncherOverlay(target)
    }

    override fun onAttachedToWindow() {
        external.onAttachedToWindow()
        windowAttached = true
        applyOverlay()
    }

    override fun onDetachedFromWindow() {
        external.onDetachedFromWindow()
    }

    override fun onDeviceProvideChanged() = external.onDeviceProvideChanged()

    override fun openOverlay() {
        if (useBuiltIn) builtIn.open(animate = true) else external.openOverlay()
    }

    override fun hideOverlay(animate: Boolean) {
        if (useBuiltIn) builtIn.close(animate) else external.hideOverlay(animate)
    }

    override fun hideOverlay(duration: Int) {
        if (useBuiltIn) builtIn.close(animate = duration > 0, duration = duration.toLong()) else external.hideOverlay(duration)
    }

    override fun onActivityStarted() = external.onActivityStarted()

    override fun onActivityResumed() = external.onActivityResumed()

    override fun onActivityPaused() = external.onActivityPaused()

    override fun onActivityStopped() = external.onActivityStopped()

    override fun onActivityDestroyed() {
        external.onActivityDestroyed()
        builtIn.detach()
    }
}
