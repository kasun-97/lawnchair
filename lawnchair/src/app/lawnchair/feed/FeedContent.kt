package app.lawnchair.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.feed.rss.FeedArticle
import app.lawnchair.feed.rss.RssRepository
import app.lawnchair.feed.sections.FeedAppSectionBlock
import app.lawnchair.feed.sections.launcherAppsState
import app.lawnchair.feed.sections.resolveApps
import app.lawnchair.feed.widgets.FeedWidget
import app.lawnchair.feed.widgets.FeedWidgetBlock
import app.lawnchair.feed.widgets.FeedWidgetHost
import app.lawnchair.launcherNullable
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.HomeScreenFeed
import coil.compose.AsyncImage
import com.android.launcher3.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val HorizontalPadding = 20.dp

/**
 * Content of the built-in feed. [contentPadding] keeps it clear of the system bars.
 * [resizingWidgetId] is the widget showing its resize frame; touching anything else ends that.
 *
 * Widgets and app sections are picked up by holding them: dragged on, they change places. Let go
 * where it was, a block held by its title is renamed and a widget held elsewhere shows its resize
 * frame. [onReorderingChange] reports while a block is held.
 *
 * The headlines come last, under a title of their own that is renamed by holding it too. Once
 * they are scrolled into, a button leads back to the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedContent(
    contentPadding: PaddingValues,
    resizingWidgetId: Long?,
    onResizingWidgetChange: (Long?) -> Unit,
    onReorderingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember { RssRepository.getInstance(context) }
    val state by repository.state.collectAsStateWithLifecycle()
    val prefs2 = preferenceManager2()
    val sources by prefs2.feedSources.asState()
    val rssEnabled by prefs2.feedRssEnabled.asState()
    val hasSources = rssEnabled && sources.any { it.enabled }
    val maxHeadlines by prefs2.feedMaxHeadlines.asState()
    val articles = remember(state.articles, maxHeadlines) { state.articles.take(maxHeadlines) }
    val showHeadlinesTitle by prefs2.feedHeadlinesShowTitle.asState()
    val savedHeadlinesTitle by prefs2.feedHeadlinesTitle.asState()
    val defaultHeadlinesTitle = stringResource(R.string.feed_headlines_title)
    val headlinesTitle = savedHeadlinesTitle.ifBlank { defaultHeadlinesTitle }
    val widgets by prefs2.feedWidgets.asState()
    val sections by prefs2.feedAppSections.asState()
    val apps by launcherAppsState()
    val columns = remember(context) {
        (context.launcherNullable?.deviceProfile?.numShownAllAppsColumns ?: 0).takeIf { it > 0 } ?: 4
    }
    val layoutDirection = LocalLayoutDirection.current
    val haptics = LocalHapticFeedback.current

    val savedOrder by prefs2.feedBlockOrder.asState()
    // Follows the drag at once; the saved order catches up when the block is dropped.
    var blockOrder by remember(savedOrder, widgets, sections) {
        mutableStateOf(FeedBlocks.resolveOrder(savedOrder, widgets, sections))
    }
    val widgetsByKey = remember(widgets) { widgets.associateBy { FeedBlocks.widgetKey(it.id) } }
    val sectionsByKey = remember(sections) { sections.associateBy { FeedBlocks.sectionKey(it.id) } }
    val listState = rememberLazyListState()
    // The header and the blocks come before the headlines and their title.
    val firstHeadlineIndex by rememberUpdatedState(1 + blockOrder.size + if (showHeadlinesTitle) 1 else 0)
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > firstHeadlineIndex }
    }
    // Set while the question whether to remove this widget is on screen.
    var widgetToRemove by remember { mutableStateOf<FeedWidget?>(null) }
    // Set while the title of this block is being edited.
    var renaming by remember { mutableStateOf<RenameTarget?>(null) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = blockOrder.indexOf(from.key)
        val toIndex = blockOrder.indexOf(to.key)
        if (fromIndex >= 0 && toIndex >= 0) {
            blockOrder = blockOrder.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        }
    }

    widgetToRemove?.let { widget ->
        AlertDialog(
            onDismissRequest = { widgetToRemove = null },
            title = { Text(stringResource(R.string.feed_widgets_remove_title)) },
            text = { Text(stringResource(R.string.feed_widgets_remove_message, widget.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        widgetToRemove = null
                        onResizingWidgetChange(null)
                        scope.launch { FeedWidgetHost.getInstance(context).remove(widget) }
                    },
                ) { Text(stringResource(R.string.remove_drop_target_label)) }
            },
            dismissButton = {
                TextButton(onClick = { widgetToRemove = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    renaming?.let { target ->
        RenameDialog(
            target = target,
            onDismiss = { renaming = null },
            onSave = { text ->
                renaming = null
                val widget = widgetsByKey[target.key]
                val section = sectionsByKey[target.key]
                scope.launch {
                    if (target.key == HEADLINES_KEY) {
                        // Emptied: the title is hidden and the name is kept.
                        if (text.isNotEmpty()) {
                            prefs2.feedHeadlinesTitle.set(if (text == defaultHeadlinesTitle) "" else text)
                        }
                        prefs2.feedHeadlinesShowTitle.set(text.isNotEmpty())
                    } else if (widget != null) {
                        prefs2.feedWidgets.set(
                            prefs2.feedWidgets.get().first().map {
                                when {
                                    it.id != widget.id -> it

                                    // Emptied: the title is hidden and the name is kept.
                                    text.isEmpty() -> it.copy(showTitle = false)

                                    else -> it.withTitle(text).copy(showTitle = true)
                                }
                            },
                        )
                    } else if (section != null) {
                        prefs2.feedAppSections.set(
                            prefs2.feedAppSections.get().first().map { if (it.id == section.id) it.copy(title = text) else it },
                        )
                    }
                }
            },
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { repository.refresh(force = true) },
            // Kept below the status bar so the refresh indicator is not drawn under it.
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = contentPadding.calculateStartPadding(layoutDirection),
                    top = contentPadding.calculateTopPadding(),
                    end = contentPadding.calculateEndPadding(layoutDirection),
                ),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(resizingWidgetId) {
                        if (resizingWidgetId == null) return@pointerInput
                        // Runs after the content: the resize frame has consumed touches on it.
                        // A tap anywhere else ends resizing; scrolling the feed does not.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (!down.isConsumed && waitForUpOrCancellation() != null) {
                                onResizingWidgetChange(null)
                            }
                        }
                    },
                state = listState,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                item(key = "header") {
                    FeedHeader(onOpenSettings = { openFeedSettings(context) })
                }
                items(blockOrder, key = { it }) { key ->
                    ReorderableItem(reorderState, key = key) { isDragging ->
                        val widget = widgetsByKey[key]
                        val section = sectionsByKey[key]
                        val resizing = widget != null && widget.id == resizingWidgetId
                        var titleBottom by remember { mutableFloatStateOf(0f) }
                        val onHeldRelease by rememberUpdatedState { heldAt: Offset ->
                            onReorderingChange(false)
                            // Let go where it was: a title is renamed, a widget shows its resize frame.
                            val onTitle = titleBottom > 0f && heldAt.y < titleBottom
                            when {
                                onTitle && widget != null -> renaming = RenameTarget(key, widget.shownTitle, canBeEmpty = true)
                                onTitle && section != null -> renaming = RenameTarget(key, section.title, canBeEmpty = false)
                                widget != null -> onResizingWidgetChange(widget.id)
                            }
                        }
                        val holdDetector = remember {
                            HoldDragDetector(
                                onHeld = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onReorderingChange(true)
                                },
                                onHeldRelease = { onHeldRelease(it) },
                            )
                        }
                        val blockModifier = Modifier
                            .draggableHandle(
                                // The handles of the resize frame are dragged instead.
                                enabled = !resizing,
                                onDragStopped = {
                                    onReorderingChange(false)
                                    scope.launch { prefs2.feedBlockOrder.set(blockOrder) }
                                },
                                dragGestureDetector = holdDetector,
                            )
                            .graphicsLayer {
                                if (isDragging) {
                                    scaleX = 1.02f
                                    scaleY = 1.02f
                                    alpha = 0.92f
                                }
                            }
                        when {
                            widget != null -> FeedWidgetBlock(
                                widget = widget,
                                resizing = resizing,
                                onResize = { resized ->
                                    scope.launch {
                                        prefs2.feedWidgets.set(
                                            prefs2.feedWidgets.get().first().map { if (it.id == resized.id) resized else it },
                                        )
                                    }
                                },
                                onRemove = { widgetToRemove = widget },
                                onTitleBottomChange = { titleBottom = it },
                                modifier = blockModifier,
                            )

                            section != null -> {
                                val sectionApps = remember(section, apps) { section.resolveApps(apps) }
                                if (sectionApps.isNotEmpty()) {
                                    FeedAppSectionBlock(
                                        title = section.title,
                                        apps = sectionApps,
                                        columns = columns,
                                        onTitleBottomChange = { titleBottom = it },
                                        modifier = blockModifier,
                                    )
                                }
                            }
                        }
                    }
                }
                if (!hasSources) {
                    if (sections.isEmpty() && widgets.isEmpty()) {
                        item(key = "message") {
                            FeedMessage(
                                text = stringResource(R.string.feed_empty),
                                action = stringResource(R.string.feed_set_up),
                                onAction = { openFeedSettings(context) },
                            )
                        }
                    }
                } else {
                    if (showHeadlinesTitle) {
                        item(key = HEADLINES_KEY) {
                            HeadlinesTitle(
                                title = headlinesTitle,
                                onHold = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    renaming = RenameTarget(HEADLINES_KEY, headlinesTitle, canBeEmpty = true)
                                },
                            )
                        }
                    }
                    if (articles.isEmpty()) {
                        item(key = "message") {
                            FeedMessage(
                                text = stringResource(
                                    when {
                                        state.failed -> R.string.feed_load_failed
                                        state.refreshing || !state.ready -> R.string.feed_loading
                                        else -> R.string.feed_no_headlines
                                    },
                                ),
                            )
                        }
                    } else {
                        items(articles, key = { "${it.sourceId}:${it.link}" }) { article ->
                            ArticleRow(article = article, onClick = { openArticle(context, article) })
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = hasSources && showScrollToTop,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = stringResource(R.string.feed_scroll_to_top),
                    )
                }
            }
        }
    }
}

/** Key of the headlines' title, in the list and as what is being renamed. */
private const val HEADLINES_KEY = "headlines"

