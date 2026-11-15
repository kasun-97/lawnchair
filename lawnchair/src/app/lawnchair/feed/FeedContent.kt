package app.lawnchair.feed

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.feed.rss.FeedArticle
import app.lawnchair.feed.rss.RssRepository
import app.lawnchair.feed.sections.FeedAppSectionBlock
import app.lawnchair.feed.sections.launcherAppsState
import app.lawnchair.feed.sections.resolveApps
import app.lawnchair.feed.widgets.FeedWidgetBlock
import app.lawnchair.launcherNullable
import app.lawnchair.preferences2.asState
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.HomeScreenFeed
import coil.compose.AsyncImage
import com.android.launcher3.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val HorizontalPadding = 20.dp

/**
 * Content of the built-in feed. [contentPadding] keeps it clear of the system bars.
 * [resizingWidgetId] is the widget showing its resize frame; touching anything else ends that.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedContent(
    contentPadding: PaddingValues,
    resizingWidgetId: Long?,
    onResizingWidgetChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val repository = remember { RssRepository.getInstance(context) }
    val state by repository.state.collectAsStateWithLifecycle()
    val prefs2 = preferenceManager2()
    val sources by prefs2.feedSources.asState()
    val widgets by prefs2.feedWidgets.asState()
    val sections by prefs2.feedAppSections.asState()
    val apps by launcherAppsState()
    val columns = remember(context) {
        (context.launcherNullable?.deviceProfile?.numShownAllAppsColumns ?: 0).takeIf { it > 0 } ?: 4
    }
    val layoutDirection = LocalLayoutDirection.current

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
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                item(key = "header") {
                    FeedHeader(onOpenSettings = { openFeedSettings(context) })
                }
                items(widgets, key = { "widget:${it.id}" }) { widget ->
                    FeedWidgetBlock(
                        widget = widget,
                        resizing = widget.id == resizingWidgetId,
                        onResizeStart = { onResizingWidgetChange(widget.id) },
                        onResized = { resized ->
                            scope.launch {
                                prefs2.feedWidgets.set(
                                    prefs2.feedWidgets.get().first().map { if (it.id == resized.id) resized else it },
                                )
                            }
                        },
                    )
                }
                items(sections, key = { "section:${it.id}" }) { section ->
                    val sectionApps = remember(section, apps) { section.resolveApps(apps) }
                    if (sectionApps.isNotEmpty()) {
                        FeedAppSectionBlock(title = section.title, apps = sectionApps, columns = columns)
                    }
                }
                when {
                    sources.isEmpty() -> if (sections.isEmpty() && widgets.isEmpty()) {
                        item(key = "message") {
                            FeedMessage(
                                text = stringResource(R.string.feed_empty),
                                action = stringResource(R.string.feed_set_up),
                                onAction = { openFeedSettings(context) },
                            )
                        }
                    }

                    state.articles.isEmpty() -> item(key = "message") {
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

                    else -> items(state.articles, key = { "${it.sourceId}:${it.link}" }) { article ->
                        ArticleRow(article = article, onClick = { openArticle(context, article) })
                    }
                }
            }
        }
    }
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
        PreferenceActivity.createIntent(context, HomeScreenFeed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
