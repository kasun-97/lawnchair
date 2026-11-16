package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import app.lawnchair.feed.FeedBlocks
import app.lawnchair.feed.sections.FeedAppSection
import app.lawnchair.feed.sections.FeedAppSections
import app.lawnchair.preferences.PreferenceAdapter
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.AppItem
import app.lawnchair.ui.preferences.components.AppItemPlaceholder
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.appSearchEmpty
import app.lawnchair.ui.preferences.components.appSearchField
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.filterByQuery
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.components.reorderable.ReorderableDragHandle
import app.lawnchair.ui.preferences.components.reorderable.ReorderablePreferenceGroup
import app.lawnchair.ui.preferences.navigation.HomeScreenFeedSectionEditor
import app.lawnchair.ui.preferences.navigation.HomeScreenFeedSections
import app.lawnchair.ui.util.bottomSheetHandler
import app.lawnchair.util.App
import app.lawnchair.util.appComparator
import app.lawnchair.util.appsState
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import java.util.Comparator.comparing

/** Entry in the built-in feed settings. */
@Composable
fun FeedSectionsPreferenceItem(
    modifier: Modifier = Modifier,
) {
    val sections = preferenceManager2().feedAppSections.getAdapter().state.value
    NavigationActionPreference(
        label = stringResource(R.string.feed_sections_label),
        destination = HomeScreenFeedSections,
        subtitle = if (sections.isEmpty()) {
            stringResource(R.string.feed_sections_description)
        } else {
            sections.joinToString(", ") { it.title }
        },
        modifier = modifier,
    )
}

/** App sections of the built-in feed: add, reorder, delete. */
@Composable
fun FeedSectionsPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val sectionsAdapter = prefs2.feedAppSections.getAdapter()
    val orderAdapter = prefs2.feedBlockOrder.getAdapter()
    val widgets = prefs2.feedWidgets.getAdapter().state.value
    // Listed in the order they have in the feed, where widgets may sit between them.
    val order = FeedBlocks.resolveOrder(orderAdapter.state.value, widgets, sectionsAdapter.state.value)
    val sections = sectionsAdapter.state.value.sortedBy { order.indexOf(FeedBlocks.sectionKey(it.id)) }
    val navController = LocalNavController.current
    val bottomSheetHandler = bottomSheetHandler
    val haptics = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)
    val resources = LocalResources.current
    val titleLabel = stringResource(R.string.feed_sections_title_hint)
    val newSectionTitle = stringResource(R.string.feed_sections_new)

    PreferenceLayout(
        label = stringResource(R.string.feed_sections_label),
        backArrowVisible = true,
        modifier = modifier,
    ) {
        PreferenceGroup(description = stringResource(R.string.feed_sections_description)) {
            PreferenceTemplate(
                title = {
                    Text(
                        text = stringResource(R.string.feed_sections_add),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                startWidget = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = {
                    haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    bottomSheetHandler.show {
                        DrawerTabTitleSheet(
                            initialTitle = newSectionTitle,
                            label = titleLabel,
                            onSave = { title ->
                                val current = sectionsAdapter.state.value
                                val section = FeedAppSections.create(title.trim(), current)
                                sectionsAdapter.onChange(current + section)
                                navController.navigate(HomeScreenFeedSectionEditor(section.id))
                            },
                            onDismiss = { bottomSheetHandler.hide() },
                        )
                    }
                },
            )
            if (sections.isEmpty()) {
                PreferenceTemplate(
                    title = {},
                    description = { Text(stringResource(R.string.feed_sections_empty)) },
                )
            }
        }

        if (sections.isNotEmpty()) {
            ReorderablePreferenceGroup(
                label = null,
                items = sections,
                defaultList = sections,
                onOrderChange = { reordered ->
                    orderAdapter.onChange(
                        FeedBlocks.withKindOrder(order, FeedBlocks.SECTION, reordered.map { FeedBlocks.sectionKey(it.id) }),
                    )
                },
            ) { section, _, _ ->
                val interactionSource = remember { MutableInteractionSource() }
                PreferenceTemplate(
                    title = { Text(section.title) },
                    description = {
                        Text(
                            resources.getQuantityString(
                                R.plurals.drawer_tabs_app_count,
                                section.apps.size,
                                section.apps.size,
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
                                sectionsAdapter.onChange(sectionsAdapter.state.value.filterNot { it.id == section.id })
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.feed_sections_delete),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                        navController.navigate(HomeScreenFeedSectionEditor(section.id))
                    },
                    interactionSource = interactionSource,
                )
            }
        }
    }
}

/** Editor for one section: title and the app checklist. */
@Composable
fun FeedSectionEditorPreferences(
    sectionId: Long,
    modifier: Modifier = Modifier,
) {
    val sectionsAdapter = preferenceManager2().feedAppSections.getAdapter()
    val section = sectionsAdapter.state.value.find { it.id == sectionId }
    val navController = LocalNavController.current
    val bottomSheetHandler = bottomSheetHandler
    val haptics = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)
    val titleLabel = stringResource(R.string.feed_sections_title_hint)

    if (section == null) {
        // Deleted elsewhere: show the sections list instead.
        FeedSectionsPreferences(modifier)
        return
    }

    val selected = remember(section.apps) { section.apps.toSet() }
    val apps by appsState(comparator = sectionAppsComparator(selected))
    var query by remember { mutableStateOf("") }
    val shownApps = remember(apps, query) { apps.filterByQuery(query) }

    PreferenceLayoutLazyColumn(
        label = section.title,
        modifier = modifier,
        enabled = apps.isNotEmpty(),
    ) {
        item {
            PreferenceGroup {
                ClickablePreference(
                    label = titleLabel,
                    subtitle = section.title,
                    onClick = {
                        bottomSheetHandler.show {
                            DrawerTabTitleSheet(
                                initialTitle = section.title,
                                label = titleLabel,
                                onSave = { title ->
                                    sectionsAdapter.updateSection(sectionId) { it.copy(title = title.trim()) }
                                },
                                onDismiss = { bottomSheetHandler.hide() },
                            )
                        }
                    },
                )
                ClickablePreference(
                    label = stringResource(R.string.feed_sections_delete),
                    onClick = {
                        sectionsAdapter.onChange(sectionsAdapter.state.value.filterNot { it.id == sectionId })
                        navController.popBackStack()
                    },
                )
            }
        }
        if (apps.isNotEmpty()) {
            appSearchField(query = query, onQueryChange = { query = it })
            if (shownApps.isEmpty()) appSearchEmpty(query)
            preferenceGroupItems(
                items = shownApps,
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
                        sectionsAdapter.updateSection(sectionId) { current ->
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

private fun PreferenceAdapter<List<FeedAppSection>>.updateSection(id: Long, transform: (FeedAppSection) -> FeedAppSection) {
    onChange(state.value.map { if (it.id == id) transform(it) else it })
}

@Composable
private fun sectionAppsComparator(selected: Set<String>): Comparator<App> = remember {
    // Order is fixed when the screen opens so rows don't jump while ticking boxes.
    comparing<App, Int> { if (selected.contains(it.key.toString())) 0 else 1 }.then(appComparator)
}
