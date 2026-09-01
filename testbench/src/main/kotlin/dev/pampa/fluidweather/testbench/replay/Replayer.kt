package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.events.EventWindow
import dev.pampa.fluidweather.testbench.events.PrecipitationEvents
import java.time.Instant
import java.time.ZoneOffset

/** Una verifica con la stagione attaccata, cosi' il tabellone si spacca anche per stagione. */
data class TaggedVerification(
  val probability: Double,
  val occurred: Boolean,
  val season: String,
)

/** predittore -> finestra -> verifiche. Le metriche si calcolano a valle, mai qui dentro. */
class ReplayOutcome(
  val locationName: String,
  val evaluations: Int,
  val cells: Map<String, Map<String, List<TaggedVerification>>>,
)

/**
 * Il replay: una "adesso" che scorre sulla storia. A ogni passo la pipeline rivede solo il
 * passato ([historyHours] di campioni sintetizzati), i predittori sparano le probabilita' per
 * ogni finestra, e la verita' arriva dagli stessi archivi — dal futuro che il predittore non
 * ha visto. Nessun leakage: la temperatura per la riduzione e' l'ultima *passata*, e la storia
 * (72 h) e' troppo corta per il fit di marea, quindi lo stadio 3 usa il prior — esattamente
 * come fara' il telefono nelle prime settimane.
 */
class Replayer(
  private val pipeline: CleaningPipeline = CleaningPipeline(),
  private val historyHours: Int = 72,
  private val stepHours: Int = 3,
  private val windows: List<EventWindow> = EventWindow.Standard,
  private val predictors: List<Predictor> = listOf(
    ClimatologyPredictor(),
    PersistencePredictor(),
    BarometricRulePredictor(),
  ),
) {

  fun replay(dataset: StationDataset): ReplayOutcome {
    val synthesizer = SampleSynthesizer(dataset)
    val events = PrecipitationEvents(dataset.records)
    val climatologyRates = climatologyRates(dataset, events)

    val cells = predictors.associate { predictor ->
      predictor.name to windows.associate { it.label to mutableListOf<TaggedVerification>() }
    }

    val first = dataset.records.first().timestampMillis + historyHours * 3_600_000L
    val last = dataset.records.last().timestampMillis
    var evaluations = 0
    var now = first
    while (now <= last) {
      val samples = synthesizer.samplesBetween(now - historyHours * 3_600_000L, now)
      if (samples.size >= MIN_HISTORY_SAMPLES) {
        val temperature = dataset.records
          .lastOrNull { it.timestampMillis <= now && it.temperatureC != null }
          ?.temperatureC
        val cleaning = pipeline.process(samples, temperatureCelsius = temperature)
        val state = ReplayState(cleaning, events.rainingAt(now), climatologyRates)
        val season = seasonOf(now)
        for (window in windows) {
          val truth = events.occurred(now, window) ?: continue
          for (predictor in predictors) {
            val probability = predictor.probability(state, window).coerceIn(0.0, 1.0)
            cells.getValue(predictor.name).getValue(window.label) +=
              TaggedVerification(probability, truth, season)
          }
        }
        evaluations++
      }
      now += stepHours * 3_600_000L
    }

    return ReplayOutcome(dataset.location.name, evaluations, cells)
  }

  /** Il tasso base per finestra: quante finestre di quel tipo sono finite bagnate. */
  private fun climatologyRates(
    dataset: StationDataset,
    events: PrecipitationEvents,
  ): Map<String, Double> = windows.associate { window ->
    var wet = 0
    var total = 0
    var now = dataset.records.first().timestampMillis
    val last = dataset.records.last().timestampMillis
    while (now <= last) {
      events.occurred(now, window)?.let { occurred ->
        if (occurred) wet++
        total++
      }
      now += 3_600_000L
    }
    window.label to if (total == 0) 0.1 else wet.toDouble() / total
  }

  private fun seasonOf(timestampMillis: Long): String =
    when (Instant.ofEpochMilli(timestampMillis).atOffset(ZoneOffset.UTC).monthValue) {
      12, 1, 2 -> "DJF"
      3, 4, 5 -> "MAM"
      6, 7, 8 -> "JJA"
      else -> "SON"
    }

  private companion object {
    /** Sotto questa storia il filtro non ha ancora un'opinione seria: si salta il passo. */
    const val MIN_HISTORY_SAMPLES = 24
  }
}
