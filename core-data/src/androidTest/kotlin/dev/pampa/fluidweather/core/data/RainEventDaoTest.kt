package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.core.model.RainEventVerification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il magazzino della pioggia su Room vero (in memoria): chiave composta, IGNORE sui doppioni,
 * giudizio prima e pendente dopo, filtri per versione e tempo, scadenza.
 */
@RunWith(AndroidJUnit4::class)
class RainEventDaoTest {

  private lateinit var database: FluidWeatherDatabase
  private lateinit var store: RoomRainEventStore

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Before
  fun apri() {
    database = Room.inMemoryDatabaseBuilder(context, FluidWeatherDatabase::class.java).build()
    store = RoomRainEventStore(database.rainEventDao())
  }

  @After
  fun chiudi() {
    database.close()
  }

  private val ora = 1_781_517_600_000L
  private val ore = 3_600_000L

  private fun pendente(
    roundId: Long = ora,
    providerId: String = RainBoardIds.BAROMETER,
    window: String = "1-3h",
    probability: Double = 0.4,
    modelVersion: String = "v-test",
    tier: String? = "FRESH",
    placeKey: String = "gps",
  ) = RainEventPending(
    placeKey = placeKey,
    latitude = 43.83,
    longitude = 11.2,
    roundId = roundId,
    providerId = providerId,
    window = window,
    issuedAtMillis = roundId,
    probability = probability,
    modelVersion = modelVersion,
    tier = tier,
  )

  private fun giudizio(row: RainEventPending, rained: Boolean = true, sumMm: Double? = 1.5) =
    RainEventVerification(
      prediction = row,
      rained = rained,
      truthSumMm = sumMm,
      truthVoters = 3,
      truthSource = "panel-1",
      settledAtMillis = row.roundId + 30 * ore,
    )

  @Test
  fun una_chiave_pendente_doppia_e_ignorata_e_la_prima_resta() = runBlocking {
    store.addPending(listOf(pendente(probability = 0.4)))
    store.addPending(listOf(pendente(probability = 0.9), pendente(window = "0-1h")))

    val rows = store.pendingIssuedBefore(Long.MAX_VALUE)

    assertEquals(2, rows.size)
    assertEquals(2, store.pendingCount())
    // Il giro riscritto non cambia cio' che era stato detto la prima volta.
    assertEquals(0.4, rows.first { it.window == "1-3h" }.probability, 1e-12)
  }

  @Test
  fun la_stessa_finestra_di_un_altro_giro_un_altro_id_o_un_altro_posto_e_un_altra_riga() = runBlocking {
    store.addPending(
      listOf(
        pendente(),
        pendente(roundId = ora + 1),
        pendente(providerId = RainBoardIds.ALWAYS_ZERO),
        pendente(placeKey = "place-1"),
      ),
    )

    assertEquals(4, store.pendingCount())
  }

  @Test
  fun le_pendenti_si_leggono_dalla_piu_vecchia_con_l_estremo_incluso() = runBlocking {
    store.addPending(listOf(pendente(roundId = ora + 2 * ore), pendente(roundId = ora), pendente(roundId = ora + ore)))

    val fino = store.pendingIssuedBefore(ora + ore)

    assertEquals(listOf(ora, ora + ore), fino.map { it.issuedAtMillis })
  }

  @Test
  fun il_giudizio_scritto_due_volte_lascia_una_verifica_e_nessuna_pendente() = runBlocking {
    val riga = pendente()
    store.addPending(listOf(riga, pendente(window = "0-1h")))

    store.settle(listOf(giudizio(riga)))
    store.settle(listOf(giudizio(riga, rained = false)))

    assertEquals(1, store.verificationCount())
    assertEquals(1, store.pendingCount())
    assertEquals("0-1h", store.pendingIssuedBefore(Long.MAX_VALUE).single().window)
    // IGNORE: il primo giudizio e' quello che vale.
    assertTrue(store.allVerifications().single().rained)
  }

  @Test
  fun un_giudizio_porta_con_se_esito_somma_giudici_e_fonte() = runBlocking {
    val riga = pendente(tier = null)
    store.addPending(listOf(riga))

    store.settle(listOf(giudizio(riga, rained = false, sumMm = null)))

    val v = store.allVerifications().single()
    assertEquals(riga, v.prediction)
    assertEquals(false, v.rained)
    assertNull(v.truthSumMm)
    assertEquals(3, v.truthVoters)
    assertEquals("panel-1", v.truthSource)
    assertEquals(ora + 30 * ore, v.settledAtMillis)
  }

