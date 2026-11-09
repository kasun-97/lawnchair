package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.lawnchair.allapps.tabs.DrawerTab
import app.lawnchair.allapps.tabs.DrawerTabs
import app.lawnchair.preferences.PreferenceAdapter
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.ModalBottomSheetContent
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.AppItemPlaceholder
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.components.reorderable.ReorderableDragHandle
import app.lawnchair.ui.preferences.components.reorderable.ReorderablePreferenceGroup
import app.lawnchair.ui.preferences.navigation.AppDrawerTabEditor
import app.lawnchair.ui.preferences.navigation.AppDrawerTabs
import app.lawnchair.ui.util.bottomSheetHandler
import app.lawnchair.util.App
import app.lawnchair.util.appComparator
import app.lawnchair.util.appsState
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import java.util.Comparator.comparing

/** Entry in the app drawer settings screen. */
@Composable
fun DrawerTabsPreferenceItem(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val enabled = prefs2.drawerTabsEnabled.getAdapter().state.value
    val tabs = prefs2.drawerTabs.getAdapter().state.value
    val resources = LocalResources.current
    NavigationActionPreference(
        label = stringResource(R.string.drawer_tabs_label),
        destination = AppDrawerTabs,
        subtitle = if (enabled && tabs.isNotEmpty()) {
            tabs.joinToString(", ") { it.title }
        } else if (enabled) {
            resources.getString(R.string.drawer_tabs_empty)
        } else {
            resources.getString(R.string.drawer_tabs_description)
        },
        modifier = modifier,
    )
}

