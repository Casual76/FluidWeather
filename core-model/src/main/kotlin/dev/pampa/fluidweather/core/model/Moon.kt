package dev.pampa.fluidweather.core.model

import kotlin.math.PI
import kotlin.math.cos

/** La faccia della luna in otto parole. */
enum class MoonPhase {
  NEW,
  WAXING_CRESCENT,
  FIRST_QUARTER,
  WAXING_GIBBOUS,
  FULL,
  WANING_GIBBOUS,
  LAST_QUARTER,
  WANING_CRESCENT,
}

/**
 * La luna per il widget: eta' del ciclo, fase, illuminazione, prossima luna piena.
 *
 * Modello del mese sinodico medio (29,530588 giorni) ancorato al novilunio del 6 gennaio 2000
 * 18:14 UTC: l'errore massimo e' di poche ore — invisibile su un widget, e senza effemeridi da
 * imbarcare. Il sorgere della luna richiede la posizione topocentrica vera: rimandato con
 * onesta' alla rifinitura, non finto.
 */
object Moon {

  const val SYNODIC_MONTH_DAYS: Double = 29.530588853

  /** Novilunio di riferimento: 2000-01-06T18:14Z. */
  private const val REFERENCE_NEW_MOON_MILLIS = 947_182_440_000L

  /** Eta' della luna in giorni dall'ultimo novilunio, in [0, mese sinodico). */
  fun ageDays(timestampMillis: Long): Double {
    val days = (timestampMillis - REFERENCE_NEW_MOON_MILLIS) / 86_400_000.0
    return ((days % SYNODIC_MONTH_DAYS) + SYNODIC_MONTH_DAYS) % SYNODIC_MONTH_DAYS
  }

  /** Frazione illuminata [0..1]: coseno dell'angolo di fase, non una rampa lineare. */
  fun illuminatedFraction(timestampMillis: Long): Double {
    val phaseAngle = 2 * PI * ageDays(timestampMillis) / SYNODIC_MONTH_DAYS
    return (1 - cos(phaseAngle)) / 2
  }

  fun phase(timestampMillis: Long): MoonPhase {
    val age = ageDays(timestampMillis)
    val eighth = SYNODIC_MONTH_DAYS / 8
    return when {
      age < eighth * 0.5 -> MoonPhase.NEW
      age < eighth * 1.5 -> MoonPhase.WAXING_CRESCENT
      age < eighth * 2.5 -> MoonPhase.FIRST_QUARTER
      age < eighth * 3.5 -> MoonPhase.WAXING_GIBBOUS
      age < eighth * 4.5 -> MoonPhase.FULL
      age < eighth * 5.5 -> MoonPhase.WANING_GIBBOUS
      age < eighth * 6.5 -> MoonPhase.LAST_QUARTER
      age < eighth * 7.5 -> MoonPhase.WANING_CRESCENT
      else -> MoonPhase.NEW
    }
  }

  /** Il prossimo plenilunio (eta' = mezzo mese sinodico), in avanti da adesso. */
  fun nextFullMoonMillis(timestampMillis: Long): Long {
    val age = ageDays(timestampMillis)
    val half = SYNODIC_MONTH_DAYS / 2
    val daysToFull = if (age <= half) half - age else SYNODIC_MONTH_DAYS - age + half
    return timestampMillis + (daysToFull * 86_400_000).toLong()
  }
}

/**
 * Alba e tramonto per una data: si cercano gli attraversamenti dell'orizzonte (-0,833 gradi,
 * rifrazione inclusa) scandendo la giornata e raffinando per bisezione. Meno elegante di una
 * formula chiusa, ma funziona identico ovunque — poli compresi, dove semplicemente non trova
 * attraversamenti e lo dice con dei null.
 */
object SunTimes {

  /** Il bordo superiore del sole, rifrazione inclusa: e' l'alba e il tramonto "visti". */
  const val HORIZON_DEGREES = -0.833

  data class Times(val sunriseMillis: Long?, val sunsetMillis: Long?)

