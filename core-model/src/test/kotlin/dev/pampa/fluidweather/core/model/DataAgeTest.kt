package dev.pampa.fluidweather.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L'eta' dei dati dei provider: la sola cosa che l'app puo' dire onestamente quando la rete manca.
 *
 * I confini si provano da entrambi i lati perche' sono numeri con una ragione — tre giri saltati
 * nella modalita' piu' lenta, e il salto da "da quanto" a "di quando" — e un `<` diventato `<=`
 * per sbaglio non si vedrebbe mai a occhio sul telefono.
 */
class DataAgeTest {

  private val adesso = 1_781_517_600_000L

  private fun aEta(millis: Long) = DataAge.of(adesso - millis, adesso)

  @Test
  fun `senza dati non c'e' eta`() {
    assertEquals(DataFreshness.NONE, DataAge.of(null, adesso))
    assertNull(DataAge.ageMillis(null, adesso))
  }

  @Test
  fun `appena preso e' fresco`() {
    assertEquals(DataFreshness.FRESH, aEta(0L))
    assertEquals(DataFreshness.FRESH, aEta(30 * 60_000L))
  }

  @Test
  fun `il confine dei novanta minuti`() {
    assertEquals(DataFreshness.FRESH, aEta(DataAge.STALE_AFTER_MILLIS - 1))
    assertEquals(DataFreshness.STALE, aEta(DataAge.STALE_AFTER_MILLIS))
  }

  @Test
  fun `il confine delle sei ore`() {
    assertEquals(DataFreshness.STALE, aEta(DataAge.VERY_STALE_AFTER_MILLIS - 1))
    assertEquals(DataFreshness.VERY_STALE, aEta(DataAge.VERY_STALE_AFTER_MILLIS))
  }

  @Test
  fun `un dato di tre giorni resta molto vecchio, non sparisce`() {
    // La decisione presa: nessun tetto oltre cui si smette di mostrare. Si mostra e si data.
    assertEquals(DataFreshness.VERY_STALE, aEta(3 * 24 * 3_600_000L))
  }

  @Test
  fun `l'eta' non va mai indietro`() {
    // L'orologio del telefono si sposta (fuso, rete, mano dell'utente): senza il taglio a zero
    // la testata scriverebbe "aggiornato fra due ore".
    assertEquals(0L, DataAge.ageMillis(adesso + 2 * 3_600_000L, adesso))
    assertEquals(DataFreshness.FRESH, DataAge.of(adesso + 2 * 3_600_000L, adesso))
  }

  @Test
  fun `su dati vecchi non si decide niente da soli`() {
    // Mostrare una previsione vecchia dichiarandola e' onesto; farci nascere una notifica no.
    val ore = listOf(FusedHour(adesso, emptyMap(), null))

    assertEquals(ore, DataAge.hoursForDecisions(ore, adesso - 60_000L, adesso))
    assertEquals(emptyList<FusedHour>(), DataAge.hoursForDecisions(ore, adesso - 3 * 3_600_000L, adesso))
    assertEquals(emptyList<FusedHour>(), DataAge.hoursForDecisions(ore, null, adesso))
  }

  @Test
  fun `il confine per decidere e' lo stesso che per la testata`() {
    // Un numero solo, o l'app e il centro notifiche finiscono per non essere d'accordo.
    val ore = listOf(FusedHour(adesso, emptyMap(), null))

    assertEquals(ore, DataAge.hoursForDecisions(ore, adesso - DataAge.STALE_AFTER_MILLIS + 1, adesso))
    assertEquals(emptyList<FusedHour>(), DataAge.hoursForDecisions(ore, adesso - DataAge.STALE_AFTER_MILLIS, adesso))
  }

  @Test
  fun `novanta minuti sono tre giri della modalita' piu' lenta`() {
    // La costante non e' un numero tondo a caso: e' il motivo per cui vale in tutte le modalita'.
    val piuLenta = SamplingMode.entries.maxOf { it.cadenceMinutes }
    assertEquals(30, piuLenta)
    assertEquals(3L, DataAge.STALE_AFTER_MILLIS / (piuLenta * 60_000L))
  }
}
