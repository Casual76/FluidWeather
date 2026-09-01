package dev.pampa.fluidweather.nowcast.cleaning

import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.nowcast.acquisition.AggregatedPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleScreenerTest {

  private val screener = SampleScreener()
  private val start = 1_700_000_000_000L

  private fun point(
    minutes: Int,
    hPa: Double = 1013.0,
    activity: ActivityKind = ActivityKind.STILL,
    confidence: Int? = 90,
    altitude: Double? = null,
  ) = AggregatedPoint(
    timestampMillis = start + minutes * 60_000L,
    pressureHpa = hPa,
    spreadHpa = 0.0,
    sampleCount = 1,
    altitudeMeters = altitude,
    latitude = null,
    longitude = null,
    activity = activity,
    activityConfidence = confidence,
    source = SampleSource.PERIODIC,
  )

  @Test
  fun `in auto si scarta, con confidenza`() {
    val result = screener.screen(
      listOf(point(0), point(15, activity = ActivityKind.IN_VEHICLE), point(30)),
    )
    assertEquals(2, result.accepted.size)
    assertEquals(RejectionReason.VEHICLE, result.rejected.single().reason)
  }

  @Test
  fun `un riconoscimento poco confidente non basta a scartare`() {
    val result = screener.screen(
      listOf(point(0), point(15, activity = ActivityKind.IN_VEHICLE, confidence = 30)),
    )
    assertTrue(result.rejected.isEmpty())
  }

  @Test
  fun `camminare non e' un veicolo`() {
    val result = screener.screen(listOf(point(0, activity = ActivityKind.WALKING)))
    assertTrue(result.rejected.isEmpty())
  }

  @Test
  fun `trenta metri di dislivello in cinque minuti sono piani, non rumore GPS`() {
    val result = screener.screen(
      listOf(point(0, altitude = 100.0), point(5, altitude = 130.0)),
    )
    assertEquals(RejectionReason.ALTITUDE_CHANGE, result.rejected.single().reason)
  }

  @Test
  fun `dieci metri sono rumore GPS e passano`() {
    val result = screener.screen(
      listOf(point(0, altitude = 100.0), point(5, altitude = 110.0)),
    )
    assertTrue(result.rejected.isEmpty())
  }

  @Test
  fun `lo stesso dislivello fuori dalla finestra temporale non dice niente`() {
    val result = screener.screen(
      listOf(point(0, altitude = 100.0), point(20, altitude = 130.0)),
    )
    assertTrue(result.rejected.isEmpty())
  }

  @Test
  fun `senza quota non si scarta per quota`() {
    val result = screener.screen(listOf(point(0), point(5), point(10)))
    assertTrue(result.rejected.isEmpty())
  }

  @Test
  fun `il confronto di quota e' con l'ultimo accettato, non col rifiutato`() {
    // Il punto in auto viene tolto: il dislivello si valuta fra 0' e 10', fuori sequenza stretta.
    val result = screener.screen(
      listOf(
        point(0, altitude = 100.0),
        point(5, activity = ActivityKind.IN_VEHICLE, altitude = 115.0),
        point(10, altitude = 118.0),
      ),
    )
    assertEquals(1, result.rejected.size)
    assertEquals(RejectionReason.VEHICLE, result.rejected.single().reason)
    assertEquals(2, result.accepted.size)
  }
}
