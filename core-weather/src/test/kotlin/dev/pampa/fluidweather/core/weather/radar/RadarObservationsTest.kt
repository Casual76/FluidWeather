package dev.pampa.fluidweather.core.weather.radar

import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import dev.pampa.fluidweather.core.weather.RainObservations
import dev.pampa.fluidweather.core.weather.RainViewerPalette
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'adattatore nowcast usa il lettore neutrale: niente Context Android o rete nei test. */
class RadarObservationsTest {
  private val at = 1_700_000_000_000L
  private val frames = RadarFrames(
    host = "https://radar.invalid",
    generatedMillis = at,
    past = listOf(RadarFrame(at, "/observed")),
    nowcast = listOf(RadarFrame(at + 10 * 60_000L, "/forecast")),
  )

  private fun wetTile(): TileResult {
    val colour = RainViewerPalette.universalBlue.first { it.dbz == 30 }.argb.toInt()
    return TileResult.Ok(Raster(256, 256, IntArray(256 * 256) { colour }))
  }

  @Test
  fun `lettura diretta e adattatore condividono sorgenti e prune del sampler iniettato`() = runBlocking {
    var frameReads = 0
    var prunes = 0
    val sampler = RadarSampler(
      frames = { frameReads++; frames },
      tiles = { url -> if ("/v2/coverage/" in url) TileResult.Ok(Raster(256, 256, IntArray(256 * 256))) else wetTile() },
      clock = { at },
      prune = { prunes++ },
    )

    val reading = (sampler.sample(43.77, 11.25) as RadarSample.Ok).reading
    val observation = RadarObservations.of(sampler, 43.77, 11.25)

    assertEquals(2, frameReads)
    assertEquals(2, prunes)
    assertEquals(
      RainObservation.raining(RadarObservations.millimetresPerHour(reading.atPointDbz), RainObservations.SOURCE_RADAR),
      observation,
    )
  }

  @Test
  fun `una previsione non diventa osservazione quando il suo istante passa`() = runBlocking {
    val sampler = RadarSampler(
      frames = { frames },
      tiles = { url -> if ("/forecast/" in url) wetTile() else TileResult.Ok(Raster(256, 256, IntArray(256 * 256))) },
      // Anche il fotogramma di previsione e' ormai nel passato rispetto all'orologio.
      clock = { at + 15 * 60_000L },
    )

    val reading = (sampler.sample(43.77, 11.25) as RadarSample.Ok).reading
    assertEquals(at, reading.frameTimeMillis)
    assertTrue(reading.nowcastAtPoint.isEmpty())
    assertEquals(RainObservation.dry(RainObservations.SOURCE_RADAR), RadarObservations.of(sampler, 43.77, 11.25))
  }

  @Test
  fun `dati assenti restano unknown e non osservazione asciutta`() = runBlocking {
    val unavailable = RadarSampler(frames = { null }, tiles = { TileResult.Missing })
    val missingTiles = RadarSampler(frames = { frames }, tiles = { TileResult.Missing }, clock = { at })

    assertNull(RadarObservations.of(unavailable, 43.77, 11.25))
    assertNull(RadarObservations.of(missingTiles, 43.77, 11.25))
  }
}
