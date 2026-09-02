package dev.pampa.fluidweather.nowcast.learning

import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.core.model.PlattParamsRecord

/**
 * Dall'archivio (verdetti iscritti, esiti, mappe salvate) allo stato che il motore consuma.
 * Puro: home e ciclo in background lo chiamano con gli stessi record e ottengono lo stesso
 * verdetto, che e' l'unico modo perche' i due non si contraddicano.
 */
object LearningStateBuilder {

  fun build(
    platt: Map<String, PlattParamsRecord>,
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
  ): LearningState {
    val outcomesByIssue = outcomes.groupBy { it.issuedAtMillis }
    val cases = issues.map { issue ->
      AnalogCase(
        issuedAtMillis = issue.issuedAtMillis,
        features = issue.features.toDoubleArray(),
        outcomes = outcomesByIssue[issue.issuedAtMillis].orEmpty().associate { it.window to it.rained },
      )
    }
    return LearningState(
      platt = platt.mapValues { (_, record) -> PlattParams(record.a, record.b) },
      cases = cases,
    )
  }

  /** Le coppie (probabilita' grezza, esito) di una finestra: la materia della ricalibrazione. */
  fun samples(
    window: String,
    issues: List<NowcastIssueRecord>,
    outcomes: List<NowcastOutcomeRecord>,
  ): List<CalibrationSample> {
    val byIssue = issues.associateBy { it.issuedAtMillis }
    return outcomes.filter { it.window == window }.mapNotNull { outcome ->
      val issue = byIssue[outcome.issuedAtMillis] ?: return@mapNotNull null
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
