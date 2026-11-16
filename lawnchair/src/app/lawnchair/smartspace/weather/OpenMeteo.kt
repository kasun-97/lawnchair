package app.lawnchair.smartspace.weather

import android.util.Log
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** Current conditions and the next few hours from Open-Meteo (free, no API key). */
@Serializable
data class WeatherReport(
    val current: Current,
    val hourly: Hourly? = null,
) {
    @Serializable
    data class Current(
        /** Unix seconds. */
        val time: Long,
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("weather_code") val weatherCode: Int,
        @SerialName("is_day") val isDay: Int = 1,
    )

    @Serializable
    data class Hourly(
        /** Unix seconds, starting at the current hour. */
        val time: List<Long> = emptyList(),
        @SerialName("precipitation_probability") val precipitationProbability: List<Int?> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
    )
}

/** Fetches forecasts. [fetch] blocks, so call it off the main thread. */
object OpenMeteo {
    private const val TAG = "OpenMeteo"
    private const val ENDPOINT = "https://api.open-meteo.com/v1/forecast"

    val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns the raw JSON and the parsed report, or null on any network or parse failure.
     * Coordinates are rounded to two decimals (about 1 km), which is plenty for a forecast.
     */
    fun fetch(latitude: Double, longitude: Double): Pair<String, WeatherReport>? {
        val url = String.format(
            Locale.ROOT,
            "%s?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day" +
                "&hourly=precipitation_probability,weather_code&forecast_hours=6&timezone=auto&timeformat=unixtime",
            ENDPOINT,
            latitude,
            longitude,
        )
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code}")
                    return null
                }
                val body = response.body.string()
                body to json.decodeFromString<WeatherReport>(body)
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetch failed", e)
            null
        } catch (e: SerializationException) {
            Log.w(TAG, "unexpected response", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "unexpected response", e)
            null
        }
    }
}
