package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.SamplingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quanto spesso si va davvero in rete.
 *
 * Il baco che questi test tengono chiuso: la sorveglianza chiama il ciclo ogni minuto, la guardia
 * scritta per fermarla era chiavata sul trigger sbagliato e non partiva mai, e per due ore di
 * sorveglianza partivano 120 giri di rete (offline, 120 fallimenti). La soglia ora e' un fatto dei
 * dati, non di chi chiama.
 */
class RefreshBudgetTest {

  private fun cadenza(mode: SamplingMode) = mode.cadenceMinutes * 60_000L

  @Test
  fun `chi tocca esegui adesso ottiene un giro vero`() {
    // Il tasto nelle impostazioni deve fare quello che dice, non restituire la cache.
    assertEquals(0L, RefreshBudget.enoughMillis(CycleTrigger.MANUAL, cadenza(SamplingMode.MASSIMA)))
  }

  @Test
  fun `in massima accuratezza il pavimento batte la cadenza`() {
    // MASSIMA legge il sensore ogni 5 minuti, ma i modelli meteo escono a ore intere: chiedere
    // ogni 5 minuti riotterrebbe lo stesso JSON dodici volte all'ora.
    val enough = RefreshBudget.enoughMillis(CycleTrigger.SAMPLING_PASS, cadenza(SamplingMode.MASSIMA))

    assertEquals(RefreshBudget.MIN_REFRESH_INTERVAL_MILLIS, enough)
    assertTrue(enough > cadenza(SamplingMode.MASSIMA))
  }

  @Test
  fun `in risparmio comanda la cadenza, che e' piu' lenta del pavimento`() {
    assertEquals(
      cadenza(SamplingMode.RISPARMIO),
      RefreshBudget.enoughMillis(CycleTrigger.SAMPLING_PASS, cadenza(SamplingMode.RISPARMIO)),
    )
  }

  @Test
  fun `la sorveglianza non e' un caso speciale`() {
    // Il punto della correzione: non c'e' piu' una regola per la sorveglianza. Vale la stessa di
    // tutti, quindi un chiamante nuovo (il widget, domani) e' protetto senza dover sapere niente.
    val cadenzaMassima = cadenza(SamplingMode.MASSIMA)

    assertEquals(
      RefreshBudget.enoughMillis(CycleTrigger.SAMPLING_PASS, cadenzaMassima),
      RefreshBudget.enoughMillis(CycleTrigger.SURVEILLANCE_TICK, cadenzaMassima),
    )
  }

  @Test
  fun `il riepilogo vuole dati di mezz'ora`() {
    assertEquals(
      RefreshBudget.SUMMARY_FRESH_MILLIS,
      RefreshBudget.enoughMillis(CycleTrigger.DAILY_SUMMARY, cadenza(SamplingMode.RISPARMIO)),
    )
  }

  @Test
  fun `in due ore di sorveglianza i giri passano da centoventi a dodici`() {
    // La misura della correzione, in numeri: la sorveglianza campiona ogni minuto per due ore.
    val enough = RefreshBudget.enoughMillis(CycleTrigger.SURVEILLANCE_TICK, cadenza(SamplingMode.MASSIMA))
    val giriDiRete = 2 * 3_600_000L / enough

    assertEquals(12L, giriDiRete)
  }
}
