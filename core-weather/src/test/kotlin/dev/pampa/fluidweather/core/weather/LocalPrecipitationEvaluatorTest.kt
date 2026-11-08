package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.*
import dev.pampa.fluidweather.core.weather.radar.*
import org.junit.Assert.*
import org.junit.Test

/** Observable semantic cases, shared fixtures also used by lifecycle tests. */
class LocalPrecipitationEvaluatorTest {
  private val f = AssessmentFixture()
  private val evaluator = LocalPrecipitationEvaluator()
  private fun assess(snapshot: WeatherSnapshot? = f.snapshot(), radar: RadarSample? = null, now: Long = f.noon) =
    evaluator.evaluate(f.place, f.point, snapshot, radar, now)

  @Test fun `present model and radar retain independent provenance`() {
    val b = assess(radar = f.radar(30))
    assertEquals(PrecipitationState.WET, b.precipitation.state)
    assertEquals(setOf(WeatherEvidenceType.MODEL, WeatherEvidenceType.RADAR_ESTIMATE), b.precipitation.evidence.map { it.type }.toSet())
    val r = b.precipitation.evidence.single { it.type == WeatherEvidenceType.RADAR_ESTIMATE }
    assertNull(r.effectiveInterval)
    assertEquals(f.noon, r.sourceTimestampMillis)
    assertEquals(f.noon - 1000, r.downloadedAtMillis)
    assertNull(b.current!!.evidence.single().effectiveInterval)
  }

  @Test fun `arrival and end are future absolute estimates`() {
    val arrival = assess(f.snapshot(0.0), f.radar(0, Eta(20, null, null, 60)), f.noon + 5 * 60_000)
    assertEquals(f.noon + 20 * 60_000, arrival.precipitation.arrivalAtMillis)
    assertEquals(600_000L, arrival.precipitation.transitionResolutionMillis)
    val end = assess(radar = f.radar(30, Eta(null, null, 30, 60)))
    assertEquals(f.noon + 30 * 60_000, end.precipitation.endAtMillis)
  }

  @Test fun `current wet condition cannot silently agree with a dry recent accumulation`() {
    val dry = f.snapshot(0.0)
    val rainCode = dry.copy(context = dry.context!!.copy(current = dry.context!!.current!!.copy(kind = WeatherKind.RAIN)))
    val conflict = assess(rainCode)
    assertEquals(WeatherKind.RAIN, conflict.current!!.kind)
    assertEquals(PrecipitationState.UNCERTAIN, conflict.precipitation.state)
    assertTrue(WeatherEvidenceLimit.CONFLICTING_SIGNALS in conflict.precipitation.limits)
    val amountMissing = rainCode.copy(context = rainCode.context!!.copy(current = rainCode.context!!.current!!.copy(precipitationMm = null)))
    assertEquals(PrecipitationState.WET, assess(amountMissing).precipitation.state)
  }

  @Test fun `source resampling and prediction bookkeeping do not change weather revision`() {
    val initial = f.radar(30) as RadarSample.Ok
    val later = initial.copy(reading = initial.reading.copy(frameAgeMin = 5, sampledAtMillis = f.noon + 300_000,
      notes = listOf("sampling age changed")))
    assertEquals(assess(radar = initial).revision, assess(f.snapshot().copy(predictionsRegisteredAtMillis = f.noon + 1), later).revision)
    assertNotEquals(assess(radar = initial).revision, assess(radar = later.copy(reading = later.reading.copy(atPointDbz = 35))).revision)
  }

  @Test fun `intermittent timeline keeps wet dry wet and holes`() {
    val b = f.snapshot().context!!.copy(minutely = listOf(MinutePoint(f.noon + 900_000, 1.0),
      MinutePoint(f.noon + 1_800_000, 0.0), MinutePoint(f.noon + 2_700_000, 2.0)))
    val t = assess(f.snapshot().copy(context = b)).timeline
    assertEquals(listOf(PrecipitationState.WET, PrecipitationState.DRY, PrecipitationState.WET, PrecipitationState.UNAVAILABLE), t.map { it.state })
    assertNull(t.last().amountMm)
  }

