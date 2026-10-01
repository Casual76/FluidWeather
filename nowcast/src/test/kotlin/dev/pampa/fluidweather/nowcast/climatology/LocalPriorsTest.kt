package dev.pampa.fluidweather.nowcast.climatology

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPriorsTest {

  private val hour = 3_600_000L
  private val tables = PriorsFixtures.tables(0)
  private val pooled = PriorsFixtures.pooled()
  private val local = LocalPriors.local(tables.climatology, tables.baselines, pooled)
  private val global = LocalPriors.pooledOnly(pooled)
  private val issue = Instant.parse("2023-03-10T10:20:00Z").toEpochMilli()
  private val slot = Instant.parse("2023-03-10T09:00:00Z").toEpochMilli()

  @Test
  fun `il locale risponde come le sue tabelle`() {
    assertTrue(local.isLocal)
    for (window in RainWindows.ALL) {
      assertEquals(tables.climatology.rate(window.label, issue)!!, local.climatology(window, issue), 0.0)
      assertEquals(
        tables.baselines.persistence(window.label, issue, 1.0, slot)!!,
        local.persistence(window, issue, 1.0, slot)!!,
        0.0,
      )
      assertEquals(
        tables.baselines.barometric(window.label, issue, -0.8)!!,
        local.barometric(window, issue, -0.8),
        0.0,
      )
    }
  }

  @Test
  fun `il globale risponde come il riferimento di tutti i posti`() {
    assertFalse(global.isLocal)
    val lagBin = LocalBaselines.lagBinOf(LocalBaselines.lagOf(issue, slot))!!
    for (window in RainWindows.ALL) {
      assertEquals(pooled.constantRate(window.label)!!, global.climatology(window, issue), 0.0)
      assertEquals(pooled.persistence(window.label, lagBin, 2)!!, global.persistence(window, issue, 1.0, slot)!!, 0.0)
      assertEquals(pooled.barometric(window.label, 1)!!, global.barometric(window, issue, -0.8), 0.0)
    }
  }

  @Test
  fun `senza tendenza la regola barometrica e' la climatologia, come nel gate`() {
    for (priors in listOf(local, global)) {
      for (window in RainWindows.ALL) {
        val clima = priors.climatology(window, issue)
        assertEquals(clima, priors.barometric(window, issue, null), 0.0)
        assertEquals(clima, priors.barometric(window, issue, Double.NaN), 0.0)
      }
    }
  }

  @Test
  fun `la persistenza non ha risposta senza pioggia di adesso o oltre la fascia piu' vecchia`() {
    for (priors in listOf(local, global)) {
      val window = RainWindows.ZERO_ONE
      assertNull(priors.persistence(window, issue, null, slot))
      assertNull(priors.persistence(window, issue, Double.NaN, slot))
      // Ritardo 13: c'e'; ritardo 14: niente.
      val anchor = RainWindows.anchorOf(issue)
      assertNotNull(priors.persistence(window, issue, 1.0, anchor - 13 * hour))
      assertNull(priors.persistence(window, issue, 1.0, anchor - 14 * hour))
    }
  }

  @Test
  fun `la persistenza locale tiene conto del ritardo`() {
    val anchor = RainWindows.anchorOf(issue)
    val recent = local.persistence(RainWindows.ZERO_ONE, issue, 1.0, anchor - 1 * hour)!!
    val stale = local.persistence(RainWindows.ZERO_ONE, issue, 1.0, anchor - 12 * hour)!!
    assertTrue("$recent contro $stale", recent > stale + 0.2)
  }

  @Test
  fun `lo stato e' una riga e due oggetti con le stesse tabelle la condividono`() {
    val again = LocalPriors.local(tables.climatology, tables.baselines, PriorsFixtures.pooled())
    assertEquals(local.encodedState(), again.encodedState())
    assertTrue(local.encodedState() != global.encodedState())
  }

  @Test
  fun `il locale si decodifica tutto o niente`() {
    val wc1 = tables.climatology.encode()
    val lb2 = tables.baselines.encode()
    val decoded = LocalPriors.decodeLocal(wc1, lb2, pooled)
    assertNotNull(decoded)
    assertTrue(decoded!!.isLocal)
    assertEquals(local.encodedState(), decoded.encodedState())

    // LB1 (il vecchio formato) o WC1 rotta: niente, e non un misto di locale e globale.
    assertNull(LocalPriors.decodeLocal(wc1, lb2.replaceFirst("LB2", "LB1"), pooled))
    assertNull(LocalPriors.decodeLocal(wc1.replaceFirst("WC1", "WC0"), lb2, pooled))
    assertNull(LocalPriors.decodeLocal(wc1, "", pooled))
    // Una climatologia a cui manca una finestra non basta.
    val shortClima = wc1.split("|").filterNot { it.startsWith("3-6h:") }.joinToString("|")
    assertNull(LocalPriors.decodeLocal(shortClima, lb2, pooled))
  }

  @Test
  fun `un riferimento che non conosce le finestre non si accetta`() {
    val empty = PooledPriors.of(listOf(null to null))
    val failure = runCatching { LocalPriors.pooledOnly(empty) }.exceptionOrNull()
    assertTrue(failure is IllegalArgumentException)
    assertTrue(runCatching { LocalPriors.local(tables.climatology, tables.baselines, empty) }.isFailure)
    assertNull(LocalPriors.decodeLocal(tables.climatology.encode(), tables.baselines.encode(), empty))
  }
}
