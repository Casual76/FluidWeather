package dev.pampa.fluidweather.nowcast.verdict

import kotlin.math.exp
import kotlin.math.ln

/**
 * GENERATO da `gradlew :testbench:run --args=recalibrate-v2r` — non modificare a mano.
 *
 * La ricalibrazione v2r: il modello v2 spedito ([TrainedNowcastV1], versione [BASE_MODEL_VERSION])
 * riportato sull'etichetta del pannello dei giudici (panel-1), che e' piu' asciutta di ERA5
 * su cui il v2 e' stato addestrato. Una mappa di Platt per finestra e per famiglia di livello:
 *
 *     logit(p') = a * logit(p) + b
 *
 * - con contesto dei provider ([WITH_CONTEXT]): i livelli FRESH e STALE insieme;
 * - senza contesto ([WITHOUT_CONTEXT]): NONE e NONE_NOCLIMA (il modello non vede la climatologia,
 *   quindi per lui sono lo stesso livello).
 *
 * Stimata al banco (reports/recalibration-v2r.txt) sulle emissioni dal 2022-11-24 al 2025-08-31
 * di dieci localita', ogni tre ore a minuto casuale, con il contesto com'era al fetch e la
 * verita' del pannello; massima verosimiglianza con una cresta verso l'identita' e la pendenza
 * tenuta in [0.3, 3.0]. Valutata sull'anno di TEST, che non ha visto.
 *
 * Dati puri: nessuno la collega a [NowcastModel] o al motore (lo fara' P2). Stessa forma e stessi
 * ritagli di `PlattParams`, cosi' la si puo' comporre con la ricalibrazione personale.
 * Dati: Open-Meteo.com (CC BY 4.0), uso non commerciale.
 */
object RecalibrationV2r {

  /** La versione di cio' che dice il modello ricalibrato: la data e' quella del v2 su cui poggia. */
  const val VERSION: String = "v2r-2026-09-10"

  /** Il modello grezzo su cui le mappe sono stimate: con un altro modello non valgono. */
  const val BASE_MODEL_VERSION: String = "v2-2026-09-10"

  /** Una mappa: [a] pendenza sul logit, [b] spostamento; [samples] casi su cui e' stata stimata. */
  data class Coefficients(val a: Double, val b: Double, val samples: Int)

  /** Con il contesto dei provider (FRESH, STALE): finestra -> mappa. */
  val WITH_CONTEXT: Map<String, Coefficients> = mapOf(
    "0-1h" to Coefficients(a = 0.492123, b = -0.807060, samples = 136288),
    "1-3h" to Coefficients(a = 0.664688, b = -0.518968, samples = 136288),
    "3-6h" to Coefficients(a = 0.767714, b = -0.461458, samples = 136276),
  )

  /** Senza contesto (NONE, NONE_NOCLIMA): finestra -> mappa. */
  val WITHOUT_CONTEXT: Map<String, Coefficients> = mapOf(
    "0-1h" to Coefficients(a = 0.652838, b = -0.192770, samples = 68155),
    "1-3h" to Coefficients(a = 0.696739, b = -0.262509, samples = 68155),
    "3-6h" to Coefficients(a = 0.953437, b = -0.009151, samples = 68149),
  )

  /** La mappa di una finestra ("0-1h", "1-3h", "3-6h") per la famiglia di livello. */
  fun coefficients(windowLabel: String, hasContext: Boolean): Coefficients =
    requireNotNull((if (hasContext) WITH_CONTEXT else WITHOUT_CONTEXT)[windowLabel]) { "finestra sconosciuta: $windowLabel" }

  /**
   * La probabilita' grezza del v2 ricalibrata: sigma(a * logit(p) + b), con p e il risultato tenuti
   * in [1e-4, 1 - 1e-4] come fa `PlattParams`. Pura: stesso ingresso, stesso numero.
   */
  fun apply(windowLabel: String, hasContext: Boolean, probability: Double): Double {
    val map = coefficients(windowLabel, hasContext)
    val p = probability.coerceIn(EPS, 1 - EPS)
    val z = map.a * ln(p / (1 - p)) + map.b
    return (1.0 / (1.0 + exp(-z))).coerceIn(EPS, 1 - EPS)
  }

  private const val EPS = 1e-4
}
