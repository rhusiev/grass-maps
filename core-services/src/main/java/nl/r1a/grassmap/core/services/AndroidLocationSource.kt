package nl.r1a.grassmap.core.services

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import nl.r1a.grassmap.plugin.api.model.GeoPoint
import nl.r1a.grassmap.plugin.api.model.LocationFix

private const val DEFAULT_UPDATE_INTERVAL_MILLIS: Long = 1_000L
private const val DEFAULT_UPDATE_DISTANCE_METERS: Float = 0f
private const val FALLBACK_ACCURACY_METERS: Double = 50.0
private val PROVIDERS: List<String> =
    listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
    )

/**
 * The foreground-only location source over the platform [LocationManager]
 * (architecture.md sections 4.3 and 26).
 *
 * It uses the GPS and network providers, never Play Services and never a
 * background permission. The flow is cold: providers are registered while it
 * has a collector and removed when the collector goes away. A disabled
 * provider is dropped and retried when the system reports it enabled again;
 * the last fix stays visible, and the map marks it stale by its age.
 *
 * The first value is the most recent last-known fix, or null when the platform
 * has none. No coordinate ever reaches a log.
 */
@SuppressLint("MissingPermission")
class AndroidLocationSource(
    context: Context,
    private val updateIntervalMillis: Long = DEFAULT_UPDATE_INTERVAL_MILLIS,
    private val updateDistanceMeters: Float = DEFAULT_UPDATE_DISTANCE_METERS,
) : LocationSource {
    private val appContext: Context = context.applicationContext
    private val manager: LocationManager? = appContext.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        appContext.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            appContext.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    override val fixes: Flow<LocationFix?> =
        callbackFlow {
            val locationManager = manager
            if (locationManager == null || !hasPermission()) {
                trySend(null)
                awaitClose { }
                return@callbackFlow
            }
            trySend(lastKnownFix(locationManager))
            val listener =
                ProviderListener(
                    locationManager = locationManager,
                    updateIntervalMillis = updateIntervalMillis,
                    updateDistanceMeters = updateDistanceMeters,
                    onFix = { fix -> trySend(fix) },
                )
            listener.syncProviders()
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        listener.syncProviders()
                    }
                }
            registerProviderReceiver(receiver)
            awaitClose {
                listener.stop()
                runCatching { appContext.unregisterReceiver(receiver) }
            }
        }.conflate()

    /** One fix per provider update, shared by every provider this source listens to. */
    private class ProviderListener(
        private val locationManager: LocationManager,
        private val updateIntervalMillis: Long,
        private val updateDistanceMeters: Float,
        private val onFix: (LocationFix) -> Unit,
    ) : LocationListener {
        private val registered = linkedSetOf<String>()

        override fun onLocationChanged(location: Location) {
            onFix(location.toFix())
        }

        override fun onProviderEnabled(provider: String) {
            registerIfEnabled(provider)
        }

        override fun onProviderDisabled(provider: String) {
            syncProviders()
        }

        @Deprecated("Deprecated in the platform, kept for API 29 compatibility")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?,
        ) = Unit

        /**
         * Registers every enabled provider, and drops the ones that turned off.
         * The platform removes the listener from every provider at once, so a
         * change re-registers the whole set instead of one provider.
         */
        fun syncProviders() {
            stop()
            for (provider in PROVIDERS) {
                registerIfEnabled(provider)
            }
        }

        fun stop() {
            if (registered.isNotEmpty()) {
                runCatching { locationManager.removeUpdates(this) }
            }
            registered.clear()
        }

        private fun registerIfEnabled(provider: String) {
            if (provider in registered || !isEnabled(provider)) {
                return
            }
            try {
                locationManager.requestLocationUpdates(
                    provider,
                    updateIntervalMillis,
                    updateDistanceMeters,
                    this,
                    Looper.getMainLooper(),
                )
                registered += provider
            } catch (exception: SecurityException) {
                // The permission was revoked between the check and the request.
            } catch (exception: IllegalArgumentException) {
                // The provider vanished between isProviderEnabled and the request.
            }
        }

        private fun isEnabled(provider: String): Boolean = runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
    }

    private fun lastKnownFix(locationManager: LocationManager): LocationFix? {
        var best: Location? = null
        for (provider in PROVIDERS) {
            val location =
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (exception: SecurityException) {
                    null
                } catch (exception: IllegalArgumentException) {
                    null
                }
            if (location != null && (best == null || location.time > best.time)) {
                best = location
            }
        }
        return best?.toFix()
    }

    private fun registerProviderReceiver(receiver: BroadcastReceiver) {
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private fun Context.isGranted(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}

private fun Location.toFix(): LocationFix {
    val now = System.currentTimeMillis()
    return LocationFix(
        position = GeoPoint(latitude = latitude, longitude = longitude),
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else FALLBACK_ACCURACY_METERS,
        altitudeMeters = if (hasAltitude()) altitude else null,
        bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
        speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
        timeMillis = time.takeIf { it > 0L } ?: now,
    )
}
