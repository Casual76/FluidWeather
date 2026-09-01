package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.WeatherKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifierAndFusionTest {

  private val now = 1_700_000_000_000L

  private fun descriptorId(index: Int) = listOf(
    ProviderRegistry.OPEN_METEO,
    ProviderRegistry.OPEN_METEO_ICON,
    ProviderRegistry.MET_NORWAY,
  )[index]

  private fun bundle(
    providerId: String,
    temperatureAt: (hourFromNow: Int) -> Double?,
    kind: WeatherKind? = null,
  ): ProviderFetch {
    val descriptor = ProviderRegistry.all.first { it.id == providerId }
    val hours = (-6..24).map { h ->
      HourlyPoint(
        timestampMillis = now + h * 3_600_000L,
        temperatureC = temperatureAt(h),
        pressureMslHpa = 1013.0,
        precipitationMm = 0.0,
        cloudCoverPercent = 50.0,
        windSpeedKmh = 10.0,
        kind = kind,
      )
    }
    return ProviderFetch(descriptor, ForecastBundle(providerId, now, 43.83, 11.2, hours), null)
  }

  @Test
  fun `il verificatore semina, aspetta, e giudica contro la mediana`() = runTest {
    val store = InMemoryVerificationStore()
    var clock = now
    val verifier = ForecastVerifier(store, clock = { clock })

    val fetches = listOf(
      bundle(descriptorId(0), { 20.0 }),
      bundle(descriptorId(1), { 22.0 }),
      bundle(descriptorId(2), { 21.0 }),
    )
    verifier.registerPending(fetches)
    // 3 provider x 5 variabili x 5 orizzonti.
    assertEquals(75, store.pending.size)

    // Tre ore dopo: i +1h e +3h sono scaduti; la verita' per la temperatura e' la mediana (21).
    clock = now + 3 * 3_600_000L
    verifier.settle(fetches)

    val judged = store.verifications.filter { it.variable == FusionVariables.TEMPERATURE }
    assertEquals(6, judged.size) // 3 provider x 2 orizzonti scaduti
    assertEquals(1.0, judged.first { it.providerId == descriptorId(0) }.absoluteError, 1e-9)
    assertEquals(0.0, judged.first { it.providerId == descriptorId(2) }.absoluteError, 1e-9)
    // Le pendenti scadute sono state saldate e tolte.
    assertTrue(store.pending.none { it.targetTimestampMillis <= clock })
  }

  @Test
  fun `senza quorum non si giudica`() = runTest {
    val store = InMemoryVerificationStore()
    var clock = now
    val verifier = ForecastVerifier(store, minTruthOpinions = 3, clock = { clock })

    store.pending += PendingPrediction(descriptorId(0), FusionVariables.TEMPERATURE, now + 3_600_000L, 20.0, now)
    clock = now + 2 * 3_600_000L
    // Solo due opinioni fresche: niente mediana, niente giudizio, la pendente resta.
    verifier.settle(listOf(bundle(descriptorId(0), { 20.0 }), bundle(descriptorId(1), { 22.0 })))

    assertTrue(store.verifications.isEmpty())
    assertEquals(1, store.pending.size)
  }

  @Test
  fun `la fusione e' una media pesata tracciabile`() = runTest {
    val store = InMemoryVerificationStore()
    val fusion = ForecastFusion(ProviderScoreboard(store, clock = { now }))

    val fetches = listOf(
      bundle(descriptorId(0), { 20.0 }),
      bundle(descriptorId(1), { 24.0 }),
    )
    val fused = fusion.fuse(fetches, 43.83, 11.2, now)

    val hour = fused.hours.first { it.timestampMillis == now + 3_600_000L }
    val temperature = hour.values.getValue(FusionVariables.TEMPERATURE)
    // Priori Europa: ICON 1.0, best_match 0.9 -> fuso piu' vicino a 24 che a 20.
    assertTrue(temperature.value in 20.0..24.0)
    assertTrue(temperature.value > 22.0)
    // Tracciabilita': i contributi ci sono tutti e i pesi normalizzati sommano a uno.
    assertEquals(2, temperature.contributions.size)
    assertEquals(1.0, temperature.contributions.sumOf { it.weight }, 1e-9)
  }

  @Test
  fun `l'override usa solo il provider scelto dove arriva`() = runTest {
    val store = InMemoryVerificationStore()
    val fusion = ForecastFusion(ProviderScoreboard(store, clock = { now }))

    val fetches = listOf(
      bundle(descriptorId(0), { 20.0 }),
      bundle(descriptorId(1), { if (it <= 6) 24.0 else null }), // ICON arriva solo a +6h
    )
    val fused = fusion.fuse(fetches, 43.83, 11.2, now, onlyProviderId = descriptorId(1))

    val early = fused.hours.first { it.timestampMillis == now + 3_600_000L }
    assertEquals(24.0, early.values.getValue(FusionVariables.TEMPERATURE).value, 1e-9)
    assertEquals(1, early.values.getValue(FusionVariables.TEMPERATURE).contributions.size)

    // Dove l'override non copre, la cascata riprende: nessun buco nel forecast.
    val late = fused.hours.first { it.timestampMillis == now + 12 * 3_600_000L }
    assertEquals(20.0, late.values.getValue(FusionVariables.TEMPERATURE).value, 1e-9)
  }

  @Test
  fun `la condizione la dice il provider col peso maggiore`() = runTest {
    val store = InMemoryVerificationStore()
    val fusion = ForecastFusion(ProviderScoreboard(store, clock = { now }))

    val fetches = listOf(
      bundle(descriptorId(0), { 20.0 }, kind = WeatherKind.CLEAR),
      bundle(descriptorId(1), { 24.0 }, kind = WeatherKind.RAIN), // ICON: prior piu' alto in Europa
    )
    val fused = fusion.fuse(fetches, 43.83, 11.2, now)

    assertEquals(WeatherKind.RAIN, fused.hours.first().kind)
  }
}
