package app.lawnchair.ui.preferences.destinations

import android.app.Activity
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lawnchair.feed.FeedBlocks
import app.lawnchair.feed.widgets.FeedWidget
import app.lawnchair.feed.widgets.FeedWidgetHost
import app.lawnchair.feed.widgets.FeedWidgetPreview
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.ModalBottomSheetContent
import app.lawnchair.ui.preferences.LocalNavController
import app.lawnchair.ui.preferences.components.NavigationActionPreference
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceLayoutLazyColumn
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import app.lawnchair.ui.preferences.components.layout.preferenceGroupItems
import app.lawnchair.ui.preferences.components.reorderable.ReorderableDragHandle
import app.lawnchair.ui.preferences.components.reorderable.ReorderablePreferenceGroup
import app.lawnchair.ui.preferences.navigation.HomeScreenFeedWidgetPicker
import app.lawnchair.ui.preferences.navigation.HomeScreenFeedWidgets
import app.lawnchair.ui.util.bottomSheetHandler
import com.android.launcher3.R
import com.android.launcher3.util.MSDLPlayerWrapper
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.google.android.msdl.data.model.MSDLToken
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entry in the built-in feed settings. */
@Composable
fun FeedWidgetsPreferenceItem(
    modifier: Modifier = Modifier,
) {
    val widgets = preferenceManager2().feedWidgets.getAdapter().state.value
    NavigationActionPreference(
        label = stringResource(R.string.feed_widgets_label),
        destination = HomeScreenFeedWidgets,
        subtitle = if (widgets.isEmpty()) {
            stringResource(R.string.feed_widgets_description)
        } else {
            widgets.joinToString(", ") { it.label }
        },
        modifier = modifier,
    )
}

