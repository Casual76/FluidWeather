package dev.pampa.fluidweather.nowcast.climatology

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PooledPriorsTest {

  private val a = PriorsFixtures.tables(0)
  private val b = PriorsFixtures.tables(7)
  private val pooled = PriorsFixtures.pooled()

  /**
   * Il vecchio `PooledReference` del banco, ricopiato com'era (tasso arrotondato a intero, regola
   * barometrica sui conteggi sommati): e' il metro con cui si verifica che il trasloco in `:nowcast`
   * non abbia spostato un numero di NONE_NOCLIMA.
   */
  private class LegacyPooledReference(parts: List<Pair<WindowClimatology?, LocalBaselines?>>) {
    val constants = LinkedHashMap<String, Double>()
    val barometric = LinkedHashMap<String, List<Double>>()

    init {
      for (window in RainWindows.ALL) {
        val label = window.label
        var wet = 0L
        var total = 0L
        for ((climatology, _) in parts) {
          val samples = climatology?.samples(label) ?: 0
          val rate = climatology?.overallRate(label) ?: continue
          total += samples
          wet += (rate * samples).roundToInt()
        }
        if (total == 0L) continue
        val overall = wet.toDouble() / total
        constants[label] = overall
        val classWet = LongArray(LocalBaselines.TREND_CLASSES)
        val classTotal = LongArray(LocalBaselines.TREND_CLASSES)
        for ((_, baselines) in parts) {
          val cells = baselines?.barometricCounts(label) ?: continue
          for ((i, cell) in cells.withIndex()) {
            classWet[i] += cell.wet.toLong()
            classTotal[i] += cell.total.toLong()
          }
        }
        barometric[label] = List(LocalBaselines.TREND_CLASSES) { i ->
          RateCell(classWet[i].toInt(), classTotal[i].toInt()).shrunkToward(overall, LocalBaselines.SHRINKAGE_PSEUDO_COUNTS)
        }
      }
    }
  }

  @Test
  fun `i valori di NONE_NOCLIMA sono quelli del vecchio riferimento, a 1e-12`() {
    val legacy = LegacyPooledReference(listOf(a.climatology to a.baselines, b.climatology to b.baselines))
    for (window in RainWindows.ALL) {
      assertEquals(legacy.constants.getValue(window.label), pooled.constantRate(window.label)!!, 1e-12)
      for (cls in 0 until LocalBaselines.TREND_CLASSES) {
        assertEquals(
          legacy.barometric.getValue(window.label)[cls],
          pooled.barometric(window.label, cls)!!,
          1e-12,
        )
      }
    }
    // E attraverso LocalPriors (la porta che il gate e il modello usano davvero).
    val priors = LocalPriors.pooledOnly(pooled)
    val issue = PriorsFixtures.START + 500 * 3_600_000L
    for (window in RainWindows.ALL) {
      assertEquals(legacy.constants.getValue(window.label), priors.climatology(window, issue), 1e-12)
      for (trend in listOf(-2.0, -0.8, -0.3, 0.0, 0.3, 0.8)) {
        assertEquals(
          legacy.barometric.getValue(window.label)[LocalBaselines.trendClassOf(trend)],
          priors.barometric(window, issue, trend),
          1e-12,
        )
      }
    }
  }

  @Test
  fun `il tasso costante e' la somma dei conteggi, non la media dei tassi`() {
    val label = "1-3h"
    val ca = a.climatology.overallCounts(label)!!
    val cb = b.climatology.overallCounts(label)!!
    assertEquals((ca.wet + cb.wet).toDouble() / (ca.total + cb.total), pooled.constantRate(label)!!, 1e-15)
    assertEquals(RateCell(ca.wet + cb.wet, ca.total + cb.total), pooled.constantCounts(label))
  }

  @Test
  fun `la persistenza del riferimento e' ristretta verso il tasso costante`() {
    val label = "0-1h"
    val prior = pooled.constantRate(label)!!
    for (bin in 0 until LocalBaselines.LAG_BINS) {
      for (cls in 0 until LocalBaselines.RAIN_NOW_CLASSES) {
        val wet = a.baselines.persistenceCounts(label, bin)!![cls].wet + b.baselines.persistenceCounts(label, bin)!![cls].wet
        val total = a.baselines.persistenceCounts(label, bin)!![cls].total + b.baselines.persistenceCounts(label, bin)!![cls].total
        assertEquals((wet + 40 * prior) / (total + 40), pooled.persistence(label, bin, cls)!!, 1e-12)
      }
    }
  }

  @Test
  fun `la riga PP1 torna identica`() {
    val text = pooled.encode()
    assertTrue(text.startsWith("PP1|40|C0-1h:"))
    val decoded = PooledPriors.decode(text)
    assertNotNull(decoded)
    assertEquals(text, decoded!!.encode())
    for (window in RainWindows.ALL) {
      assertEquals(pooled.constantRate(window.label), decoded.constantRate(window.label))
      assertEquals(pooled.barometric(window.label, 1), decoded.barometric(window.label, 1))
      assertEquals(pooled.persistence(window.label, 2, 2), decoded.persistence(window.label, 2, 2))
    }
    assertTrue(decoded.covers())
  }

  @Test
  fun `le righe rotte, incomplete o di un altro formato non si leggono`() {
    val text = pooled.encode()
    assertNull(PooledPriors.decode(text.replaceFirst("PP1", "PP0")))
    assertNull(PooledPriors.decode("PP1"))
    assertNull(PooledPriors.decode(text.substringBeforeLast(",")))
    val withoutRule = text.split("|").filterNot { it.startsWith("T1-3h:") }.joinToString("|")
    assertNull(PooledPriors.decode(withoutRule))
    val withoutBin = text.split("|").filterNot { it.startsWith("P3-6h@1:") }.joinToString("|")
    assertNull(PooledPriors.decode(withoutBin))
    val withoutConstant = text.split("|").filterNot { it.startsWith("C0-1h:") }.joinToString("|")
    assertNull(PooledPriors.decode(withoutConstant))
    assertNull(PooledPriors.decode("$text|${text.split("|").first { it.startsWith("C0-1h:") }}"))
  }

  @Test
  fun `senza storia il riferimento e' vuoto e non copre le finestre`() {
    val empty = PooledPriors.of(listOf(null to null))
    assertFalse(empty.covers())
    assertNull(empty.constantRate("0-1h"))
    assertNull(empty.barometric("0-1h", 0))
    assertNull(empty.persistence("0-1h", 0, 0))
    // Una sola localita' con storia e' gia' un riferimento.
    val single = PooledPriors.of(listOf(a.climatology to a.baselines, null to null))
    assertTrue(single.covers())
    assertEquals(a.climatology.overallRate("0-1h")!!, single.constantRate("0-1h")!!, 1e-15)
  }
}
