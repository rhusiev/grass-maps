package nl.r1a.grassmap.core.services

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import nl.r1a.grassmap.plugin.api.service.HeadingSource

private const val FULL_CIRCLE_DEGREES: Double = 360.0
private const val STOP_TIMEOUT_MILLIS: Long = 5_000L

/**
 * The device compass over the platform rotation-vector sensor.
 *
 * The flow is cold: the sensor registers while it has a collector and stops a
 * few seconds after the last one leaves, so a backgrounded map costs no sensor
 * time. A device without the sensor reports null and heading mode falls back
 * to follow.
 */
class AndroidHeadingSource(
    context: Context,
    scope: CoroutineScope,
) : HeadingSource {
    private val manager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)

    override val headingDegrees: StateFlow<Double?> =
        callbackFlow {
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (manager == null || sensor == null) {
                trySend(null)
                awaitClose { }
                return@callbackFlow
            }
            val listener = RotationListener { degrees -> trySend(degrees) }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            awaitClose { manager.unregisterListener(listener) }
        }.stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

    private class RotationListener(
        private val onHeading: (Double) -> Unit,
    ) : SensorEventListener {
        private val rotation = FloatArray(ROTATION_MATRIX_SIZE)
        private val orientation = FloatArray(ORIENTATION_SIZE)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.values.size < ROTATION_VECTOR_SIZE) return
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            val degrees = Math.toDegrees(orientation[AZIMUTH_INDEX].toDouble())
            onHeading(((degrees % FULL_CIRCLE_DEGREES) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES)
        }

        override fun onAccuracyChanged(
            sensor: Sensor?,
            accuracy: Int,
        ) = Unit
    }

    private companion object {
        const val ROTATION_VECTOR_SIZE = 3
        const val ROTATION_MATRIX_SIZE = 9
        const val ORIENTATION_SIZE = 3
        const val AZIMUTH_INDEX = 0
    }
}
