package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.testbench.data.HourlyRecord
import dev.pampa.fluidweather.testbench.data.StationDataset

/**
 * Trasforma i record orari nella cosa che il telefono avrebbe visto: pressione DI STAZIONE
 * (non ridotta — ridurla e' compito della pipeline), quota della stazione, coordinate, fermo.
 *
 * Cadenza oraria cosi' com'e': interpolare a 15 minuti fabbricherebbe una liscezza che il
 * mondo non ha promesso. Il banco giudica la pipeline su cio' che l'archivio sa davvero.
 */
class SampleSynthesizer(private val dataset: StationDataset) {

  fun samplesBetween(fromMillis: Long, toMillis: Long): List<PressureSample> =
    dataset.records
      .asSequence()
      .filter { it.timestampMillis in fromMillis..toMillis }
      .mapNotNull { it.toSample() }
      .toList()

  private fun HourlyRecord.toSample(): PressureSample? {
    val station = surfacePressureHpa ?: return null
    return PressureSample(
      timestampMillis = timestampMillis,
      pressureHpa = station,
      source = SampleSource.PERIODIC,
      altitudeMeters = dataset.elevationMeters,
      latitude = dataset.location.latitude,
      longitude = dataset.location.longitude,
      activity = ActivityKind.STILL,
      activityConfidence = 100,
    )
  }
}
