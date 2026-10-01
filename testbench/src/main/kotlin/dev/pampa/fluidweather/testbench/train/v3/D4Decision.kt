package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.verdict.ModelFamily
import java.util.Locale

/** Come e' finita la decisione D4. */
enum class D4Outcome(val label: String) {
  GBM("alberi"),
  LOGISTICA("logistica"),

  /** Solo gli alberi passano il gate di sviluppo, ma guadagnano meno del 3%: decide l'utente. */
  ESCALATE_ONLY_GBM_PASSES("DA DECIDERE: passano solo gli alberi, ma non sono spedibili (guadagno sotto la soglia o peso oltre il limite)"),

  /** Gli alberi sarebbero scelti, ma il paracadute logistico non passa il gate: decide l'utente. */
  ESCALATE_FALLBACK_FAILS("DA DECIDERE: alberi scelti ma il paracadute logistico non passa il gate"),
}

/** La decisione D4 con i suoi numeri: [line] e' la riga `SCELTA: ...` del rapporto. */
class D4Result(
  val outcome: D4Outcome,
  val meanGain: Double,
  val gbmPassesGate: Boolean?,
  val logisticPassesGate: Boolean?,
  val treeBytes: Int,
  val reasons: List<String>,
) {
  /** La famiglia da spedire se la decisione e' presa; null se tocca all'utente. */
  val family: ModelFamily?
    get() = when (outcome) {
      D4Outcome.GBM -> ModelFamily.GBM
      D4Outcome.LOGISTICA -> ModelFamily.LOGISTICA
      else -> null
    }

  val line: String
    get() = String.format(
      Locale.ROOT, "SCELTA: %s — G = %+.2f%% (soglia %+.0f%%), gate di sviluppo alberi %s, logistica %s, alberi %.2f MB (limite %.1f MB)",
      outcome.label, 100 * meanGain, 100 * D4Decision.MIN_MEAN_GAIN, passLabel(gbmPassesGate), passLabel(logisticPassesGate),
      treeBytes / 1_000_000.0, D4Decision.MAX_TREE_BYTES / 1_000_000.0,
    )

  private fun passLabel(passed: Boolean?): String = when (passed) {
    null -> "non giocato"
    true -> "PASSA"
    false -> "NON PASSA"
  }
}

/**
 * D4, la regola dichiarata prima di guardare: si spediscono gli alberi **solo se** il guadagno relativo
 * medio di Brier sulla logistica arricchita, sulle dodici celle livello x finestra (EUROPA, VALIDATION B,
 * semantica del gate, modelli grezzi), e' almeno il 3%, **e** gli alberi passano il gate di sviluppo,
 * **e** pesano al piu' 1,5 MB. Altrimenti la logistica. Le tabelle logistiche si emettono sempre (sono il
 * paracadute), e devono passare il gate anche loro.
 *
 * Due casi non si decidono in silenzio e tornano all'utente: passano solo gli alberi ma il guadagno e'
 * sotto soglia; gli alberi sarebbero scelti ma il paracadute non passa.
 */
object D4Decision {
  const val MIN_MEAN_GAIN: Double = 0.03
  const val MAX_TREE_BYTES: Int = 1_500_000

  /** g_c = (Brier logistica - Brier alberi) / Brier logistica per cella; G = la media. */
  fun meanGain(logisticBrier: List<Double>, gbmBrier: List<Double>): Double {
    require(logisticBrier.size == gbmBrier.size && logisticBrier.isNotEmpty()) { "celle diverse" }
    return logisticBrier.indices.map { (logisticBrier[it] - gbmBrier[it]) / logisticBrier[it] }.average()
  }

  fun decide(meanGain: Double, gbmPassesGate: Boolean?, logisticPassesGate: Boolean?, treeBytes: Int): D4Result {
    val reasons = ArrayList<String>()
    val gainOk = meanGain >= MIN_MEAN_GAIN
    val sizeOk = treeBytes in 1..MAX_TREE_BYTES
    if (!gainOk) reasons += String.format(Locale.ROOT, "G %+.2f%% sotto la soglia del 3%%", 100 * meanGain)
    if (!sizeOk) reasons += "alberi assenti o oltre 1,5 MB"
    if (gbmPassesGate != true) reasons += "gli alberi non passano (o non hanno giocato) il gate di sviluppo"
    val gbmEligible = gainOk && sizeOk && gbmPassesGate == true
    val outcome = when {
      gbmEligible && logisticPassesGate != true -> D4Outcome.ESCALATE_FALLBACK_FAILS
      gbmEligible -> D4Outcome.GBM
      // Solo gli alberi passano ma non si possono spedire (guadagno o peso): spedire in silenzio una logistica che non
      // passa il gate sarebbe peggio che chiedere. Vale per il guadagno sotto soglia come per il peso oltre il limite.
      gbmPassesGate == true && logisticPassesGate != true -> D4Outcome.ESCALATE_ONLY_GBM_PASSES
      else -> D4Outcome.LOGISTICA
    }
    return D4Result(outcome, meanGain, gbmPassesGate, logisticPassesGate, treeBytes, reasons)
  }
}
