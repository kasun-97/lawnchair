package app.lawnchair.smartspace.provider

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.util.Patterns
import androidx.activity.result.contract.ActivityResultContracts
import app.lawnchair.BlankActivity
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.smartspace.model.SmartspaceTimeFormat
import app.lawnchair.smartspace.model.formatTime
import app.lawnchair.util.broadcastReceiverFlow
import app.lawnchair.util.formatShortElapsedTimeRoundingUpToMinutes
import com.android.launcher3.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Shows events from the phone's own calendars (any account synced to the device), so it works
 * without the Google app:
 * - the timed event in progress, if any, and the next timed event starting within the chosen window
 *   (3–48 hours, or the rest of today; 24 hours by default);
 * - otherwise an all-day event happening within that window.
 * Declined and cancelled events and hidden calendars are skipped.
 *
 * While this source is on, [SmartspaceProvider] hides the Google widget's event card to avoid duplicates.
 */
class CalendarEventsProvider(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_calendar_events,
        { smartspaceCalendarEvents },
    ) {
    private val icon = Icon.createWithResource(context, R.drawable.ic_event)

    private val updates: Flow<Unit> = merge(
        broadcastReceiverFlow(
            context,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            },
        ).map { },
        calendarChanges(),
    ).onStart { emit(Unit) }

    override val internalTargets = combine(
        updates,
        PreferenceManager2.getInstance(context).smartspaceTimeFormat.get(),
        PreferenceManager2.getInstance(context).smartspaceCalendarWindowHours.get(),
    ) { _, timeFormat, windowHours -> loadCards(timeFormat, windowHours) }
        .distinctUntilChanged()
        .map { cards -> cards.map(::toTarget) }
        .flowOn(Dispatchers.IO)

    override suspend fun requiresSetup(): Boolean = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED

    override suspend fun startSetup(activity: Activity) {
        val intent = ActivityResultContracts.RequestPermission().createIntent(activity, Manifest.permission.READ_CALENDAR)
        BlankActivity.startBlankActivityForResult(activity, intent)
    }

    private fun calendarChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    private fun loadCards(timeFormat: SmartspaceTimeFormat, windowHours: Int): List<Card> {
        val now = System.currentTimeMillis()
        val windowEnd = if (windowHours == REST_OF_TODAY) {
            LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } else {
            now + TimeUnit.HOURS.toMillis(windowHours.toLong())
        }
        val events = queryEvents(now - TimeUnit.DAYS.toMillis(1), windowEnd)

        val timed = events.filter { !it.allDay }
        val current = timed.filter { it.begin <= now && it.end > now }.maxByOrNull { it.begin }
        val next = timed.firstOrNull { it.begin > now && it.begin <= windowEnd }
        val timedCards = listOfNotNull(
            current?.let { Card(it, inProgressLabel(it, timeFormat), SmartspaceScores.SCORE_CALENDAR + 0.1f) },
            next?.let { Card(it, upcomingLabel(it, now, timeFormat), SmartspaceScores.SCORE_CALENDAR) },
        )
        if (timedCards.isNotEmpty()) return timedCards

        // All-day instances are stored as UTC midnights; shift them to local midnights before comparing.
        val allDay = events
            .filter { it.allDay }
            .map { it.copy(begin = utcMidnightToLocal(it.begin), end = utcMidnightToLocal(it.end)) }
            .filter { it.begin < windowEnd && it.end > now }
            .minByOrNull { it.begin }
            ?: return emptyList()
        return listOf(Card(allDay, allDayLabel(allDay), SmartspaceScores.SCORE_CALENDAR))
    }

    private fun queryEvents(from: Long, to: Long): List<Event> {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID,
            Instances.TITLE,
            Instances.BEGIN,
            Instances.END,
            Instances.ALL_DAY,
            Instances.EVENT_LOCATION,
        )
        val selection = "${Instances.VISIBLE} = 1" +
            " AND IFNULL(${Instances.STATUS}, ${Events.STATUS_TENTATIVE}) != ${Events.STATUS_CANCELED}" +
            " AND IFNULL(${Instances.SELF_ATTENDEE_STATUS}, ${Attendees.ATTENDEE_STATUS_NONE}) != ${Attendees.ATTENDEE_STATUS_DECLINED}"
        val cursor = context.contentResolver.query(uri, projection, selection, null, "${Instances.BEGIN} ASC")
            ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) {
                    add(
                        Event(
                            id = it.getLong(0),
                            title = it.getString(1)?.takeIf(String::isNotBlank),
                            begin = it.getLong(2),
                            end = it.getLong(3),
                            allDay = it.getInt(4) != 0,
                            location = it.getString(5)?.takeIf(String::isNotBlank),
                        ),
                    )
                }
            }
        }
    }

    private fun inProgressLabel(event: Event, timeFormat: SmartspaceTimeFormat): String {
        val label = context.getString(R.string.calendar_event_now_until, timeFormat.formatTime(context, event.end))
        return withLocation(label, event)
    }

    private fun upcomingLabel(event: Event, now: Long, timeFormat: SmartspaceTimeFormat): String {
        val range = context.getString(
            R.string.calendar_event_time_range,
            timeFormat.formatTime(context, event.begin),
            timeFormat.formatTime(context, event.end),
        )
        val untilStart = event.begin - now
        val label = when {
            untilStart <= SOON_MS -> {
                val countdown = formatShortElapsedTimeRoundingUpToMinutes(context, untilStart)
                context.getString(R.string.calendar_event_in, countdown, range)
            }

            localDate(event.begin) == localDate(now).plusDays(1) -> context.getString(R.string.calendar_event_tomorrow, range)

            localDate(event.begin) != localDate(now) -> context.getString(R.string.calendar_event_on_day, weekday(event.begin), range)

            else -> range
        }
        return withLocation(label, event)
    }

    private fun allDayLabel(event: Event): String {
        val today = LocalDate.now()
        val day = localDate(event.begin)
        val label = when {
            !day.isAfter(today) -> context.getString(R.string.calendar_event_today_all_day)
            day == today.plusDays(1) -> context.getString(R.string.calendar_event_tomorrow_all_day)
            else -> context.getString(R.string.calendar_event_day_all_day, weekday(event.begin))
        }
        return withLocation(label, event)
    }

    private fun weekday(millis: Long): String = localDate(millis).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    private fun withLocation(label: String, event: Event): String = event.location
        ?.takeUnless(::isWebLink)
        ?.let { context.getString(R.string.calendar_event_with_location, label, it) }
        ?: label

    /** Meeting links (Zoom, Meet, Teams) fill the line without saying where the event is. */
    private fun isWebLink(location: String): Boolean = "://" in location || Patterns.WEB_URL.matcher(location.trim()).matches()

    private fun toTarget(card: Card): SmartspaceTarget {
        val event = card.event
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, event.allDay)
        return SmartspaceTarget(
            id = "calendarEvent-${event.id}-${event.begin}",
            headerAction = SmartspaceAction(
                id = "calendarEventAction-${event.id}-${event.begin}",
                icon = icon,
                title = event.title ?: context.getString(R.string.calendar_event_no_title),
                subtitle = card.subtitle,
                intent = intent,
            ),
            score = card.score,
            featureType = SmartspaceTarget.FeatureType.FEATURE_CALENDAR,
        )
    }

    private fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun utcMidnightToLocal(millis: Long): Long = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private data class Event(
        val id: Long,
        val title: String?,
        val begin: Long,
        val end: Long,
        val allDay: Boolean,
        val location: String?,
    )

    private data class Card(val event: Event, val subtitle: String, val score: Float)

    companion object {
        /** Window setting value meaning "until midnight" rather than a number of hours. */
        const val REST_OF_TODAY = 0
        val WINDOW_CHOICES = listOf(REST_OF_TODAY, 3, 6, 12, 24, 48)
        private val SOON_MS = TimeUnit.HOURS.toMillis(1)
    }
}
