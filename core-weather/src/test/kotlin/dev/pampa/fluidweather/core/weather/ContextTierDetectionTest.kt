package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.nowcast.features.ContextTier
import org.junit.Assert.assertEquals
import org.junit.Test

/** Il livello di contesto di un verdetto: eta' dal download vero, stesso posto, climatologia. */
class ContextTierDetectionTest {

  private val now = MIDNIGHT + 12 * HOUR
  private val lat = 43.8321
  private val lon = 11.1994

  private fun context(ageMillis: Long, latitude: Double = lat) =
    ForecastBundle("open-meteo", now - ageMillis, latitude, lon, listOf(HourlyPoint(now, temperatureC = 20.0)))

  private fun tier(
    context: ForecastBundle?,
    pointLatitude: Double? = lat,
    pointLongitude: Double? = lon,
    climatology: Boolean = true,
  ) = ContextTierDetection.detect(context, pointLatitude, pointLongitude, now, climatology)

  @Test
  fun `un contesto giovane dello stesso posto e' FRESH`() {
    assertEquals(ContextTier.FRESH, tier(context(10 * 60_000L)))
    assertEquals(ContextTier.FRESH, tier(context(ContextTier.FRESH_MAX_AGE_MILLIS)))
  }

  @Test
  fun `un contesto di qualche ora e' STALE, di piu' di dodici non c'e'`() {
    assertEquals(ContextTier.STALE, tier(context(3 * HOUR)))
    assertEquals(ContextTier.NONE, tier(context(13 * HOUR)))
  }

  @Test
  fun `senza contesto dipende dalla climatologia`() {
    assertEquals(ContextTier.NONE, tier(null))
    assertEquals(ContextTier.NONE_NOCLIMA, tier(null, climatology = false))
    assertEquals(ContextTier.NONE_NOCLIMA, tier(context(13 * HOUR), climatology = false))
  }

  @Test
  fun `un contesto fresco di cinque chilometri piu' in la non e' un contesto`() {
    assertEquals(ContextTier.NONE, tier(context(10 * 60_000L, latitude = 43.8771)))
  }

  @Test
  fun `un punto ignoto non e' lo stesso posto`() {
    assertEquals(ContextTier.NONE, tier(context(10 * 60_000L), pointLatitude = null, pointLongitude = null))
    assertEquals(
      ContextTier.NONE_NOCLIMA,
      tier(context(10 * 60_000L), pointLatitude = lat, pointLongitude = null, climatology = false),
    )
  }

  @Test
  fun `un contesto portato avanti invecchia dal suo download, non dal giro`() {
    // L'istantanea del giro e' di adesso, ma il contesto dentro e' stato scaricato tre ore fa.
    assertEquals(ContextTier.STALE, tier(context(3 * HOUR)))
  }
}
