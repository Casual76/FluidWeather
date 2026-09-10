package dev.pampa.fluidweather.testbench.train

import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.events.EventWindow
import dev.pampa.fluidweather.testbench.events.PrecipitationEvents
import dev.pampa.fluidweather.testbench.replay.SampleSynthesizer
import dev.pampa.fluidweather.testbench.replay.SamplingProfile

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
  /** Lo stesso profilo del replay: si impara su cio' su cui si viene giudicati. */
  private val profile: SamplingProfile = SamplingProfile.TELEFONO,
  /** Ventiquattro ore, le stesse che il telefono tiene in finestra. */
  private val historyHours: Int = 24,
  private val windows: List<EventWindow> = EventWindow.Standard,
) {

  fun build(dataset: StationDataset): List<TrainingRow> {
    val synthesizer = SampleSynthesizer(dataset, profile)
    val events = PrecipitationEvents(dataset.records)
    val recordContext = RecordContext(dataset)
    val temperatures = TemperatureTrack(dataset)

    val first = dataset.records.first().timestampMillis + historyHours * 3_600_000L
    val last = dataset.records.last().timestampMillis
    val instants = generateSequence(first) { it + 3_600_000L }.takeWhile { it <= last }.toList()

    // Un'ora di passo su quattro anni e dieci localita' sono centinaia di migliaia di righe, e
    // ognuna rigioca ventiquattro ore di raffiche: in fila sarebbero minuti di attesa a ogni
    // giro del banco. Le righe sono indipendenti fra loro, quindi si fanno in parallelo.
    return instants.parallelStream().map { now ->
      buildRow(dataset, synthesizer, events, recordContext, temperatures, now)
    }.filter { it != null }.map { it!! }.toList()
  }

  private fun buildRow(
    dataset: StationDataset,
    synthesizer: SampleSynthesizer,
    events: PrecipitationEvents,
    recordContext: RecordContext,
    temperatures: TemperatureTrack,
    now: Long,
  ): TrainingRow? {
    val labels = windows.mapNotNull { window ->
      events.occurred(now, window)?.let { window.label to it }
    }.toMap()
    if (labels.size != windows.size) return null
    val samples = synthesizer.samplesBetween(now - historyHours * 3_600_000L, now)
    if (samples.size < 24) return null
    val cleaning = pipeline.process(
      samples,
      temperatureCelsius = temperatures.at(now),
      referenceAltitudeMeters = dataset.elevationMeters,
    )
    val features = FeatureExtractor.extract(
      cleaning = cleaning,
      context = recordContext.context(now),
      normalHpa = recordContext.normal(now),
      nowMillis = now,
    ) ?: return null
    return TrainingRow(dataset.location.name, now, features, labels)
  }
}

/**
 * L'ultima temperatura *passata* nota, in tempo costante.
 *
 * Prima era una scansione lineare dell'archivio intero per ogni riga: su quattro anni erano
 * trentacinquemila confronti per riga, moltiplicati per trentacinquemila righe. Il risultato non
 * cambia di una virgola; il tempo si'.
 */
private class TemperatureTrack(dataset: StationDataset) {
  private val ordered = dataset.records.sortedBy { it.timestampMillis }
  private val lastKnown = DoubleArray(ordered.size)

  init {
    var carried = Double.NaN
    for (i in ordered.indices) {
      ordered[i].temperatureC?.let { carried = it }
      lastKnown[i] = carried
    }
  }

  fun at(nowMillis: Long): Double? {
    var low = 0
    var high = ordered.size
    while (low < high) {
      val mid = (low + high) / 2
      if (ordered[mid].timestampMillis <= nowMillis) low = mid + 1 else high = mid
    }
    if (low == 0) return null
    return lastKnown[low - 1].takeIf { !it.isNaN() }
  }
}
