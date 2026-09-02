package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastBundle
import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import dev.pampa.fluidweather.core.model.HourlyPoint
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import dev.pampa.fluidweather.nowcast.verdict.WindowVerdict
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
  fun `l'evento pioggia si semina per finestra e si giudica sulla mediana delle analisi`() = runTest {
    val store = InMemoryVerificationStore()
    var clock = now
    val verifier = ForecastVerifier(store, clock = { clock })

    // Tre provider: piove alle +2 e +3 secondo tutti; le probabilita' variano.
    val rain: (Int) -> Double = { h -> if (h == 2 || h == 3) 1.0 else 0.0 }
    val fetches = listOf(
      bundle(ProviderRegistry.OPEN_METEO, { h -> if (h in 2..3) 80.0 else 10.0 }, rain),
      bundle(ProviderRegistry.OPEN_METEO_ICON, { h -> if (h in 2..3) 40.0 else 10.0 }, rain),
      bundle(ProviderRegistry.MET_NORWAY, { 20.0 }, rain),
    )
    verifier.registerRainEvents(fetches)
    // 3 provider x 3 finestre.
    assertEquals(9, store.pending.size)
    val openMeteo13 = store.pending.first { it.providerId == ProviderRegistry.OPEN_METEO && it.variable == "${RainEvent.PREFIX}1_3" }
    assertEquals(0.8, openMeteo13.predictedValue, 1e-9)
    assertEquals(now + 3 * 3_600_000L, openMeteo13.targetTimestampMillis)

    // Il barometro alla pari: 0-1h 10%, 1-3h 70%, 3-6h 30%.
    verifier.registerBarometer(
      NowcastVerdict(
        listOf(
          WindowVerdict("0-1h", 0.10, 0.05, 0.15, emptyList()),
          WindowVerdict("1-3h", 0.70, 0.6, 0.8, emptyList()),
          WindowVerdict("3-6h", 0.30, 0.2, 0.4, emptyList()),
        ),
        AlertLevel.SORVEGLIANZA,
      ),
      now,
    )
    assertEquals(12, store.pending.size)

    // Sette ore dopo tutte le finestre sono chiuse: 0-1h asciutta (y=0), 1-3h e 3-6h bagnate (y=1).
    clock = now + 7 * 3_600_000L
    // Le analisi: gli stessi bundle (le ore +2 e +3 sono ormai passate e valgono come analisi).
    verifier.settle(fetches)
    assertTrue(store.pending.isEmpty())

    fun error(providerId: String, window: String) =
      store.verifications.first { it.providerId == providerId && it.variable == "${RainEvent.PREFIX}$window" }.absoluteError
    assertEquals(0.10, error(RainEvent.LOCAL_BAROMETER_ID, "0_1"), 1e-9) // 0.10 - 0
    assertEquals(0.30, error(RainEvent.LOCAL_BAROMETER_ID, "1_3"), 1e-9) // |0.70 - 1|
    assertEquals(0.20, error(ProviderRegistry.OPEN_METEO, "1_3"), 1e-9) // |0.80 - 1|
    assertEquals(0.60, error(ProviderRegistry.OPEN_METEO_ICON, "1_3"), 1e-9) // |0.40 - 1|
    assertEquals(0.10, error(ProviderRegistry.OPEN_METEO, "0_1"), 1e-9) // |0.10 - 0|
    assertTrue(store.verifications.all { it.horizonBucket == HorizonBucket.SHORT })
  }

  @Test
  fun `la pagella ordina per quota appresa, trova il migliore per variabile e il barometro sulla pioggia`() {
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
      // L'evento pioggia, barometro compreso.
      repeat(10) {
        add(verification(RainEvent.LOCAL_BAROMETER_ID, "${RainEvent.PREFIX}1_3", 0.2))
        add(verification("open-meteo", "${RainEvent.PREFIX}1_3", 0.35))
      }
    }
    val report = Benchmark.build(verifications, now)

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

    val rain = report.rainEvent.getValue("${RainEvent.PREFIX}1_3")
    assertEquals(RainEvent.LOCAL_BAROMETER_ID, rain.first().providerId)
    assertEquals(0.2, rain.first().decayedMae, 1e-9)

    val daily = report.dailyError.getValue("open-meteo").getValue(FusionVariables.TEMPERATURE)
    assertEquals(5, daily.size)
    assertTrue(daily.zipWithNext().all { (a, b) -> b.epochDay > a.epochDay })
    assertEquals(verifications.size, report.totalVerifications)
  }
}
