package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.learning.LearningState
import dev.pampa.fluidweather.nowcast.learning.NowcastEngine
import org.junit.Assert.*
import org.junit.Test

class NowcastSignalRecencyRegressionTest {
  private val start = 1_700_000_000_000L
  private val hour = 3_600_000L
  private val cleaning = CleaningPipeline().process((0..56).map { q ->
    PressureSample(timestampMillis = start + q * hour / 4, pressureHpa = 1013.0,
      source = SampleSource.PERIODIC)
  })

  @Test fun `production feature and solo evaluation paths cannot renew stale history`() {
    val engine = NowcastEngine.v3()
    val staleNow = start + 22 * hour
    assertNull(NowcastUseCase.featuresOf(engine, cleaning, null, null, staleNow, V3Priors.pooledOnly, true))
    assertNull(NowcastUseCase.soloOf(engine, cleaning, null, LearningState.EMPTY, staleNow,
      ContextTier.NONE_NOCLIMA, V3Priors.pooledOnly))
    assertTrue(cleaning.historyHours >= 13.0)
  }

  @Test fun `same production path returns a fresh solo verdict without network`() {
    assertNotNull(NowcastUseCase.soloOf(NowcastEngine.v3(), cleaning, null, LearningState.EMPTY,
      start + 14 * hour, ContextTier.NONE_NOCLIMA, V3Priors.pooledOnly))
  }
}
