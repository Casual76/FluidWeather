package dev.pampa.fluidweather.core.weather.radar

import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RadarSafetyTest {
  private val noon = java.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
  private fun raster(colour: Int = 0) = TileResult.Ok(Raster(256, 256, IntArray(256 * 256) { colour }), noon - 1234)
  private fun frames(past: List<RadarFrame> = listOf(RadarFrame(noon, "/past")), future: List<RadarFrame> = emptyList()) =
    RadarFrames("https://example.invalid", noon, past, future, noon - 2345)
  private fun sampler(at: Long = noon, frames: RadarFrames = frames(), source: TileSource = TileSource { raster() }) =
    RadarSampler({ frames }, source, { at })

  @Test fun `absolute ETA uses frame time and never moves an expired transition forward`() {
    val eta = Eta(20, 30, null, 60)
    val at1212 = RadarSafety.absoluteEta(noon, eta, noon + 12 * 60_000L)!!
    assertEquals(noon + 20 * 60_000L, at1212.arrivalAtMillis)
    assertEquals(8 * 60_000L, at1212.arrivalAtMillis!! - (noon + 12 * 60_000L))
    assertEquals(noon + 60 * 60_000L, at1212.horizonEndMillis)
    val expired = RadarSafety.absoluteEta(noon, Eta(10, 30, null, 60), noon + 11 * 60_000L)!!
    assertNull(expired.arrivalAtMillis)
    assertTrue(expired.transitionAlreadyPassed)
    assertNull(RadarSafety.absoluteEta(noon, eta, noon + 21 * 60_000L))
    assertEquals(600_000L, at1212.temporalResolutionMillis)
  }

  @Test fun `twenty minutes inclusive and one millisecond older unavailable`() = runBlocking {
    assertTrue(sampler(noon + 1_200_000).sample(43.77, 11.25) is RadarSample.Ok)
    assertTrue(sampler(noon + 1_200_001).sample(43.77, 11.25) is RadarSample.Unavailable)
    assertTrue(sampler(noon - 60_001).sample(43.77, 11.25) is RadarSample.Unavailable)
    assertTrue(sampler(noon - 60_000).sample(43.77, 11.25) is RadarSample.Ok)
  }

  @Test fun `unreliable motion blocks ETA without hiding a valid frame reading`() = runBlocking {
    val base = (sampler().sample(43.77, 11.25) as RadarSample.Ok).reading
    val motion = Motion(30.0, 270.0, 90.0, 2, 5.0, true, false, 10.0, 0.0)
    val reading = base.copy(motion = motion, eta = Eta(20, 30, null, 60), confidence = 0.8)
    assertNotNull(reading.absoluteEta(noon + 60_000))
    for (bad in listOf(
      reading.copy(motion = null),
      reading.copy(motion = motion.copy(agreement = false)),
      reading.copy(motion = motion.copy(atSearchEdge = true)),
      reading.copy(motion = motion.copy(meanPsr = 2.0)),
      reading.copy(confidence = 0.3),
    )) {
      assertTrue(bad.isUsableAt(noon + 60_000))
      assertNull(bad.absoluteEta(noon + 60_000))
    }
  }

  @Test fun `mask all supported outside failed and unknown colour`() = runBlocking {
    assertEquals(GeographicCoverage.SUPPORTED, (sampler().sample(43.77, 11.25) as RadarSample.Ok).reading.geographicCoverage)
    for ((mask, expected) in listOf(raster(0xFF000000.toInt()) to GeographicCoverage.OUTSIDE,
      TileResult.Missing to GeographicCoverage.UNKNOWN, raster(0xFF123456.toInt()) to GeographicCoverage.UNKNOWN)) {
      val sample = sampler(source = TileSource { if ("/coverage/" in it) mask else raster() }).sample(43.77, 11.25)
      assertEquals(expected, (sample as RadarSample.Unavailable).geographicCoverage)
    }
  }

  @Test fun `404 versus clear raster and partial palette never dry`() = runBlocking {
    for (radar in listOf(TileResult.Empty, TileResult.Missing, raster(0xFF123456.toInt()))) {
      val s = sampler(source = TileSource { if ("/coverage/" in it) raster() else radar })
      assertTrue(s.sample(43.77, 11.25) is RadarSample.Unavailable)
      assertNull(RadarObservations.of(s, 43.77, 11.25))
    }
    val clear = sampler()
    assertNotNull(RadarObservations.of(clear, 43.77, 11.25))
    assertNull(RadarObservations.of(sampler(noon + 1_200_001), 43.77, 11.25))
    val pixels = IntArray(64) { 30 }; pixels[4 * 8 + 4] = -1
    assertEquals(-1, RadarWindow(8, pixels, noon).maxAround(4, 4, 1))
  }

  @Test fun `supported geography survives raster failure and late frame expiry`() = runBlocking {
    suspend fun verify(sample: RadarSample) {
      assertTrue(sample is RadarSample.Unavailable)
      assertEquals(GeographicCoverage.SUPPORTED, (sample as RadarSample.Unavailable).geographicCoverage)
      val result = dev.pampa.fluidweather.core.weather.LocalPrecipitationEvaluator().evaluate(
        dev.pampa.fluidweather.core.model.Place(1, "Test", null, 43.77, 11.25),
        dev.pampa.fluidweather.core.model.GeoPoint(43.77, 11.25), null, sample, noon).precipitation
      assertEquals(dev.pampa.fluidweather.core.model.PrecipitationState.UNAVAILABLE, result.state)
      assertEquals(dev.pampa.fluidweather.core.model.RadarAvailability.UNAVAILABLE, result.radarAvailability)
      assertFalse(dev.pampa.fluidweather.core.model.WeatherEvidenceLimit.RADAR_SUPPORT_UNKNOWN in result.limits)
    }
    for (bad in listOf(TileResult.Missing, TileResult.Empty, raster(0xFF123456.toInt()))) {
      verify(sampler(source = TileSource { if ("/coverage/" in it) raster() else bad }).sample(43.77, 11.25))
    }
    var now = noon
    val expiresDuringDownload = RadarSampler({ frames() }, TileSource {
      if ("/coverage/" !in it) now = noon + 1_200_001
      raster()
    }, { now })
    verify(expiresDuringDownload.sample(43.77, 11.25))
  }

  @Test fun `unknown centre with known neighbours is unavailable and masks fetched only once`() = runBlocking {
    var masks = 0
    val global = TileMath.toGlobalPixel(43.77, 11.25, RadarSampler.ZOOM)
    val tile = TileMath.tileOf(global)
    val centreX = global.x.toInt() - tile.x * 256
    val centreY = global.y.toInt() - tile.y * 256
    val s = sampler(source = TileSource { url ->
      if ("/coverage/" in url) { masks++; raster() }
      else {
        val pixels = IntArray(256 * 256)
        pixels[centreY * 256 + centreX] = 0xFF123456.toInt()
        TileResult.Ok(Raster(256, 256, pixels))
      }
    })
    assertTrue(s.sample(43.77, 11.25) is RadarSample.Unavailable)
    assertTrue(masks in 1..4)
    assertNull(RadarObservations.of(s, 43.77, 11.25))
  }

  @Test fun `future provenance ordering and duplicates rejected without tile requests`() = runBlocking {
    val last = RadarFrame(noon, "/past")
    for (bad in listOf(listOf(last, last), listOf(last, RadarFrame(noon - 1, "/older")))) {
      val s = sampler(frames = frames(bad), source = TileSource { error("must not request tiles") })
      assertTrue(s.sample(43.77, 11.25) is RadarSample.Unavailable)
    }
    var futureRequested = false
    val s = sampler(noon + 900_000L, frames(future = listOf(RadarFrame(noon + 600_000L, "/future"))), TileSource {
      if ("/future/" in it) futureRequested = true
      raster()
    })
    assertTrue(s.sample(43.77, 11.25) is RadarSample.Ok)
    assertFalse(futureRequested)
  }

  @Test fun `sample and source download metadata remain distinct UTC instants`() = runBlocking {
    val sample = sampler(noon + 60_000).sample(43.77, 11.25)
    val reading = (sample as RadarSample.Ok).reading
    assertEquals(noon + 60_000, reading.sampledAtMillis)
    assertEquals(noon - 2345, reading.manifestDownloadedAtMillis)
    assertEquals(noon - 1234, reading.tilesDownloadedAtMillis)
    val evaluation = sample.evaluateAt(noon + 120_000)
    assertEquals(noon + 120_000, evaluation.evaluatedAtMillis)
    assertEquals(dev.pampa.fluidweather.core.model.WeatherEvidenceType.RADAR_ESTIMATE, evaluation.evidence!!.type)
    assertNull(evaluation.evidence!!.effectiveInterval)
    assertFalse(sample.evaluateAt(noon + 1_200_001).usable)
  }

  @Test fun `unknown trajectory stops horizon before gap without estimating cessation`() {
    val size = 128
    val pixels = IntArray(size * size) { 30 }
    for (y in 62..66) for (x in 58..60) pixels[y * size + x] = -1
    for (y in 0 until size) for (x in 0..55) pixels[y * size + x] = 0
    val motion = Motion(30.0, 270.0, 90.0, 2, 5.0, true, false, 10.0, 0.0)
    val eta = EtaEstimator.estimate(RadarWindow(size, pixels, noon), motion, true)
    assertNull(eta.endsInMin)
    assertEquals(0, eta.horizonMin)
  }

  @Test fun `cancellation from manifest mask and observation propagates`() = runBlocking {
    val manifest = RadarSampler({ throw CancellationException("manifest") }, TileSource { raster() })
    val mask = sampler(source = TileSource { throw CancellationException("mask") })
    for (s in listOf(manifest, mask)) {
      try { RadarObservations.of(s, 43.77, 11.25); fail("cancellation swallowed") }
      catch (_: CancellationException) { }
    }
  }
}
