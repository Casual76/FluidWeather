package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.core.model.PendingPrediction
import dev.pampa.fluidweather.core.model.RainBoardIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkTest {

  private val now = 1_700_000_000_000L

  private fun bundle(providerId: String, pop: (hourFromNow: Int) -> Double?, rain: (hourFromNow: Int) -> Double) =
    ProviderFetch(
      ProviderRegistry.all.first { it.id == providerId },
      ForecastBundle(
        providerId,
        now,
        43.83,
        11.2,
        (-6..12).map { h ->
          HourlyPoint(
            timestampMillis = now + h * 3_600_000L,
            precipitationProbabilityPercent = pop(h),
            precipitationMm = rain(h),
            temperatureC = 20.0,
          )
        },
      ),
      null,
    )

  @Test
  fun `le righe pioggia rimaste nelle tabelle generali si tolgono senza giudizio`() = runTest {
    // La loro verita' era la mediana dei provider in classifica: circolare. Il barometro non le
    // scrive piu': sono le righe che un'installazione vecchia si porta nel database.
    val store = InMemoryVerificationStore()
    var clock = now
    val verifier = ForecastVerifier(store, clock = { clock })
    store.addPending(
      listOf(
        Triple("0_1", 1, 0.10),
        Triple("1_3", 3, 0.70),
        Triple("3_6", 6, 0.30),
      ).map { (window, toHour, probability) ->
        PendingPrediction(
          providerId = RainBoardIds.BAROMETER,
          variable = "${RainEvent.PREFIX}$window",
          targetTimestampMillis = now + toHour * 3_600_000L,
          predictedValue = probability,
          issuedAtMillis = now,
        )
      },
    )
    assertEquals(3, store.pending.size)

    clock = now + 7 * 3_600_000L
    val rain: (Int) -> Double = { h -> if (h == 2 || h == 3) 1.0 else 0.0 }
    verifier.settle(
      listOf(
        bundle(ProviderRegistry.OPEN_METEO, { 10.0 }, rain),
        bundle(ProviderRegistry.OPEN_METEO_ICON, { 10.0 }, rain),
        bundle(ProviderRegistry.MET_NORWAY, { 10.0 }, rain),
      ),
    )

    assertTrue(store.pending.isEmpty())
    assertTrue(store.verifications.isEmpty())
  }

  @Test
  fun `la pagella ordina per quota appresa e trova il migliore per variabile, senza contare le righe pioggia di una volta`() {
    fun verification(providerId: String, variable: String, error: Double, daysAgo: Int = 0) = ForecastVerification(
      providerId = providerId,
      variable = variable,
      horizonBucket = HorizonBucket.SHORT,
      absoluteError = error,
      verifiedAtMillis = now - daysAgo * 86_400_000L,
    )
    val verifications = buildList {
      repeat(25) {
        add(verification("open-meteo", FusionVariables.TEMPERATURE, 1.0, daysAgo = it % 5))
        add(verification("open-meteo-icon", FusionVariables.TEMPERATURE, 2.0, daysAgo = it % 5))
        add(verification("open-meteo", FusionVariables.WIND_SPEED, 6.0))
        add(verification("open-meteo-icon", FusionVariables.WIND_SPEED, 3.0))
      }
      // Pochi giudizi sulla pressione: compaiono ma non "appresi".
      repeat(5) { add(verification("met-norway", FusionVariables.PRESSURE_MSL, 0.5)) }
    }
    // Le righe pioggia delle tabelle generali (legacy): il magazzino le restituisce ancora, la
    // pagella non le conta ne' le mostra, la pioggia ha la sua classifica.
    val legacyRain = buildList {
      repeat(10) {
        add(verification(RainBoardIds.BAROMETER, "${RainEvent.PREFIX}1_3", 0.2))
        add(verification("open-meteo", "${RainEvent.PREFIX}1_3", 0.35))
      }
    }
    val report = Benchmark.build(verifications + legacyRain, now)

    assertEquals(3, report.ranking.size)
    // Quote: temperatura 1/(1.3)^2 vs 1/(2.3)^2 -> open-meteo domina; vento il contrario.
    val openMeteo = report.ranking.first { it.providerId == "open-meteo" }
    val icon = report.ranking.first { it.providerId == "open-meteo-icon" }
    assertTrue(openMeteo.share > 0.0 && icon.share > 0.0)
    assertEquals(listOf(FusionVariables.TEMPERATURE), openMeteo.bestAt)
    assertEquals(listOf(FusionVariables.WIND_SPEED), icon.bestAt)
    assertTrue(openMeteo.maeByVariable.getValue(FusionVariables.TEMPERATURE).learned)
    assertTrue(!report.byVariable.getValue(FusionVariables.PRESSURE_MSL).first().learned)
    assertEquals(50, openMeteo.verifications)

    assertTrue(report.ranking.none { it.providerId == RainBoardIds.BAROMETER })
    assertTrue(report.dailyError.values.none { byVariable -> byVariable.keys.any { RainEvent.isRainEvent(it) } })

    val daily = report.dailyError.getValue("open-meteo").getValue(FusionVariables.TEMPERATURE)
    assertEquals(5, daily.size)
    assertTrue(daily.zipWithNext().all { (a, b) -> b.epochDay > a.epochDay })
    assertEquals(verifications.size, report.totalVerifications)
  }
}