  @Test fun `both conflict directions preserve sources and suppress ETA`() {
    for ((mm, dbz) in listOf(0.0 to 30, 1.0 to 0)) {
      val b = assess(f.snapshot(mm), f.radar(dbz, Eta(20, 30, null, 60)))
      assertEquals(PrecipitationState.UNCERTAIN, b.precipitation.state)
      assertTrue(WeatherEvidenceLimit.CONFLICTING_SIGNALS in b.precipitation.limits)
      assertEquals(2, b.precipitation.evidence.size)
      assertNull(b.precipitation.arrivalAtMillis)
      assertNull(b.precipitation.endAtMillis)
    }
  }

  @Test fun `stale original carried context is not refreshed by round`() {
    val old = f.snapshot().context!!.copy(fetchedAtMillis = f.noon - 7_200_000,
      current = f.snapshot().context!!.current!!.copy(downloadedAtMillis = f.noon - 7_200_000))
    val b = assess(f.snapshot().copy(context = old, fetchedAtMillis = f.noon, contextCarriedFromMillis = f.noon - 7_200_000))
    assertEquals(PrecipitationState.UNAVAILABLE, b.precipitation.state)
    assertNull(b.current)
    assertTrue(WeatherEvidenceLimit.STALE in b.precipitation.limits)
  }

  @Test fun `outside and failed radar do not negate model or imply dry`() {
    val outside = RadarSample.Unavailable("outside", GeographicCoverage.OUTSIDE)
    val b = assess(radar = outside)
    assertEquals(PrecipitationState.WET, b.precipitation.state)
    assertEquals(RadarAvailability.OUTSIDE, b.precipitation.radarAvailability)
    assertTrue(WeatherEvidenceLimit.MODEL_ONLY in b.precipitation.limits)
    assertEquals(PrecipitationState.UNAVAILABLE, assess(null, outside).precipitation.state)
    assertEquals(PrecipitationState.UNAVAILABLE, assess(null, RadarSample.Unavailable("download failed")).precipitation.state)
  }

  @Test fun `no data coordinates key or nearby point never fabricates weather`() {
    assertEquals(PrecipitationState.UNAVAILABLE, assess(null).precipitation.state)
    assertEquals(PrecipitationState.UNAVAILABLE, evaluator.evaluate(Place.gps(), null, f.snapshot(), f.radar(0), f.noon).precipitation.state)
    for (s in listOf(f.snapshot().copy(placeKey = "place-99"), f.snapshot().copy(latitude = Double.NaN),
      f.snapshot().copy(latitude = 0.0), f.snapshot().copy(context = f.snapshot().context!!.copy(longitude = 0.0)))) {
      assertEquals(PrecipitationState.UNAVAILABLE, assess(s).precipitation.state)
    }
  }

  @Test fun `closed quarter distinct from future and forecast never promoted by clock`() {
    val b = f.snapshot().context!!.copy(current = null, minutely = listOf(MinutePoint(f.noon, 0.5), MinutePoint(f.noon + 900_000, 2.0)))
    assertEquals(PrecipitationState.WET, assess(f.snapshot().copy(context = b)).precipitation.state)
    val futureOnly = f.snapshot().copy(context = b.copy(minutely = listOf(MinutePoint(f.noon + 900_000, 2.0))))
    assertEquals(PrecipitationState.UNAVAILABLE, assess(futureOnly).precipitation.state)
    assertEquals(PrecipitationState.UNAVAILABLE, assess(futureOnly, now = f.noon + 900_000).precipitation.state)
  }

