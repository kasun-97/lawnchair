package app.lawnchair.feed.widgets

import android.os.Build
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * One widget of the feed, across its full width and up to its set height. Draws nothing once the
 * widget is gone.
 */
@Composable
fun FeedWidgetBlock(
    widget: FeedWidget,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val host = remember { FeedWidgetHost.getInstance(context) }
    // The view is kept by the host, so scrolling a widget out of the feed and back does not
    // inflate it again.
    val view = remember(widget.appWidgetId) { host.getView(context, widget.appWidgetId) } ?: return
    val cornerRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dimensionResource(android.R.dimen.system_app_widget_background_radius)
    } else {
        16.dp
    }
    AndroidView(
        factory = {
            (view.parent as? ViewGroup)?.removeView(view)
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            view
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            // An upper limit: FeedWidgetHostView takes less for a widget with a height of its own.
            .heightIn(max = widget.heightDp.dp)
            .clip(RoundedCornerShape(cornerRadius)),
    )
}