  /**
   * Gli attraversamenti di [horizonDegrees] nel giorno: con la soglia di default sono alba e
   * tramonto, con quelle di [Twilight] l'inizio e la fine dei crepuscoli.
   */
  fun forDay(
    dayStartUtcMillis: Long,
    latitude: Double,
    longitude: Double,
    horizonDegrees: Double = HORIZON_DEGREES,
  ): Times {
    var sunrise: Long? = null
    var sunset: Long? = null
    var previous = SolarEphemeris.elevationDegrees(dayStartUtcMillis, latitude, longitude)
    for (minute in 10..1440 step 10) {
      val t = dayStartUtcMillis + minute * 60_000L
      val elevation = SolarEphemeris.elevationDegrees(t, latitude, longitude)
      if (previous < horizonDegrees && elevation >= horizonDegrees && sunrise == null) {
        sunrise = refine(t - 10 * 60_000L, t, latitude, longitude, horizonDegrees, rising = true)
      }
      if (previous >= horizonDegrees && elevation < horizonDegrees) {
        sunset = refine(t - 10 * 60_000L, t, latitude, longitude, horizonDegrees, rising = false)
      }
      previous = elevation
    }
    return Times(sunrise, sunset)
  }

  /** Il mezzogiorno solare: l'istante di massima elevazione nel giorno, e quanto e' alto. */
  fun solarNoon(dayStartUtcMillis: Long, latitude: Double, longitude: Double): Pair<Long, Double> {
    var bestMillis = dayStartUtcMillis
    var bestElevation = -90.0
    for (minute in 0 until 1440) {
      val t = dayStartUtcMillis + minute * 60_000L
      val elevation = SolarEphemeris.elevationDegrees(t, latitude, longitude)
      if (elevation > bestElevation) {
        bestElevation = elevation
        bestMillis = t
      }
    }
    return bestMillis to bestElevation
  }

  private fun refine(
    fromMillis: Long,
    toMillis: Long,
    latitude: Double,
    longitude: Double,
    horizonDegrees: Double,
    rising: Boolean,
  ): Long {
    var low = fromMillis
    var high = toMillis
    repeat(12) {
      val mid = (low + high) / 2
      val above = SolarEphemeris.elevationDegrees(mid, latitude, longitude) >= horizonDegrees
      if (above == rising) high = mid else low = mid
    }
    return (low + high) / 2
  }
}

/** Le tre soglie dei crepuscoli: sotto -6 l'occhio dice notte, sotto -18 anche il cielo. */
object Twilight {
  const val CIVIL = -6.0
  const val NAUTICAL = -12.0
  const val ASTRONOMICAL = -18.0
}

/**
 * La durata del giorno lungo un anno intero, per una latitudine: la curva della pagina del
 * sole, con i suoi due estremi (solstizi) e i due punti in cui vale dodici ore (equinozi).
 */
object SunCalendar {

  data class DayLength(val date: java.time.LocalDate, val lengthMillis: Long?)

  fun year(year: Int, latitude: Double, longitude: Double): List<DayLength> {
    val first = java.time.LocalDate.of(year, 1, 1)
    return (0 until first.lengthOfYear()).map { offset ->
      val date = first.plusDays(offset.toLong())
      val dayStart = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
      val times = SunTimes.forDay(dayStart, latitude, longitude)
      val length = if (times.sunriseMillis != null && times.sunsetMillis != null) {
        (times.sunsetMillis - times.sunriseMillis).takeIf { it > 0 }
      } else {
        null
      }
      DayLength(date, length)
    }
  }
}

/**
 * La temperatura percepita: apparent temperature australiana (Steadman), la stessa famiglia
 * usata dal BOM. AT = T + 0,33*e - 0,70*v - 4,00, con e la tensione di vapore (hPa) da T e
 * umidita' relativa, v il vento in m/s. Vale per il caldo e il fresco umido; il wind chill
 * estremo e' un raffinamento successivo.
 */
object ApparentTemperature {

  fun celsius(temperatureC: Double, relativeHumidityPercent: Double, windSpeedKmh: Double): Double {
    val vapourPressure = relativeHumidityPercent / 100.0 * 6.105 *
      kotlin.math.exp(17.27 * temperatureC / (237.7 + temperatureC))
    val windMs = windSpeedKmh / 3.6
    return temperatureC + 0.33 * vapourPressure - 0.70 * windMs - 4.00
  }
}
