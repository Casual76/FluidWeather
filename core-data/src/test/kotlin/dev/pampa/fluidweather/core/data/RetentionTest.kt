package dev.pampa.fluidweather.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le finestre di ritenzione degli archivi.
 *
 * Le quattro funzioni di potatura erano tutte scritte e **nessuna aveva un chiamante**:
 * `pressure_samples` cresceva per sempre, ed e' proprio l'archivio su cui vive l'offline. Qui si
 * verifica che la potatura tagli dove dice di tagliare, e che i numeri scelti abbiano ancora il
 * loro motivo se qualcuno li tocca.
 */
class RetentionTest {

  private val adesso = 1_781_517_600_000L
  private val giorno = 24 * 3_600_000L

  /** Registra solo l'ultimo taglio chiesto: e' tutto quello che serve sapere. */
  private class DaoSpia : PressureDao {
    var tagliatoPrimaDi: Long? = null
    override suspend fun insertAll(samples: List<PressureSampleEntity>) = Unit
    override suspend fun samplesSince(sinceMillis: Long): List<PressureSampleEntity> = emptyList()
    override fun latest(limit: Int): Flow<List<PressureSampleEntity>> = flowOf(emptyList())
    override fun count(): Flow<Long> = flowOf(0L)
    override suspend fun deleteOlderThan(beforeMillis: Long) {
      tagliatoPrimaDi = beforeMillis
    }
  }

  @Test
  fun `la potatura taglia a trenta giorni`() = runBlocking {
    val dao = DaoSpia()

    PressureRepository(dao).prune(adesso)

    assertEquals(adesso - 30 * giorno, dao.tagliatoPrimaDi)
  }

  @Test
  fun `svuotare e' un'altra cosa dal potare`() = runBlocking {
    // "Dati e privacy" cancella tutto; la potatura tiene la finestra. Confonderle vorrebbe dire
    // perdere l'archivio a ogni riepilogo giornaliero.
    val dao = DaoSpia()

    PressureRepository(dao).clear()

    assertEquals(Long.MAX_VALUE, dao.tagliatoPrimaDi)
  }

  @Test
  fun `trenta giorni coprono quattro volte la finestra piu' lunga che l'app chiede`() {
    // La piu' lunga e' la pagina Pressione: sette giorni. Se un domani qualcuno la allunga, questo
    // test dice che la ritenzione va allungata con lei invece di scoprirlo da un grafico vuoto.
    val finestraPiuLunga = 7 * giorno

    assertTrue(PressureRepository.KEEP_MILLIS >= 4 * finestraPiuLunga)
  }

  @Test
  fun `sei mesi di verifiche sono aritmeticamente invisibili alla classifica`() {
    // Il punteggio dei provider decade con emivita quattordici giorni: a centottanta giorni una
    // verifica pesa 0,5^12,9. Se qualcuno accorciasse la ritenzione, il peso buttato crescerebbe.
    val emivitaGiorni = 14.0
    val giorniTenuti = RoomVerificationStore.KEEP_MILLIS / giorno.toDouble()
    val pesoResiduo = Math.pow(0.5, giorniTenuti / emivitaGiorni)

    assertTrue("si buttano verifiche che pesano ancora: $pesoResiduo", pesoResiduo < 0.001)
  }

  @Test
  fun `l'archivio dell'apprendimento tiene molto piu' dei campioni grezzi`() {
    // La memoria lunga dell'app sta nei vettori gia' ridotti, non nelle letture a 1 Hz.
    assertTrue(LearningRepository.KEEP_MILLIS > PressureRepository.KEEP_MILLIS * 20)
  }

  @Test
  fun `lo storico dei verdetti tiene due mesi`() {
    assertEquals(60L, NowcastHistoryStore.KEEP_MILLIS / giorno)
  }
}
