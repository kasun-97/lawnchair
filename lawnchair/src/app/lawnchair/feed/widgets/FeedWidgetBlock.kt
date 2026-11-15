package app.lawnchair.feed.widgets

import android.os.Build
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.android.launcher3.R
import kotlin.math.roundToInt

private val BlockPadding = 8.dp
private val MinWidth = 120.dp
private val HandleArea = 48.dp
private val HandleDot = 16.dp
private val RemoveButton = 28.dp

/** Which edge of the resize frame a handle sits on. */
private enum class Edge { Start, End, Bottom }

/**
 * One widget of the feed under its title, up to its set height and across the feed unless a width
 * is set. Draws nothing once the widget is gone.
 *
 * While [resizing], the widget is covered by a frame whose handles change its size; the result is
 * reported through [onResized] when a handle is let go. The frame's corner button asks for
 * [onRemove]. [onTitleBottomChange] reports how far down the block the title reaches, in pixels (0
 * without a title), so a hold can be told apart by where it lands.
 */
@Composable
fun FeedWidgetBlock(
    widget: FeedWidget,
    resizing: Boolean,
    onResized: (FeedWidget) -> Unit,
    onRemove: () -> Unit,
    onTitleBottomChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val host = remember { FeedWidgetHost.getInstance(context) }
    // The view is kept by the host, so scrolling a widget out of the feed and back does not
    // inflate it again.
    val view = remember(widget.appWidgetId) { host.getView(context, widget.appWidgetId) } ?: return
    val shape = RoundedCornerShape(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dimensionResource(android.R.dimen.system_app_widget_background_radius)
        } else {
            16.dp
        },
    )

    // Shown until the saved size arrives, so letting go of a handle does not flash the old size.
    var shown by remember(widget) { mutableStateOf(widget) }
    // Set while a handle is held: the frame then follows the finger exactly.
    var dragSize by remember { mutableStateOf<DpSize?>(null) }
    var blockHeightPx by remember { mutableStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = BlockPadding),
        contentAlignment = Alignment.TopCenter,
    ) {
        val fullWidth = maxWidth
        val setWidth = if (shown.widthDp > 0) shown.widthDp.dp.coerceAtMost(fullWidth) else fullWidth
        val drag = dragSize
        Column(modifier = Modifier.width(drag?.width ?: setWidth)) {
            if (shown.shownTitle.isNotBlank()) {
                Text(
                    text = shown.shownTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Starts where the titles of the app sections start.
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .onSizeChanged { onTitleBottomChange(with(density) { BlockPadding.toPx() } + it.height) },
                )
            } else {
                SideEffect { onTitleBottomChange(0f) }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (drag != null) Modifier.height(drag.height) else Modifier)
                    .onSizeChanged { blockHeightPx = it.height },
            ) {
                AndroidView(
                    factory = {
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        view
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        // An upper limit: FeedWidgetHostView takes less for a widget with a height of
                        // its own.
                        .heightIn(max = drag?.height ?: shown.heightDp.dp)
                        .clip(shape),
                )
                if (resizing) {
                    ResizeFrame(
                        shape = shape,
                        onRemove = onRemove,
                        onDragStart = {
                            dragSize = DpSize(setWidth, with(density) { blockHeightPx.toDp() })
                        },
                        onDrag = { edge, amount ->
                            dragSize = dragSize?.let { size ->
                                when (edge) {
                                    Edge.Bottom -> size.copy(
                                        height = (size.height + amount)
                                            .coerceIn(FeedWidget.MIN_HEIGHT_DP.dp, FeedWidget.MAX_HEIGHT_DP.dp),
                                    )

                                    // The widget stays centred, so each side moves by the same amount.
                                    else -> size.copy(
                                        width = (size.width + amount * 2).coerceIn(MinWidth.coerceAtMost(fullWidth), fullWidth),
                                    )
                                }
                            }
                        },
                        onDragEnd = {
                            dragSize?.let { size ->
                                val resized = shown.copy(
                                    heightDp = size.height.value.roundToInt(),
                                    // Zero stands for the full width, whatever that is on this screen.
                                    widthDp = if (size.width >= fullWidth - 8.dp) 0 else size.width.value.roundToInt(),
                                )
                                shown = resized
                                onResized(resized)
                            }
                            dragSize = null
                        },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

/**
 * Outline with a handle on the bottom edge (height), one on each side (width) and a remove button
 * on the top corner. It also takes every touch on the widget beneath it, so the widget cannot be
 * operated by accident.
 */
@Composable
private fun ResizeFrame(
    shape: RoundedCornerShape,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (edge: Edge, amount: Dp) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .border(2.dp, MaterialTheme.colorScheme.primary, shape)
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            },
    ) {
        ResizeHandle(Edge.Start, onDragStart, onDrag, onDragEnd)
        ResizeHandle(Edge.End, onDragStart, onDrag, onDragEnd)
        ResizeHandle(Edge.Bottom, onDragStart, onDrag, onDragEnd)
        // The touch area lies inside the frame; the button is drawn over its corner.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(HandleArea)
                .clickable(
                    onClickLabel = stringResource(R.string.feed_widgets_remove),
                    role = Role.Button,
                    onClick = onRemove,
                ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.feed_widgets_remove),
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = RemoveButton / 3, y = -RemoveButton / 3)
                    .size(RemoveButton)
                    .background(MaterialTheme.colorScheme.error, CircleShape)
                    .padding(4.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.ResizeHandle(
    edge: Edge,
    onDragStart: () -> Unit,
    onDrag: (edge: Edge, amount: Dp) -> Unit,
    onDragEnd: () -> Unit,
) {
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    // The touch area lies inside the frame; the dot is drawn centred on the frame's edge.
    val (areaAlignment, dotAlignment) = when (edge) {
        Edge.Start -> Alignment.CenterStart to Alignment.CenterStart
        Edge.End -> Alignment.CenterEnd to Alignment.CenterEnd
        Edge.Bottom -> Alignment.BottomCenter to Alignment.BottomCenter
    }
    Box(
        modifier = Modifier
            .align(areaAlignment)
            .size(HandleArea)
            .pointerInput(edge) {
                detectDragGestures(
                    onDragStart = { currentOnDragStart() },
                    onDragEnd = { currentOnDragEnd() },
                    onDragCancel = { currentOnDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    val amount = when (edge) {
                        Edge.Start -> -dragAmount.x
                        Edge.End -> dragAmount.x
                        Edge.Bottom -> dragAmount.y
                    }
                    currentOnDrag(edge, amount.toDp())
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(dotAlignment)
                .offset(
                    x = when (edge) {
                        Edge.Start -> -HandleDot / 2
                        Edge.End -> HandleDot / 2
                        Edge.Bottom -> 0.dp
                    },
                    y = if (edge == Edge.Bottom) HandleDot / 2 else 0.dp,
                )
                .size(HandleDot)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}
