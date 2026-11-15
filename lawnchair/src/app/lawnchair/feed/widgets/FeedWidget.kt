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
 * @param title name given by the user, or null to use [label].
 * @param showTitle whether the name is shown above the widget in the feed.
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
    val showTitle: Boolean = true,
) {
    /** What the widget is called, in its title and in lists and questions. */
    val name: String get() = title?.takeIf { it.isNotBlank() } ?: label

    // An empty title is how hiding was stored before there was a switch for it.
    val titleVisible: Boolean get() = showTitle && title != ""

    /** The line shown above the widget in the feed; blank when the title is switched off. */
    val shownTitle: String get() = if (titleVisible) name else ""

    /** This widget called [text]; left empty or as the widget's own name, it follows that name. */
    fun withTitle(text: String): FeedWidget = copy(title = text.trim().takeIf { it.isNotEmpty() && it != label })

    companion object {
        const val MIN_HEIGHT_DP = 56
        const val MAX_HEIGHT_DP = 640
    }
}
