package dev.pampa.fluidweather.core.sensor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il posto da cui si chiede un fix al `LocationManager`: dopo Google Play Services la scelta dei
 * provider e' dell'app, e questi test dicono quale e perche'. Tutto senza Android: i provider sono
 * stringhe e il tempo e' virtuale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationPlanTest {

  private val fused = LocationPlan.FUSED
  private val network = LocationPlan.NETWORK
  private val gps = LocationPlan.GPS
  private val passive = LocationPlan.PASSIVE

  @Test
  fun `il basso consumo va per primo e il GPS dopo, col suo pezzo del tempo`() {
    val stages = LocationPlan.freshStages(setOf(network, gps, passive), fineGranted = true)

    assertEquals(listOf(listOf(network), listOf(gps)), stages.map { it.providers })
    assertEquals(LocationPlan.LOW_POWER_SHARE, stages[0].share, 1e-9)
    assertEquals(1.0 - LocationPlan.LOW_POWER_SHARE, stages[1].share, 1e-9)
    assertEquals(1.0, stages.sumOf { it.share }, 1e-9)
  }

  @Test
  fun `dove c'e' un servizio fused e' del basso consumo insieme alla rete`() {
    val stages = LocationPlan.freshStages(setOf(fused, network, gps), fineGranted = true)

    assertEquals(listOf(fused, network), stages.first().providers)
  }

  @Test
  fun `col solo permesso approssimato il GPS non si chiede mai`() {
    val stages = LocationPlan.freshStages(setOf(network, gps), fineGranted = false)

    assertEquals(listOf(listOf(network)), stages.map { it.providers })
    assertEquals(1.0, stages.single().share, 1e-9)
  }

  @Test
  fun `senza rete il GPS ha tutto il tempo`() {
    val stages = LocationPlan.freshStages(setOf(gps), fineGranted = true)

    assertEquals(listOf(listOf(gps)), stages.map { it.providers })
    assertEquals(1.0, stages.single().share, 1e-9)
  }

  @Test
  fun `con la posizione spenta nelle impostazioni il fix nuovo non si prova`() {
    assertTrue(LocationPlan.freshStages(emptySet(), fineGranted = true).isEmpty())
    assertTrue(LocationPlan.freshStages(setOf(passive), fineGranted = true).isEmpty())
    // Col solo approssimato e il solo GPS acceso: niente da chiedere.
    assertTrue(LocationPlan.freshStages(setOf(gps), fineGranted = false).isEmpty())
  }

  @Test
  fun `l'ultima posizione nota non chiede al GPS e al passivo un permesso che non c'e'`() {
    val all = listOf(passive, gps, network, fused)

    assertEquals(listOf(network, fused), LocationPlan.lastKnownProviders(all, fineGranted = false))
    assertEquals(all, LocationPlan.lastKnownProviders(all, fineGranted = true))
  }

  @Test
  fun `fra piu' posizioni note vince la piu' recente, e senza orario perde`() {
    val old = LocationSnapshot(41.0, 16.0, null, fixedAtMillis = 1_000L)
    val recent = LocationSnapshot(41.1, 16.9, 12.0, fixedAtMillis = 9_000L)
    val undated = LocationSnapshot(0.0, 0.0, null, fixedAtMillis = null)

    assertEquals(recent, LastKnown.newest(listOf(old, undated, recent)))
    assertEquals(old, LastKnown.newest(listOf(undated, old)))
    assertNull(LastKnown.newest(emptyList()))
  }

  @Test
  fun `la corsa fra i provider restituisce il primo che risponde e annulla gli altri`() = runTest {
    var slowFinished = false
    val fix = LocationRace.first(
      listOf<suspend () -> String?>(
        { delay(5_000); slowFinished = true; "gps" },
        { delay(800); "rete" },
      ),
    )

    assertEquals("rete", fix)
    assertEquals(800L, currentTime)
    assertTrue(!slowFinished)
  }

  @Test
  fun `un provider che non da' niente non ferma gli altri`() = runTest {
    val fix = LocationRace.first(
      listOf<suspend () -> String?>(
        { null },
        { delay(1_000); "gps" },
      ),
    )

    assertEquals("gps", fix)
    assertEquals(1_000L, currentTime)
  }

  @Test
  fun `un provider che lancia vale niente fix, non porta giu' la corsa`() = runTest {
    val fix = LocationRace.first(
      listOf<suspend () -> String?>(
        { error("il provider e' sparito") },
        { delay(300); "rete" },
      ),
    )

    assertEquals("rete", fix)
  }

  @Test
  fun `se nessuno risponde la corsa finisce appena finisce l'ultimo`() = runTest {
    val fix = LocationRace.first(
      listOf<suspend () -> String?>(
        { delay(200); null },
        { delay(700); null },
      ),
    )

    assertNull(fix)
    assertEquals(700L, currentTime)
    assertNull(LocationRace.first(emptyList<suspend () -> String?>()))
  }

  @Test
  fun `il fix nuovo ha il budget del totale meno due secondi, ma mai meno della meta'`() {
    assertEquals(8_000L, LocationFallback.currentBudgetMillis(10_000L))
    assertEquals(3_000L, LocationFallback.currentBudgetMillis(5_000L))
    assertEquals(1_500L, LocationFallback.currentBudgetMillis(3_000L))
  }

  @Test
  fun `una richiesta in volo che arriva dopo la corsa non la fa ripartire`() = runTest {
    val late = CompletableDeferred<String?>()
    val fix = LocationRace.first(
      listOf<suspend () -> String?>(
        { late.await() },
        { "subito" },
      ),
    )

    assertEquals("subito", fix)
    // La richiesta lenta e' stata annullata, non completata: nessuno aspetta piu' il suo risultato.
    assertTrue(!late.isCompleted)
  }
}
