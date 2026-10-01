package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.Place
import dev.pampa.fluidweather.core.sensor.LocationSnapshot
import dev.pampa.fluidweather.core.weather.WeatherSnapshot

/** Da dove viene il punto di un giro in background. */
enum class PointSource {
  /** Un fix di adesso: il telefono e' qui. */
  FRESH_FIX,

  /** Nessun fix nuovo, ma l'ultimo e' recente e da allora non ci si e' mossi: il telefono e' ancora qui. */
  LAST_FIX,

  /** Nessuna posizione utilizzabile: la prima localita' salvata, dove il barometro non c'e'. */
  SAVED_PLACE,
}

/** Un fix di posizione, con l'istante in cui e' stato preso (non quello in cui l'abbiamo letto). */
data class FixSample(val latitude: Double, val longitude: Double, val fixedAtMillis: Long)

data class ResolvedPoint(
  val placeKey: String,
  val latitude: Double,
  val longitude: Double,
  val source: PointSource,
)

/**
 * Dove fare il giro in background: il posto del telefono se lo si puo' dire, altrimenti la prima
 * localita' salvata.
 *
 * Prima c'erano due casi soli: un fix entro il timeout, o la localita' salvata. In background il
 * fix fallisce spesso (il sistema lo nega o lo ritarda), e ogni fallimento era un giro perso per il
 * barometro — ma il telefono sul comodino non si e' mosso, e l'ultimo fix di un'ora fa dice
 * esattamente dov'e'. Il terzo caso lo recupera, con due condizioni: il fix ha al piu'
 * [LAST_FIX_MAX_AGE_MILLIS], e da quando e' stato preso il riconoscimento attivita' non ha visto
 * auto o bici. Un'attivita' sconosciuta (niente permesso) conta come "fermo": il tetto delle tre ore
 * limita il danno, e la verita' si giudica comunque nel punto registrato.
 *
 * La localita' salvata non e' il telefono: il giro si fa, ma non conta per il barometro (lo decide
 * chi chiama, dalla chiave).
 *
 * Oggetto puro come [RefreshBudget]: la decisione si prova senza sensori ne' permessi.
 */
internal object PointResolver {

  /**
   * Un fix "di adesso": dieci minuti, il passo piu' lento del campionamento. E' la stessa regola con
   * cui la home e l'assistente decidono se un giro puo' iscriversi ([LocationSnapshot.isFreshAt]):
   * una sola definizione di "fresco" per tutti i giri del telefono.
   */
  const val FRESH_FIX_MAX_AGE_MILLIS: Long = LocationSnapshot.FRESH_MAX_AGE_MILLIS

  /** Fin dove l'ultimo fix vale ancora "qui", se non ci si e' mossi. */
  const val LAST_FIX_MAX_AGE_MILLIS: Long = 3 * 3_600_000L

  /** Quanto un fix puo' sembrare dal futuro ed essere ancora buono: l'orologio si corregge a salti. */
  const val CLOCK_SKEW_MILLIS: Long = LocationSnapshot.CLOCK_SKEW_MILLIS

  fun resolve(
    nowMillis: Long,
    locationPermitted: Boolean,
    currentFix: FixSample?,
    lastFix: FixSample?,
    lastInTransitMillis: Long?,
    firstSavedPlace: Place?,
  ): ResolvedPoint? {
    if (locationPermitted) {
      if (currentFix != null && ageOf(currentFix, nowMillis) in -CLOCK_SKEW_MILLIS..FRESH_FIX_MAX_AGE_MILLIS) {
        return ResolvedPoint(WeatherSnapshot.GPS_KEY, currentFix.latitude, currentFix.longitude, PointSource.FRESH_FIX)
      }
      // Il piu' recente fra i fix credibili: uno "dal futuro" oltre la tolleranza e' un orologio
      // rotto, e non deve nascondere un fix buono di un'ora fa.
      val newest = listOfNotNull(currentFix, lastFix)
        .filter { ageOf(it, nowMillis) >= -CLOCK_SKEW_MILLIS }
        .maxByOrNull { it.fixedAtMillis }
      if (newest != null &&
        ageOf(newest, nowMillis) <= LAST_FIX_MAX_AGE_MILLIS &&
        (lastInTransitMillis == null || lastInTransitMillis < newest.fixedAtMillis)
      ) {
        return ResolvedPoint(WeatherSnapshot.GPS_KEY, newest.latitude, newest.longitude, PointSource.LAST_FIX)
      }
    }
    val saved = firstSavedPlace?.takeUnless { it.isGps } ?: return null
    return ResolvedPoint(WeatherSnapshot.keyFor(saved.id), saved.latitude, saved.longitude, PointSource.SAVED_PLACE)
  }

  private fun ageOf(fix: FixSample, nowMillis: Long): Long = nowMillis - fix.fixedAtMillis
}
