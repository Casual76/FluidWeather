package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.nowcast.features.ContextTier

/**
 * Il livello di contesto di un verdetto del telefono: l'unico posto che lo decide.
 *
 * Il caso d'uso e il registratore dei giri lo chiedevano ognuno per conto suo (uno con `tierOf`,
 * l'altro con niente): due copie della stessa regola erano due modi di iscrivere un giro con un
 * livello diverso da quello con cui il verdetto era stato calcolato, e quindi di imparare su una
 * mappa di Platt sbagliata. Qui la regola e' una.
 *
 * - **Eta'**: adesso meno [ForecastBundle.fetchedAtMillis], cioe' il download vero del contesto, non
 *   il giro che lo ha riportato avanti (un contesto portato da un giro all'altro invecchia).
 * - **Stesso posto**: il contesto e' entro [ContextTier.SAME_PLACE_RADIUS_METERS] dal punto del
 *   verdetto. Un punto ignoto conta come "non lo stesso posto": nel dubbio il contesto non entra.
 */
internal object ContextTierDetection {

  fun detect(
    context: ForecastBundle?,
    pointLatitude: Double?,
    pointLongitude: Double?,
    nowMillis: Long,
    hasClimatology: Boolean,
  ): ContextTier {
    val age = context?.let { nowMillis - it.fetchedAtMillis }
    val samePlace = context != null && pointLatitude != null && pointLongitude != null &&
      haversineKm(context.latitude, context.longitude, pointLatitude, pointLongitude) * 1000.0 <=
      ContextTier.SAME_PLACE_RADIUS_METERS
    return ContextTier.of(age, samePlace, hasClimatology)
  }
}
