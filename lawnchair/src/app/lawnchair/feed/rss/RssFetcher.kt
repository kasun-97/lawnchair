package app.lawnchair.feed.rss

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/** A feed address that was checked to work, with what it currently holds. */
class ResolvedFeed(
    val url: String,
    val feed: ParsedFeed,
)

/** Downloads feeds. Every call blocks, so use it off the main thread. */
object RssFetcher {

    private const val MAX_BYTES = 4L * 1024 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) FeedReader/1.0"
    private const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5"

    /** Where sites that do not advertise a feed usually keep it. */
    private val COMMON_FEED_PATHS = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml")

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Turns what a person typed into a usable address, or null when it cannot be one. A missing
     * scheme becomes https, and so does http: the launcher does not allow unencrypted traffic.
     */
    fun normalizeUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = when {
            text.startsWith("https://", ignoreCase = true) -> text
            text.startsWith("http://", ignoreCase = true) -> "https://" + text.substring(7)
            "://" in text -> return null
            else -> "https://$text"
        }
        val url = withScheme.toHttpUrlOrNull() ?: return null
        return if ('.' in url.host) url.toString() else null
    }

    fun load(url: String, limit: Int): ParsedFeed = RssParser.parse(download(url).inputStream(), url, limit)

    /**
     * Checks an address a person entered. When it is a web page rather than a feed, the feed that
     * page advertises is used instead, or failing that one at the usual places on that site.
     *
     * @throws NotAFeedException when no feed is found there.
     * @throws IOException when the address cannot be reached.
     */
    fun resolve(url: String): ResolvedFeed {
        val body = download(url)
        try {
            return ResolvedFeed(url, RssParser.parse(body.inputStream(), url))
        } catch (_: NotAFeedException) {
            // Fall through: it may be a page that links to its feed.
        }
        val advertised = RssParser.discoverFeedUrl(String(body, Charsets.UTF_8), url)?.let(::normalizeUrl)
        val origin = url.toHttpUrlOrNull()
        val candidates = listOfNotNull(advertised) + COMMON_FEED_PATHS.mapNotNull { origin?.resolve(it)?.toString() }
        for (candidate in candidates.distinct()) {
            if (candidate == url) continue
            val feed = runCatching { RssParser.parse(download(candidate).inputStream(), candidate) }.getOrNull()
            if (feed != null) return ResolvedFeed(candidate, feed)
        }
        throw NotAFeedException()
    }

    private fun download(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body.source()
            val buffer = Buffer()
            while (buffer.size < MAX_BYTES) {
                if (source.read(buffer, 64L * 1024) == -1L) break
            }
            return buffer.readByteArray()
        }
    }
}
