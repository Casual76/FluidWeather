package dev.pampa.fluidweather.core.ui

import dev.pampa.fluidweather.core.model.Moon
import dev.pampa.fluidweather.core.model.MoonEphemeris
import dev.pampa.fluidweather.core.model.MoonPhase
import dev.pampa.fluidweather.core.model.SolarEphemeris
import dev.pampa.fluidweather.core.model.SunTimes
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

/**
 * Il sole o la luna: dove sta in cielo adesso, e quanto e' illuminato.
 *
 * Mancava del tutto — non solo nel widget: la scena della home aveva nuvole, stelle, pioggia e
 * neve, e nessun astro. Un cielo sereno di mezzogiorno e uno di mezzanotte si distinguevano solo
 * dal colore.
 */
data class CelestialBody(
  val kind: Kind,
  /** 0 = bordo sinistro, 1 = bordo destro. */
  val x: Float,
  /** 0 = in cima, 1 = in fondo. Sale con l'altezza vera sull'orizzonte. */
  val y: Float,
  /** 0..1: la frazione illuminata. Per il sole e' sempre 1. */
  val illuminated: Float,
  /** Crescente: l'ombra sta a sinistra. Calante: a destra. */
  val waxing: Boolean,
  /** L'altezza vera sull'orizzonte, in gradi: decide il colore del sole e la grandezza della luce. */
  val altitudeDegrees: Double,
) {
  enum class Kind { SUN, MOON }
}

/**
 * Dove sta l'astro adesso. Aritmetica pura sulle effemeridi che l'app ha gia': zero rete, zero
 * permessi, e le stesse funzioni con cui la pagina Sole e la pagina Luna dicono i loro orari.
 */
object Celestial {

  /**
   * Sotto questa altezza il sole e' tramontato per davvero, non "quasi".
   *
   * Non zero: il disco resta visibile per rifrazione fin sotto l'orizzonte geometrico, e a -2 la
   * scena continua a mostrarlo mentre il gradiente e' gia' quello del crepuscolo — che e'
   * esattamente cio' che si vede fuori dalla finestra.
   */
  private const val SUN_VISIBLE_ABOVE_DEG = -2.0

  /** La luna si disegna solo se e' davvero sopra l'orizzonte: "c'e' la luna" e' un fatto, non un'ora. */
  private const val MOON_VISIBLE_ABOVE_DEG = 0.0

  /** Sotto questa frazione illuminata non c'e' niente da disegnare: e' la luna nuova. */
  private const val MOON_INVISIBLE_BELOW = 0.04

  fun at(
    nowMillis: Long,
    latitude: Double?,
    longitude: Double?,
    zone: ZoneId = ZoneId.systemDefault(),
  ): CelestialBody? {
    if (latitude == null || longitude == null) return fromClock(nowMillis, zone)
    val sunElevation = SolarEphemeris.elevationDegrees(nowMillis, latitude, longitude)
    if (sunElevation > SUN_VISIBLE_ABOVE_DEG) {
      val times = SunTimes.forDay(dayStartUtc(nowMillis, zone), latitude, longitude)
      return CelestialBody(
        kind = CelestialBody.Kind.SUN,
        x = arcX(nowMillis, times.sunriseMillis to times.sunsetMillis, zone),
        y = arcY(sunElevation),
        illuminated = 1f,
        waxing = true,
        altitudeDegrees = sunElevation,
      )
    }
    val moonAltitude = MoonEphemeris.altitudeDegrees(nowMillis, latitude, longitude)
    if (moonAltitude <= MOON_VISIBLE_ABOVE_DEG) return null
    val illuminated = Moon.illuminatedFraction(nowMillis).toFloat()
    if (illuminated < MOON_INVISIBLE_BELOW) return null
    val times = MoonEphemeris.riseSet(dayStartUtc(nowMillis, zone), latitude, longitude)
    return CelestialBody(
      kind = CelestialBody.Kind.MOON,
      x = arcX(nowMillis, times.riseMillis to times.setMillis, zone),
      y = arcY(moonAltitude),
      illuminated = illuminated,
      waxing = Moon.phase(nowMillis).isWaxing(),
      altitudeDegrees = moonAltitude,
    )
  }

  /**
   * Senza coordinate: l'orologio.
   *
   * Grossolano — la luna finta e' sempre mezza e sempre crescente — ma la stessa scelta che fanno
   * gia' il cielo della home e quello del widget quando non c'e' ancora una posizione. Un cielo
   * senza niente dentro sarebbe piu' sbagliato di un cielo approssimato.
   */
  private fun fromClock(nowMillis: Long, zone: ZoneId): CelestialBody {
    val hour = Instant.ofEpochMilli(nowMillis).atZone(zone).hour
    val day = hour in 7..18
    // Quanto si e' avanti nell'arco, da 0 (appena sorto) a 1 (sta per tramontare).
    val span = (if (day) (hour - 7) / 11.0 else ((hour + 5) % 24) / 12.0).coerceIn(0.0, 1.0)
    // Una mezza campana: basso agli estremi, alto a meta' corsa.
    val altitude = MAX_FAKE_ALTITUDE_DEG * sin(span * PI)
    return CelestialBody(
      kind = if (day) CelestialBody.Kind.SUN else CelestialBody.Kind.MOON,
      x = (0.12 + span * 0.76).toFloat(),
      y = arcY(altitude),
      illuminated = if (day) 1f else 0.5f,
      waxing = true,
      altitudeDegrees = altitude,
    )
  }

  /** L'altezza che si finge a meta' arco quando non si sa dove si e': un cielo di mezza stagione. */
  private const val MAX_FAKE_ALTITUDE_DEG = 55.0

  /** Da est a ovest lungo l'arco: 0,12 all'alba, 0,88 al tramonto. */
  private fun arcX(nowMillis: Long, riseSet: Pair<Long?, Long?>, zone: ZoneId): Float {
    val (rise, set) = riseSet
    if (rise == null || set == null || set <= rise) {
      // Niente alba o niente tramonto (le estati polari, o una luna che non tramonta): resta
      // l'orologio, che almeno fa muovere l'astro nella direzione giusta.
      val minutes = Instant.ofEpochMilli(nowMillis).atZone(zone).let { it.hour * 60 + it.minute }
      return 0.12f + (minutes % (12 * 60)) / (12f * 60f) * 0.76f
    }
    val progress = ((nowMillis - rise).toDouble() / (set - rise)).coerceIn(0.0, 1.0)
    return (0.12 + progress * 0.76).toFloat()
  }

  /**
   * L'altezza sull'orizzonte in altezza sulla tela: allo zenit sta in cima, all'orizzonte
   * appoggiato al bordo basso dell'area di cielo.
   */
  private fun arcY(altitudeDegrees: Double): Float {
    val normalized = (altitudeDegrees / 60.0).coerceIn(0.0, 1.0)
    return (SKY_BOTTOM - normalized * (SKY_BOTTOM - SKY_TOP)).toFloat()
  }

  private fun dayStartUtc(nowMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
      .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

  private fun MoonPhase.isWaxing(): Boolean = when (this) {
    MoonPhase.WAXING_CRESCENT, MoonPhase.FIRST_QUARTER, MoonPhase.WAXING_GIBBOUS -> true
    else -> false
  }

  /** Il disco non sale mai oltre un decimo dall'alto ne' scende sotto la meta' della tela. */
  private const val SKY_TOP = 0.10
  private const val SKY_BOTTOM = 0.46
}
