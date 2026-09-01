package dev.pampa.fluidweather.testbench.metrics

/**
 * Una previsione probabilistica accoppiata a cio' che e' successo davvero. Tutte le metriche
 * del banco si calcolano da liste di queste coppie: non c'e' altro stato.
 */
data class Verification(
  val probability: Double,
  val occurred: Boolean,
)

/**
 * La tabella di contingenza a una soglia di decisione, con le metriche categoriche classiche.
 *
 * POD (probability of detection): quanti eventi veri sono stati presi.
 * FAR (false alarm ratio): quanti allarmi erano a vuoto.
 * CSI (critical success index): l'intersezione su unione degli eventi — punisce sia i buchi
 * sia i falsi allarmi, ed e' la metrica che non si lascia gonfiare dai non-eventi.
 * Bias di frequenza: >1 = si allarma troppo, <1 = troppo poco.
 */
data class Contingency(
  val hits: Int,
  val misses: Int,
  val falseAlarms: Int,
  val correctNegatives: Int,
) {
  val pod: Double get() = safeRatio(hits, hits + misses)
  val far: Double get() = safeRatio(falseAlarms, hits + falseAlarms)
  val csi: Double get() = safeRatio(hits, hits + misses + falseAlarms)
  val frequencyBias: Double get() = safeRatio(hits + falseAlarms, hits + misses)

  private fun safeRatio(numerator: Int, denominator: Int): Double =
    if (denominator == 0) Double.NaN else numerator.toDouble() / denominator

  companion object {
    fun at(threshold: Double, verifications: List<Verification>): Contingency {
      var hits = 0
      var misses = 0
      var falseAlarms = 0
      var correctNegatives = 0
      for ((probability, occurred) in verifications) {
        val predicted = probability >= threshold
        when {
          predicted && occurred -> hits++
          !predicted && occurred -> misses++
          predicted && !occurred -> falseAlarms++
          else -> correctNegatives++
        }
      }
      return Contingency(hits, misses, falseAlarms, correctNegatives)
    }
  }
}

/** Un gradino della curva di affidabilita': "quando dico X%, succede davvero Y% delle volte". */
data class ReliabilityBin(
  val meanForecast: Double,
  val observedFrequency: Double,
  val count: Int,
)

object Probabilistic {

  /** Brier score: errore quadratico medio della probabilita'. Zero e' perfetto, 0,25 e' un coin flip su base rate 50%. */
  fun brier(verifications: List<Verification>): Double {
    if (verifications.isEmpty()) return Double.NaN
    return verifications.sumOf { (p, occurred) ->
      val outcome = if (occurred) 1.0 else 0.0
      (p - outcome) * (p - outcome)
    } / verifications.size
  }

  /**
   * Brier Skill Score contro la climatologia: quanto si batte chi dice sempre il tasso base.
   * Positivo = meglio della climatologia; zero = inutile; negativo = dannoso. E' il numero che
   * decide se un predittore merita di esistere.
   */
  fun brierSkillScore(verifications: List<Verification>): Double {
    if (verifications.isEmpty()) return Double.NaN
    val baseRate = verifications.count { it.occurred }.toDouble() / verifications.size
    val climatology = verifications.map { Verification(baseRate, it.occurred) }
    val reference = brier(climatology)
    if (reference == 0.0) return Double.NaN
    return 1.0 - brier(verifications) / reference
  }

  fun baseRate(verifications: List<Verification>): Double =
    if (verifications.isEmpty()) Double.NaN
    else verifications.count { it.occurred }.toDouble() / verifications.size

  /** La curva di affidabilita' su [bins] gradini uguali; i gradini vuoti non compaiono. */
  fun reliability(verifications: List<Verification>, bins: Int = 10): List<ReliabilityBin> =
    verifications
      .groupBy { ((it.probability * bins).toInt()).coerceAtMost(bins - 1) }
      .toSortedMap()
      .map { (_, group) ->
        ReliabilityBin(
          meanForecast = group.sumOf { it.probability } / group.size,
          observedFrequency = group.count { it.occurred }.toDouble() / group.size,
          count = group.size,
        )
      }
}
