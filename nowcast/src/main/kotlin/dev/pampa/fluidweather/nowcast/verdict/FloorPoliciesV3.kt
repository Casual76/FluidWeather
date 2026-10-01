package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.ContextTier

/**
 * GENERATO da `gradlew :testbench:run --args="floors-v3 validation --emit"` — non modificare a mano.
 * I pavimenti dell'osservazione del v3, decisi su VALIDATION con la regola preregistrata (reports/floors-v3-validation.txt):
 * - FRESH 0-1h: nessun pavimento
 * - FRESH 1-3h: nessun pavimento
 * - FRESH 3-6h: nessun pavimento
 * - STALE 0-1h: nessun pavimento
 * - STALE 1-3h: nessun pavimento
 * - STALE 3-6h: nessun pavimento
 * Nessun pavimento dal quarto d'ora (non rigiocabile). Il radar e' un dato, spento: accenderlo e' una decisione dell'utente.
 * P2 li passa al motore come [dev.pampa.fluidweather.nowcast.learning.RainObservation] ([ObservationFloors.observation]).
 */
object FloorPoliciesV3 {
  const val VERSION: String = "floors-v3-none"

  val BY_TIER: Map<ContextTier, ObservationFloors> = mapOf(
    ContextTier.FRESH to ObservationFloors(ContextTier.FRESH),
    ContextTier.STALE to ObservationFloors(ContextTier.STALE),
    ContextTier.NONE to ObservationFloors(ContextTier.NONE),
    ContextTier.NONE_NOCLIMA to ObservationFloors(ContextTier.NONE_NOCLIMA),
  )

  /** Il pavimento del radar come dato (SPENTO: il banco non lo puo' certificare). */
  val RADAR_CANDIDATE: RadarFloor? = RadarFloor(window = "0-1h", minDbz = 20.0, minConfidence = 0.4, floor = 0.44654987925970396)
}
