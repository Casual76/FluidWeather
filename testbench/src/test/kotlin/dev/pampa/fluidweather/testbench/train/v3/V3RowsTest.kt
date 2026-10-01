package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.testbench.replay.TruthKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V3RowsTest {

  private val locations = listOf("a", "b")
  private val n = FeatureExtractorV3.COUNT

  private fun rows(count: Int, offset: Int = 0, locationIndex: Int = 0): V3Rows {
    val writer = V3RowsWriter(locations)
    for (i in 0 until count) {
      val features = DoubleArray(n) { column -> if (column == 7) Double.NaN else column + 0.5 + (if (column == 3) i else 0) }
      writer.add(
        location = locationIndex,
        tierOrdinal = (i + offset) % 4,
        t0Millis = 86_400_000L * (i + offset),
        issueMillis = 86_400_000L * (i + offset) - 1_000L,
        featureValues = features,
        panelLabels = intArrayOf((i / 4) % 2, -1, 1),
        era5Labels = intArrayOf(1, 0, -1),
        flagBits = V3Rows.FLAG_HISTORY_KNOWN,
      )
    }
    return writer.build()
  }

  @Test
  fun `le colonne tornano quello che si e' scritto, NaN compreso`() {
    val r = rows(10)
    assertEquals(10, r.size)
    assertEquals(3.5f + 4f, r.feature(4, 3), 0f)
    assertTrue(r.feature(0, 7).isNaN())
    assertEquals(ContextTier.entries[2], r.tierOf(2))
    assertEquals("a", r.locationOf(0))
    assertEquals(0, r.label(0, 0))
    assertEquals(1, r.label(4, 0))
    assertEquals(-1, r.label(0, 1))
    assertEquals(-1, r.label(0, 2, TruthKind.ERA5))
    assertEquals(1, r.label(0, 0, TruthKind.ERA5))
    assertEquals(3, r.epochDay(3))
    assertTrue(r.historyKnown(0))
    assertFalse(r.localPriors(0))
    val row = r.featureRow(2)
    assertEquals(n, row.size)
    assertEquals(2.5, row[2], 0.0)
    assertTrue(row[7].isNaN())
  }

  @Test
  fun `il cantiere cresce oltre la capacita' iniziale senza perdere righe`() {
    val r = rows(3000)
    assertEquals(3000, r.size)
    assertEquals(3.5f + 2999f, r.feature(2999, 3), 0f)
    assertEquals(2999, r.epochDay(2999))
  }

  @Test
  fun `concat mette le parti una dopo l'altra`() {
    val a = rows(5)
    val b = rows(4, offset = 100, locationIndex = 1)
    val all = V3Rows.concat(locations, listOf(a, b))
    assertEquals(9, all.size)
    assertEquals("a", all.locationOf(4))
    assertEquals("b", all.locationOf(5))
    assertEquals(b.feature(2, 3), all.feature(7, 3), 0f)
    assertEquals(b.t0[3], all.t0[8])
    assertEquals(0, V3Rows.concat(locations, emptyList()).size)
    assertTrue(runCatching { V3Rows.concat(locations, listOf(a, V3Rows.empty(listOf("x")))) }.isFailure)
  }

  @Test
  fun `select tiene le righe scelte nello stesso ordine`() {
    val r = rows(20)
    val even = r.select { it % 2 == 0 }
    assertEquals(10, even.size)
    for (i in 0 until even.size) {
      assertEquals(r.t0[2 * i], even.t0[i])
      assertEquals(r.feature(2 * i, 3), even.feature(i, 3), 0f)
      assertEquals(r.label(2 * i, 0), even.label(i, 0))
      assertEquals(r.tierOf(2 * i), even.tierOf(i))
    }
  }

  @Test
  fun `i conteggi di finestra e le colonne costanti`() {
    val r = rows(40)
    assertEquals(10, r.count(ContextTier.FRESH))
    // Finestra 0: la meta' bagnata; finestra 1: mai giudicata; finestra 2: sempre bagnata.
    assertEquals(10 to 5, r.labelCounts(ContextTier.FRESH, 0))
    assertEquals(0 to 0, r.labelCounts(ContextTier.FRESH, 1))
    assertEquals(10 to 10, r.labelCounts(ContextTier.FRESH, 2))
    val constant = r.constantColumns(ContextTier.FRESH)
    // La colonna 3 varia, la 7 e' tutta NaN (costante per definizione), le altre sono costanti.
    assertFalse(3 in constant)
    assertTrue(7 in constant)
    assertTrue(0 in constant && (n - 1) in constant)
    assertEquals(n - 1, constant.size)
  }

  @Test
  fun `una colonna con qualche NaN e un solo valore e' costante`() {
    val writer = V3RowsWriter(locations)
    for (i in 0 until 6) {
      val features = DoubleArray(n) { 1.0 }
      if (i % 2 == 0) features[5] = Double.NaN
      writer.add(0, 0, i * 86_400_000L, i * 86_400_000L, features, intArrayOf(0, 0, 0), intArrayOf(0, 0, 0), 0)
    }
    val r = writer.build()
    assertTrue(5 in r.constantColumns(ContextTier.FRESH))
  }
}
