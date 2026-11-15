package app.lawnchair.feed.sections

import android.app.ActivityOptions
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lawnchair.launcherNullable
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.model.data.AppInfo
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import java.util.Locale

private val IconSize = 52.dp

/**
 * The launcher's loaded apps by ComponentKey string, kept current as apps are installed,
 * removed or re-themed. Empty outside the launcher activity.
 */
@Composable
fun launcherAppsState(): State<Map<String, AppInfo>> {
    val store = LocalContext.current.launcherNullable?.appsView?.appsStore
    val state = remember(store) { mutableStateOf(store.toAppMap()) }
    DisposableEffect(store) {
        val listener = AllAppsStore.OnUpdateListener { state.value = store.toAppMap() }
        store?.addUpdateListener(listener)
        // Apps may have been bound between composition and this effect.
        state.value = store.toAppMap()
        onDispose { store?.removeUpdateListener(listener) }
    }
    return state
}

private fun AllAppsStore<*>?.toAppMap(): Map<String, AppInfo> = this?.apps?.associateBy { it.toComponentKey().toString() }.orEmpty()

/** The apps of [section] that are still installed, by name. */
fun FeedAppSection.resolveApps(apps: Map<String, AppInfo>): List<AppInfo> = this.apps
    .mapNotNull(apps::get)
    .sortedBy { it.title?.toString().orEmpty().lowercase(Locale.getDefault()) }

/** One section of the feed: its title over a grid of app icons. */
@Composable
fun FeedAppSectionBlock(
    title: String,
    apps: List<AppInfo>,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
        )
        apps.chunked(columns).forEach { rowApps ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowApps.forEach { app ->
                    AppCell(
                        app = app,
                        onClick = { bounds -> launchApp(context, hostView, bounds, app) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowApps.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AppCell(
    app: AppInfo,
    onClick: (iconBounds: ComposeRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Drawn the way the launcher draws it elsewhere: icon pack, shape and themed icons included.
    val icon = remember(app, app.bitmap) { app.newIcon(context) }
    var iconBounds by remember { mutableStateOf(ComposeRect.Zero) }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick(iconBounds) }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(
            painter = rememberDrawablePainter(icon),
            contentDescription = null,
            modifier = Modifier
                .size(IconSize)
                .onGloballyPositioned { iconBounds = it.boundsInRoot() },
        )
        Text(
            text = app.title?.toString().orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Starts [app] with a reveal that grows out of its icon. */
private fun launchApp(context: Context, hostView: View, iconBounds: ComposeRect, app: AppInfo) {
    val component = app.componentName ?: return
    try {
        val options = ActivityOptions.makeClipRevealAnimation(
            hostView,
            iconBounds.left.toInt(),
            iconBounds.top.toInt(),
            iconBounds.width.toInt(),
            iconBounds.height.toInt(),
        )
        val location = IntArray(2).also(hostView::getLocationOnScreen)
        val sourceBounds = Rect(
            location[0] + iconBounds.left.toInt(),
            location[1] + iconBounds.top.toInt(),
            location[0] + iconBounds.right.toInt(),
            location[1] + iconBounds.bottom.toInt(),
        )
        context.getSystemService(LauncherApps::class.java)
            .startMainActivity(component, app.user, sourceBounds, Utilities.allowBGLaunch(options).toBundle())
    } catch (_: Exception) {
        Toast.makeText(context, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
    }
}
