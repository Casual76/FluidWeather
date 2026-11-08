package dev.pampa.fluidweather.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

/** Una lettura del sensore con l'orologio di parete, che e' quello che l'archivio capisce. */
data class TimedReading(
  val timestampMillis: Long,
  val pressureHpa: Double,
)

/** A rejected registration is a failed attempt, not unsupported hardware. */
class SensorRegistrationException : IllegalStateException("Pressure sensor registration rejected")

internal interface PressureSensorInput {
  val isAvailable: Boolean
  val sensorName: String?
  fun register(onReading: (TimedReading) -> Unit): Boolean
  fun unregister()
}

private class AndroidPressureSensorInput(context: Context) : PressureSensorInput {
  private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val sensor = manager.getDefaultSensor(Sensor.TYPE_PRESSURE)
  private var listener: SensorEventListener? = null
  override val isAvailable get() = sensor != null
  override val sensorName get() = sensor?.name
  override fun register(onReading: (TimedReading) -> Unit): Boolean {
    val pressureSensor = sensor ?: return false
    val registeredListener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        onReading(TimedReading(System.currentTimeMillis(), event.values[0].toDouble()))
      }
      override fun onAccuracyChanged(changed: Sensor?, accuracy: Int) = Unit
    }
    listener = registeredListener
    return manager.registerListener(registeredListener, pressureSensor, SensorManager.SENSOR_DELAY_UI)
  }
  override fun unregister() {
    listener?.let(manager::unregisterListener)
    listener = null
  }
}

class Barometer internal constructor(private val inputFactory: () -> PressureSensorInput) {
  constructor(context: Context) : this({ AndroidPressureSensorInput(context) })

  val isAvailable: Boolean get() = inputFactory().isAvailable
  val sensorName: String? get() = inputFactory().sensorName

  /** Each collector owns its listener, including rejected or cancelled registrations. */
  fun readings(): Flow<TimedReading> = callbackFlow {
    val input = inputFactory()
    if (!input.isAvailable) {
      close()
      return@callbackFlow
    }
    try {
      if (!input.register { trySend(it) }) throw SensorRegistrationException()
      awaitClose { }
    } finally {
      input.unregister()
    }
  }

  /** La prima lettura che arriva, o null se il sensore manca o tace oltre il timeout. */
  suspend fun single(timeoutMillis: Long = 5_000): TimedReading? =
    withTimeoutOrNull(timeoutMillis) { readings().firstOrNull() }

  /**
   * Una raffica: al piu' una lettura per finestra di [periodMillis], per [durationSeconds].
   * Il sensore consegna piu' veloce di 1 Hz; il di piu' non porta informazione barica e
   * gonfierebbe solo l'archivio.
   */
  fun burst(durationSeconds: Int, periodMillis: Long = 1_000): Flow<TimedReading> = flow {
    val start = System.currentTimeMillis()
    val endAt = start + durationSeconds * 1_000L
    var lastBucket = -1L
    withTimeoutOrNull(durationSeconds * 1_000L) {
      readings()
        .takeWhile { it.timestampMillis < endAt }
        .collect { reading ->
          val bucket = (reading.timestampMillis - start) / periodMillis
          if (bucket != lastBucket) {
            lastBucket = bucket
            emit(reading)
          }
        }
    }
  }
}
