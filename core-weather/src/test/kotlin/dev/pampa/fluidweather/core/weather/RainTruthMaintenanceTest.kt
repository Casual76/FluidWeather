package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.RainBoardIds
import dev.pampa.fluidweather.core.model.RainEventPending
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.ModelVersions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** La manutenzione oraria: il freno su disco, e l'ordine dei lavori (prima i giudizi, poi la climatologia). */
class RainTruthMaintenanceTest {

  private val issued = MIDNIGHT + 10 * HOUR + 20 * 60_000L
  private val times = (0 until 48).map { MIDNIGHT + it * HOUR }
  private val panelReply = archiveJson(times, TruthPanel.MODELS.associate { "precipitation_$it" to times.map { 0.0 } })
  private val climatologyReply = archiveJson(
    times,
    (TruthPanel.MODELS + LocalClimatology.BEST_MATCH).associate { model -> "precipitation_$model" to times.map { if (it % (5 * HOUR) == 0L) 0.6 else 0.0 } },
  )
  private val pressureReply = archiveJson(times, mapOf("pressure_msl" to times.map { 1012.0 }))

  private inner class Bench(lastRun: Long? = null) {
    var now = MIDNIGHT + 14 * HOUR + TruthPanel.FINALITY_MILLIS
    val throttle = InMemoryThrottle(lastRun)
    val store = InMemoryRainEventStore()
    val http = RecordingHttp { url ->
      when {
        "hourly=pressure_msl" in url -> pressureReply
        LocalClimatology.BEST_MATCH in url -> climatologyReply
        else -> panelReply
      }
    }
    private val providerHttp = providerHttpOf(http) { now }
    val maintenance = RainTruthMaintenance(
      settler = TruthPanelSettler(providerHttp, store, observations = { emptyList() }, clock = { now }),
      climatology = LocalClimatology(providerHttp, InMemoryLocalClimatologyRecords()) { now },
      throttle = throttle,
      homePoint = { 43.832 to 11.199 },
      clock = { now },
    )
  }

  private fun dueRow() =
    RainEventPending("gps", 43.832, 11.199, issued, RainBoardIds.BAROMETER, "1-3h", issued, 0.2, ModelVersions.TAG, null)

  @Test
  fun `entro l'ora non si rifa' niente`() = runTest {
    val bench = Bench()
    bench.throttle.last = bench.now - 30 * 60_000L
    bench.store.addPending(listOf(dueRow()))

    assertFalse(bench.maintenance.runIfDue())
    assertTrue(bench.http.urls.isEmpty())
    assertEquals(0, bench.throttle.marks)
    assertEquals(1, bench.store.pendingCount())
  }

  @Test
  fun `un orologio tornato indietro non blocca per sempre`() = runTest {
    val bench = Bench()
    bench.throttle.last = bench.now + 2 * HOUR

    assertTrue(bench.maintenance.runIfDue())
    assertEquals(bench.now, bench.throttle.last)
  }

  @Test
  fun `prima i giudizi, poi la climatologia del posto, e il freno segnato`() = runTest {
    val bench = Bench()
    bench.store.addPending(listOf(dueRow()))

    assertTrue(bench.maintenance.runIfDue())

    assertEquals(1, bench.throttle.marks)
    assertEquals(1, bench.store.verificationCount())
    // L'ordine delle richieste: la verita' (solo i giudici), poi la storia della cella.
    assertEquals(3, bench.http.urls.size)
    assertFalse(bench.http.urls[0].contains(LocalClimatology.BEST_MATCH))
    assertTrue(bench.http.urls[1].contains("models=meteofrance_seamless,ukmo_seamless,gem_seamless,best_match"))
    assertTrue(bench.http.urls[2].contains("hourly=pressure_msl"))

    // Un'ora meno un minuto dopo: fermo.
    bench.now += RainTruthMaintenance.INTERVAL_MILLIS - 60_000L
    assertFalse(bench.maintenance.runIfDue())
    assertEquals(3, bench.http.urls.size)
  }
}