  @Test
  fun le_verifiche_si_filtrano_per_versione_e_per_tempo() = runBlocking {
    val vecchiaVersione = pendente(roundId = ora, modelVersion = "v-vecchia")
    val recente = pendente(roundId = ora + 10 * ore, modelVersion = "v-test")
    val antica = pendente(roundId = ora - 10 * ore, modelVersion = "v-test")
    store.addPending(listOf(vecchiaVersione, recente, antica))
    store.settle(listOf(giudizio(vecchiaVersione), giudizio(recente), giudizio(antica)))

    val soloNuova = store.verifications("v-test", ora)
    val tutta = store.verifications("v-test", 0)

    assertEquals(listOf(ora + 10 * ore), soloNuova.map { it.prediction.roundId })
    assertEquals(listOf(ora - 10 * ore, ora + 10 * ore), tutta.map { it.prediction.roundId })
    assertEquals(3, store.allVerifications().size)
  }

  @Test
  fun la_scadenza_butta_le_pendenti_piu_vecchie_con_l_estremo_escluso() = runBlocking {
    store.addPending(listOf(pendente(roundId = ora), pendente(roundId = ora + ore), pendente(roundId = ora + 2 * ore)))

    val scadute = store.expirePending(ora + ore)

    assertEquals(1, scadute)
    assertEquals(listOf(ora + ore, ora + 2 * ore), store.pendingIssuedBefore(Long.MAX_VALUE).map { it.issuedAtMillis })
  }

  @Test
  fun la_potatura_tiene_sei_mesi_di_giudizi_e_butta_le_pendenti_dimenticate_da_otto_giorni() = runBlocking {
    val adesso = ora + 200L * 24 * ore
    val vecchio = pendente(roundId = ora) // 200 giorni prima: fuori
    val entro = pendente(roundId = adesso - 100L * 24 * ore) // 100 giorni: dentro
    val pendenteVecchia = pendente(roundId = adesso - 9L * 24 * ore, providerId = "x")
    val pendenteFresca = pendente(roundId = adesso - 2L * 24 * ore, providerId = "y")
    store.addPending(listOf(vecchio, entro, pendenteVecchia, pendenteFresca))
    store.settle(listOf(giudizio(vecchio), giudizio(entro)))

    store.prune(adesso)

    assertEquals(listOf(entro.roundId), store.allVerifications().map { it.prediction.roundId })
    assertEquals(listOf("y"), store.pendingIssuedBefore(Long.MAX_VALUE).map { it.providerId })
  }

  @Test
  fun clear_svuota_pendenti_e_verifiche() = runBlocking {
    val riga = pendente()
    store.addPending(listOf(riga, pendente(window = "0-1h")))
    store.settle(listOf(giudizio(riga)))

    store.clear()

    assertEquals(0, store.pendingCount())
    assertEquals(0, store.verificationCount())
  }

  @Test
  fun le_liste_vuote_non_fanno_niente() = runBlocking {
    store.addPending(emptyList())
    store.settle(emptyList())

    assertEquals(0, store.pendingCount())
    assertEquals(0, store.verificationCount())
  }

  @Test
  fun le_pagine_dell_export_coprono_tutti_i_giudizi_una_volta_sola_nell_ordine_della_chiave() = runBlocking {
    // Inseriti in disordine: l'export li vuole per giro, id e finestra, e nessuno ripetuto o saltato.
    val righe = listOf(2, 0, 1).flatMap { giro ->
      listOf("sempre-0", RainBoardIds.BAROMETER).flatMap { id ->
        listOf("3-6h", "0-1h").map { finestra -> giudizio(pendente(roundId = ora + giro * ore, providerId = id, window = finestra)) }
      }
    }
    store.settle(righe)

    val pagine = generateSequence(0) { it + 5 }
      .map { offset -> runBlocking { store.verificationsPage(offset, 5) } }
      .takeWhile { it.isNotEmpty() }
      .toList()

    assertEquals(listOf(5, 5, 2), pagine.map { it.size })
    val lette = pagine.flatten().map { Triple(it.prediction.roundId, it.prediction.providerId, it.prediction.window) }
    val attese = righe.map { Triple(it.prediction.roundId, it.prediction.providerId, it.prediction.window) }
      .sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    assertEquals(attese, lette)
  }
}
