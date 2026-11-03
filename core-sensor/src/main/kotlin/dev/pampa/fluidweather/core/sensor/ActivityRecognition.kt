package dev.pampa.fluidweather.core.sensor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.pampa.fluidweather.core.data.LatestActivity
import dev.pampa.fluidweather.core.data.LatestActivityStore
import dev.pampa.fluidweather.nowcast.cleaning.TransitRule
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import kotlinx.coroutines.delay

/**
 * Tiene aggiornata l'ultima attivita' riconosciuta, cosi' ogni campione barometrico sa se e' nato
 * fermo, in auto o in ascensore (fase 2: la pulizia scarta i cambi di quota).
 *
 * Prima l'attivita' arrivava ogni minuto da Google Play Services (`ActivityRecognition`, una
 * libreria proprietaria: fuori da un'app GPL-3.0). Adesso la si misura quando serve, ed e' il
 * campionamento a chiederla, in due tempi: [measure] sta quattro secondi con l'accelerometro e il
 * rilevatore di passi (mentre il fix della posizione arriva), poi [record] la giudica insieme alla
 * velocita' che la posizione conosce e la scrive. Il giudizio e' di [MotionClassifier].
 *
 * Il permesso `ACTIVITY_RECOGNITION` resta, ed e' quello che Android pretende per leggere i passi.
 */
class ActivityRecognizer(
  private val context: Context,
  private val store: LatestActivityStore,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

  fun hasPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
      PackageManager.PERMISSION_GRANTED

  /**
   * [windowMillis] di movimento dai sensori del telefono, senza la velocita' (non la conosce).
   * Null se il telefono non ha ne' accelerometro ne' rilevatore di passi (o non c'e' il permesso
   * per i passi): niente da dire, e l'ultima attivita' resta quella di prima.
   */
  suspend fun measure(windowMillis: Long = WINDOW_MILLIS): MotionFeatures? {
    val manager = sensorManager ?: return null
    val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    val stepDetector = if (hasPermission()) manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) else null
    if (accelerometer == null && stepDetector == null) return null

    val stats = RunningStats()
    val steps = AtomicInteger(0)
    val listener = object : SensorEventListener {
      override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
          Sensor.TYPE_ACCELEROMETER -> {
            val x = event.values[0].toDouble()
            val y = event.values[1].toDouble()
            val z = event.values[2].toDouble()
            stats.add(sqrt(x * x + y * y + z * z))
          }
          Sensor.TYPE_STEP_DETECTOR -> steps.incrementAndGet()
        }
      }

      override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    accelerometer?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    stepDetector?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    try {
      delay(windowMillis)
    } finally {
      manager.unregisterListener(listener)
    }
    return MotionFeatures(
      steps = steps.get(),
      windowSeconds = windowMillis / 1_000.0,
      hasStepSensor = stepDetector != null,
      accelStdDev = stats.standardDeviation(),
      speedMetersPerSecond = null,
    )
  }

  /**
   * Giudica [motion] con la velocita' [speedMetersPerSecond] (quella che la posizione conosce, o
   * null) e la scrive come ultima attivita'. Senza misura non scrive niente.
   */
  suspend fun record(motion: MotionFeatures?, speedMetersPerSecond: Double?): LatestActivity? {
    motion ?: return null
    val found = MotionClassifier.classify(motion.copy(speedMetersPerSecond = speedMetersPerSecond))
    val activity = LatestActivity(kind = found.kind, confidence = found.confidence, observedAtMillis = clock())
    // Lo stesso criterio della pulizia del segnale: "in auto o in bici" lo decide una regola sola, e
    // qui serve a sapere da quando l'ultimo fix GPS non e' piu' "qui".
    store.store(activity, inTransit = TransitRule.isInTransit(found.kind, found.confidence))
    return activity
  }

  companion object {
    /** Quattro secondi: abbastanza per due o tre passi e per una media dell'accelerazione, senza far aspettare la raffica. */
    const val WINDOW_MILLIS: Long = 4_000L
  }
}
