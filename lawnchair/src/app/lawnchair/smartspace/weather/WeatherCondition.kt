package app.lawnchair.smartspace.weather

import androidx.annotation.StringRes
import com.android.launcher3.R

/** WMO weather codes (as used by Open-Meteo) grouped into what the card says. */
enum class WeatherCondition(
    @StringRes val dayLabel: Int,
    @StringRes val nightLabel: Int = dayLabel,
) {
    CLEAR(R.string.weather_sunny, R.string.weather_clear),
    MOSTLY_CLEAR(R.string.weather_mostly_sunny, R.string.weather_mostly_clear),
    PARTLY_CLOUDY(R.string.weather_partly_cloudy),
    CLOUDY(R.string.weather_cloudy),
    FOG(R.string.weather_fog),
    DRIZZLE(R.string.weather_drizzle),
    FREEZING_DRIZZLE(R.string.weather_freezing_drizzle),
    LIGHT_RAIN(R.string.weather_light_rain),
    RAIN(R.string.weather_rain),
    HEAVY_RAIN(R.string.weather_heavy_rain),
    FREEZING_RAIN(R.string.weather_freezing_rain),
    SHOWERS(R.string.weather_showers),
    HEAVY_SHOWERS(R.string.weather_heavy_showers),
    LIGHT_SNOW(R.string.weather_light_snow),
    SNOW(R.string.weather_snow),
    HEAVY_SNOW(R.string.weather_heavy_snow),
    THUNDERSTORM(R.string.weather_thunderstorm),
    THUNDERSTORM_HAIL(R.string.weather_thunderstorm_hail),
    ;

    fun label(isDay: Boolean) = if (isDay) dayLabel else nightLabel

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
