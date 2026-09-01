package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.FusionVariables
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreboardTest {

  private val now = 1_700_000_000_000L
  private val providers = listOf(
    ProviderRegistry.OPEN_METEO,
    ProviderRegistry.OPEN_METEO_ICON,
    ProviderRegistry.MET_NORWAY,
  )

  private fun verification(
    providerId: String,
    error: Double,
    ageDays: Double = 0.0,
  ) = ForecastVerification(
    providerId = providerId,
    variable = FusionVariables.TEMPERATURE,
    horizonBucket = HorizonBucket.SHORT,
    absoluteError = error,
    verifiedAtMillis = now - (ageDays * 86_400_000).toLong(),
  )

  @Test
  fun `sotto la soglia di verifiche comandano i priori regionali`() = runTest {
    val store = InMemoryVerificationStore()
    val scoreboard = ProviderScoreboard(store, clock = { now })

    // Sesto Fiorentino: Europa. ICON parte davanti a MET Norway, come da editoriale.
    val weights = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, 43.83, 11.2, providers)

    assertTrue(weights.values.all { it.source == WeightSource.PRIOR })
    assertTrue(
      weights.getValue(ProviderRegistry.OPEN_METEO_ICON).weight >
        weights.getValue(ProviderRegistry.MET_NORWAY).weight,
    )
  }

  @Test
  fun `il test del piano - un provider deliberatamente scarso scende entro N verifiche`() = runTest {
    val store = InMemoryVerificationStore()
    // 25 giudizi a testa: ICON sbaglia di 3 gradi, gli altri di mezzo grado.
    repeat(25) {
      store.verifications += verification(ProviderRegistry.OPEN_METEO_ICON, error = 3.0)
      store.verifications += verification(ProviderRegistry.OPEN_METEO, error = 0.5)
      store.verifications += verification(ProviderRegistry.MET_NORWAY, error = 0.5)
    }
    val scoreboard = ProviderScoreboard(store, clock = { now })
    val weights = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, 43.83, 11.2, providers)

    assertTrue(weights.values.all { it.source == WeightSource.LEARNED })
    val icon = weights.getValue(ProviderRegistry.OPEN_METEO_ICON)
    val best = weights.getValue(ProviderRegistry.OPEN_METEO)
    // Nonostante partisse davanti da prior, ICON ora pesa MOLTO meno di chi ci prende.
    assertTrue("icon=${icon.weight} best=${best.weight}", icon.weight < best.weight / 5)
    assertEquals(3.0, icon.decayedMae, 0.01)
  }

  @Test
  fun `il decadimento fa vincere il presente sul passato`() = runTest {
    val store = InMemoryVerificationStore()
    // ICON: bravo tre mesi fa (30 giudizi a 0,3), sbandato nell'ultima settimana (30 a 3,0).
    repeat(30) { store.verifications += verification(ProviderRegistry.OPEN_METEO_ICON, 0.3, ageDays = 90.0) }
    repeat(30) { store.verifications += verification(ProviderRegistry.OPEN_METEO_ICON, 3.0, ageDays = 2.0) }
    val scoreboard = ProviderScoreboard(store, clock = { now })
    val weights = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, 43.83, 11.2, providers)

    // Con dimezzamento a 14 giorni, i 90 giorni pesano ~1%: il MAE decaduto sta vicino a 3.
    assertTrue(weights.getValue(ProviderRegistry.OPEN_METEO_ICON).decayedMae > 2.5)
  }

  @Test
  fun `variabili e orizzonti hanno classifiche indipendenti`() = runTest {
    val store = InMemoryVerificationStore()
    repeat(25) {
      store.verifications += verification(ProviderRegistry.OPEN_METEO_ICON, error = 3.0)
    }
    val scoreboard = ProviderScoreboard(store, clock = { now })

    val shortTemp = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.SHORT, 43.83, 11.2, providers)
    val mediumTemp = scoreboard.weights(FusionVariables.TEMPERATURE, HorizonBucket.MEDIUM, 43.83, 11.2, providers)

    assertEquals(WeightSource.LEARNED, shortTemp.getValue(ProviderRegistry.OPEN_METEO_ICON).source)
    // Sulla fascia 6-24h nessuno ha giudizi: si torna ai priori.
    assertEquals(WeightSource.PRIOR, mediumTemp.getValue(ProviderRegistry.OPEN_METEO_ICON).source)
  }
}
