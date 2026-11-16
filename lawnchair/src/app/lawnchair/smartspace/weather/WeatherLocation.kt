package app.lawnchair.smartspace.weather

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationManagerCompat
import com.android.launcher3.Utilities
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Finds a rough (city-level) location with the platform location APIs; no Play services needed. */
object WeatherLocation {
    private const val TAG = "WeatherLocation"
    private val FRESH_MS = TimeUnit.HOURS.toMillis(2)
    private val FIX_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(30)

    /** Needs the coarse location permission; returns null when no location is available. */
    suspend fun find(context: Context): Location? {
        val manager = context.getSystemService<LocationManager>() ?: return null
        val providers = buildList {
            if (Utilities.ATLEAST_S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.filter { it in manager.allProviders }

        val lastKnown = providers
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < FRESH_MS) return lastKnown

        val fixProvider = providers.firstOrNull {
            it != LocationManager.PASSIVE_PROVIDER && it != LocationManager.GPS_PROVIDER && manager.isProviderEnabled(it)
        }
        val fresh = fixProvider?.let { provider ->
            withTimeoutOrNull(FIX_TIMEOUT_MS) { currentLocation(context, manager, provider) }
        }
        return fresh ?: lastKnown
    }

    private suspend fun currentLocation(context: Context, manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val cancel = CancellationSignal()
        continuation.invokeOnCancellation { cancel.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, cancel, ContextCompat.getMainExecutor(context)) {
                if (continuation.isActive) continuation.resume(it)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "location permission missing", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }
}
