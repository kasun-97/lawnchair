package app.lawnchair.feed.sections

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A titled group of apps shown in the built-in feed, stored in
 * [app.lawnchair.preferences2.PreferenceManager2.feedAppSections].
 *
 * @param apps ComponentKey strings (see [com.android.launcher3.util.ComponentKey.toString]).
 */
@Immutable
@Serializable
data class FeedAppSection(
    val id: Long,
    val title: String,
    val apps: List<String> = emptyList(),
)

object FeedAppSections {
    /** Creates a section with an id that is unique within [existing]. */
    fun create(title: String, existing: List<FeedAppSection>): FeedAppSection {
        var id = System.currentTimeMillis()
        while (existing.any { it.id == id }) id++
        return FeedAppSection(id = id, title = title)
    }
}
