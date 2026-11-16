package app.lawnchair.smartspace.model

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import com.android.launcher3.R
import java.util.Date
import java.util.Locale

sealed class SmartspaceTimeFormat(@StringRes val nameResourceId: Int) {

    companion object {

        fun fromString(value: String): SmartspaceTimeFormat = when (value) {
            "12_hour_format" -> TwelveHourFormat
            "24_hour_format" -> TwentyFourHourFormat
            else -> FollowSystem
        }

        /**
         * @return The list of all time format options.
         */
        fun values() = listOf(FollowSystem, TwelveHourFormat, TwentyFourHourFormat)
    }

    object FollowSystem : SmartspaceTimeFormat(
        nameResourceId = R.string.smartspace_time_follow_system,
    ) {
        override fun toString() = "system"
    }

    object TwelveHourFormat : SmartspaceTimeFormat(
        nameResourceId = R.string.smartspace_time_12_hour_format,
    ) {
        override fun toString() = "12_hour_format"
    }

    object TwentyFourHourFormat : SmartspaceTimeFormat(
        nameResourceId = R.string.smartspace_time_24_hour_format,
    ) {
        override fun toString() = "24_hour_format"
    }
}

/** Formats [millis] as a time of day (e.g. "8:20 AM" or "08:20") following this setting. */
fun SmartspaceTimeFormat.formatTime(context: Context, millis: Long): String {
    val use24Hour = when (this) {
        SmartspaceTimeFormat.TwelveHourFormat -> false
        SmartspaceTimeFormat.TwentyFourHourFormat -> true
        SmartspaceTimeFormat.FollowSystem -> DateFormat.is24HourFormat(context)
    }
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (use24Hour) "Hm" else "hm")
    return DateFormat.format(pattern, Date(millis)).toString()
}
