package dev.pampa.fluidweather.nowcast.learning

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlattCalibrationTest {

  /** Coppie in cui la vera probabilita' e' sigma(slope * logit(p) + shift): una distorsione nota. */
  private fun distorted(n: Int, slope: Double, shift: Double, seed: Long = 11): List<CalibrationSample> {
    val random = Random(seed)
    return List(n) {
      val p = 0.05 + 0.9 * random.nextDouble()
      val truth = PlattParams.sigmoid(slope * PlattParams.logit(p) + shift)
      CalibrationSample(p, random.nextDouble() < truth)
    }
  }

  private fun balanced(wet: Int, dry: Int): List<CalibrationSample> =
    List(wet) { CalibrationSample(0.3 + 0.004 * it, true) } + List(dry) { CalibrationSample(0.2 + 0.004 * it, false) }

  @Test
  fun `novantanove verifiche non bastano, e nemmeno nove con pioggia o nove senza`() {
    assertEquals(PlattStatus.TOO_FEW_SAMPLES, PlattCalibration.fitWithRules(balanced(50, 49)).status)
    assertEquals(PlattStatus.TOO_FEW_WET, PlattCalibration.fitWithRules(balanced(9, 91)).status)
    assertEquals(PlattStatus.TOO_FEW_DRY, PlattCalibration.fitWithRules(balanced(91, 9)).status)
    val rejected = PlattCalibration.fitWithRules(balanced(9, 91))
    assertNull(rejected.params)
    assertEquals(9, rejected.wet)
    assertEquals(91, rejected.dry)

    val accepted = PlattCalibration.fitWithRules(balanced(50, 50))
    assertNotNull(accepted.params)
    assertEquals(PlattStatus.ACTIVE, accepted.status)
  }

  @Test
  fun `una pendenza vera di quattro si ferma a due, e b e' ricalcolata per quella a`() {
    val samples = distorted(4000, slope = 4.0, shift = 0.0)
    val fit = PlattCalibration.fitWithRules(samples)
    val params = fit.params!!
    assertEquals(2.0, params.a, 0.0)

    // Con a fissa a 2 il gradiente di b (esiti ammorbiditi + penalita') e' ~0: b e' davvero quella giusta.
    val rules = PlattFitRules.DEFAULT
    val wet = samples.count { it.rained }
    val dry = samples.size - wet
    var gradient = rules.ridge * params.b
    for (sample in samples) {
      val target = if (sample.rained) (wet + 1.0) / (wet + 2.0) else 1.0 / (dry + 2.0)
      gradient += PlattParams.sigmoid(params.a * PlattParams.logit(sample.probability) + params.b) - target
    }
    assertEquals(0.0, gradient, 1e-4)
  }

  @Test
  fun `una pendenza vera di 0,2 si ferma a 0,5`() {
    val fit = PlattCalibration.fitWithRules(distorted(4000, slope = 0.2, shift = 0.0))
    assertEquals(0.5, fit.params!!.a, 0.0)
  }

  @Test
  fun `una pendenza dentro il recinto non viene toccata`() {
    val fit = PlattCalibration.fitWithRules(distorted(6000, slope = 1.3, shift = 0.0))
    assertTrue("a = ${fit.params!!.a}", fit.params!!.a in 1.1..1.5)
  }

  @Test
  fun `la penalita' accorcia l'intercetta del 20 per cento a cento verifiche e del 5 per cento a cinquemila`() {
    // Il grezzo sovrastima di un logit intero: senza penalita' b ~ -1; con la penalita' meno, tanto meno quanti piu' dati.
    val noRidge = PlattFitRules(ridge = 0.0, maxSlope = 100.0, minSlope = 0.0)
    val small = distorted(200, slope = 1.0, shift = -1.0, seed = 5).take(100)
    val large = distorted(5000, slope = 1.0, shift = -1.0, seed = 5)

    val smallRidge = PlattCalibration.fitWithRules(small).params!!
    val smallFree = PlattCalibration.fitWithRules(small, noRidge).params!!
    val largeRidge = PlattCalibration.fitWithRules(large).params!!
    val largeFree = PlattCalibration.fitWithRules(large, noRidge).params!!

    val smallShrink = 1.0 - abs(smallRidge.b) / abs(smallFree.b)
    val largeShrink = 1.0 - abs(largeRidge.b) / abs(largeFree.b)
    assertTrue("accorciamento a n=100: $smallShrink", smallShrink >= 0.20)
    assertTrue("accorciamento a n=5000: $largeShrink", largeShrink in 0.0..0.05)
  }

  @Test
  fun `fit di sempre, quello del banco, e' rimasto com'era`() {
    // Dati senza generatore casuale e numeri attesi calcolati con la stessa procedura (Newton, ridge 0,05,
    // esiti ammorbiditi): se qualcuno tocca `fit`, il banco cambia di cifra e qui lo si vede.
    val samples = List(400) { i ->
      val p = 0.05 + 0.9 * ((i * 7919) % 400) / 399.0
      val truth = PlattParams.sigmoid(PlattParams.logit(p) - 0.5)
      CalibrationSample(p, ((i * 104729) % 1000) / 1000.0 < truth)
    }
    val params = PlattCalibration.fit(samples)!!
    assertEquals(0.9281504676900942, params.a, 1e-6)
    assertEquals(-0.4819224777848737, params.b, 1e-6)
    assertNull(PlattCalibration.fit(samples.take(PlattCalibration.MIN_SAMPLES - 1)))
    assertNotNull(PlattCalibration.fit(samples.take(PlattCalibration.MIN_SAMPLES)))
    assertEquals(30, PlattCalibration.MIN_SAMPLES)
  }
}
