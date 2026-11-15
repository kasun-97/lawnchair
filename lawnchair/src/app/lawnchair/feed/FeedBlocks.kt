package app.lawnchair.feed

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import app.lawnchair.feed.sections.FeedAppSection
import app.lawnchair.feed.widgets.FeedWidget
import sh.calvin.reorderable.DragGestureDetector

/**
 * The blocks of the built-in feed that can be put in any order: widgets and app sections. Their
 * order is stored as a list of keys in
 * [app.lawnchair.preferences2.PreferenceManager2.feedBlockOrder]; the headlines always come last.
 */
object FeedBlocks {
    const val WIDGET = "widget:"
    const val SECTION = "section:"

    fun widgetKey(id: Long) = "$WIDGET$id"

    fun sectionKey(id: Long) = "$SECTION$id"

    /**
     * The saved order brought up to date: keys of blocks that no longer exist are left out and
     * blocks that are not in it yet follow at the end, widgets before sections.
     */
    fun resolveOrder(saved: List<String>, widgets: List<FeedWidget>, sections: List<FeedAppSection>): List<String> {
        val existing = LinkedHashSet<String>()
        widgets.mapTo(existing) { widgetKey(it.id) }
        sections.mapTo(existing) { sectionKey(it.id) }
        val kept = saved.filter { it in existing }.distinct()
        return kept + (existing - kept.toSet())
    }

    /**
     * Reorders the blocks of one kind among themselves: the places in [order] held by keys with
     * [prefix] are refilled with [keysInNewOrder], and every other block stays where it is.
     */
    fun withKindOrder(order: List<String>, prefix: String, keysInNewOrder: List<String>): List<String> {
        val next = keysInNewOrder.iterator()
        return order.map { key -> if (key.startsWith(prefix) && next.hasNext()) next.next() else key }
    }
}

/**
 * Starts a drag once a block has been held still, the way an item on the home screen is picked
 * up. It watches touches before the block's content does and leaves them alone until the hold;
 * from then on it takes them, so the widget or icon under the finger sees the touch end without
 * a tap.
 *
 * @param onHeld the hold was recognised; a drag may follow.
 * @param onHeldRelease the block was let go again without being dragged; the position is where
 * it was held, relative to the block.
 */
class HoldDragDetector(
    private val onHeld: () -> Unit,
    private val onHeldRelease: (heldAt: Offset) -> Unit,
) : DragGestureDetector {

    override suspend fun PointerInputScope.detect(
        onDragStart: (Offset) -> Unit,
        onDragEnd: () -> Unit,
        onDragCancel: () -> Unit,
        onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)

            // Until the hold: scrolling, tapping and the block's own content go on as usual.
            val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed) break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            } ?: false
            if (endedEarly) return@awaitEachGesture

            onHeld()
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null) {
                    if (dragging) onDragCancel() else onHeldRelease(down.position)
                    break
                }
                if (!change.pressed) {
                    change.consume()
                    if (dragging) onDragEnd() else onHeldRelease(down.position)
                    break
                }
                if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                    dragging = true
                    onDragStart(change.position)
                }
                if (dragging) onDrag(change, change.positionChange())
                change.consume()
            }
        }
    }
}
