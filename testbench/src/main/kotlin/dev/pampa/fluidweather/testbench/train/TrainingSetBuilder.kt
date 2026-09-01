package dev.pampa.fluidweather.testbench.train

import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.events.EventWindow
import dev.pampa.fluidweather.testbench.events.PrecipitationEvents
import dev.pampa.fluidweather.testbench.replay.SampleSynthesizer

/** Una riga di addestramento: feature all'istante t, verita' per finestra, e da dove viene. */
data class TrainingRow(
  val locationName: String,
  val timestampMillis: Long,
  val features: DoubleArray,
  val labels: Map<String, Boolean>,
)

/**
 * Costruisce il set con la stessa disciplina del replay: a ogni istante le feature vedono solo
 * il passato (72 ore di campioni, contesto e normale del momento), le etichette vengono dal
 * futuro. Un'ora di passo invece di tre: per addestrare, ogni riga conta.
 */
class TrainingSetBuilder(
  private val pipeline: CleaningPipeline = CleaningPipeline(),
  private val historyHours: Int = 72,
  private val windows: List<EventWindow> = EventWindow.Standard,
) {

  fun build(dataset: StationDataset): List<TrainingRow> {
    val synthesizer = SampleSynthesizer(dataset)
    val events = PrecipitationEvents(dataset.records)
    val recordContext = RecordContext(dataset)

    val rows = mutableListOf<TrainingRow>()
    var now = dataset.records.first().timestampMillis + historyHours * 3_600_000L
    val last = dataset.records.last().timestampMillis
    while (now <= last) {
      val labels = windows.mapNotNull { window ->
        events.occurred(now, window)?.let { window.label to it }
      }.toMap()
      if (labels.size == windows.size) {
        val samples = synthesizer.samplesBetween(now - historyHours * 3_600_000L, now)
        if (samples.size >= 24) {
          val temperature = dataset.records
            .lastOrNull { it.timestampMillis <= now && it.temperatureC != null }
            ?.temperatureC
          val cleaning = pipeline.process(samples, temperatureCelsius = temperature)
          val features = FeatureExtractor.extract(
            cleaning = cleaning,
            context = recordContext.context(now),
            normalHpa = recordContext.normal(now),
            nowMillis = now,
          )
          if (features != null) {
            rows += TrainingRow(dataset.location.name, now, features, labels)
          }
        }
      }
      now += 3_600_000L
    }
    return rows
  }
}
