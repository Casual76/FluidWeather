package dev.pampa.fluidweather.core.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

/** Una lettura del sensore con l'orologio di parete, che e' quello che l'archivio capisce. */
data class TimedReading(
  val timestampMillis: Long,
  val pressureHpa: Double,
)

class Barometer(context: Context) {

  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
  private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)

  val isAvailable: Boolean get() = sensor != null

  val sensorName: String? get() = sensor?.name

  /**
   * Il flusso vivo delle letture: il sensore e' acceso solo mentre qualcuno colleziona.
   * Senza barometro il flusso e' vuoto e si chiude subito — chi legge decide come degradare.
   */
  fun readings(): Flow<TimedReading> {
    val pressureSensor = sensor ?: return emptyFlow()
    return callbackFlow {
      val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
          trySend(TimedReading(System.currentTimeMillis(), event.values[0].toDouble()))
        }

        override fun onAccuracyChanged(changed: Sensor?, accuracy: Int) = Unit
      }
      sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_UI)
      awaitClose { sensorManager.unregisterListener(listener) }
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
