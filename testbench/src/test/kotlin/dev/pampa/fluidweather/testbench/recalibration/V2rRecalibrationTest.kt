package dev.pampa.fluidweather.testbench.recalibration

import dev.pampa.fluidweather.nowcast.learning.PlattParams
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.replay.CaseRecord
import dev.pampa.fluidweather.testbench.replay.TierReplayResult
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.SplittableRandom
import kotlin.math.exp
import kotlin.math.ln

class V2rRecalibrationTest {

  /** Casi con esiti estratti da una mappa nota: p_vera = sigma(a0 logit(p) + b0). */
  private fun draw(n: Int, a0: Double, b0: Double, seed: Long = 7): Pair<DoubleArray, BooleanArray> {
    val random = SplittableRandom(seed)
    val p = DoubleArray(n) { 0.02 + 0.96 * random.nextDouble() }
    val y = BooleanArray(n) {
      val z = a0 * ln(p[it] / (1 - p[it])) + b0
      random.nextDouble() < 1 / (1 + exp(-z))
    }
    return p to y
  }

  @Test
  fun `la stima ritrova una mappa nota`() {
    val (p, y) = draw(60_000, a0 = 0.6, b0 = -0.8)
    val fit = RidgePlattFit.fit(p, y)
    assertEquals(0.6, fit.a, 0.03)
    assertEquals(-0.8, fit.b, 0.04)
    assertFalse(fit.clamped)
    assertEquals(60_000, fit.samples)
  }

  @Test
  fun `un modello gia' tarato resta com'e'`() {
    val (p, y) = draw(60_000, a0 = 1.0, b0 = 0.0, seed = 3)
    val fit = RidgePlattFit.fit(p, y)
    assertEquals(1.0, fit.a, 0.05)
    assertEquals(0.0, fit.b, 0.05)
  }

  @Test
  fun `una pendenza fuori dal recinto si ferma al bordo e l'intercetta si ristima`() {
    val (p, y) = draw(40_000, a0 = 6.0, b0 = 0.5, seed = 11)
    val fit = RidgePlattFit.fit(p, y)
    assertTrue(fit.clamped)
    assertEquals(RidgePlattFit.A_MAX, fit.a, 0.0)
    // L'intercetta e' l'ottimo a pendenza fissa: spostarla di poco peggiora la perdita.
    val xs = DoubleArray(p.size) { ln(p[it] / (1 - p[it])) }
    val ys = DoubleArray(y.size) { if (y[it]) 1.0 else 0.0 }
    val best = RidgePlattFit.loss(xs, ys, RidgePlattFit.RIDGE, fit.a, fit.b)
    assertTrue(RidgePlattFit.loss(xs, ys, RidgePlattFit.RIDGE, fit.a, fit.b + 0.01) > best)
    assertTrue(RidgePlattFit.loss(xs, ys, RidgePlattFit.RIDGE, fit.a, fit.b - 0.01) > best)

    val flat = RidgePlattFit.fit(p, BooleanArray(p.size) { it % 5 == 0 })
    assertTrue(flat.clamped)
    assertEquals(RidgePlattFit.A_MIN, flat.a, 0.0)
  }

  @Test
  fun `con pochi casi la cresta tiene la mappa vicino all'identita'`() {
    val (p, y) = draw(12, a0 = 0.3, b0 = -2.0, seed = 5)
    val loose = RidgePlattFit.fit(p, y, ridge = 0.0001)
    val tight = RidgePlattFit.fit(p, y, ridge = 100.0)
    assertTrue(kotlin.math.abs(tight.a - 1) + kotlin.math.abs(tight.b) < kotlin.math.abs(loose.a - 1) + kotlin.math.abs(loose.b))
    assertEquals(RecalibrationFit.IDENTITY, RidgePlattFit.fit(DoubleArray(0), BooleanArray(0)))
  }

  @Test
  fun `la mappa si applica come quella del motore`() {
    val fit = RecalibrationFit(0.49, -0.8, 100, 10, clamped = false)
    for (p in listOf(0.0, 1e-6, 0.05, 0.3, 0.9, 1.0)) assertEquals(PlattParams(0.49, -0.8).apply(p), fit.apply(p), 0.0)
    assertEquals(0.123457, RecalibrationFit(0.1234567, 1.0, 1, 1, false).rounded().a, 0.0)
  }

  @Test
  fun `la versione viene dal v2 su cui poggia`() {
    assertEquals("v2r-2026-09-10", V2rRecalibration.versionFor("v2-2026-09-10"))
    assertTrue(runCatching { V2rRecalibration.versionFor("v3-2026-10-01") }.isFailure)
  }

  @Test
  fun `la stima prende FRESH e STALE insieme, NONE una volta sola, e niente eta' fisse`() {
    assertEquals(true, V2rRecalibration.familyOf(TierKind.FRESH))
    assertEquals(true, V2rRecalibration.familyOf(TierKind.STALE))
    assertEquals(false, V2rRecalibration.familyOf(TierKind.NONE))
    assertEquals(null, V2rRecalibration.familyOf(TierKind.NONE_NOCLIMA))
    assertEquals(null, V2rRecalibration.familyOf(TierKind.STALE_6H))

    val records = TierKind.entries.flatMap { kind ->
      listOf(
        CaseRecord("a", 0L, 0L, kind, 0, panelOutcome = 1, era5Outcome = 0, probabilities = doubleArrayOf(0.4)),
        CaseRecord("a", 0L, 0L, kind, 0, panelOutcome = -1, era5Outcome = 1, probabilities = doubleArrayOf(0.4)),
      )
    }
    val result = TierReplayResult(TierPeriods.TEST, listOf(PredictorNames.MODELLO), records, emptyList(), emptyMap(), emptyMap())
    val samples = V2rRecalibration.collect(listOf(result))
    assertEquals(2, samples.getValue(V2rKey("0-1h", true)).first.size)
    assertEquals(1, samples.getValue(V2rKey("0-1h", false)).first.size)
  }

