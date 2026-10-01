package dev.pampa.fluidweather.core.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.fluidweather.core.model.ActivityKind
import dev.pampa.fluidweather.core.model.LastFix
import dev.pampa.fluidweather.core.model.LocalClimatologyRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gli archivi su DataStore della verita' della pioggia: ultimo fix, climatologie locali, freno
 * della manutenzione, e l'istante dell'ultimo transito. I DataStore sono singoli per processo e
 * i file restano fra un test e l'altro: ogni test parte da `clear()`.
 */
@RunWith(AndroidJUnit4::class)
class RainTruthStoresTest {

  private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private lateinit var lastFix: LastFixStore
  private lateinit var climatology: LocalClimatologyStore
  private lateinit var maintenance: RainMaintenanceStore

  @Before
  fun parti() = runBlocking {
    lastFix = LastFixStore(context)
    climatology = LocalClimatologyStore(context)
    maintenance = RainMaintenanceStore(context)
    lastFix.clear()
    climatology.clear()
  }

  private fun cella(key: String, built: Long = 1_000L, used: Long = 2_000L) = LocalClimatologyRecord(
    cellKey = key,
    latitude = 43.875,
    longitude = 11.125,
    climatology = "clima-$key",
    baselines = "base-$key",
    builtAtMillis = built,
    usedAtMillis = used,
  )

  // ------------------------------------------------------------------ ultimo fix

  @Test
  fun l_ultimo_fix_si_scrive_e_si_rilegge_e_clear_lo_cancella() = runBlocking {
    assertNull(lastFix.current())

    lastFix.record(LastFix(43.83, 11.2, 5_000L))

    assertEquals(LastFix(43.83, 11.2, 5_000L), lastFix.current())
    lastFix.clear()
    assertNull(lastFix.current())
  }

  @Test
  fun un_fix_piu_vecchio_arrivato_dopo_non_fa_tornare_indietro_il_punto() = runBlocking {
    lastFix.record(LastFix(43.83, 11.2, 9_000L))
    lastFix.record(LastFix(45.0, 9.0, 4_000L))

    assertEquals(LastFix(43.83, 11.2, 9_000L), lastFix.current())

    lastFix.record(LastFix(41.9, 12.5, 9_500L))
    assertEquals(LastFix(41.9, 12.5, 9_500L), lastFix.current())
  }

  // ------------------------------------------------------------------ climatologie locali

  @Test
  fun una_cella_si_scrive_si_rilegge_e_compare_in_all() = runBlocking {
    assertNull(climatology.get("c175_44"))

    climatology.put(cella("c175_44"))
    climatology.put(cella("c10_10", built = 500L))

    assertEquals(cella("c175_44"), climatology.get("c175_44"))
    assertEquals(setOf("c175_44", "c10_10"), climatology.all().map { it.cellKey }.toSet())
  }

  @Test
  fun touch_aggiorna_l_ultimo_uso_ma_non_inventa_celle() = runBlocking {
    climatology.put(cella("c1_1", used = 10L))

    climatology.touch("c1_1", 99L)
    climatology.touch("c2_2", 99L)

    assertEquals(99L, climatology.get("c1_1")?.usedAtMillis)
    assertNull(climatology.get("c2_2"))
    assertEquals(1, climatology.all().size)
  }

  @Test
  fun il_fallimento_si_ricorda_e_una_riuscita_lo_dimentica() = runBlocking {
    assertNull(climatology.lastFailureMillis("c1_1"))

    climatology.markFailure("c1_1", 777L)
    assertEquals(777L, climatology.lastFailureMillis("c1_1"))
    // Un fallimento non crea una cella: all() scorre solo le costruite.
    assertEquals(0, climatology.all().size)
    assertNull(climatology.get("c1_1"))

    climatology.put(cella("c1_1"))
    assertNull(climatology.lastFailureMillis("c1_1"))
  }

  @Test
  fun i_fallimenti_di_celle_mai_riuscite_e_vecchi_di_una_settimana_si_buttano_al_fallimento_dopo() = runBlocking {
    val giorno = 86_400_000L
    climatology.markFailure("vecchia", 1_000L)
    climatology.put(cella("costruita"))
    climatology.markFailure("costruita", 1_000L)

    climatology.markFailure("nuova", 1_000L + 8 * giorno)

    assertNull(climatology.lastFailureMillis("vecchia"))
    assertNotNull(climatology.lastFailureMillis("nuova"))
    // Una cella costruita conserva il suo segno: il rinnovo a sei mesi la riguarda.
    assertEquals(1_000L, climatology.lastFailureMillis("costruita"))
  }

  @Test
  fun remove_toglie_una_cella_e_il_suo_fallimento_e_clear_svuota_tutto_freno_compreso() = runBlocking {
    climatology.put(cella("c1_1"))
    climatology.put(cella("c2_2"))
    climatology.markFailure("c1_1", 5L)
    maintenance.markRun(1_234L)

    climatology.remove("c1_1")

    assertNull(climatology.get("c1_1"))
    assertNull(climatology.lastFailureMillis("c1_1"))
    assertEquals(listOf("c2_2"), climatology.all().map { it.cellKey })
    assertEquals(1_234L, maintenance.lastRunMillis())

    climatology.clear()

    assertEquals(0, climatology.all().size)
    assertNull(maintenance.lastRunMillis())
  }

  // ------------------------------------------------------------------ freno della manutenzione

  @Test
  fun il_freno_ricorda_l_ultimo_giro() = runBlocking {
    maintenance.markRun(42L)
    assertEquals(42L, maintenance.lastRunMillis())
    maintenance.markRun(43L)
    assertEquals(43L, maintenance.lastRunMillis())
  }

  // ------------------------------------------------------------------ ultimo transito

  @Test
  fun l_ultimo_transito_si_segna_solo_quando_chi_riconosce_lo_dice() = runBlocking {
    val activity = LatestActivityStore(context)

    activity.store(LatestActivity(ActivityKind.IN_VEHICLE, 90, 10_000L), inTransit = true)
    assertEquals(10_000L, activity.lastInTransitMillis())

    // Una sosta dopo: l'attivita' cambia, ma "si correva" non si cancella.
    activity.store(LatestActivity(ActivityKind.STILL, 95, 20_000L))
    assertEquals(20_000L, activity.current()?.observedAtMillis)
    assertEquals(ActivityKind.STILL, activity.current()?.kind)
    assertEquals(10_000L, activity.lastInTransitMillis())

    activity.store(LatestActivity(ActivityKind.ON_BICYCLE, 80, 30_000L), inTransit = true)
    assertEquals(30_000L, activity.lastInTransitMillis())
  }
}
