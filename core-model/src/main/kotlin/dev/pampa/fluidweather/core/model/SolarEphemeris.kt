package dev.pampa.fluidweather.core.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** La fase di luce che governa il cielo: quattro parole, non un angolo nudo. */
enum class DayPhase { NIGHT, DAWN, DAY, DUSK }

/**
 * Effemeridi solari essenziali: elevazione del sole e fase del giorno per un punto e un istante.
 *
 * Formule NOAA semplificate (declinazione + equazione del tempo approssimata + angolo orario):
 * precisione dell'ordine del grado, che per colorare un cielo e disegnare un arco e' abbondante.
 * I crepuscoli usano la soglia civile (-6 gradi): sotto, per l'occhio e' notte.
 */
object SolarEphemeris {

  /** Elevazione del sole sull'orizzonte, in gradi. */
  fun elevationDegrees(timestampMillis: Long, latitude: Double, longitude: Double): Double {
    val days = timestampMillis / 86_400_000.0
    val julianCentury = (days - 10957.5) / 36525.0 // dal J2000

    val meanLongitude = (280.46646 + julianCentury * 36000.76983).mod(360.0)
    val meanAnomaly = 357.52911 + julianCentury * 35999.05029
    val anomalyRad = Math.toRadians(meanAnomaly)
    val equationOfCenter = sin(anomalyRad) * (1.914602 - julianCentury * 0.004817) +
      sin(2 * anomalyRad) * 0.019993 + sin(3 * anomalyRad) * 0.000289
    val trueLongitude = meanLongitude + equationOfCenter
    val obliquity = 23.439291 - julianCentury * 0.0130042
    val declination = Math.toDegrees(
      asin(sin(Math.toRadians(obliquity)) * sin(Math.toRadians(trueLongitude))),
    )

    // Equazione del tempo (minuti), forma compatta sufficiente al grado di precisione dichiarato.
    val y = kotlin.math.tan(Math.toRadians(obliquity / 2)).let { it * it }
    val longitudeRad = Math.toRadians(meanLongitude)
    val equationOfTime = 4 * Math.toDegrees(
      y * sin(2 * longitudeRad) - 2 * 0.016708 * sin(anomalyRad) +
        4 * 0.016708 * y * sin(anomalyRad) * cos(2 * longitudeRad),
    )

    val utcHours = Math.floorMod(timestampMillis, 86_400_000L) / 3_600_000.0
    val trueSolarHours = (utcHours + longitude / 15.0 + equationOfTime / 60.0).mod(24.0)
    val hourAngle = Math.toRadians((trueSolarHours - 12.0) * 15.0)

    val latitudeRad = Math.toRadians(latitude)
    val declinationRad = Math.toRadians(declination)
    val elevation = asin(
      sin(latitudeRad) * sin(declinationRad) +
        cos(latitudeRad) * cos(declinationRad) * cos(hourAngle),
    )
    return Math.toDegrees(elevation)
  }

  /** La longitudine eclittica vera del sole, in gradi: serve alle fasi vere della luna. */
  fun eclipticLongitudeDegrees(timestampMillis: Long): Double {
    val days = timestampMillis / 86_400_000.0
    val julianCentury = (days - 10957.5) / 36525.0
    val meanLongitude = (280.46646 + julianCentury * 36000.76983).mod(360.0)
    val anomalyRad = Math.toRadians(357.52911 + julianCentury * 35999.05029)
    val equationOfCenter = sin(anomalyRad) * (1.914602 - julianCentury * 0.004817) +
      sin(2 * anomalyRad) * 0.019993 + sin(3 * anomalyRad) * 0.000289
    return (meanLongitude + equationOfCenter).mod(360.0)
  }

  /**
   * DAWN e DUSK sono il crepuscolo civile (-6..+6 gradi), separati dal lato del mezzogiorno
   * solare in cui ci si trova: la stessa elevazione all'alba e al tramonto colora due cieli
   * diversi, e distinguerli e' il minimo sindacale del realismo.
   */
  fun phaseAt(timestampMillis: Long, latitude: Double, longitude: Double): DayPhase {
    val elevation = elevationDegrees(timestampMillis, latitude, longitude)
    if (elevation > 6.0) return DayPhase.DAY
    if (elevation < -6.0) return DayPhase.NIGHT

    // Il sole sta salendo se fra poco e' piu' alto di adesso.
    val soon = elevationDegrees(timestampMillis + 10 * 60_000L, latitude, longitude)
    return if (soon > elevation) DayPhase.DAWN else DayPhase.DUSK
  }

  private fun Double.mod(modulus: Double): Double = ((this % modulus) + modulus) % modulus
}
