package app.lawnchair.smartspace.weather

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.android.launcher3.R

/** WMO weather codes (as used by Open-Meteo) grouped into what the card says. */
enum class WeatherCondition(
    @StringRes val dayLabel: Int,
    @DrawableRes val dayIcon: Int,
    @StringRes val nightLabel: Int = dayLabel,
    @DrawableRes val nightIcon: Int = dayIcon,
    /** Rain, snow or storms falling: no separate "rain soon" card while it is already happening. */
    val isWet: Boolean = false,
) {
    CLEAR(R.string.weather_sunny, R.drawable.ic_weather_sunny, R.string.weather_clear, R.drawable.ic_weather_clear_night),
    MOSTLY_CLEAR(R.string.weather_mostly_sunny, R.drawable.ic_weather_partly_cloudy_day, R.string.weather_mostly_clear, R.drawable.ic_weather_partly_cloudy_night),
    PARTLY_CLOUDY(R.string.weather_partly_cloudy, R.drawable.ic_weather_partly_cloudy_day, nightIcon = R.drawable.ic_weather_partly_cloudy_night),
    CLOUDY(R.string.weather_cloudy, R.drawable.ic_weather_cloudy),
    FOG(R.string.weather_fog, R.drawable.ic_weather_fog),
    DRIZZLE(R.string.weather_drizzle, R.drawable.ic_weather_drizzle, isWet = true),
    FREEZING_DRIZZLE(R.string.weather_freezing_drizzle, R.drawable.ic_weather_drizzle, isWet = true),
    LIGHT_RAIN(R.string.weather_light_rain, R.drawable.ic_weather_drizzle, isWet = true),
    RAIN(R.string.weather_rain, R.drawable.ic_weather_rain, isWet = true),
    HEAVY_RAIN(R.string.weather_heavy_rain, R.drawable.ic_weather_rain, isWet = true),
    FREEZING_RAIN(R.string.weather_freezing_rain, R.drawable.ic_weather_rain, isWet = true),
    SHOWERS(R.string.weather_showers, R.drawable.ic_weather_drizzle, isWet = true),
    HEAVY_SHOWERS(R.string.weather_heavy_showers, R.drawable.ic_weather_rain, isWet = true),
    LIGHT_SNOW(R.string.weather_light_snow, R.drawable.ic_weather_snow, isWet = true),
    SNOW(R.string.weather_snow, R.drawable.ic_weather_snow, isWet = true),
    HEAVY_SNOW(R.string.weather_heavy_snow, R.drawable.ic_weather_snow, isWet = true),
    THUNDERSTORM(R.string.weather_thunderstorm, R.drawable.ic_weather_thunderstorm, isWet = true),
    THUNDERSTORM_HAIL(R.string.weather_thunderstorm_hail, R.drawable.ic_weather_thunderstorm, isWet = true),
    ;

    fun label(isDay: Boolean) = if (isDay) dayLabel else nightLabel

    fun icon(isDay: Boolean) = if (isDay) dayIcon else nightIcon

    companion object {
        fun fromWmo(code: Int): WeatherCondition? = when (code) {
            0 -> CLEAR
            1 -> MOSTLY_CLEAR
            2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            51, 53, 55 -> DRIZZLE
            56, 57 -> FREEZING_DRIZZLE
            61 -> LIGHT_RAIN
            63 -> RAIN
            65 -> HEAVY_RAIN
            66, 67 -> FREEZING_RAIN
            80, 81 -> SHOWERS
            82 -> HEAVY_SHOWERS
            71, 77, 85 -> LIGHT_SNOW
            73, 86 -> SNOW
            75 -> HEAVY_SNOW
            95 -> THUNDERSTORM
            96, 99 -> THUNDERSTORM_HAIL
            else -> null
        }
    }
}
