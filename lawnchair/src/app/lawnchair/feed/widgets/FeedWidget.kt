package app.lawnchair.feed.widgets

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A widget of another app shown in the built-in feed, stored in
 * [app.lawnchair.preferences2.PreferenceManager2.feedWidgets].
 *
 * @param appWidgetId id the widget is bound under in [FeedWidgetHost].
 * @param label name of the widget, kept so it can still be listed if its app is removed.
 * @param heightDp height in the feed, or the most it may take if the widget has a height of its
 * own.
 * @param widthDp width in the feed, centred; 0 for the full width of the feed.
 * @param title title set by the user: null to use [label], blank to show none.
 */
@Immutable
@Serializable
data class FeedWidget(
    val id: Long,
    val appWidgetId: Int,
    val label: String,
    val heightDp: Int,
    val widthDp: Int = 0,
    val title: String? = null,
) {
    /** The line shown above the widget in the feed; blank when it was switched off. */
    val shownTitle: String get() = title ?: label

    /** What the widget is called in lists and questions, whether or not its title is shown. */
    val name: String get() = title?.takeIf { it.isNotBlank() } ?: label

    companion object {
        const val MIN_HEIGHT_DP = 56
        const val MAX_HEIGHT_DP = 640
    }
}
