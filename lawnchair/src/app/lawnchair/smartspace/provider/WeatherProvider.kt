package app.lawnchair.smartspace.provider

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import app.lawnchair.BlankActivity
import app.lawnchair.smartspace.model.SmartspaceAction
import app.lawnchair.smartspace.model.SmartspaceScores
import app.lawnchair.smartspace.model.SmartspaceTarget
import app.lawnchair.smartspace.weather.OpenMeteo
import app.lawnchair.smartspace.weather.WeatherCondition
import app.lawnchair.smartspace.weather.WeatherLocation
import app.lawnchair.smartspace.weather.WeatherReport
import com.android.launcher3.R
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Weather from Open-Meteo for the phone's approximate location, so At a Glance does not need the
 * Google app. Shown on the date page like Google's weather; while this source is on,
 * [SmartspaceProvider] ignores the Google widget entirely.
 *
 * Refreshes every [REFRESH_MS] while At a Glance is in use and keeps the last report on disk, so the
 * date page has weather straight after a launcher restart. Reports older than [STALE_MS] are not shown.
 */
class WeatherProvider(context: Context) :
    SmartspaceDataSource(
        context,
        R.string.smartspace_open_meteo_weather,
        { smartspaceOpenMeteoWeather },
    ) {
    private val cache = context.getSharedPreferences("smartspace_weather", Context.MODE_PRIVATE)

    /** Keeps the date page when there is no weather (or no permission yet). */
    override val disabledTargets = listOf(dateOnlyTarget)

    override val internalTargets: Flow<List<SmartspaceTarget>> = flow {
        readCache()?.let { emit(it) }
        while (true) {
            val cached = readCache()
            val age = cached?.let { System.currentTimeMillis() - it.fetchedAt } ?: Long.MAX_VALUE
            val wait = if (age >= REFRESH_MS) {
                val fresh = refresh()
                fresh?.let { emit(it) } ?: cached?.let { emit(it) }
                if (fresh != null) REFRESH_MS else RETRY_MS
            } else {
                REFRESH_MS - age
            }
            delay(wait)
        }
    }
        .map { listOf(toTarget(it)) }
        .distinctUntilChanged()
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
        val latitude = location?.latitude ?: cache.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = location?.longitude ?: cache.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val (json, report) = OpenMeteo.fetch(latitude, longitude) ?: return null
        val fetchedAt = System.currentTimeMillis()
        cache.edit {
            putString(KEY_LATITUDE, latitude.toString())
            putString(KEY_LONGITUDE, longitude.toString())
            putString(KEY_JSON, json)
            putLong(KEY_FETCHED_AT, fetchedAt)
        }
        return CachedReport(report, fetchedAt)
    }

    private fun readCache(): CachedReport? {
        val json = cache.getString(KEY_JSON, null) ?: return null
        val report = runCatching { OpenMeteo.json.decodeFromString<WeatherReport>(json) }.getOrNull() ?: return null
        return CachedReport(report, cache.getLong(KEY_FETCHED_AT, 0))
    }

    private fun toTarget(cached: CachedReport): SmartspaceTarget {
        if (System.currentTimeMillis() - cached.fetchedAt > STALE_MS) return dateOnlyTarget
        val current = cached.report.current
        val temperature = context.getString(R.string.weather_temperature_celsius, current.temperature.roundToInt())
        val condition = WeatherCondition.fromWmo(current.weatherCode)
        val subtitle = condition
            ?.let { context.getString(R.string.weather_temperature_and_condition, temperature, context.getString(it.label(current.isDay == 1))) }
            ?: temperature
        return SmartspaceTarget(
            id = "openMeteoWeather",
            headerAction = SmartspaceAction(
                id = "openMeteoWeatherAction",
                title = "",
                subtitle = subtitle,
            ),
            score = SmartspaceScores.SCORE_WEATHER,
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )
    }

    private data class CachedReport(val report: WeatherReport, val fetchedAt: Long)

    companion object {
        private const val TAG = "WeatherProvider"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_JSON = "report_json"
        private const val KEY_FETCHED_AT = "fetched_at"
        private val REFRESH_MS = TimeUnit.MINUTES.toMillis(30)
        private val RETRY_MS = TimeUnit.MINUTES.toMillis(10)
        private val STALE_MS = TimeUnit.HOURS.toMillis(3)

        private val dateOnlyTarget = SmartspaceTarget(
            id = "openMeteoDate",
            featureType = SmartspaceTarget.FeatureType.FEATURE_WEATHER,
        )
    }
}
