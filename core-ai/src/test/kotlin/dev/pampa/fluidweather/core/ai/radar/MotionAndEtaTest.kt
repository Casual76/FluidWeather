package dev.pampa.fluidweather.core.ai.radar

import kotlin.math.exp
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Finestre sintetiche: una o piu' macchie gaussiane di riflettivita' su fondo asciutto. */
internal object SyntheticRadar {
  fun window(size: Int = 128, timeMillis: Long = 0L, blobs: List<Triple<Double, Double, Int>> = emptyList(), sigma: Double = 6.0): RadarWindow {
    val dbz = IntArray(size * size) { 0 }
    for (y in 0 until size) for (x in 0 until size) {
      var best = 0
      blobs.forEach { (bx, by, peak) ->
        val d2 = (x - bx) * (x - bx) + (y - by) * (y - by)
        val v = (peak * exp(-d2 / (2 * sigma * sigma))).roundToInt()
        val quantised = (v / 5) * 5
        if (quantised > best) best = quantised
      }
      dbz[y * size + x] = best
    }
    return RadarWindow(size, dbz, timeMillis)
  }
}

class MotionAndEtaTest {

  @Test
  fun `una macchia spostata di (6, -3) pixel si ritrova`() {
    val c = 64.0
    val previous = SyntheticRadar.window(blobs = listOf(Triple(c - 6, c + 3, 45)))
    val next = SyntheticRadar.window(blobs = listOf(Triple(c, c, 45)))
    val pair = MotionEstimator.pairMotion(previous, next)
    assertNotNull(pair)
    assertEquals(6.0, pair!!.dx, 0.6)
    assertEquals(-3.0, pair.dy, 0.6)
    assertTrue("psr troppo basso: ${pair.psr}", pair.psr > 3.0)
    assertTrue(!pair.atSearchEdge)
  }

  @Test
  fun `senza eco nel template non c'e' moto, e la combinazione lo dice`() {
    val empty = SyntheticRadar.window()
    assertNull(MotionEstimator.pairMotion(empty, empty))
    assertNull(MotionEstimator.combine(emptyList(), 440.0))
  }

  @Test
  fun `il moto combinato ha velocita' e direzione meteorologica`() {
    // 8 px in 10 minuti verso est a 440 m/px: 3,52 km in 10 min = 21 km/h, da ovest.
    val pair = PairMotion(dx = 8.0, dy = 0.0, psr = 6.0, atSearchEdge = false, echoFraction = 0.2)
    val motion = MotionEstimator.combine(listOf(pair, pair), 440.0)!!
    assertEquals(21.1, motion.speedKmh, 0.3)
    assertEquals(90.0, motion.towardDeg, 1.0)
    assertEquals(270.0, motion.fromDeg, 1.0)
    assertTrue(motion.agreement)
    assertEquals(2, motion.pairsUsed)
  }

  @Test
  fun `coppie in disaccordo si notano`() {
    val east = PairMotion(8.0, 0.0, 6.0, false, 0.2)
    val south = PairMotion(0.0, 8.0, 6.0, false, 0.2)
    assertTrue(!MotionEstimator.combine(listOf(east, south), 440.0)!!.agreement)
  }

  @Test
  fun `una cella 20 px a ovest che avanza di 4 px ogni 10 minuti arriva in 50 minuti`() {
    val c = 64.0
    val now = SyntheticRadar.window(blobs = listOf(Triple(c - 20, c, 35)), sigma = 3.0)
    val motion = Motion(speedKmh = 10.0, fromDeg = 270.0, towardDeg = 90.0, pairsUsed = 2, meanPsr = 5.0, agreement = true, atSearchEdge = false, dxPxPer10Min = 4.0, dyPxPer10Min = 0.0)
    val eta = EtaEstimator.estimate(now, motion, rainingNow = false)
    assertNotNull(eta.arrivesInMin)
    assertTrue("atteso ~50, avuto ${eta.arrivesInMin}", eta.arrivesInMin!! in 35..55)
    assertTrue((eta.expectedDbz ?: 0) >= 25)
    assertNull(eta.endsInMin)
  }

  @Test
  fun `quando piove ora e la cella se ne va, si stima la fine`() {
    val c = 64.0
    val now = SyntheticRadar.window(blobs = listOf(Triple(c, c, 35)), sigma = 3.0)
    val motion = Motion(10.0, 270.0, 90.0, 2, 5.0, true, false, dxPxPer10Min = 4.0, dyPxPer10Min = 0.0)
    val eta = EtaEstimator.estimate(now, motion, rainingNow = true)
    assertNotNull(eta.endsInMin)
    assertTrue("fine attesa entro 30 min, avuta ${eta.endsInMin}", eta.endsInMin!! in 5..30)
  }

  @Test
  fun `l'eco piu' vicino, la copertura e la tendenza`() {
    val c = 64.0
    val now = SyntheticRadar.window(blobs = listOf(Triple(c + 10, c, 40)), sigma = 2.0)
    val nearest = EtaEstimator.nearest(now, 68, 440.0)!!
    assertTrue(nearest.km in 1.5..4.5)
    assertTrue("rilevamento ${nearest.bearingDeg}", kotlin.math.abs(nearest.bearingDeg - 90) <= 5)
    assertTrue("dBZ vicino all'eco: ${nearest.maxDbz}", nearest.maxDbz >= 30)
    val (fraction, max) = EtaEstimator.coverage(now, 68)
    assertTrue(fraction > 0 && fraction < 0.2)
    assertEquals(40, max)
    assertEquals(2.0, EtaEstimator.slope(listOf(1.0, 3.0, 5.0, 7.0)), 1e-9)
  }
}
