package app.lawnchair.allapps.tabs

import android.content.Context
import android.content.Intent
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.AppDrawerTabEditor
import app.lawnchair.ui.preferences.navigation.AppDrawerTabs

object DrawerTabsActions {
    /** Opens the editor for [tabId], or the tabs list when [tabId] is negative (the "All" tab). */
    @JvmStatic
    fun openEditor(context: Context, tabId: Long) {
        val route = if (tabId < 0) AppDrawerTabs else AppDrawerTabEditor(tabId)
        val intent = PreferenceActivity.createIntent(context, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
