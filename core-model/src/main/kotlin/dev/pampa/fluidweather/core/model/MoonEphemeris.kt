package dev.pampa.fluidweather.core.model

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * La posizione della luna, quanto basta per sorgere, tramonto e distanza.
 *
 * Longitudine e latitudine eclittiche dalla "MiniMoon" di Montenbruck & Pfleger (Astronomy on
 * the Personal Computer): una manciata di termini periodici, precisione di qualche primo
 * d'arco — su un sorgere fa un paio di minuti, che per una pagina e' abbondante. La distanza
 * dai termini principali della tavola 47.A di Meeus (Astronomical Algorithms), entro ~100 km.
 * Niente effemeridi da imbarcare, niente rete: e' aritmetica.
 */
object MoonEphemeris {

  data class Position(
    val eclipticLongitudeDeg: Double,
    val eclipticLatitudeDeg: Double,
    val distanceKm: Double,
    val rightAscensionDeg: Double,
    val declinationDeg: Double,
  )

  data class Times(val riseMillis: Long?, val setMillis: Long?)

  /** Perigeo e apogeo medi: le due estremita' fra cui la distanza oscilla ogni mese anomalistico. */
  const val PERIGEE_KM = 363_300.0
  const val APOGEE_KM = 405_500.0

  private const val ARCSEC_PER_RAD = 206_264.8062
  private const val EARTH_RADIUS_KM = 6_378.14

  fun position(timestampMillis: Long): Position {
    // Secoli giuliani dal J2000 (2000-01-01T12:00Z = giorno 10957.5 dall'epoca Unix).
    val t = (timestampMillis / 86_400_000.0 - 10957.5) / 36525.0
    val twoPi = 2 * PI
    val l0 = frac(0.606433 + 1336.855225 * t)
    val l = twoPi * frac(0.374897 + 1325.552410 * t) // anomalia media della luna
    val ls = twoPi * frac(0.993133 + 99.997361 * t) // anomalia media del sole
    val d = twoPi * frac(0.827361 + 1236.853086 * t) // elongazione media luna-sole
    val f = twoPi * frac(0.259086 + 1342.227825 * t) // distanza dal nodo ascendente

    val dl = 22640 * sin(l) - 4586 * sin(l - 2 * d) + 2370 * sin(2 * d) + 769 * sin(2 * l) -
      668 * sin(ls) - 412 * sin(2 * f) - 212 * sin(2 * l - 2 * d) - 206 * sin(l + ls - 2 * d) +
      192 * sin(l + 2 * d) - 165 * sin(ls - 2 * d) - 125 * sin(d) - 110 * sin(l + ls) +
      148 * sin(l - ls) - 55 * sin(2 * f - 2 * d)
    val s = f + (dl + 412 * sin(2 * f) + 541 * sin(ls)) / ARCSEC_PER_RAD
    val h = f - 2 * d
    val n = -526 * sin(h) + 44 * sin(l + h) - 31 * sin(-l + h) - 23 * sin(ls + h) +
      11 * sin(-ls + h) - 25 * sin(-2 * l + f) + 21 * sin(-l + f)
    val longitude = twoPi * frac(l0 + dl / 1_296_000.0)
    val latitude = (18_520.0 * sin(s) + n) / ARCSEC_PER_RAD

    val distance = 385_000.56 +
      (-20905.355 * cos(l) - 3699.111 * cos(2 * d - l) - 2955.968 * cos(2 * d) -
        569.925 * cos(2 * l) + 48.888 * cos(ls) - 3.149 * cos(2 * f) + 246.158 * cos(2 * d - 2 * l) -
        152.138 * cos(2 * d - ls - l) - 170.733 * cos(2 * d + l) - 204.586 * cos(2 * d - ls) -
        129.620 * cos(ls - l) + 108.743 * cos(d) + 104.755 * cos(ls + l) + 10.321 * cos(2 * d - 2 * f) +
        79.661 * cos(l - 2 * f) - 34.782 * cos(4 * d - l) - 23.210 * cos(3 * l) - 21.636 * cos(4 * d - 2 * l))

    // Dall'eclittica all'equatore: ascensione retta e declinazione.
    val obliquity = Math.toRadians(23.439291 - 0.0130042 * t)
    val x = cos(latitude) * cos(longitude)
    val y = cos(obliquity) * cos(latitude) * sin(longitude) - sin(obliquity) * sin(latitude)
    val z = sin(obliquity) * cos(latitude) * sin(longitude) + cos(obliquity) * sin(latitude)
    val rightAscension = Math.toDegrees(atan2(y, x)).mod360()
    val declination = Math.toDegrees(asin(z))

    return Position(
      eclipticLongitudeDeg = Math.toDegrees(longitude).mod360(),
      eclipticLatitudeDeg = Math.toDegrees(latitude),
      distanceKm = distance,
      rightAscensionDeg = rightAscension,
      declinationDeg = declination,
    )
  }

