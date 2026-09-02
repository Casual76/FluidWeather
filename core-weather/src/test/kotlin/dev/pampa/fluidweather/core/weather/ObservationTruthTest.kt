package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.Observation
import dev.pampa.fluidweather.core.model.PendingPrediction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationTruthTest {

  private val now = 1_700_000_000_000L

  private fun dryBundle(providerId: String) = ProviderFetch(
    ProviderRegistry.all.first { it.id == providerId },
    ForecastBundle(providerId, now, 43.83, 11.2, (-6..12).map { h -> HourlyPoint(now + h * 3_600_000L, precipitationMm = 0.0) }),
    null,
  )

  private fun observation(hourFromIssue: Int, condition: ObservedCondition) =
    Observation(1, now + hourFromIssue * 3_600_000L, condition, 43.83, 11.2, "Sesto Fiorentino")

  @Test
  fun `l'occhio dell'utente vince sulla mediana - pioggia vista, finestra bagnata`() = runTest {
    val store = InMemoryVerificationStore()
    var clock = now
    val verifier = ForecastVerifier(store, clock = { clock })
    val issued = now
    store.pending += PendingPrediction(RainEvent.LOCAL_BAROMETER_ID, "${RainEvent.PREFIX}1_3", issued + 3 * 3_600_000L, 0.7, issued)
    store.pending += PendingPrediction(ProviderRegistry.OPEN_METEO, "${RainEvent.PREFIX}0_1", issued + 3_600_000L, 0.1, issued)

    clock = now + 4 * 3_600_000L
    // Tutti i provider dicono asciutto; l'utente ha visto piovere alle +2.
    val analyses = listOf(dryBundle(ProviderRegistry.OPEN_METEO), dryBundle(ProviderRegistry.OPEN_METEO_ICON), dryBundle(ProviderRegistry.MET_NORWAY))
    verifier.settle(analyses, listOf(observation(2, ObservedCondition.RAIN)))

    val barometer = store.verifications.first { it.providerId == RainEvent.LOCAL_BAROMETER_ID }
    assertEquals(0.3, barometer.absoluteError, 1e-9) // |0.7 - 1|: la finestra 1-3 era bagnata
    val openMeteo = store.verifications.first { it.providerId == ProviderRegistry.OPEN_METEO }
    assertEquals(0.1, openMeteo.absoluteError, 1e-9) // la finestra 0-1 non ha osservazioni: asciutta per mediana
    assertTrue(store.pending.isEmpty())
  }

  @Test
  fun `un'osservazione asciutta fa fede per la sua ora e le altre ore le decide la mediana`() = runTest {
    val store = InMemoryVerificationStore()
    var clock = now
    val verifier = ForecastVerifier(store, clock = { clock })
    store.pending += PendingPrediction(RainEvent.LOCAL_BAROMETER_ID, "${RainEvent.PREFIX}1_3", now + 3 * 3_600_000L, 0.2, now)
    clock = now + 4 * 3_600_000L
    // Due sole analisi (niente quorum) ma osservazioni su ENTRAMBE le ore della finestra: si giudica.
    verifier.settle(
      listOf(dryBundle(ProviderRegistry.OPEN_METEO), dryBundle(ProviderRegistry.OPEN_METEO_ICON)),
      listOf(observation(2, ObservedCondition.CLEAR), observation(3, ObservedCondition.CLOUDY)),
    )
    assertEquals(1, store.verifications.size)
    assertEquals(0.2, store.verifications.single().absoluteError, 1e-9)
  }

  @Test
  fun `il vocabolario delle osservazioni sa cosa e' pioggia`() {
    assertTrue(ObservedCondition.entries.filter { it.wet }.map { it.name }.containsAll(listOf("DRIZZLE", "RAIN", "SNOW", "THUNDERSTORM", "HAIL")))
    assertTrue(ObservedCondition.entries.filter { !it.wet }.map { it.name }.containsAll(listOf("CLEAR", "CLOUDY", "FOG")))
  }
}
