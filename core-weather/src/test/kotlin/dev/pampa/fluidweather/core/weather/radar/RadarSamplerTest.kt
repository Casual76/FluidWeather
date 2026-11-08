package dev.pampa.fluidweather.core.weather.radar

import dev.pampa.fluidweather.core.weather.RadarFrame
import dev.pampa.fluidweather.core.weather.RadarFrames
import dev.pampa.fluidweather.core.weather.RainViewerPalette
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il campionatore intero su un mondo finto: un `TileSource` che disegna, per ogni fotogramma,
 * una cella di pioggia che avanza da ovest verso il punto. Niente PNG: i raster nascono gia'
 * decodificati, con i colori esatti della palette.
 */
class RadarSamplerTest {

  private val lat = 43.77
  private val lon = 11.25
  private val zoom = RadarSampler.ZOOM
  private val frameStep = 10 * 60_000L
  private val t0 = 1_700_000_000_000L

  private fun argbFor(dbz: Int): Int = RainViewerPalette.universalBlue.lastOrNull { it.dbz <= dbz && dbz >= 5 }?.argb?.toInt() ?: 0

  /** Il mondo: una cella gaussiana (picco 40 dBZ) che a t0 sta 60 px a ovest del punto e avanza di 5 px per fotogramma. */
  private inner class World : TileSource {
    val requests = mutableListOf<String>()
    private val centre = TileMath.toGlobalPixel(lat, lon, zoom)

    override suspend fun raster(url: String): TileResult {
      requests += url
      if ("/v2/coverage/" in url) return TileResult.Ok(Raster(256, 256, IntArray(256 * 256)))
      val match = Regex("/(\\d+)/(\\d+)/(\\d+)/(\\d+)/2/0_0\\.png$").find(url) ?: return TileResult.Missing
      val (size, z, x, y) = match.destructured
      require(size == "256" && z.toInt() == zoom)
      val frameIndex = Regex("frame(-?\\d+)").find(url)!!.groupValues[1].toInt()
      val blobX = centre.x - 60 + 5 * frameIndex
      val blobY = centre.y
      val pixels = IntArray(256 * 256)
      for (py in 0 until 256) for (px in 0 until 256) {
        val gx = x.toInt() * 256 + px
        val gy = y.toInt() * 256 + py
        val d2 = (gx - blobX) * (gx - blobX) + (gy - blobY) * (gy - blobY)
        val dbz = ((40 * exp(-d2 / (2 * 5.0 * 5.0))).roundToInt() / 5) * 5
        pixels[py * 256 + px] = argbFor(dbz)
      }
      return TileResult.Ok(Raster(256, 256, pixels))
    }
  }

  private fun frames(pastCount: Int, nowcastCount: Int): RadarFrames {
    val past = (0 until pastCount).map { i -> RadarFrame(t0 + i * frameStep, "/v2/radar/frame$i") }
    val nowcast = (0 until nowcastCount).map { i -> RadarFrame(t0 + (pastCount + i) * frameStep, "/v2/radar/frame${pastCount + i}") }
    return RadarFrames(host = "https://tilecache.rainviewer.com", generatedMillis = t0, past = past, nowcast = nowcast)
  }

  @Test
  fun `una cella che avanza da ovest ha moto verso est, ETA plausibile e confidenza decente`() = runBlocking {
    val world = World()
    val frames = frames(pastCount = 7, nowcastCount = 3)
    val sampler = RadarSampler(frames = { frames }, tiles = world, clock = { t0 + 6 * frameStep + 3 * 60_000L })
    val sample = sampler.sample(lat, lon)
    assertTrue("atteso Ok, avuto $sample", sample is RadarSample.Ok)
    val reading = (sample as RadarSample.Ok).reading
    assertEquals(3, reading.frameAgeMin)
    assertTrue("il punto e' ancora asciutto: ${reading.atPointDbz}", reading.atPointDbz < 10)
    val motion = reading.motion
    assertNotNull("moto atteso", motion)
    assertEquals(90.0, motion!!.towardDeg, 15.0)
    // Cinque pixel per fotogramma: ~13 km/h a zoom 8, ~26 a zoom 7 dove un pixel e' il doppio.
    assertTrue("velocita' ${motion.speedKmh}", motion.speedKmh in 10.0..45.0)
    val eta = reading.eta
    assertNotNull("eta attesa", eta)
    // A frame 6 la cella sta 30 px a ovest e avanza di 5 px ogni 10 minuti: un'ora all'arrivo.
    assertTrue("arrivo atteso ~60 min, avuto ${eta!!.arrivesInMin}", eta.arrivesInMin != null && eta.arrivesInMin!! in 40..80)
    assertNotNull(reading.nearest)
    assertTrue(reading.nearest!!.bearingDeg in 240..300)
    assertTrue("confidenza ${reading.confidence}", reading.confidence >= 0.4)
    assertTrue(reading.nowcastAtPoint.isEmpty())
    assertTrue(reading.requestsAreBounded(world.requests.size, frames))
  }

  @Test
  fun `non chiede mai un livello che RainViewer non disegna`() = runBlocking {
    val world = World()
    val frames = frames(pastCount = 7, nowcastCount = 3)
    RadarSampler(frames = { frames }, tiles = world, clock = { t0 + 6 * frameStep + 3 * 60_000L }).sample(lat, lon)

    assertTrue("nessuna richiesta?", world.requests.isNotEmpty())
    val zooms = world.requests.map { Regex("/256/(\\d+)/").find(it)!!.groupValues[1].toInt() }
    // Oltre il 7 il servizio risponde con un'immagine grigia "Zoom Level Not Supported" (200, non 404).
    assertTrue("zoom chiesti: ${zooms.toSet()}", zooms.all { it <= RadarFrames.MAX_ZOOM })
    assertEquals(7, RadarFrames.MAX_ZOOM)
    assertEquals(RadarFrames.MAX_ZOOM, RadarSampler.ZOOM)
  }

  private fun RadarReading.requestsAreBounded(requests: Int, frames: RadarFrames): Boolean =
    requests <= (RadarSampler.PAST_FRAMES + RadarSampler.NOWCAST_FRAMES) * 4

  @Test
  fun `senza fotogrammi il radar non e' disponibile`() = runBlocking {
    val sampler = RadarSampler(frames = { null }, tiles = World())
    assertTrue(sampler.sample(lat, lon) is RadarSample.Unavailable)
  }

  @Test
  fun `tile del centro mancante vuol dire non disponibile, non un crash`() = runBlocking {
    val frames = frames(pastCount = 3, nowcastCount = 0)
    val sampler = RadarSampler(frames = { frames }, tiles = { TileResult.Missing })
    assertTrue(sampler.sample(lat, lon) is RadarSample.Unavailable)
  }

  @Test
  fun `tile 404 non provano assenza eco`() = runBlocking {
    val frames = frames(pastCount = 3, nowcastCount = 1)
    val sampler = RadarSampler(frames = { frames }, tiles = { url ->
      if ("/v2/coverage/" in url) TileResult.Ok(Raster(256, 256, IntArray(256 * 256))) else TileResult.Empty
    }, clock = { t0 + 3 * frameStep })
    assertTrue(sampler.sample(lat, lon) is RadarSample.Unavailable)
  }
}
