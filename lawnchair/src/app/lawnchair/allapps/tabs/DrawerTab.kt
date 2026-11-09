package app.lawnchair.allapps.tabs

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A user-defined app drawer tab. The built-in "All" tab is not stored here; it is
 * always the first page and its title lives in [app.lawnchair.preferences2.PreferenceManager2.drawerAllTabTitle].
 *
 * @param apps ComponentKey strings (see [com.android.launcher3.util.ComponentKey.toString]).
 * @param hideFromAll when true, apps in this tab are not listed in the "All" tab.
 */
@Immutable
@Serializable
data class DrawerTab(
    val id: Long,
    val title: String,
    val hideFromAll: Boolean = false,
    val apps: List<String> = emptyList(),
)

object DrawerTabs {
    /** Creates a tab with an id that is unique within [existing]. */
    fun create(title: String, existing: List<DrawerTab>): DrawerTab {
        var id = System.currentTimeMillis()
        while (existing.any { it.id == id }) id++
        return DrawerTab(id = id, title = title)
    }

    /** Component keys that must be left out of the "All" tab. */
    @JvmStatic
    fun keysHiddenFromAll(tabs: List<DrawerTab>): Set<String> =
        tabs.asSequence().filter { it.hideFromAll }.flatMap { it.apps.asSequence() }.toSet()
}