  @Test fun `legacy metadata coarse accumulations and unknown kind are not dry evidence`() {
    val b = f.snapshot().context!!
    for (bad in listOf(b.copy(current = null, minutelyPrecipitation = null),
      b.copy(current = b.current!!.copy(accumulationIntervalMillis = 10_800_000), minutely = emptyList()),
      b.copy(current = b.current!!.copy(precipitationMm = null, kind = WeatherKind.UNKNOWN), minutely = emptyList()),
      b.copy(current = b.current!!.copy(downloadedAtMillis = null), minutely = emptyList()))) {
      assertEquals(PrecipitationState.UNAVAILABLE, assess(f.snapshot().copy(context = bad)).precipitation.state)
    }
  }

  @Test fun `future timestamps rejected and current temperature expires separately`() {
    val b = f.snapshot().context!!
    assertEquals(PrecipitationState.UNAVAILABLE, assess(f.snapshot().copy(context = b.copy(fetchedAtMillis = f.noon + 1))).precipitation.state)
    val late = assess(now = f.noon + 31 * 60_000)
    assertNull(late.current)
    assertEquals(PrecipitationState.UNAVAILABLE, late.precipitation.state)
    assertTrue(WeatherEvidenceLimit.POSSIBLY_INTERPOLATED in assess().precipitation.limits)
    assertEquals(3_600_000L, assess().precipitation.evidence.single().temporalResolutionMillis)
  }

  @Test fun `expired transition is uncertain not dry and cutoff removes radar evidence signal`() {
    val r = f.radar(0, Eta(10, 30, null, 60))
    val expired = assess(null, r, f.noon + 11 * 60_000)
    assertEquals(PrecipitationState.UNCERTAIN, expired.precipitation.state)
    assertNull(expired.precipitation.arrivalAtMillis)
    assertNull(expired.precipitation.endAtMillis)
    assertEquals(PrecipitationState.UNAVAILABLE, assess(null, r, f.noon + 1_200_001).precipitation.state)
  }

  @Test fun `a short radar trajectory horizon cannot restore the pre-transition state`() {
    val size = 128
    val pixels = IntArray(size * size)
    for (y in 49..53) for (x in 49..53) pixels[y * size + x] = 30
    val motion = Motion(200.0, 225.0, 45.0, 2, 5.0, true, false, 26.0, 26.0)
    val arrival = EtaEstimator.estimate(RadarWindow(size, pixels, f.noon), motion, false)
    assertEquals(5, arrival.arrivesInMin)
    assertEquals(16, arrival.horizonMin)
    for ((dbz, rawEta) in listOf(0 to arrival, 30 to Eta(null, null, 5, 16))) {
      val base = f.radar(dbz, rawEta) as RadarSample.Ok
      val sample = base.copy(reading = base.reading.copy(motion = motion))
      for (minute in listOf(9, 11, 16, 17, 20)) {
        val result = assess(null, sample, f.noon + minute * 60_000L).precipitation
        assertEquals("minute $minute", PrecipitationState.UNCERTAIN, result.state)
        assertTrue(WeatherEvidenceLimit.TRANSITION_EXPIRED in result.limits)
        assertNull(result.arrivalAtMillis)
        assertNull(result.endAtMillis)
      }
      assertEquals(PrecipitationState.UNAVAILABLE, assess(null, sample, f.noon + 1_200_001).precipitation.state)
    }
  }

  @Test fun `duplicates invalid amounts probability and boundary fragments stay unknown`() {
    val b = f.snapshot().context!!.copy(minutely = listOf(MinutePoint(f.noon + 900_000, 1.0), MinutePoint(f.noon + 900_000, 0.0),
      MinutePoint(f.noon + 1_800_000, Double.NaN), MinutePoint(f.noon + 2_700_000, 1.0, 101.0)))
    val t = assess(f.snapshot().copy(context = b), now = f.noon + 60_000).timeline
    assertEquals(PrecipitationState.UNAVAILABLE, t.first().state)
    assertNull(t.first().amountMm)
    assertTrue(t.filter { it.amountMm != null }.all { it.probabilityPercent == null })
    assertTrue(t.zipWithNext().all { (a, b) -> a.interval.endMillis == b.interval.startMillis })
  }

