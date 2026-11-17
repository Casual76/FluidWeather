package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.climatology.PriorsFixtures
import org.junit.Assert.*
import org.junit.Test

class CurrentSignalPolicyTest {
  private val start = 1_700_000_000_000L
  private val hour = 3_600_000L
  private val pipeline = CleaningPipeline()
  private fun series(hours: Int) = (0..hours * 4).map { quarter ->
    PressureSample(timestampMillis = start + quarter * hour / 4, pressureHpa = 1013.0,
      source = SampleSource.PERIODIC)
  }

  @Test fun `fourteen good hours followed by eight hours silence emit neither v2 nor v3 features`() {
    val cleaning = pipeline.process(series(14))
    val now = start + 22 * hour
    assertTrue(cleaning.historyHours >= 13)
    assertFalse(CurrentSignalPolicy.isCurrent(cleaning.filtered, now))
    assertNull(FeatureExtractor.extract(cleaning, null, null, now))
    assertNull(FeatureExtractorV3.extract(cleaning, null, null, now, PriorsFixtures.pooledOnly()))
  }

  @Test fun `fresh offline sensor history remains usable without any provider context`() {
    val cleaning = pipeline.process(series(14))
    val now = start + 14 * hour
    assertTrue(CurrentSignalPolicy.isCurrent(cleaning.filtered, now))
    assertNotNull(FeatureExtractor.extract(cleaning, null, null, now))
    assertNotNull(FeatureExtractorV3.extract(cleaning, null, null, now, PriorsFixtures.pooledOnly()))
  }

  @Test fun `one new point after outage is insufficient but sustained resumption recovers`() {
    val stopped = series(14)
    val resumedStart = start + 22 * hour
    val first = PressureSample(timestampMillis = resumedStart, pressureHpa = 1013.0, source = SampleSource.PERIODIC)
    assertNull(FeatureExtractor.extract(pipeline.process(stopped + first), null, null, resumedStart))
    val resumed = (0..8).map { q -> first.copy(timestampMillis = resumedStart + q * hour / 4) }
    val now = resumedStart + 2 * hour
    assertNotNull(FeatureExtractor.extract(pipeline.process(stopped + resumed), null, null, now))
  }

  @Test fun `clock rollback future points cannot form current signal`() {
    val cleaning = pipeline.process(series(14))
    assertNull(FeatureExtractor.extract(cleaning, null, null, start + 14 * hour - 1))
    assertNull(FeatureExtractorV3.extract(cleaning, null, null, start + 14 * hour - 1, PriorsFixtures.pooledOnly()))
  }

  @Test fun `latest age boundary allows saving cadence but expires after forty five minutes`() {
    val cleaning = pipeline.process(series(14))
    assertTrue(CurrentSignalPolicy.isCurrent(cleaning.filtered, start + 14 * hour + CurrentSignalPolicy.MAX_AGE_MILLIS))
    assertFalse(CurrentSignalPolicy.isCurrent(cleaning.filtered, start + 14 * hour + CurrentSignalPolicy.MAX_AGE_MILLIS + 1))
  }

  @Test fun `gated extrapolation after outage is not a new accepted sensor observation`() {
    val cleaning = pipeline.process(series(14))
    val now = start + 22 * hour
    val extrapolated = cleaning.filtered.last().copy(timestampMillis = now, gated = true)
    assertFalse(CurrentSignalPolicy.isCurrent(cleaning.filtered + extrapolated, now))
  }
}
