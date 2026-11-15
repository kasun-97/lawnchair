package app.lawnchair.feed.rss

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * A news source of the built-in feed, stored in
 * [app.lawnchair.preferences2.PreferenceManager2.feedSources].
 *
 * @param url address of the RSS or Atom document.
 * @param title name shown next to its headlines.
 * @param enabled whether its headlines are loaded and shown.
 */
@Immutable
@Serializable
data class FeedSource(
    val id: Long,
    val url: String,
    val title: String,
    val enabled: Boolean = true,
)

/** @param published epoch milliseconds, or 0 when unknown. */
@Immutable
@Serializable
data class FeedArticle(
    val sourceId: Long,
    val source: String,
    val title: String,
    val link: String,
    val published: Long = 0,
    val image: String? = null,
)

object FeedSources {
    /** What the most headlines the feed shows can be set to. */
    val HEADLINE_LIMITS = listOf(20, 50, 100, 200, 500)
    const val DEFAULT_HEADLINE_LIMIT = 20

    /** Creates a source with an id that is unique within [existing]. */
    fun create(url: String, title: String, existing: List<FeedSource>): FeedSource {
        var id = System.currentTimeMillis()
        while (existing.any { it.id == id }) id++
        return FeedSource(id = id, url = url, title = title)
    }
}