  @Test fun `earlier wet forecast prevents later first arrival headline`() {
    val s = f.snapshot(0.0).let { it.copy(context = it.context!!.copy(minutely = listOf(MinutePoint(f.noon + 900_000, 1.0)))) }
    val b = assess(s, f.radar(0, Eta(30, null, null, 60)))
    assertNull(b.precipitation.arrivalAtMillis)
    assertTrue(WeatherEvidenceLimit.FUTURE_DIVERGENCE in b.precipitation.limits)
  }

  @Test fun `last hour remains an accumulation with four real closed quarters`() {
    val s = f.snapshot().let { it.copy(context = it.context!!.copy(minutely = (0..3).map { i ->
      MinutePoint(f.noon - i * 900_000L, 0.5)
    })) }
    val b = assess(s)
    assertEquals(2.0, b.rainLastHour!!.amountMm, 0.0)
    assertEquals(WeatherInterval(f.noon - 3_600_000, f.noon), b.rainLastHour!!.interval)
    val bad = s.copy(context = s.context!!.copy(minutely = s.context!!.minutely.drop(1)))
    assertNull(assess(bad).rainLastHour)
  }

  @Test fun `current original download cannot borrow fresh bundle or round timestamp`() {
    val s = f.snapshot().let { it.copy(context = it.context!!.copy(current = it.context!!.current!!.copy(
      downloadedAtMillis = f.noon - 7_200_000))) }
    val b = assess(s)
    assertEquals(PrecipitationState.UNAVAILABLE, b.precipitation.state)
    assertNull(b.current)
  }

  @Test fun `revision stable over clock changes but sensitive to same fetch content source and location`() {
    val a = assess()
    assertEquals(a.revision, assess(now = f.noon + 1000).revision)
    assertNotEquals(a.revision, assess(f.snapshot(0.0)).revision)
    assertNotEquals(a.revision, assess(f.snapshot().let { it.copy(context = it.context!!.copy(providerId = "other")) }).revision)
    assertNotEquals(a.revision, evaluator.evaluate(f.place, GeoPoint(44.001, 11.0), f.snapshot(), null, f.noon).revision)
    assertNotEquals(assess(null, f.radar(0)).revision, assess(null, f.radar(0, frame = f.noon - 600_000)).revision)
  }
}

internal class AssessmentFixture {
  val noon = java.time.Instant.parse("2026-11-07T12:00:00Z").toEpochMilli()
  val place = Place(1, "Test", null, 44.0, 11.0)
  val point = GeoPoint(44.0, 11.0)
  fun snapshot(mm: Double = 1.0): WeatherSnapshot = WeatherSnapshot("place-1", 44.0, 11.0, noon,
    emptyList(), FusedForecast(emptyList(), emptyMap()), ForecastBundle("open-meteo", noon, 44.0, 11.0,
      emptyList(), current = ProviderCurrent(noon, 10.0, WeatherKind.CLOUDY, mm, 900_000, noon, true),
      minutelyPrecipitation = PrecipitationAccumulation(900_000, AccumulationAnchor.PRECEDING), minutelyPossiblyInterpolated = true))
  fun radar(dbz: Int, eta: Eta? = null, frame: Long = noon): RadarSample = RadarSample.Ok(RadarReading(
    frame, 0, 30, dbz, null, 30.0, 0, dbz, RadarTrend.STEADY, 0.0,
    Motion(30.0, 270.0, 90.0, 2, 5.0, true, false, 10.0, 0.0), eta,
    emptyList(), 1.0, 0.8, emptyList(), geographicCoverage = GeographicCoverage.SUPPORTED,
    tilesDownloadedAtMillis = frame - 1000, complete = true))
}
