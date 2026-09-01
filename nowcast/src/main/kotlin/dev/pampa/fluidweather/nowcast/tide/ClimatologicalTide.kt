package dev.pampa.fluidweather.nowcast.tide

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * I priori climatologici delle maree S1/S2: il modello con cui si parte, prima che l'archivio
 * locale permetta un fit vero.
 *
 * S2 (semidiurna) e' la componente solida della letteratura: ampiezza ~1,16·cos³(latitudine) hPa
 * con massimi alle 09:44 e 21:44 di tempo solare locale (Haurwitz 1956) e una modulazione
 * semestrale di ~±10% con i massimi agli equinozi. Fase e ampiezza sono notevolmente stabili:
 * questo pezzo del prior e' affidabile.
 *
 * S1 (diurna) e' un'altra storia: e' guidata dal riscaldamento locale, varia molto fra terra e
 * mare e fra siti, e la sua fase non e' universale. Il prior usa ~0,6·cos³φ hPa con massimo
 * verso le 06 solari, ma **attenuato della meta'**: sottrarre una sinusoide con la fase
 * sbagliata puo' *aggiungere* errore, e sotto incertezza di fase la scelta che minimizza il
 * danno atteso e' accorciare l'ampiezza, non fingere di conoscerla. Il fit locale, quando
 * arriva, impara la S1 vera del posto e il prior va in pensione.
 *
 * I numeri sono priori, non verita': il banco di prova (fase 4) li giudichera' su dati reali.
 */
class ClimatologicalTide(
  latitude: Double,
  private val longitude: Double,
  /** Fissa i fattori stagionali: dentro una finestra di ore o giorni non cambiano nulla. */
  referenceTimestampMillis: Long,
) : TidalModel {

  override val source = TideSource.CLIMATOLOGICAL

  private val cos3 = cos(Math.toRadians(latitude)).pow(3)
  private val dayOfYear = SolarTime.dayOfYear(referenceTimestampMillis)

  override val s1AmplitudeHpa =
    S1_BASE_AMPLITUDE_HPA * cos3 * S1_PRIOR_SHRINK * s1Seasonal(dayOfYear, latitude)
  override val s2AmplitudeHpa =
    S2_BASE_AMPLITUDE_HPA * cos3 * s2Seasonal(dayOfYear)

  override fun tideAt(timestampMillis: Long): Double {
    val solar = SolarTime.solarHours(timestampMillis, longitude)
    return s1AmplitudeHpa * cos(2 * PI * (solar - S1_MAX_SOLAR_HOUR) / 24.0) +
      s2AmplitudeHpa * cos(2 * PI * (solar - S2_MAX_SOLAR_HOUR) / 12.0)
  }

  private companion object {
    const val S2_BASE_AMPLITUDE_HPA = 1.16
    const val S2_MAX_SOLAR_HOUR = 9.73 // 09:44, e per periodicita' anche 21:44

    const val S1_BASE_AMPLITUDE_HPA = 0.6
    const val S1_MAX_SOLAR_HOUR = 6.0
    const val S1_PRIOR_SHRINK = 0.5

    /** S1 e' termica: piu' forte nell'estate locale. Modulazione annuale, ±30%. */
    fun s1Seasonal(dayOfYear: Int, latitude: Double): Double {
      val summerSolsticeDoy = if (latitude >= 0) 172 else 355
      return 1.0 + 0.3 * cos(2 * PI * (dayOfYear - summerSolsticeDoy) / 365.25)
    }

    /** S2: massimi agli equinozi (doy ~80 e ~262), ±10%. */
    fun s2Seasonal(dayOfYear: Int): Double =
      1.0 + 0.1 * cos(4 * PI * (dayOfYear - 80) / 365.25)
  }
}
