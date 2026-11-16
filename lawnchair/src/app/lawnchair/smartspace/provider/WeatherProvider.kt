package app.lawnchair.smartspace.provider

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import app.lawnchair.BlankActivity
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.smartspace.model.SmartspaceTimeFormat
import app.lawnchair.smartspace.model.formatTime
import app.lawnchair.smartspace.weather.OpenMeteo
import app.lawnchair.smartspace.weather.WeatherCondition
import app.lawnchair.smartspace.weather.WeatherLocation
import app.lawnchair.smartspace.weather.WeatherReport
import com.android.launcher3.R
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Weather from Open-Meteo for the phone's approximate location, so At a Glance does not need the
 * Google app. Shown on the date page like Google's weather; while this source is on,
 * [SmartspaceProvider] ignores the Google widget entirely.
 *
 * Refreshes every [REFRESH_MS] while At a Glance is in use and keeps the last report on disk, so the
 * date page has weather straight after a launcher restart. Reports older than [STALE_MS] are not shown.
 * A separate card warns when rain is likely within [RAIN_HORIZON_MS]. Tapping opens windy.com there.
 */
class WeatherProvider(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_open_meteo_weather,
        { smartspaceOpenMeteoWeather },
    ) {
    private val cache = context.getSharedPreferences("smartspace_weather", Context.MODE_PRIVATE)
    private val rainIcon = Icon.createWithResource(context, R.drawable.ic_weather_rain)

    /** Keeps the date page when there is no weather (or no permission yet). */
    override val disabledTargets = listOf(dateOnlyTarget)

    /** The latest report every [TICK_MS] (re-fetched once it is [REFRESH_MS] old), so time-based text stays current. */
    private val reports: Flow<CachedReport?> = flow {
        readCache()?.let { emit(it) }
        while (true) {
            val cached = readCache()
            val age = cached?.let { System.currentTimeMillis() - it.fetchedAt } ?: Long.MAX_VALUE
            emit(if (age >= REFRESH_MS) refresh() ?: cached else cached)
            delay(TICK_MS)
        }
    }

    override val internalTargets: Flow<List<SmartspaceTarget>> = combine(
        reports,
        PreferenceManager2.getInstance(context).smartspaceTimeFormat.get(),
    ) { cached, timeFormat -> cached?.let { toTargets(it, timeFormat) } ?: disabledTargets }
        .flowOn(Dispatchers.IO)

    override suspend fun requiresSetup(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED

    override suspend fun startSetup(activity: Activity) {
        val intent = ActivityResultContracts.RequestPermission().createIntent(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
        BlankActivity.startBlankActivityForResult(activity, intent)
    }

    /** Never throws: an exception here would make [SmartspaceDataSource] switch the source off. */
    private suspend fun refresh(): CachedReport? {
        val location = try {
            WeatherLocation.find(context)
        } catch (e: Exception) {
            Log.w(TAG, "location lookup failed", e)
            null
        }
        // Rounded before it leaves the phone or reaches the disk: ~1 km is plenty for a forecast.
        val latitude = (location?.latitude ?: cache.getString(KEY_LATITUDE, null)?.toDoubleOrNull())?.let(::roundCoordinate) ?: return null
        val longitude = (location?.longitude ?: cache.getString(KEY_LONGITUDE, null)?.toDoubleOrNull())?.let(::roundCoordinate) ?: return null
        val (json, report) = OpenMeteo.fetch(latitude, longitude) ?: return null
        val fetchedAt = System.currentTimeMillis()
        cache.edit {
            putString(KEY_LATITUDE, latitude.toString())
            putString(KEY_LONGITUDE, longitude.toString())
            putString(KEY_JSON, json)
            putLong(KEY_FETCHED_AT, fetchedAt)
        }
        return CachedReport(report, fetchedAt, latitude, longitude)
    }

    private fun readCache(): CachedReport? {
        val json = cache.getString(KEY_JSON, null) ?: return null
        val report = runCatching { OpenMeteo.json.decodeFromString<WeatherReport>(json) }.getOrNull() ?: return null
        val latitude = cache.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = cache.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return CachedReport(report, cache.getLong(KEY_FETCHED_AT, 0), latitude, longitude)
    }

    private fun toTargets(cached: CachedReport, timeFormat: SmartspaceTimeFormat): List<SmartspaceTarget> {
        if (System.currentTimeMillis() - cached.fetchedAt > STALE_MS) return disabledTargets
        val current = cached.report.current
        val isDay = current.isDay == 1
        val condition = WeatherCondition.fromWmo(current.weatherCode)
        val temperature = context.getString(R.string.weather_temperature_celsius, current.temperature.roundToInt())
        val subtitle = condition
            ?.let { context.getString(R.string.weather_temperature_and_condition, temperature, context.getString(it.label(isDay))) }
            ?: temperature
        val forecast = forecastIntent(cached)
        val weather = SmartspaceTarget(
            id = "openMeteoWeather",
            headerAction = SmartspaceAction(
                id = "openMeteoWeatherAction",
                icon = condition?.let { Icon.createWithResource(context, it.icon(isDay)) },
                title = "",
                subtitle = subtitle,
                intent = forecast,
            ),
            score = SmartspaceScores.SCORE_WEATHER,
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )
        val rain = if (condition?.isWet == true) null else rainSoonTarget(cached.report, timeFormat, forecast)
        return listOfNotNull(weather, rain)
    }

    /** The first hour within [RAIN_HORIZON_MS] with at least [RAIN_PROBABILITY]% chance of rain, if any. */
    private fun rainSoonTarget(report: WeatherReport, timeFormat: SmartspaceTimeFormat, forecast: Intent): SmartspaceTarget? {
        val hourly = report.hourly ?: return null
        val now = System.currentTimeMillis()
        val index = hourly.time.indices.firstOrNull { i ->
            val start = TimeUnit.SECONDS.toMillis(hourly.time[i])
            val chance = hourly.precipitationProbability.getOrNull(i) ?: 0
            start + HOUR_MS > now && start < now + RAIN_HORIZON_MS && chance >= RAIN_PROBABILITY
        } ?: return null
        val start = TimeUnit.SECONDS.toMillis(hourly.time[index])
        val chance = hourly.precipitationProbability[index] ?: return null
        val title = if (start <= now) {
            context.getString(R.string.weather_rain_soon)
        } else {
            context.getString(R.string.weather_rain_around, timeFormat.formatTime(context, start))
        }
        return SmartspaceTarget(
            id = "openMeteoRainSoon",
            headerAction = SmartspaceAction(
                id = "openMeteoRainSoonAction",
                icon = rainIcon,
                title = title,
                subtitle = context.getString(R.string.weather_rain_chance, chance),
                intent = forecast,
            ),
            score = SmartspaceScores.SCORE_RAIN_SOON,
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER_ALERT,
        )
    }

    /** windy.com's point forecast for the (already rounded) location. */
    private fun forecastIntent(cached: CachedReport): Intent {
        val url = String.format(
            Locale.ROOT,
            "https://www.windy.com/%1\$.2f/%2\$.2f?%1\$.2f,%2\$.2f,9",
            cached.latitude,
            cached.longitude,
        )
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private fun roundCoordinate(value: Double) = round(value * 100) / 100

    private data class CachedReport(
        val report: WeatherReport,
        val fetchedAt: Long,
        val latitude: Double,
        val longitude: Double,
    )

    companion object {
        private const val TAG = "WeatherProvider"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_JSON = "report_json"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val RAIN_PROBABILITY = 50
        private val TICK_MS = TimeUnit.MINUTES.toMillis(10)
        private val REFRESH_MS = TimeUnit.MINUTES.toMillis(30)
        private val STALE_MS = TimeUnit.HOURS.toMillis(3)
        private val HOUR_MS = TimeUnit.HOURS.toMillis(1)
        private val RAIN_HORIZON_MS = TimeUnit.HOURS.toMillis(3)

        private val dateOnlyTarget = SmartspaceTarget(
            id = "openMeteoDate",
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )
    }
}
