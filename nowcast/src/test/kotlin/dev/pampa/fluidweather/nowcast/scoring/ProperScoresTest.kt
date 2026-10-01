package dev.pampa.fluidweather.nowcast.scoring

import java.util.Random
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProperScoresTest {

  private fun case(p: Double, occurred: Boolean) = ForecastCase(p, occurred)

  @Test
  fun `brier, log-loss e MAE su valori noti`() {
    assertEquals(0.09, ProperScores.brier(0.7, true), 1e-12)
    assertEquals(0.49, ProperScores.brier(0.7, false), 1e-12)
    assertEquals(ln(2.0), ProperScores.logLoss(0.5, true), 1e-12)
    assertEquals(-ln(0.8), ProperScores.logLoss(0.2, false), 1e-12)
    assertEquals(0.3, ProperScores.absoluteError(0.7, true), 1e-12)

    val cases = listOf(case(0.7, true), case(0.7, false), case(0.0, false), case(1.0, true))
    assertEquals((0.09 + 0.49 + 0.0 + 0.0) / 4, ProperScores.brier(cases), 1e-12)
    assertEquals((0.3 + 0.7 + 0.0 + 0.0) / 4, ProperScores.mae(cases), 1e-12)
    assertEquals(0.5, ProperScores.baseRate(cases), 0.0)
  }

  @Test
  fun `la log-loss e' ritagliata, non infinita`() {
    assertEquals(-ln(1e-6), ProperScores.logLoss(0.0, true), 1e-9)
    assertEquals(-ln(1e-6), ProperScores.logLoss(1.0, false), 1e-9)
    assertEquals(-ln(1 - 1e-6), ProperScores.logLoss(0.0, false), 1e-15)
    assertTrue(ProperScores.logLoss(listOf(case(0.0, true))).isFinite())
  }

  @Test
  fun `niente casi, niente punteggio`() {
    assertTrue(ProperScores.brier(emptyList()).isNaN())
    assertTrue(ProperScores.logLoss(emptyList()).isNaN())
    assertTrue(ProperScores.mae(emptyList()).isNaN())
  }

  @Test
  fun `la skill contro un riferimento`() {
    assertEquals(0.2, ProperScores.bss(0.08, 0.10), 1e-12)
    assertEquals(0.0, ProperScores.bss(0.10, 0.10), 1e-12)
    assertEquals(-1.0, ProperScores.bss(0.20, 0.10), 1e-12)
    assertTrue(ProperScores.bss(0.1, 0.0).isNaN())
  }

  @Test
  fun `la MAE premia la spavalderia, il Brier no`() {
    // Piove il 30% delle volte. Chi dice sempre 0 vince in MAE contro chi dice il 30% vero...
    val truth = List(100) { it < 30 }
    val calibrated = truth.map { case(0.3, it) }
    val never = truth.map { case(0.0, it) }
    assertTrue(ProperScores.mae(never) < ProperScores.mae(calibrated))
    // ...e perde in Brier e log-loss, com'e' giusto.
    assertTrue(ProperScores.brier(calibrated) < ProperScores.brier(never))
    assertTrue(ProperScores.logLoss(calibrated) < ProperScores.logLoss(never))
  }

  @Test
  fun `la curva di affidabilita' salta i gradini vuoti`() {
    val cases = listOf(case(0.05, true), case(0.05, false), case(0.95, true), case(1.0, true))
    val bins = ProperScores.reliability(cases, bins = 10)
    assertEquals(2, bins.size)
    assertEquals(0.0, bins[0].lower, 0.0)
    assertEquals(0.1, bins[0].upper, 1e-12)
    assertEquals(0.5, bins[0].observedFrequency, 0.0)
    assertEquals(2, bins[0].count)
    // L'1,0 finisce nell'ultimo gradino, non in un undicesimo.
    assertEquals(0.9, bins[1].lower, 1e-12)
    assertEquals(0.975, bins[1].meanForecast, 1e-12)
    assertEquals(1.0, bins[1].observedFrequency, 0.0)
  }

  // ------------------------------------------------------------------------- bootstrap

  /** Sessanta giorni, otto casi al giorno, valori a caso. */
  private val random = Random(1)
  private val days: List<Long> = List(480) { 19_000L + it / 8 }
  private val values: List<Double> = List(480) { random.nextDouble() * random.nextDouble() }

  @Test
  fun `stesso elenco, stesso intervallo`() {
    val first = DayBlockBootstrap().summarize(values, days)
    val second = DayBlockBootstrap().summarize(values, days)
    assertEquals(first, second)
    assertNotEquals(first, DayBlockBootstrap(seed = 7).summarize(values, days))
  }

  @Test
  fun `l'intervallo contiene la media, e conta i giorni`() {
    val summary = DayBlockBootstrap().summarize(values, days)
    assertEquals(values.average(), summary.mean, 1e-12)
    assertTrue(summary.low < summary.mean && summary.mean < summary.high)
    assertTrue(summary.standardError > 0.0)
    assertEquals(480, summary.count)
    assertEquals(60, summary.days)
    // L'ordine dei casi non conta.
    val shuffled = values.indices.shuffled(Random(3))
    val again = DayBlockBootstrap().summarize(shuffled.map { values[it] }, shuffled.map { days[it] })
    assertEquals(summary.low, again.low, 1e-12)
    assertEquals(summary.high, again.high, 1e-12)
  }

  @Test
  fun `i casi dello stesso giorno non sono prove indipendenti`() {
    // Venti giorni, dieci casi identici al giorno: meta' giorni tutti 1, meta' tutti 0.
    val clustered = List(200) { if ((it / 10) % 2 == 0) 1.0 else 0.0 }
    val byDay = DayBlockBootstrap().summarize(clustered, List(200) { it / 10L })
    val asIfIndependent = DayBlockBootstrap().summarize(clustered, List(200) { it.toLong() })
    assertEquals(byDay.mean, asIfIndependent.mean, 1e-12)
    // Contare i giorni allarga l'errore standard di circa la radice di dieci.
    assertTrue(byDay.standardError > 2.5 * asIfIndependent.standardError)
  }

  @Test
  fun `la differenza appaiata di due liste uguali e' zero`() {
    val paired = DayBlockBootstrap().paired(values, values, days)
    assertEquals(0.0, paired.mean, 0.0)
    assertEquals(0.0, paired.low, 0.0)
    assertEquals(0.0, paired.high, 0.0)
    assertEquals(0.0, paired.standardError, 0.0)
  }

  @Test
  fun `la differenza appaiata vede uno scarto costante`() {
    val better = values.map { it - 0.01 }
    val paired = DayBlockBootstrap().paired(better, values, days)
    assertEquals(-0.01, paired.mean, 1e-12)
    assertEquals(-0.01, paired.low, 1e-12)
    assertEquals(-0.01, paired.high, 1e-12)
  }

  @Test
  fun `un giorno solo non ha incertezza da ricampionare, e niente casi niente numeri`() {
    val oneDay = DayBlockBootstrap().summarize(listOf(0.1, 0.3), listOf(5L, 5L))
    assertEquals(0.2, oneDay.mean, 1e-12)
    assertEquals(0.2, oneDay.low, 1e-12)
    assertEquals(0.2, oneDay.high, 1e-12)
    assertEquals(1, oneDay.days)
    val empty = DayBlockBootstrap().summarize(emptyList(), emptyList())
    assertTrue(empty.mean.isNaN())
    assertEquals(0, empty.count)
  }

  @Test
  fun `il giorno e' quello UTC`() {
    assertEquals(0L, DayBlockBootstrap.epochDayOf(86_399_999L))
    assertEquals(1L, DayBlockBootstrap.epochDayOf(86_400_000L))
    assertEquals(-1L, DayBlockBootstrap.epochDayOf(-1L))
  }
}
