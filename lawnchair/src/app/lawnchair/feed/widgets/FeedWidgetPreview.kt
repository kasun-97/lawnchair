package app.lawnchair.feed.widgets

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What an app offers to show a widget before it is added. */
sealed interface FeedWidgetPreviewSource {
    /** A picture supplied by the app. */
    class Image(val drawable: Drawable) : FeedWidgetPreviewSource

    /**
     * A layout: [views] when the app generated a preview, otherwise the initial layout of [info],
     * which has been pointed at the app's preview layout.
     */
    class Layout(val info: AppWidgetProviderInfo, val views: RemoteViews?) : FeedWidgetPreviewSource

    data object None : FeedWidgetPreviewSource
}

/**
 * Preview of a widget that is not added yet, scaled to fit the available width and [maxHeight].
 * Takes no space while it loads or when the app offers no preview.
 *
 * @param spanX width the widget was designed for, in home screen cells.
 * @param spanY height the widget was designed for, in home screen cells.
 */
@Composable
fun FeedWidgetPreview(
    info: AppWidgetProviderInfo,
    spanX: Int,
    spanY: Int,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 168.dp,
) {
    val context = LocalContext.current
    val host = remember { FeedWidgetHost.getInstance(context) }
    val source by produceState<FeedWidgetPreviewSource>(FeedWidgetPreviewSource.None, info) {
        value = withContext(Dispatchers.IO) { host.loadPreview(info) }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        when (val current = source) {
            is FeedWidgetPreviewSource.Image -> Image(
                painter = rememberDrawablePainter(current.drawable),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.sizeIn(maxWidth = maxWidth, maxHeight = maxHeight),
            )

            is FeedWidgetPreviewSource.Layout -> {
                // Laid out at about the size it has on a home screen, then scaled down as a whole.
                val width = (spanX.coerceIn(1, 5) * CELL_WIDTH_DP).dp
                val height = (spanY.coerceIn(1, 6) * CELL_HEIGHT_DP).dp
                val scale = minOf(maxWidth / width, maxHeight / height, 1f)
                Box(modifier = Modifier.size(width * scale, height * scale)) {
                    key(current) {
                        AndroidView(
                            factory = { viewContext ->
                                PreviewHostView(viewContext).apply {
                                    // The id is not used: nothing is bound for a preview.
                                    setAppWidget(-1, current.info)
                                    setPadding(0, 0, 0, 0)
                                    updateAppWidget(current.views)
                                }
                            },
                            modifier = Modifier
                                .requiredSize(width, height)
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .clip(RoundedCornerShape(16.dp)),
                        )
                    }
                }
            }

            FeedWidgetPreviewSource.None -> Unit
        }
    }
}

private const val CELL_WIDTH_DP = 84
private const val CELL_HEIGHT_DP = 96

/** Shows a widget's preview without ever taking a touch, so the row around it stays tappable. */
private class PreviewHostView(context: Context) : AppWidgetHostView(context) {
    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onTouchEvent(ev: MotionEvent) = false

    // A preview that cannot be inflated is left blank rather than showing an error message.
    override fun getErrorView(): View = View(context)
}
