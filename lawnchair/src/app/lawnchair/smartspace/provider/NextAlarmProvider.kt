package app.lawnchair.smartspace.provider

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.core.content.getSystemService
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.smartspace.model.SmartspaceTimeFormat
import app.lawnchair.util.broadcastReceiverFlow
import app.lawnchair.util.formatShortElapsedTimeRoundingUpToMinutes
import com.android.launcher3.R
import com.android.launcher3.Utilities
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Shows the next alarm set in a clock app once it is less than [WINDOW_MS] away,
 * with a countdown that refreshes every minute.
 *
 * Other apps can register "alarm clocks" too (MIUI's calendar provider does it for event
 * reminders), so entries whose show intent was not created by a clock app are ignored.
 */
class NextAlarmProvider(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_next_alarm,
        { smartspaceNextAlarm },
    ) {
    private val alarmManager = context.getSystemService<AlarmManager>()
    private val icon = Icon.createWithResource(context, R.drawable.ic_alarm)

    private val updates = broadcastReceiverFlow(
        context,
        IntentFilter().apply {
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        },
    )
        .map { }
        .onStart { emit(Unit) }

    override val internalTargets = combine(
        updates,
        PreferenceManager2.getInstance(context).smartspaceTimeFormat.get(),
    ) { _, timeFormat ->
        listOfNotNull(getSmartspaceTarget(timeFormat))
    }.distinctUntilChanged()

    private fun getSmartspaceTarget(timeFormat: SmartspaceTimeFormat): SmartspaceTarget? {
        val alarm = alarmManager?.nextAlarmClock ?: return null
        val remaining = alarm.triggerTime - System.currentTimeMillis()
        if (remaining <= 0 || remaining > WINDOW_MS) return null
        val showIntent = alarm.showIntent
        if (showIntent != null && showIntent.creatorPackage !in clockPackages()) return null
        // Tapping must open a screen; a show intent that starts a service or broadcast does nothing visible.
        val showActivity = showIntent?.takeIf { !Utilities.ATLEAST_S || it.isActivity }

        val time = formatTime(alarm.triggerTime, timeFormat)
        val countdown = formatShortElapsedTimeRoundingUpToMinutes(context, remaining)
        return SmartspaceTarget(
            id = "nextAlarm",
            headerAction = SmartspaceAction(
                id = "nextAlarmAction",
                icon = icon,
                title = context.getString(R.string.next_alarm_title, time),
                subtitle = countdown?.let { context.getString(R.string.next_alarm_in, it) },
                pendingIntent = showActivity,
                intent = if (showActivity == null) Intent(AlarmClock.ACTION_SHOW_ALARMS) else null,
            ),
            score = SmartspaceScores.SCORE_ALARM,
            featureType = SmartspaceTarget.FeatureType.FEATURE_UPCOMING_ALARM,
        )
    }

    private fun clockPackages(): Set<String> = listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM)
        .flatMap { context.packageManager.queryIntentActivities(Intent(it), 0) }
        .mapTo(mutableSetOf()) { it.activityInfo.packageName }

    private fun formatTime(millis: Long, timeFormat: SmartspaceTimeFormat): String {
        val use24Hour = when (timeFormat) {
            SmartspaceTimeFormat.TwelveHourFormat -> false
            SmartspaceTimeFormat.TwentyFourHourFormat -> true
            SmartspaceTimeFormat.FollowSystem -> DateFormat.is24HourFormat(context)
        }
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (use24Hour) "Hm" else "hm")
        return DateFormat.format(pattern, Date(millis)).toString()
    }

    companion object {
        private val WINDOW_MS = TimeUnit.HOURS.toMillis(12)
    }
}
