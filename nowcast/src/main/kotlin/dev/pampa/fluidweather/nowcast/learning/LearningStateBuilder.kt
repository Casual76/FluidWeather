package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattMapRecord
import dev.pampa.fluidweather.core.model.PlattParamsRecord
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions

/**
 * Dall'archivio (verdetti iscritti, esiti, mappe salvate) allo stato che il motore consuma.
 * Puro: home e ciclo in background lo chiamano con gli stessi record e ottengono lo stesso
 * verdetto, che e' l'unico modo perche' i due non si contraddicano.
 */
object LearningStateBuilder {

  /**
   * Lo stato "di sempre": una mappa per finestra e tutte le emissioni come analoghi.
   *
   * [modelVersion] null = nessun filtro (il percorso del banco, che non ha versioni). Con un valore
   * entrano solo le emissioni di quel modello, del vettore della lunghezza giusta e non ombra, e gli
   * esiti si agganciano per giro: le righe di un modello che non c'e' piu' non insegnano niente
   * al nuovo.
   */
  fun build(
    platt: Map<String, PlattParamsRecord>,
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
    modelVersion: String? = null,
  ): LearningState {
    val outcomesByKey = outcomes.groupBy { it.issuedAtMillis }
    return LearningState(
      platt = platt.mapValues { (_, record) -> PlattParams(record.a, record.b) },
      cases = casesOf(issues, outcomesByKey, modelVersion),
    )
  }

  /**
   * Lo stato con le mappe per variante di contesto ([PlattMapRecord]): al motore arrivano solo
   * quelle **attive** e con parametri; le altre stanno su disco per la pagina, non nel verdetto.
   * La mappa unica storica resta vuota: chi passa un livello al motore non ricade mai su di
   * essa. [includeCases] = false evita di costruire gli analoghi quando sono spenti.
   */
  fun buildForVariants(
    maps: List<PlattMapRecord>,
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
    modelVersion: String = ModelVersions.TAG,
    includeCases: Boolean = true,
  ): LearningState {
    val byVariant = LinkedHashMap<PlattVariant, MutableMap<String, PlattParams>>()
    for (record in maps) {
      val variant = PlattVariant.byKey(record.variant) ?: continue
      val a = record.a
      val b = record.b
      if (!record.active || a == null || b == null) continue
      byVariant.getOrPut(variant) { LinkedHashMap() }[record.window] = PlattParams(a, b)
    }
    val cases = if (includeCases) casesOf(issues, outcomes.groupBy { it.issuedAtMillis }, modelVersion) else emptyList()
    val ruleWindows = maps.filter { it.useRule && it.variant == PlattVariant.NONE.key }.map { it.window }.toSet()
    return LearningState(plattByVariant = byVariant, cases = cases, ruleWindows = ruleWindows)
  }

  private fun casesOf(
    issues: List<NowcastIssueRecord>,
    outcomesByKey: Map<Long, List<NowcastOutcomeRecord>>,
    modelVersion: String?,
  ): List<AnalogCase> = issues
    // I vettori di una versione precedente del contratto hanno un'altra lunghezza: confrontarli
    // con quelli di adesso vorrebbe dire misurare distanze fra grandezze diverse. Si ignorano,
    // e l'archivio si ripopola da solo nei giorni successivi.
    .filter { it.features.size == FeatureExtractor.names.size }
    .filter { modelVersion == null || (it.modelVersion == modelVersion && !it.isShadow) }
    .map { issue ->
      AnalogCase(
        issuedAtMillis = issue.issuedAtMillis,
        features = issue.features.toDoubleArray(),
        outcomes = outcomesByKey[if (modelVersion == null) issue.issuedAtMillis else issue.outcomeKey]
          .orEmpty().associate { it.window to it.rained },
      )
    }

  /**
   * Le coppie (probabilita' grezza, esito) di una finestra: la materia della ricalibrazione.
   * Con [modelVersion] si filtra come in [build] (versione e lunghezza) e l'esito si aggancia per
   * giro; senza, si aggancia per istante d'emissione, come ha sempre fatto il banco.
   */
  fun samples(
    window: String,
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
    modelVersion: String? = null,
  ): List<CalibrationSample> {
    val usable = if (modelVersion == null) {
      issues
    } else {
      // L'ombra condivide la chiave del suo giro: lasciarla entrare farebbe scegliere a caso fra le due.
      issues.filter {
        it.modelVersion == modelVersion && it.features.size == ModelVersions.CURRENT_FEATURE_COUNT && !it.isShadow
      }
    }
    val byKey = usable.associateBy { if (modelVersion == null) it.issuedAtMillis else it.outcomeKey }
    return outcomes.filter { it.window == window }.mapNotNull { outcome ->
      val issue = byKey[outcome.issuedAtMillis] ?: return@mapNotNull null
      val probability = when (window) {
        "0-1h" -> issue.rawProbability01
        "1-3h" -> issue.rawProbability13
        "3-6h" -> issue.rawProbability36
        else -> return@mapNotNull null
      }
      CalibrationSample(probability, outcome.rained)
    }
  }
}