/** A block whose title is being edited: its key in the feed's order and the title to start from. */
private class RenameTarget(
    val key: String,
    val current: String,
    val canBeEmpty: Boolean,
)

/** Asks for a new title. An empty one is only accepted where the title may be left out. */
@Composable
private fun RenameDialog(
    target: RenameTarget,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // The whole title starts out selected, so typing replaces it.
    var value by remember {
        mutableStateOf(TextFieldValue(target.current, TextRange(0, target.current.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val text = value.text.trim()
    val valid = target.canBeEmpty || text.isNotEmpty()
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.feed_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.feed_widgets_title)) },
                supportingText = if (target.canBeEmpty) {
                    { Text(stringResource(R.string.feed_widgets_title_hint)) }
                } else {
                    null
                },
                isError = !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (valid) onSave(text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = valid) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun FeedHeader(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = HorizontalPadding, top = 16.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.minus_one),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.feed_built_in),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Title above the headlines, looking like the titles of the widgets and app sections. */
@Composable
private fun HeadlinesTitle(
    title: String,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnHold by rememberUpdatedState(onHold)
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) { detectTapGestures(onLongPress = { currentOnHold() }) }
            // With the padding of the first headline, as far from them as an app section's title is from its apps.
            .padding(start = HorizontalPadding, top = 8.dp, end = HorizontalPadding),
    )
}

@Composable
private fun FeedMessage(
    text: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = HorizontalPadding, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun ArticleRow(
    article: FeedArticle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = HorizontalPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = articleCaption(article),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (article.image != null) {
            Spacer(Modifier.width(16.dp))
            AsyncImage(
                model = article.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        }
    }
}

/** "Source · 2 hr. ago", or just the source when the feed gives no date. */
private fun articleCaption(article: FeedArticle): String {
    if (article.published <= 0) return article.source
    val age = DateUtils.getRelativeTimeSpanString(
        article.published,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    )
    return "${article.source} · $age"
}

private fun openArticle(context: Context, article: FeedArticle) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(article.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun openFeedSettings(context: Context) {
    context.startActivity(
        PreferenceActivity.createIntent(context, HomeScreenFeed)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
}
