package app.lawnchair.allapps.tabs

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.R
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Snapshot of the drawer tab settings, as consumed by the all-apps container. */
data class DrawerTabsConfig(
    val enabled: Boolean,
    private val allTitle: String,
    val tabs: List<DrawerTab>,
) {
    /** Custom tabs are shown only when the feature is on and at least one tab exists. */
    val isActive: Boolean get() = enabled && tabs.isNotEmpty()

    /** Tabs to show as drawer pages (empty when inactive). */
    val activeTabs: List<DrawerTab> get() = if (isActive) tabs else emptyList()

    /** Component keys hidden from the "All" tab (empty when inactive). */
    val hiddenFromAll: Set<String> by lazy {
        if (isActive) DrawerTabs.keysHiddenFromAll(tabs) else emptySet()
    }

    fun allTabTitle(context: Context): String =
        allTitle.ifBlank { context.getString(R.string.drawer_tabs_all_default_title) }

    companion object {
        @JvmField
        val EMPTY = DrawerTabsConfig(false, "", emptyList())
    }
}

/**
 * Observes the drawer tab preferences and reports changes on the main thread.
 * Call [start] when the drawer is attached and [stop] when it is detached.
 */
class DrawerTabsWatcher(
    context: Context,
    private val listener: Consumer<DrawerTabsConfig>,
) {
    private val prefs = PreferenceManager2.getInstance(context)
    private var scope: CoroutineScope? = null

    /** Current value from the preference cache (non-blocking). */
    fun current(): DrawerTabsConfig = runCatching {
        DrawerTabsConfig(
            enabled = prefs.drawerTabsEnabled.firstCached(prefs),
            allTitle = prefs.drawerAllTabTitle.firstCached(prefs),
            tabs = prefs.drawerTabs.firstCached(prefs),
        )
    }.getOrDefault(DrawerTabsConfig.EMPTY)

    fun start() {
        if (scope != null) return
        val newScope = MainScope()
        scope = newScope
        combine(
            prefs.drawerTabsEnabled.get(),
            prefs.drawerAllTabTitle.get(),
            prefs.drawerTabs.get(),
        ) { enabled, title, tabs -> DrawerTabsConfig(enabled, title, tabs) }
            .distinctUntilChanged()
            .onEach { listener.accept(it) }
            .launchIn(newScope)
    }

    fun stop() {
        scope?.cancel()
        scope = null
    }
}