/** Widgets of the built-in feed: add, reorder, set the height, remove. */
@Composable
fun FeedWidgetsPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = remember { FeedWidgetHost.getInstance(context) }
    val prefs2 = preferenceManager2()
    val widgetsAdapter = prefs2.feedWidgets.getAdapter()
    val orderAdapter = prefs2.feedBlockOrder.getAdapter()
    val sections = prefs2.feedAppSections.getAdapter().state.value
    // Listed in the order they have in the feed, where app sections may sit between them.
    val order = FeedBlocks.resolveOrder(orderAdapter.state.value, widgetsAdapter.state.value, sections)
    val widgets = widgetsAdapter.state.value.sortedBy { order.indexOf(FeedBlocks.widgetKey(it.id)) }
    val navController = LocalNavController.current
    val bottomSheetHandler = bottomSheetHandler
    val haptics = MSDLPlayerWrapper.INSTANCE.get(context)
    val scope = rememberCoroutineScope()

    LaunchedEffect(host) { host.deleteOrphans() }

    PreferenceLayout(
        label = stringResource(R.string.feed_widgets_label),
        backArrowVisible = true,
        modifier = modifier,
    ) {
        PreferenceGroup(description = stringResource(R.string.feed_widgets_description)) {
            PreferenceTemplate(
                title = {
                    Text(
                        text = stringResource(R.string.feed_widgets_add),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                },
                startWidget = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = {
                    haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    navController.navigate(HomeScreenFeedWidgetPicker)
                },
            )
            if (widgets.isEmpty()) {
                PreferenceTemplate(
                    title = {},
                    description = { Text(stringResource(R.string.feed_widgets_empty)) },
                )
            }
        }

        if (widgets.isNotEmpty()) {
            ReorderablePreferenceGroup(
                label = null,
                items = widgets,
                defaultList = widgets,
                onOrderChange = { reordered ->
                    orderAdapter.onChange(
                        FeedBlocks.withKindOrder(order, FeedBlocks.WIDGET, reordered.map { FeedBlocks.widgetKey(it.id) }),
                    )
                },
            ) { widget, _, _ ->
                val interactionSource = remember { MutableInteractionSource() }
                PreferenceTemplate(
                    title = { Text(widget.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    description = {
                        Text(
                            stringResource(R.string.feed_widgets_height) + ": " +
                                stringResource(R.string.feed_widgets_height_value, widget.heightDp),
                        )
                    },
                    startWidget = {
                        ReorderableDragHandle(interactionSource = interactionSource, scope = this)
                    },
                    endWidget = {
                        IconButton(
                            onClick = {
                                haptics.playToken(MSDLToken.SUCCESS)
                                scope.launch { host.remove(widget) }
                            },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.feed_widgets_remove),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                        bottomSheetHandler.show {
                            WidgetHeightSheet(
                                widget = widget,
                                onSave = { height ->
                                    widgetsAdapter.onChange(
                                        widgetsAdapter.state.value.map {
                                            if (it.id == widget.id) it.copy(heightDp = height) else it
                                        },
                                    )
                                },
                                onDismiss = { bottomSheetHandler.hide() },
                            )
                        }
                    },
                    interactionSource = interactionSource,
                )
            }
        }
    }
}

@Composable
private fun WidgetHeightSheet(
    widget: FeedWidget,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var height by remember { mutableFloatStateOf(widget.heightDp.toFloat()) }
    ModalBottomSheetContent(
        title = { Text(widget.label) },
        buttons = {
            OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onDismiss()
                    onSave(height.roundToInt())
                },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.feed_widgets_height) + ": " +
                    stringResource(R.string.feed_widgets_height_value, height.roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = height,
                // Whole steps of 8 dp.
                onValueChange = { height = (it / 8f).roundToInt() * 8f },
                valueRange = FeedWidget.MIN_HEIGHT_DP.toFloat()..FeedWidget.MAX_HEIGHT_DP.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private class WidgetChoice(
    val info: AppWidgetProviderInfo,
    val label: String,
    val appLabel: String,
    val icon: Drawable?,
    val spanX: Int,
    val spanY: Int,
) {
    val key = "${info.provider.flattenToShortString()}#${info.profile.hashCode()}"
}

/**
 * Lists the widgets of every app. Picking one runs the add flow of [FeedWidgetHost], which may
 * show the system's permission screen and the widget's own set-up screen before it is saved.
 */
@Composable
fun FeedWidgetPickerPreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val host = remember { FeedWidgetHost.getInstance(context) }
    val navController = LocalNavController.current
    val haptics = MSDLPlayerWrapper.INSTANCE.get(context)

    val choices by produceState<List<WidgetChoice>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadChoices(context, host) }
    }
    val bindLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (activity != null) host.onBindResult(activity, result.resultCode == Activity.RESULT_OK)
    }
    DisposableEffect(host) {
        host.onAddFinished = { added ->
            if (added) {
                navController.popBackStack()
            } else {
                Toast.makeText(context, R.string.feed_widgets_add_failed, Toast.LENGTH_SHORT).show()
            }
        }
        onDispose { host.onAddFinished = null }
    }

    PreferenceLayoutLazyColumn(
        label = stringResource(R.string.feed_widgets_add),
        modifier = modifier,
        enabled = choices != null,
    ) {
        preferenceGroupItems(
            items = choices.orEmpty(),
            isFirstChild = true,
            key = { _, choice -> choice.key },
        ) { _, choice ->
            PreferenceTemplate(
                title = { Text(choice.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                description = {
                    Column {
                        Text(
                            text = choice.appLabel + " · " +
                                stringResource(R.string.feed_widgets_size, choice.spanX, choice.spanY),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        FeedWidgetPreview(
                            info = choice.info,
                            spanX = choice.spanX,
                            spanY = choice.spanY,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                verticalAlignment = Alignment.Top,
                startWidget = {
                    if (choice.icon != null) {
                        Image(
                            painter = rememberDrawablePainter(choice.icon),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Spacer(Modifier.size(36.dp))
                    }
                },
                onClick = {
                    haptics.playToken(MSDLToken.TAP_MEDIUM_EMPHASIS)
                    if (activity != null) host.add(activity, choice.info) { bindLauncher.launch(it) }
                },
            )
        }
    }
}

private fun loadChoices(context: Context, host: FeedWidgetHost): List<WidgetChoice> {
    val packageManager = context.packageManager
    val density = context.resources.displayMetrics.densityDpi
    val appLabels = HashMap<String, String>()
    return host.availableProviders().map { info ->
        val packageName = info.provider.packageName
        WidgetChoice(
            info = info,
            label = info.loadLabel(packageManager).orEmpty(),
            appLabel = appLabels.getOrPut(packageName) {
                runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                }.getOrDefault(packageName)
            },
            icon = runCatching { info.loadIcon(context, density) }.getOrNull(),
            spanX = host.spanX(info),
            spanY = host.spanY(info),
        )
    }.sortedWith(
        compareBy<WidgetChoice> { it.appLabel.lowercase(Locale.getDefault()) }
            .thenBy { it.label.lowercase(Locale.getDefault()) },
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
