package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.model.FusedForecast

/** L'esito completo di un giro: le opinioni, il fuso, e chi ha pesato quanto. */
data class WeatherRound(
  val fetches: List<ProviderFetch>,
  val fused: FusedForecast,
)

/**
 * Il giro completo del livello meteo, nell'ordine che conta:
 *
 *  1. si interroga la costellazione (parallelo, cache, esiti per-provider);
 *  2. si SALDANO i giudizi scaduti — le analisi appena arrivate sono la verita' per le
 *     previsioni di ieri;
 *  3. si seminano le previsioni di oggi per i giudizi di domani;
 *  4. si fonde, con i pesi che le verifiche hanno appena aggiornato e l'eventuale override.
 *
 * E' il ciclo che fa "cambiare la classifica con l'esperienza" senza che nessuno la tocchi.
 */
/** Chi sa fare un giro: il coordinatore vero, o un finto nei test del refresher. */
interface RoundSource {
  suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean = true): WeatherRound
}

class FusionCoordinator(
  private val repository: WeatherRepository,
  private val verifier: ForecastVerifier,
  private val fusion: ForecastFusion,
  private val fusionSettings: FusionSettingsStore,
  private val clock: () -> Long = System::currentTimeMillis,
) : RoundSource {

  /**
   * [registerPredictions] false = si giudica ma non si semina: il ciclo in background gira
   * ogni quarto d'ora e seminare quattro volte le stesse previsioni gonfierebbe le tabelle
   * senza aggiungere informazione (gli orizzonti sono a ore intere).
   */
  override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
    val fetches = repository.fetchAll(latitude, longitude)
    verifier.settle(fetches)
    if (registerPredictions) verifier.registerPending(fetches)
    val fused = fusion.fuse(
      fetches = fetches,
      latitude = latitude,
      longitude = longitude,
      nowMillis = clock(),
      onlyProviderId = fusionSettings.currentOnlyProviderId(),
    )
    return WeatherRound(fetches, fused)
  }
}