  fun distanceKm(timestampMillis: Long): Double = position(timestampMillis).distanceKm

  /** Altezza geocentrica della luna sull'orizzonte, in gradi (senza parallasse ne' rifrazione). */
  fun altitudeDegrees(timestampMillis: Long, latitude: Double, longitude: Double): Double {
    val position = position(timestampMillis)
    val hourAngle = Math.toRadians(localSiderealDegrees(timestampMillis, longitude) - position.rightAscensionDeg)
    val latitudeRad = Math.toRadians(latitude)
    val declinationRad = Math.toRadians(position.declinationDeg)
    return Math.toDegrees(
      asin(sin(latitudeRad) * sin(declinationRad) + cos(latitudeRad) * cos(declinationRad) * cos(hourAngle)),
    )
  }

  /**
   * Sorgere e tramonto della luna nel giorno UTC che parte da [dayStartUtcMillis]: si scandisce
   * la giornata a passi di 10 minuti e si raffina per bisezione, come per il sole. La soglia e'
   * quella di Meeus per il bordo superiore rifratto, al netto della parallasse (che per la luna
   * vale quasi un grado: ignorarla sposta il sorgere di minuti). Un giorno al mese la luna non
   * sorge, o non tramonta: null, non un'invenzione.
   */
  fun riseSet(dayStartUtcMillis: Long, latitude: Double, longitude: Double): Times {
    val parallaxDeg = Math.toDegrees(asin(EARTH_RADIUS_KM / distanceKm(dayStartUtcMillis + 12 * 3_600_000L)))
    val horizon = 0.7275 * parallaxDeg - 0.5667
    var rise: Long? = null
    var set: Long? = null
    var previous = altitudeDegrees(dayStartUtcMillis, latitude, longitude)
    for (minute in 10..1440 step 10) {
      val t = dayStartUtcMillis + minute * 60_000L
      val altitude = altitudeDegrees(t, latitude, longitude)
      if (previous < horizon && altitude >= horizon && rise == null) {
        rise = refine(t - 10 * 60_000L, t, latitude, longitude, horizon, rising = true)
      }
      if (previous >= horizon && altitude < horizon && set == null) {
        set = refine(t - 10 * 60_000L, t, latitude, longitude, horizon, rising = false)
      }
      previous = altitude
    }
    return Times(rise, set)
  }

  private fun refine(
    fromMillis: Long,
    toMillis: Long,
    latitude: Double,
    longitude: Double,
    horizon: Double,
    rising: Boolean,
  ): Long {
    var low = fromMillis
    var high = toMillis
    repeat(12) {
      val mid = (low + high) / 2
      val above = altitudeDegrees(mid, latitude, longitude) >= horizon
      if (above == rising) high = mid else low = mid
    }
    return (low + high) / 2
  }

  /** Tempo siderale locale in gradi: GMST (formula IAU 1982 semplificata) piu' la longitudine. */
  private fun localSiderealDegrees(timestampMillis: Long, longitude: Double): Double {
    val daysFromJ2000 = timestampMillis / 86_400_000.0 - 10957.5
    val gmstHours = 18.697374558 + 24.06570982441908 * daysFromJ2000
    return (gmstHours * 15.0 + longitude).mod360()
  }

  private fun frac(x: Double): Double = x - floor(x)

  private fun Double.mod360(): Double = ((this % 360.0) + 360.0) % 360.0
}
