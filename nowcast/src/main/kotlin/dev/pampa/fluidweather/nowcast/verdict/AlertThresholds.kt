package dev.pampa.fluidweather.nowcast.verdict

import dev.pampa.fluidweather.nowcast.features.ContextTier

/**
 * Le soglie fra probabilita' e parole, come dato: ALLERTA quando la finestra 0-1h arriva a
 * [short01] o la 1-3h a [medium13]; SORVEGLIANZA quando una finestra qualsiasi arriva a [watchAny];
 * altrimenti QUIETE.
 *
 * Erano scritte dentro [alertLevelOf]; qui diventano un valore perche' il v3 ha un modello per
 * livello di contesto, e un modello senza contesto e' piu' prudente per costruzione (la sua
 * probabilita' sta piu' vicino al tasso del posto): con le stesse soglie direbbe ALLERTA meno spesso
 * per la stessa pioggia. Se e quanto spostarle per livello si decide sul banco (`alerts-v3`);
 * finche' non si decide, [DEFAULT] per tutti, cioe' il comportamento di sempre.
 */
data class AlertThresholds(
  val short01: Double,
  val medium13: Double,
  val watchAny: Double,
) {

  fun levelOf(verdicts: List<WindowVerdict>): AlertLevel {
    val shortTerm = verdicts.firstOrNull { it.window == "0-1h" }?.probability ?: 0.0
    val medium = verdicts.firstOrNull { it.window == "1-3h" }?.probability ?: 0.0
    val maxAny = verdicts.maxOfOrNull { it.probability } ?: 0.0
    return when {
      shortTerm >= short01 || medium >= medium13 -> AlertLevel.ALLERTA
      maxAny >= watchAny -> AlertLevel.SORVEGLIANZA
      else -> AlertLevel.QUIETE
    }
  }

  companion object {
    /** Le soglie di sempre (v1, v2): 0,55 a breve, 0,60 a medio, 0,35 per sorvegliare. */
    val DEFAULT: AlertThresholds = AlertThresholds(short01 = 0.55, medium13 = 0.60, watchAny = 0.35)
  }
}

/**
 * Le soglie del v3 per livello. Oggi sono [AlertThresholds.DEFAULT] ovunque: la taratura per livello
 * (`alerts-v3 validation`: stessi tassi di ALLERTA e SORVEGLIANZA del v2 a Sesto in FRESH, a meno del
 * dieci per cento, massimizzando il CSI) e' rimandata a P2, che vedra' anche i numeri del telefono.
 */
object AlertThresholdsV3 {
  const val VERSION: String = "alerts-v3-default"

  val BY_TIER: Map<ContextTier, AlertThresholds> = ContextTier.entries.associateWith { AlertThresholds.DEFAULT }
}
