package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.data.FusionSettingsStore
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.Observation
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict

/** L'esito completo di un giro: le opinioni, il fuso, e chi ha pesato quanto. */
data class WeatherRound(
  val fetches: List<ProviderFetch>,
  val fused: FusedForecast,
) {

  /**
   * Almeno un provider ha risposto.
   *
   * Il segnale c'era gia' dentro [fetches] e nessuno lo guardava: quando falliscono tutti, la
   * fusione restituisce una previsione VUOTA invece di un errore, e quella finiva salvata sopra
   * l'ultima buona. Un giro che non ha prodotto niente non ha niente da salvare.
   */
  val producedAnything: Boolean get() = fetches.any { it.bundle != null }
}

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
  /** Le osservazioni dell'utente da [sinceMillis]: la verita' che entra da lui (fase 14). */
  private val observations: suspend (sinceMillis: Long) -> List<Observation> = { emptyList() },
) : RoundSource {

  /**
   * [registerPredictions] false = si giudica ma non si semina: il ciclo in background gira
   * ogni quarto d'ora e seminare quattro volte le stesse previsioni gonfierebbe le tabelle
   * senza aggiungere informazione (gli orizzonti sono a ore intere).
   */
  override suspend fun refresh(latitude: Double, longitude: Double, registerPredictions: Boolean): WeatherRound {
    val fetches = repository.fetchAll(latitude, longitude)
    verifier.settle(fetches, runCatching { observations(clock() - 24 * 3_600_000L) }.getOrDefault(emptyList()))
    if (registerPredictions) {
      verifier.registerPending(fetches)
      verifier.registerRainEvents(fetches)
    }
    val fused = fusion.fuse(
      fetches = fetches,
      latitude = latitude,
      longitude = longitude,
      nowMillis = clock(),
      onlyProviderId = fusionSettings.currentOnlyProviderId(),
    )
    return WeatherRound(fetches, fused)
  }

  /** Il barometro del telefono in classifica alla pari sull'evento pioggia (Benchmark, fase 13). */
  suspend fun registerBarometer(verdict: NowcastVerdict, nowMillis: Long = clock()) {
    verifier.registerBarometer(verdict, nowMillis)
  }
}
