package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.climatology.PriorsFixtures
import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3 as V3

class FeatureSubsetsTest {

  private val hour = 3_600_000L
  private val issue = Instant.parse("2023-03-10T12:20:00Z").toEpochMilli()

  @Test
  fun `le colonne per livello hanno le dimensioni della specifica`() {
    for (w in 0..2) {
      assertEquals(36, FeatureSubsets.columns(ContextTier.FRESH, w).size)
      assertEquals(36, FeatureSubsets.columns(ContextTier.STALE, w).size)
      assertEquals(15, FeatureSubsets.columns(ContextTier.NONE, w).size)
      assertEquals(13, FeatureSubsets.columns(ContextTier.NONE_NOCLIMA, w).size)
    }
  }

  @Test
  fun `il livello del mare esce solo da NONE, dove c'e' la climatologia locale`() {
    for (w in 0..2) {
      assertTrue(V3.SEA_LEVEL in FeatureSubsets.columns(ContextTier.FRESH, w))
      assertTrue(V3.SEA_LEVEL in FeatureSubsets.columns(ContextTier.STALE, w))
      assertFalse(V3.SEA_LEVEL in FeatureSubsets.columns(ContextTier.NONE, w))
      assertTrue(V3.SEA_LEVEL in FeatureSubsets.columns(ContextTier.NONE_NOCLIMA, w))
    }
  }

  @Test
  fun `le colonne sono ordinate, uniche e dentro il contratto`() {
    for (tier in ContextTier.entries) {
      for (w in 0..2) {
        val columns = FeatureSubsets.columns(tier, w)
        assertEquals(columns.sorted(), columns.toList())
        assertEquals(columns.size, columns.toSet().size)
        assertTrue(columns.all { it in 0 until V3.COUNT })
      }
    }
  }

  @Test
  fun `ogni finestra vede solo le sue baseline`() {
    for (w in 0..2) {
      val fresh = FeatureSubsets.columns(ContextTier.FRESH, w).toSet()
      for (other in 0..2) {
        val mine = other == w
        assertEquals(mine, (V3.CLIMATOLOGY + other) in fresh)
        assertEquals(mine, (V3.PERSISTENCE + other) in fresh)
        assertEquals(mine, (V3.BAROMETRIC_RULE + other) in fresh)
      }
      assertTrue(V3.LOCAL_TABLES in fresh)
    }
  }

  @Test
  fun `i livelli senza contesto non vedono le colonne del contesto ne' la persistenza`() {
    for (tier in listOf(ContextTier.NONE, ContextTier.NONE_NOCLIMA)) {
      for (w in 0..2) {
        val columns = FeatureSubsets.columns(tier, w).toSet()
        assertTrue("${tier} ${w}", columns.none { FeatureSubsets.groupOf(it) == FeatureGroup.CONTEXT })
        assertFalse((V3.PERSISTENCE + w) in columns)
      }
    }
  }

  @Test
  fun `NONE_NOCLIMA non vede la normale, il clima ne' il flag delle tabelle`() {
    for (w in 0..2) {
      val columns = FeatureSubsets.columns(ContextTier.NONE_NOCLIMA, w).toSet()
      assertFalse(5 in columns)
      assertFalse(V3.NORMAL_MISSING in columns)
      assertFalse((V3.CLIMATOLOGY + w) in columns)
      assertFalse(V3.LOCAL_TABLES in columns)
      assertTrue((V3.BAROMETRIC_RULE + w) in columns)
    }
    // NONE la normale ce l'ha (il telefono ha trenta giorni di storia), ma nemmeno lui vede il contesto.
    val none = FeatureSubsets.columns(ContextTier.NONE, 0).toSet()
    assertTrue(5 in none && V3.NORMAL_MISSING in none && V3.CLIMATOLOGY in none)
  }

  @Test
  fun `le ancore candidate`() {
    for (w in 0..2) {
      assertEquals(listOf(null, V3.PERSISTENCE + w), FeatureSubsets.anchorCandidates(ContextTier.FRESH, w))
      assertEquals(listOf(null, V3.PERSISTENCE + w, V3.BAROMETRIC_RULE + w), FeatureSubsets.anchorCandidates(ContextTier.STALE, w))
      assertEquals(listOf(null, V3.BAROMETRIC_RULE + w), FeatureSubsets.anchorCandidates(ContextTier.NONE, w))
      assertEquals(listOf(null, V3.BAROMETRIC_RULE + w), FeatureSubsets.anchorCandidates(ContextTier.NONE_NOCLIMA, w))
      // Ogni ancora e' una colonna che il modello vede.
      for (tier in ContextTier.entries) {
        val columns = FeatureSubsets.columns(tier, w).toSet()
        assertTrue(FeatureSubsets.anchorCandidates(tier, w).filterNotNull().all { it in columns })
      }
    }
  }

  @Test
  fun `i gruppi coprono tutte le quarantadue colonne`() {
    val counts = (0 until V3.COUNT).groupingBy { FeatureSubsets.groupOf(it) }.eachCount()
    assertEquals(9, counts[FeatureGroup.BAROMETER])
    assertEquals(18, counts[FeatureGroup.CONTEXT])
    assertEquals(5, counts[FeatureGroup.TIME])
    assertEquals(10, counts[FeatureGroup.PRIORS])
  }

  /** Un vettore senza contesto ha NaN solo fuori dalle colonne del livello; uno con contesto pieno, niente NaN nel livello. */
  @Test
  fun `le colonne del livello sono quelle che il vettore riempie davvero`() {
    val start = issue - 24 * hour
    val samples = (0..24 * 4).map { i ->
      PressureSample(start + i * 900_000L, 1013.0 - 0.2 * (i * 0.25), SampleSource.PERIODIC, latitude = 45.0, longitude = 9.0)
    }
    val cleaning = CleaningPipeline().process(samples)
    val priors = PriorsFixtures.local()

    val bare = V3.extract(cleaning, null, 1013.0, issue, priors)!!
    for (tier in listOf(ContextTier.NONE, ContextTier.NONE_NOCLIMA)) {
      for (w in 0..2) {
        for (column in FeatureSubsets.columns(tier, w)) {
          assertFalse("$tier finestra $w: ${V3.names[column]} e' NaN ma il livello la usa", bare[column].isNaN())
        }
      }
    }

    val context = NowcastContext(
      relativeHumidityPercent = 80.0, dewPointSpreadC = 3.0, cloudCoverPercent = 70.0, windSpeedKmh = 12.0,
      windDirectionDeg = 200.0, windDirectionDeg3hAgo = 190.0, rainLastHourMm = 0.0, rainLast3hMm = 0.0,
      pressureMslHpa = 1010.0, pressureMsl3hAgoHpa = 1011.0, slotEndMillis = RainWindows.lastClosedSlotEnd(issue - 600_000L),
      temperatureC = 10.0, temperature3hAgoC = 9.0, dewPointC = 7.0, dewPoint3hAgoC = 7.5, cloudCover3hAgoPercent = 60.0,
      rainSlotsMm = List(7) { 0.0 },
    )
    val full = V3.extract(cleaning, context, 1013.0, issue, priors)!!
    for (tier in listOf(ContextTier.FRESH, ContextTier.STALE)) {
      for (w in 0..2) {
        for (column in FeatureSubsets.columns(tier, w)) {
          assertFalse("$tier finestra $w: ${V3.names[column]} e' NaN ma il livello la usa", full[column].isNaN())
        }
      }
    }
  }
}
