package app.lawnchair.ui.preferences.destinations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lawnchair.feed.rss.FeedSource
import app.lawnchair.feed.rss.FeedSources
import app.lawnchair.feed.rss.NotAFeedException
import app.lawnchair.feed.rss.RssFetcher
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.ModalBottomSheetContent
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import app.lawnchair.ui.preferences.components.controls.TextPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.navigation.HomeScreenFeedSources
import app.lawnchair.ui.util.bottomSheetHandler
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.android.msdl.data.model.MSDLToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Entry in the home screen settings, shown while the built-in feed is selected. */
@Composable
fun FeedSourcesPreferenceItem(
    modifier: Modifier = Modifier,
) {
    val sources = preferenceManager2().feedSources.getAdapter().state.value.filter { it.enabled }
    NavigationActionPreference(
        label = stringResource(R.string.feed_sources_label),
        destination = HomeScreenFeedSources,
        subtitle = if (sources.isEmpty()) {
            stringResource(R.string.feed_sources_description)
        } else {
            sources.joinToString(", ") { it.title }
        },
        modifier = modifier,
    )
}

/**
 * RSS settings of the built-in feed: the title above the headlines, how many are shown, and the
 * feeds they come from, which are added by address, switched on or off and removed.
 */
@Composable
fun FeedSourcesPreferences(
    modifier: Modifier = Modifier,
) {
    val prefs2 = preferenceManager2()
    val sourcesAdapter = prefs2.feedSources.getAdapter()
    val sources = sourcesAdapter.state.value
    val showTitleAdapter = prefs2.feedHeadlinesShowTitle.getAdapter()
    val defaultTitle = stringResource(R.string.feed_headlines_title)
    val limitEntries = remember {
        FeedSources.HEADLINE_LIMITS.map { limit -> ListPreferenceEntry(limit) { limit.toString() } }
    }
    val bottomSheetHandler = bottomSheetHandler
    val haptics = MSDLPlayerWrapper.INSTANCE.get(LocalContext.current)

    PreferenceLayout(
        label = stringResource(R.string.feed_sources_label),
        backArrowVisible = true,
        modifier = modifier,
    ) {
        PreferenceGroup(heading = stringResource(R.string.feed_headlines_label)) {
            SwitchPreference(
                adapter = showTitleAdapter,
                label = stringResource(R.string.feed_widgets_show_title),
            )
            TextPreference(
                adapter = prefs2.feedHeadlinesTitle.getAdapter(),
                label = stringResource(R.string.feed_widgets_title),
                enabled = showTitleAdapter.state.value,
                // Left empty, the title is the default one.
                description = { it.ifBlank { defaultTitle } },
            )
            ListPreference(
                adapter = prefs2.feedMaxHeadlines.getAdapter(),
                entries = limitEntries,
                label = stringResource(R.string.feed_headlines_max),
            )
        }

        PreferenceGroup(
            heading = stringResource(R.string.feed_sources_list_label),
            description = stringResource(R.string.feed_sources_description),
        ) {
            PreferenceTemplate(
                title = {
                    Text(
                        text = stringResource(R.string.feed_sources_add),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                startWidget = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = {
                    haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    bottomSheetHandler.show {
                        AddFeedSourceSheet(
                            existing = { sourcesAdapter.state.value },
                            onAdd = { source -> sourcesAdapter.onChange(sourcesAdapter.state.value + source) },
                            onDismiss = { bottomSheetHandler.hide() },
                        )
                    }
                },
            )
            if (sources.isEmpty()) {
                PreferenceTemplate(
                    title = {},
                    description = { Text(stringResource(R.string.feed_sources_empty)) },
                )
            }
        }

        if (sources.isNotEmpty()) {
            PreferenceGroup {
                sources.forEach { source ->
                    // Switched off, a feed stays in the list but its headlines leave the feed.
                    val setEnabled = { enabled: Boolean ->
                        haptics.playToken(if (enabled) MSDLToken.SWITCH_ON else MSDLToken.SWITCH_OFF)
                        sourcesAdapter.onChange(
                            sourcesAdapter.state.value.map { if (it.id == source.id) it.copy(enabled = enabled) else it },
                        )
                    }
                    PreferenceTemplate(
                        title = { Text(source.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        description = { Text(source.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        endWidget = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        haptics.playToken(MSDLToken.SUCCESS)
                                        sourcesAdapter.onChange(sourcesAdapter.state.value.filterNot { it.id == source.id })
                                    },
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.feed_sources_remove),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = source.enabled, onCheckedChange = setEnabled)
                            }
                        },
                        onClick = { setEnabled(!source.enabled) },
                    )
                }
            }
        }
    }
}

/**
 * Asks for an address and checks it before adding: the source is only saved once a feed was
 * actually found there, and it is named after that feed.
 */
@Composable
private fun AddFeedSourceSheet(
    existing: () -> List<FeedSource>,
    onAdd: (FeedSource) -> Unit,
    onDismiss: () -> Unit,
) {
    var address by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        val url = RssFetcher.normalizeUrl(address)
        if (url == null) {
            error = R.string.feed_sources_error_address
            return
        }
        checking = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { RssFetcher.resolve(url) } }
            checking = false
            val resolved = result.getOrNull()
            when {
                resolved == null -> error = if (result.exceptionOrNull() is NotAFeedException) {
                    R.string.feed_sources_error_not_feed
                } else {
                    R.string.feed_sources_error_network
                }

                existing().any { it.url == resolved.url } -> error = R.string.feed_sources_error_duplicate

                else -> {
                    val title = resolved.feed.title.ifBlank { resolved.url.toHttpUrlOrNull()?.host ?: resolved.url }
                    onAdd(FeedSources.create(resolved.url, title, existing()))
                    onDismiss()
                }
            }
        }
    }

    ModalBottomSheetContent(
        buttons = {
            OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = ::submit,
                enabled = address.isNotBlank() && !checking,
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(if (checking) R.string.feed_sources_checking else R.string.feed_sources_add))
            }
        },
    ) {
        Column {
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    error = null
                },
                label = { Text(stringResource(R.string.feed_sources_address_hint)) },
                supportingText = error?.let { { Text(stringResource(it)) } },
                isError = error != null,
                enabled = !checking,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            )
        }
    }
}