/** Tabs list: master switch, "All" tab title, and the custom tabs (add, reorder, delete). */
@Composable
fun DrawerTabsPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val enabledAdapter = prefs2.drawerTabsEnabled.getAdapter()
    val allTitleAdapter = prefs2.drawerAllTabTitle.getAdapter()
    val tabsAdapter = prefs2.drawerTabs.getAdapter()
    val tabs = tabsAdapter.state.value
    val navController = LocalNavController.current
    val bottomSheetHandler = bottomSheetHandler
    val haptics = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)
    val resources = LocalResources.current
    val defaultAllTitle = stringResource(R.string.drawer_tabs_all_default_title)
    val newTabTitle = stringResource(R.string.drawer_tabs_new)

    PreferenceLayout(
        label = stringResource(R.string.drawer_tabs_label),
        backArrowVisible = true,
        modifier = modifier,
    ) {
        PreferenceGroup(heading = stringResource(R.string.settings)) {
            SwitchPreference(
                adapter = enabledAdapter,
                label = stringResource(R.string.drawer_tabs_enable),
                description = stringResource(R.string.drawer_tabs_description),
            )
            ClickablePreference(
                label = stringResource(R.string.drawer_tabs_all_title_label),
                subtitle = allTitleAdapter.state.value.ifBlank { defaultAllTitle },
                onClick = {
                    bottomSheetHandler.show {
                        DrawerTabTitleSheet(
                            initialTitle = allTitleAdapter.state.value.ifBlank { defaultAllTitle },
                            allowBlank = true,
                            onSave = { allTitleAdapter.onChange(it.trim()) },
                            onDismiss = { bottomSheetHandler.hide() },
                        )
                    }
                },
            )
        }

        PreferenceGroup(heading = stringResource(R.string.drawer_tabs_custom_label)) {
            PreferenceTemplate(
                title = {
                    Text(
                        text = stringResource(R.string.drawer_tabs_add),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                startWidget = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = {
                    haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    bottomSheetHandler.show {
                        DrawerTabTitleSheet(
                            initialTitle = newTabTitle,
                            onSave = { title ->
                                val current = tabsAdapter.state.value
                                val tab = DrawerTabs.create(title.trim(), current)
                                tabsAdapter.onChange(current + tab)
                                if (!enabledAdapter.state.value) enabledAdapter.onChange(true)
                                navController.navigate(AppDrawerTabEditor(tab.id))
                            },
                            onDismiss = { bottomSheetHandler.hide() },
                        )
                    }
                },
            )
            if (tabs.isEmpty()) {
                PreferenceTemplate(
                    title = {},
                    description = { Text(stringResource(R.string.drawer_tabs_empty)) },
                )
            }
        }

        if (tabs.isNotEmpty()) {
            ReorderablePreferenceGroup(
                label = null,
                items = tabs,
                defaultList = tabs,
                onOrderChange = { reordered ->
                    val byId = tabsAdapter.state.value.associateBy { it.id }
                    // Keep the latest version of each tab; drop ids that no longer exist.
                    tabsAdapter.onChange(reordered.mapNotNull { byId[it.id] })
                },
            ) { tab, _, _ ->
                val interactionSource = remember { MutableInteractionSource() }
                PreferenceTemplate(
                    title = { Text(tab.title) },
                    description = {
                        Text(
                            resources.getQuantityString(
                                R.plurals.drawer_tabs_app_count,
                                tab.apps.size,
                                tab.apps.size,
                            ),
                        )
                    },
                    startWidget = {
                        ReorderableDragHandle(interactionSource = interactionSource, scope = this)
                    },
                    endWidget = {
                        IconButton(
                            onClick = {
                                haptics.playToken(MSDLToken.SUCCESS)
                                tabsAdapter.onChange(tabsAdapter.state.value.filterNot { it.id == tab.id })
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.drawer_tabs_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                        navController.navigate(AppDrawerTabEditor(tab.id))
                    },
                    interactionSource = interactionSource,
                )
            }
        }
    }
}

/** Editor for one tab: title, "hide from All" and the app checklist. */
@Composable
fun DrawerTabEditorPreferences(
    tabId: Long,
    modifier: Modifier = Modifier,
) {
    val tabsAdapter = preferenceManager2().drawerTabs.getAdapter()
    val tab = tabsAdapter.state.value.find { it.id == tabId }
    val navController = LocalNavController.current
    val bottomSheetHandler = bottomSheetHandler
    val haptics = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)

    if (tab == null) {
        // Deleted elsewhere (or a stale long-press shortcut): show the tabs list instead.
        DrawerTabsPreferences(modifier)
        return
    }

    val selected = remember(tab.apps) { tab.apps.toSet() }
    val apps by appsState(comparator = drawerTabAppsComparator(selected))

    PreferenceLayoutLazyColumn(
        label = tab.title,
        modifier = modifier,
        enabled = apps.isNotEmpty(),
    ) {
        item {
            PreferenceGroup {
                ClickablePreference(
                    label = stringResource(R.string.drawer_tabs_title_hint),
                    subtitle = tab.title,
                    onClick = {
                        bottomSheetHandler.show {
                            DrawerTabTitleSheet(
                                initialTitle = tab.title,
                                onSave = { title ->
                                    tabsAdapter.updateTab(tabId) { it.copy(title = title.trim()) }
                                },
                                onDismiss = { bottomSheetHandler.hide() },
                            )
                        }
                    },
                )
                SwitchPreference(
                    checked = tab.hideFromAll,
                    onCheckedChange = { checked ->
                        tabsAdapter.updateTab(tabId) { it.copy(hideFromAll = checked) }
                    },
                    label = stringResource(R.string.drawer_tabs_hide_from_all),
                )
                ClickablePreference(
                    label = stringResource(R.string.drawer_tabs_delete),
                    onClick = {
                        tabsAdapter.onChange(tabsAdapter.state.value.filterNot { it.id == tabId })
                        navController.popBackStack()
                    },
                )
            }
        }
        if (apps.isNotEmpty()) {
            preferenceGroupItems(
                items = apps,
                isFirstChild = false,
                heading = {
                    "${stringResource(R.string.drawer_tabs_manage_apps)} (${selected.size})"
                },
                key = { _, app -> app.key.toString() },
            ) { _, app ->
                val key = app.key.toString()
                AppItem(
                    app = app,
                    onClick = {
                        haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                        tabsAdapter.updateTab(tabId) { current ->
                            val newApps = if (current.apps.contains(key)) {
                                current.apps - key
                            } else {
                                current.apps + key
                            }
                            current.copy(apps = newApps)
                        }
                    },
                ) {
                    Checkbox(checked = selected.contains(key), onCheckedChange = null)
                }
            }
        } else {
            preferenceGroupItems(count = 20, isFirstChild = false) {
                AppItemPlaceholder { Spacer(Modifier.width(24.dp)) }
            }
        }
    }
}

private fun PreferenceAdapter<List<DrawerTab>>.updateTab(id: Long, transform: (DrawerTab) -> DrawerTab) {
    onChange(state.value.map { if (it.id == id) transform(it) else it })
}

@Composable
private fun drawerTabAppsComparator(selected: Set<String>): Comparator<App> = remember {
    // Order is fixed when the screen opens so rows don't jump while ticking boxes.
    comparing<App, Int> { if (selected.contains(it.key.toString())) 0 else 1 }.then(appComparator)
}

@Composable
private fun DrawerTabTitleSheet(
    initialTitle: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    allowBlank: Boolean = false,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialTitle)) }
    val isBlank = value.text.isBlank()
    ModalBottomSheetContent(
        buttons = {
            OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onDismiss()
                    onSave(value.text)
                },
                enabled = allowBlank || !isBlank,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Column {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.drawer_tabs_title_hint)) },
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                singleLine = true,
                isError = !allowBlank && isBlank,
            )
        }
    }
}
