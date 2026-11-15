package app.lawnchair.feed.rss

import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class ParsedFeed(
    val title: String,
    val items: List<ParsedItem>,
)

/** @param published epoch milliseconds, or 0 when the feed gives no usable date. */
data class ParsedItem(
    val title: String,
    val link: String,
    val published: Long,
    val image: String?,
)

class NotAFeedException : Exception("Not an RSS or Atom feed")

/**
 * Reads the headline fields out of RSS 2.0, RSS 1.0 (RDF) and Atom documents. Has no Android
 * dependencies beyond the pull parser, so it can be exercised on a plain JVM.
 */
object RssParser {

    private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
    private val FEED_ROOTS = setOf("rss", "feed", "rdf")

    /**
     * @param baseUrl address the document came from, used to resolve relative links.
     * @throws NotAFeedException when the document is something else, such as a web page.
     */
    fun parse(input: InputStream, baseUrl: String, limit: Int = 30): ParsedFeed {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        // Real-world feeds use HTML entities and other things strict XML rejects.
        runCatching { parser.setFeature(FEATURE_RELAXED, true) }
        parser.setInput(input, null)

        var feedTitle = ""
        var rootSeen = false
        val items = ArrayList<ParsedItem>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT && items.size < limit) {
                if (event == XmlPullParser.START_TAG) {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (!rootSeen) {
                        if (name !in FEED_ROOTS) throw NotAFeedException()
                        rootSeen = true
                    } else if (name == "item" || name == "entry") {
                        readItem(parser, baseUrl)?.let(items::add)
                    } else if (name == "title" && feedTitle.isEmpty() && parser.prefix == null) {
                        feedTitle = cleanText(readText(parser))
                    }
                }
                event = parser.next()
            }
        } catch (e: NotAFeedException) {
            throw e
        } catch (e: Exception) {
            // A feed that breaks part-way (or was cut off) still yields the items before that.
            if (!rootSeen) throw NotAFeedException()
        }
        if (!rootSeen) throw NotAFeedException()
        return ParsedFeed(feedTitle, items)
    }

    private fun readItem(parser: XmlPullParser, baseUrl: String): ParsedItem? {
        val depth = parser.depth
        var title = ""
        var link = ""
        var guid = ""
        var date = ""
        var image: String? = null
        var bodyImage: String? = null

        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event != XmlPullParser.START_TAG) continue

            val name = parser.name.lowercase(Locale.ROOT)
            val prefix = parser.prefix?.lowercase(Locale.ROOT)
            when {
                prefix == "media" -> {
                    // media:group and friends are walked into by the loop itself.
                    if (image == null && (name == "thumbnail" || name == "content")) {
                        val url = parser.getAttributeValue(null, "url")
                        val type = parser.getAttributeValue(null, "type")
                        val medium = parser.getAttributeValue(null, "medium")
                        val isImage = name == "thumbnail" ||
                            medium == "image" ||
                            type?.startsWith("image") == true ||
                            (type == null && medium == null)
                        if (!url.isNullOrBlank() && isImage) image = url
                    }
                }

                prefix == "content" && name == "encoded" -> {
                    val text = readText(parser)
                    if (bodyImage == null) bodyImage = firstImage(text)
                }

                prefix == "dc" && name == "date" -> if (date.isEmpty()) date = readText(parser)

                prefix != null && prefix != "atom" -> Unit

                name == "title" -> title = cleanText(readText(parser))

                name == "link" -> {
                    val href = parser.getAttributeValue(null, "href")
                    if (href == null) {
                        // RSS: the address is the element text.
                        val text = readText(parser).trim()
                        if (link.isEmpty()) link = text
                    } else {
                        // Atom: one link element per relation.
                        val rel = parser.getAttributeValue(null, "rel") ?: "alternate"
                        val type = parser.getAttributeValue(null, "type")
                        if (rel == "alternate" && (link.isEmpty() || type == "text/html")) {
                            link = href
                        } else if (rel == "enclosure" && image == null && type?.startsWith("image") == true) {
                            image = href
                        }
                    }
                }

                name == "guid" || name == "id" -> guid = readText(parser).trim()

                name == "pubdate" || name == "published" -> date = readText(parser)

                name == "updated" -> if (date.isEmpty()) date = readText(parser)

                name == "enclosure" -> {
                    val url = parser.getAttributeValue(null, "url")
                    val type = parser.getAttributeValue(null, "type")
                    if (image == null && !url.isNullOrBlank() && type?.startsWith("image") == true) image = url
                }

                name == "description" || name == "summary" || name == "content" -> {
                    val text = readText(parser)
                    if (bodyImage == null) bodyImage = firstImage(text)
                }
            }
        }

        if (link.isEmpty() && guid.startsWith("http")) link = guid
        if (title.isEmpty() || link.isEmpty()) return null
        if (image == null) image = bodyImage
        return ParsedItem(
            title = title,
            link = resolve(baseUrl, link),
            published = parseDate(date),
            image = image?.let { resolve(baseUrl, unescape(it)) },
        )
    }

    /** All text inside the current element, including text of nested elements. */
    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val text = StringBuilder()
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event == XmlPullParser.END_TAG && parser.depth == depth) break
            if (event == XmlPullParser.TEXT) text.append(parser.text)
        }
        return text.toString()
    }

    // Text helpers

    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("\\s+")
    private val ENTITY = Regex("&(#[xX]?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val IMG_SRC = Regex("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
    private val NAMED_ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“",
        "rdquo" to "”", "hellip" to "…",
    )

    /** Plain single-line text from a field that may hold markup. */
    internal fun cleanText(raw: String): String = unescape(TAG.replace(raw, " ")).replace(WHITESPACE, " ").trim()

    private fun unescape(text: String): String = ENTITY.replace(text) { match ->
        val body = match.groupValues[1]
        if (body.startsWith("#")) {
            val hex = body.length > 1 && (body[1] == 'x' || body[1] == 'X')
            val code = body.substring(if (hex) 2 else 1).toIntOrNull(if (hex) 16 else 10)
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        } else {
            NAMED_ENTITIES[body.lowercase(Locale.ROOT)] ?: match.value
        }
    }

    private fun firstImage(html: String): String? = IMG_SRC.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun resolve(baseUrl: String, link: String): String = runCatching { URI(baseUrl).resolve(link.trim()).toString() }.getOrDefault(link.trim())

    // Dates

    private val LEGACY_DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm zzz",
        "dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss",
        "EEE, dd MMM yyyy HH:mm",
        "dd MMM yyyy HH:mm",
        "yyyy-MM-dd'T'HH:mm:ssZ",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    internal fun parseDate(raw: String): Long {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(text).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        for (pattern in LEGACY_DATE_PATTERNS) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).parse(text) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0
    }

    // Finding the feed of a web page

    private val LINK_TAG = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val FEED_TYPE = Regex("application/(rss|atom)\\+xml", RegexOption.IGNORE_CASE)

    /** The feed a web page advertises in its head, if any. */
    fun discoverFeedUrl(html: String, pageUrl: String): String? {
        for (match in LINK_TAG.findAll(html)) {
            val tag = match.value
            val rel = attribute(tag, "rel") ?: continue
            val type = attribute(tag, "type") ?: continue
            val href = attribute(tag, "href") ?: continue
            if (rel.contains("alternate", ignoreCase = true) && FEED_TYPE.containsMatchIn(type)) {
                return resolve(pageUrl, unescape(href))
            }
        }
        return null
    }

    private fun attribute(tag: String, name: String): String? = Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
}
