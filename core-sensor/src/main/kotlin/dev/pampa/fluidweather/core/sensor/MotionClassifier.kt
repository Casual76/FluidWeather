package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.LastFix
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cio' che i sensori del telefono dicono di una finestra di pochi secondi. Senza Android: la
 * decisione e' di [MotionClassifier] e si prova sul computer.
 */
data class MotionFeatures(
  /** I passi contati dal rilevatore di passi nella finestra; zero senza sensore o senza permesso. */
  val steps: Int,
  val windowSeconds: Double,
  /** Il telefono ha un rilevatore di passi *e* il permesso per leggerlo: senza, zero passi non vuol dire fermo. */
  val hasStepSensor: Boolean,
  /** La deviazione standard dell'accelerazione (m/s^2) nella finestra; null senza accelerometro. */
  val accelStdDev: Double?,
  /** La velocita' che si conosce (vedi [LocationSnapshot.speedMetersPerSecond]); null se non si sa. */
  val speedMetersPerSecond: Double?,
)

data class MotionClass(val kind: ActivityKind, val confidence: Int)

/**
 * "Cosa sta facendo il telefono?" senza Google Play Services (`ActivityRecognition` e' una libreria
 * proprietaria, e l'app e' GPL-3.0). Non e' il suo classificatore, e non pretende di esserlo: a
 * `TransitRule` serve una sola risposta sicura, "questo campione e' nato in viaggio", e il resto
 * (fermo, a piedi, di corsa) serve a non dirlo a torto. Quando non c'e' un'evidenza, la risposta e'
 * [ActivityKind.UNKNOWN] con confidenza zero, che la pulizia non scarta mai: un campione tenuto per
 * errore costa meno di uno buttato.
 *
 * L'ordine e' quello dell'evidenza: i passi battono la velocita' (chi cammina in treno cammina),
 * la velocita' batte l'accelerometro (un telefono in tasca in auto trema poco), e "fermo" si dice
 * solo se non ci sono passi e nemmeno velocita'.
 */
object MotionClassifier {

  /** Passi al minuto da cui si corre: una camminata veloce ne fa 130, una corsa lenta 150. */
  const val RUN_STEPS_PER_MINUTE: Double = 140.0

  /** Passi al minuto da cui si cammina: sotto, e' uno spostamento in casa. */
  const val WALK_STEPS_PER_MINUTE: Double = 40.0

  /** Da questa velocita' (m/s, 29 km/h) in su senza passi e' un veicolo. */
  const val VEHICLE_SPEED: Double = 8.0

  /** Da questa velocita' (m/s, 12,6 km/h) in su senza passi si viaggia: bici, o un'auto in citta'. */
  const val TRANSIT_SPEED: Double = 3.5

  /** Sotto questa velocita' (m/s) il telefono puo' essere fermo. */
  const val STILL_SPEED: Double = 1.0

  /** Sotto questa deviazione (m/s^2) dell'accelerazione il telefono e' appoggiato. */
  const val STILL_ACCEL_STD: Double = 0.08

  fun classify(features: MotionFeatures): MotionClass {
    val stepsPerMinute = if (features.hasStepSensor && features.windowSeconds > 0.0) {
      features.steps / features.windowSeconds * 60.0
    } else {
      0.0
    }
    val speed = features.speedMetersPerSecond
    return when {
      stepsPerMinute >= RUN_STEPS_PER_MINUTE -> MotionClass(ActivityKind.RUNNING, 80)
      stepsPerMinute >= WALK_STEPS_PER_MINUTE -> MotionClass(ActivityKind.WALKING, 80)
      speed != null && speed >= VEHICLE_SPEED -> MotionClass(ActivityKind.IN_VEHICLE, 85)
      speed != null && speed >= TRANSIT_SPEED -> MotionClass(ActivityKind.ON_BICYCLE, 65)
      features.steps == 0 &&
        (speed == null || speed < STILL_SPEED) &&
        features.accelStdDev != null && features.accelStdDev <= STILL_ACCEL_STD -> MotionClass(ActivityKind.STILL, 85)
      else -> MotionClass(ActivityKind.UNKNOWN, 0)
    }
  }
}

/**
 * La statistica di una finestra di accelerazioni, senza tenerle in memoria (Welford): il sensore
 * ne consegna cinquanta al secondo, e servono solo la media e la deviazione.
 */
class RunningStats {
  private var count = 0
  private var mean = 0.0
  private var m2 = 0.0

  @Synchronized
  fun add(value: Double) {
    count++
    val delta = value - mean
    mean += delta / count
    m2 += delta * (value - mean)
  }

  /** La deviazione standard dei valori visti; null con meno di due (non si puo' dire nulla). */
  @Synchronized
  fun standardDeviation(): Double? = if (count < 2) null else sqrt(m2 / (count - 1))

  @Synchronized
  fun size(): Int = count
}

/** La velocita' media fra il fix precedente e quello di adesso: l'unica che si ha quando il provider non da' la sua. */
object TravelSpeed {

  /** Sotto questo intervallo due fix sono lo stesso fix: la velocita' sarebbe tutto rumore. */
  const val MIN_INTERVAL_MILLIS: Long = 30_000L

  /** Oltre questo intervallo la media non dice piu' cosa faceva il telefono *adesso*. */
  const val MAX_INTERVAL_MILLIS: Long = 30 * 60_000L

  private const val EARTH_RADIUS_METERS = 6_371_000.0

  /** Metri sulla sfera (haversine): per qualche chilometro basta e avanza. */
  fun distanceMeters(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
    val dLat = Math.toRadians(toLat - fromLat)
    val dLon = Math.toRadians(toLon - fromLon)
    val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(Math.toRadians(fromLat)) * cos(Math.toRadians(toLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(a)))
  }

  /** m/s fra [previous] e il fix nuovo, o null se l'intervallo e' troppo corto, troppo lungo o all'indietro. */
  fun between(previous: LastFix, latitude: Double, longitude: Double, atMillis: Long): Double? {
    val interval = atMillis - previous.fixedAtMillis
    if (interval < MIN_INTERVAL_MILLIS || interval > MAX_INTERVAL_MILLIS) return null
    return distanceMeters(previous.latitude, previous.longitude, latitude, longitude) / (interval / 1_000.0)
  }
}