  @Test
  fun `la stima non vede gli esiti che cadono dopo la fine dei periodi di stima`() {
    // Revisione P3b: l'ultima ancora di VALIDATION (31 agosto, 21:00) giudica la 3-6h fino alle 03 del
    // primo settembre, cioe' con verita' di TEST. Quelle finestre non entrano; le altre si'.
    val end = TierPeriods.VALIDATION.endExclusiveMillis
    val hour = RainWindows.HOUR_MILLIS
    val lastAnchor = end - 3 * hour
    val issue = lastAnchor - 17 * 60_000L
    val records = RainWindows.ALL.indices.map { w ->
      CaseRecord("a", issue, lastAnchor, TierKind.FRESH, w, panelOutcome = 1, era5Outcome = 1, probabilities = doubleArrayOf(0.4))
    }
    val result = TierReplayResult(TierPeriods.VALIDATION, listOf(PredictorNames.MODELLO), records, emptyList(), emptyMap(), emptyMap())

    val bounded = V2rRecalibration.collect(listOf(result), fitUntilMillis = end)
    // 0-1h finisce alle 22, 1-3h a mezzanotte (lo slot 23-24 e' ancora del 31): dentro. 3-6h alle 03 di TEST: fuori.
    assertEquals(1, bounded.getValue(V2rKey("0-1h", true)).first.size)
    assertEquals(1, bounded.getValue(V2rKey("1-3h", true)).first.size)
    assertFalse(V2rKey("3-6h", true) in bounded)

    // Senza limite (il comportamento di prima) la 3-6h c'era.
    assertEquals(1, V2rRecalibration.collect(listOf(result)).getValue(V2rKey("3-6h", true)).first.size)
  }

  @Test
  fun `il file generato e' dati puri con la versione, le sei mappe e la funzione`() {
    val fits = LinkedHashMap<V2rKey, RecalibrationFit>()
    for (hasContext in listOf(true, false)) {
      for (window in RainWindows.ALL) fits[V2rKey(window.label, hasContext)] = RecalibrationFit(0.5, -0.25, 1000, 100, false)
    }
    val table = V2rTable("v2r-2026-09-10", "v2-2026-09-10", fits)
    val samples = mapOf(V2rKey("0-1h", true) to (doubleArrayOf(0.1) to booleanArrayOf(true)))
    val text = V2rRecalibration.emitKotlin(table, samples, listOf(TierPeriods.TRAIN, TierPeriods.VALIDATION))
    assertTrue(text.startsWith("package dev.pampa.fluidweather.nowcast.verdict"))
    assertTrue(text.contains("const val VERSION: String = \"v2r-2026-09-10\""))
    assertTrue(text.contains("const val BASE_MODEL_VERSION: String = \"v2-2026-09-10\""))
    assertEquals(6, Regex("to Coefficients\\(a = 0\\.500000, b = -0\\.250000, samples = 1000\\)").findAll(text).count())
    assertTrue(text.contains("fun apply(windowLabel: String, hasContext: Boolean, probability: Double): Double"))
    assertTrue(text.contains("2022-11-24 al 2025-08-31"))
    assertFalse(text.contains("NowcastEngine("))
  }

  @Test
  fun `la colonna v2r e' la mappa sul grezzo, per famiglia di livello`() {
    val fits = LinkedHashMap<V2rKey, RecalibrationFit>()
    for (window in RainWindows.ALL) {
      fits[V2rKey(window.label, true)] = RecalibrationFit(1.0, 1.0, 1, 1, false)
      fits[V2rKey(window.label, false)] = RecalibrationFit(1.0, -1.0, 1, 1, false)
    }
    val table = V2rTable("v2r-x", "v2-x", fits)
    val records = listOf(TierKind.FRESH, TierKind.NONE_NOCLIMA).map { kind ->
      CaseRecord("a", 0L, 0L, kind, 1, panelOutcome = 1, era5Outcome = 1, probabilities = doubleArrayOf(0.3))
    }
    val result = TierReplayResult(TierPeriod("t", 0L, 1L, 0L), listOf(PredictorNames.MODELLO), records, emptyList(), emptyMap(), emptyMap())
    val with = V2rRecalibration.withV2r(result, table)
    val column = with.indexOf(V2rRecalibration.COLUMN)
    assertEquals(PlattParams(1.0, 1.0).apply(0.3), with.records[0].probabilities[column], 0.0)
    assertEquals(PlattParams(1.0, -1.0).apply(0.3), with.records[1].probabilities[column], 0.0)
    assertEquals(0.3, with.records[1].probabilities[with.indexOf(PredictorNames.MODELLO)], 0.0)
  }
}
