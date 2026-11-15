package app.lawnchair.feed.rss

import android.content.Context
import android.util.Log
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.util.SingletonHolder
import app.lawnchair.util.ensureOnMainThread
import app.lawnchair.util.kotlinxJson
import app.lawnchair.util.useApplicationContext
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Headlines of the built-in feed: fetched from the sources in the preferences, merged newest
 * first and kept in a file so the feed has content before (and without) a network round trip.
 */
class RssRepository private constructor(private val context: Context) {

    /**
     * @param failed whether the last refresh could not load any source.
     * @param ready false until the stored headlines have been read.
     */
    data class State(
        val articles: List<FeedArticle> = emptyList(),
        val refreshing: Boolean = false,
        val failed: Boolean = false,
        val ready: Boolean = false,
    )

    @Serializable
    private data class Cache(
        val updated: Long = 0,
        val sourceIds: List<Long> = emptyList(),
        val articles: List<FeedArticle> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = PreferenceManager2.getInstance(context)
    private val cacheFile = File(context.filesDir, "feed/headlines.json")

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var cache = Cache()
    private var refreshJob: Job? = null

    init {
        scope.launch {
            cache = withContext(Dispatchers.IO) { readCache() }
            _state.update { it.copy(articles = cache.articles, ready = true) }
            prefs.feedSources.get().distinctUntilChanged().collect(::onSourcesChanged)
        }
    }

    /**
     * Fetches the sources again. Unless [force] is set, nothing happens while the headlines are
     * younger than [STALE_AFTER_MS].
     */
    fun refresh(force: Boolean) {
        if (!_state.value.ready || refreshJob?.isActive == true) return
        val sources = prefs.feedSources.firstCached()
        if (sources.isEmpty()) return
        if (!force && System.currentTimeMillis() - cache.updated < STALE_AFTER_MS) return

        refreshJob = scope.launch {
            _state.update { it.copy(refreshing = true) }
            val previous = cache.articles.groupBy { it.sourceId }
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    sources.map { source ->
                        async { runCatching { fetch(source) }.onFailure { Log.w(TAG, "Failed to load ${source.url}", it) } }
                    }.awaitAll()
                }
            }
            // A source that failed this time keeps the headlines it had.
            val articles = sources.zip(results).flatMap { (source, result) ->
                result.getOrNull() ?: previous[source.id].orEmpty()
            }
            val anyLoaded = results.any { it.isSuccess }
            if (anyLoaded) {
                store(
                    Cache(
                        updated = System.currentTimeMillis(),
                        sourceIds = sources.zip(results).filter { it.second.isSuccess }.map { it.first.id },
                        articles = merge(articles),
                    ),
                )
            }
            _state.update { it.copy(articles = cache.articles, refreshing = false, failed = !anyLoaded) }
        }
    }

    private suspend fun onSourcesChanged(sources: List<FeedSource>) {
        val ids = sources.map { it.id }.toSet()
        val kept = cache.articles.filter { it.sourceId in ids }
        if (kept.size != cache.articles.size) {
            store(cache.copy(sourceIds = cache.sourceIds.filter { it in ids }, articles = kept))
            _state.update { it.copy(articles = kept) }
        }
        // A source that has never been loaded: fetch now instead of waiting for the next visit.
        if (!cache.sourceIds.containsAll(ids)) {
            refreshJob?.join()
            refresh(force = true)
        }
    }

    private fun fetch(source: FeedSource): List<FeedArticle> = RssFetcher.load(source.url, ITEMS_PER_SOURCE).items.map { item ->
        FeedArticle(
            sourceId = source.id,
            source = source.title,
            title = item.title,
            link = item.link,
            published = item.published,
            image = item.image,
        )
    }

    /** Newest first; undated items keep their feed order at the end. */
    private fun merge(articles: List<FeedArticle>): List<FeedArticle> = articles
        .distinctBy { it.link }
        .sortedByDescending { it.published }
        .take(MAX_ARTICLES)

    private suspend fun store(newCache: Cache) {
        cache = newCache
        withContext(Dispatchers.IO) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(kotlinxJson.encodeToString(Cache.serializer(), newCache))
            }.onFailure { Log.w(TAG, "Failed to store headlines", it) }
        }
    }

    private fun readCache(): Cache = runCatching {
        kotlinxJson.decodeFromString(Cache.serializer(), cacheFile.readText())
    }.getOrDefault(Cache())

    companion object : SingletonHolder<RssRepository, Context>(
        ensureOnMainThread(
            useApplicationContext(::RssRepository),
        ),
    ) {
        private const val TAG = "RssRepository"
        private const val STALE_AFTER_MS = 30L * 60 * 1000
        private const val ITEMS_PER_SOURCE = 30
        private const val MAX_ARTICLES = 150
    }
}
