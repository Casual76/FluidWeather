package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.testbench.data.BenchLocation
import dev.pampa.fluidweather.testbench.data.HourlyRecord
import dev.pampa.fluidweather.testbench.data.StationDataset
import dev.pampa.fluidweather.testbench.events.EventWindow
import dev.pampa.fluidweather.testbench.metrics.Probabilistic
import dev.pampa.fluidweather.testbench.metrics.Verification
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayerTest {

  private val start = 1_700_000_000_000L
  private val location = BenchLocation("sintetica", 43.0, 11.0, "fixture")

  /**
   * Venti giorni sintetici e fisicamente coerenti, su un ciclo di 4 giorni: la pressione crolla
   * di 1 hPa/h nelle sei ore prima della depressione, resta bassa mentre piove (ore 0-5), poi
   * risale lentamente col bel tempo. Un mondo in cui la regola barometrica DEVE funzionare
   * sugli orizzonti 1-6h: se non batte la climatologia qui, e' rotta.
   */
  private fun predictableWorld(): StationDataset {
    val records = (0 until 20 * 24).map { hour ->
      val dayHour = hour % 96 // ciclo di 4 giorni
      val rainHour = dayHour < 6
      val pressure = when {
        rainHour -> 1007.0 // il fondo della depressione, mentre piove
        dayHour < 18 -> 1007.0 + (dayHour - 6) * 0.5 // risalita post-frontale
        dayHour < 90 -> 1013.0 // alta pressione stabile
        else -> 1013.0 - (dayHour - 90) * 1.0 // la caduta arriva COL fronte: 1 hPa/h fino alla pioggia
      }
      HourlyRecord(
        timestampMillis = start + hour * 3_600_000L,
        temperatureC = 15.0,
        relativeHumidityPercent = null,
        dewPointC = null,
        surfacePressureHpa = pressure,
        pressureMslHpa = pressure,
        precipitationMm = if (rainHour) 1.0 else 0.0,
        cloudCoverPercent = null,
        windSpeedKmh = null,
        windDirectionDeg = null,
      )
    }
    return StationDataset(location, elevationMeters = 0.0, records = records)
  }

  @Test
  fun `il replay produce verifiche per ogni predittore e finestra, in pari numero`() {
    val outcome = Replayer().replay(predictableWorld())

    assertEquals(3, outcome.cells.size)
    val sizes = outcome.cells.values.flatMap { byWindow -> byWindow.values.map { it.size } }.toSet()
    assertTrue(outcome.evaluations > 50)
    assertEquals(1, outcome.cells.values.map { it.keys }.toSet().size)
    // Stesso numero di verifiche per tutti i predittori sulla stessa finestra.
    for (window in EventWindow.Standard) {
      val counts = outcome.cells.values.map { it.getValue(window.label).size }.toSet()
      assertEquals(1, counts.size)
    }
    assertTrue(sizes.all { it > 50 })
  }

  @Test
  fun `nel mondo prevedibile la regola barometrica batte la climatologia oltre l'ora`() {
    // Non sull'0-1h: li' il barometro non e' l'attrezzo giusto nemmeno in teoria — il piano
    // stesso assegna quell'orizzonte alla persistenza.
    val outcome = Replayer().replay(predictableWorld())

    for (window in EventWindow.Standard.filter { it.fromHours >= 1 }) {
      val rule = outcome.cells.getValue("regola-barometrica").getValue(window.label)
        .map { Verification(it.probability, it.occurred) }
      val skill = Probabilistic.brierSkillScore(rule)
      assertTrue("BSS ${window.label} = $skill", skill > 0.05)
    }
  }

  @Test
  fun `sull'ora immediata vince la persistenza`() {
    val outcome = Replayer().replay(predictableWorld())
    val persistence = outcome.cells.getValue("persistenza").getValue("0-1h")
      .map { Verification(it.probability, it.occurred) }
    assertTrue(Probabilistic.brierSkillScore(persistence) > 0.2)
  }

  @Test
  fun `la climatologia dice sempre il tasso base e ottiene BSS zero`() {
    val outcome = Replayer().replay(predictableWorld())
    val climatology = outcome.cells.getValue("climatologia").getValue("1-3h")
      .map { Verification(it.probability, it.occurred) }

    // Il tasso dichiarato coincide (quasi: bordi del dataset) con quello osservato nel replay.
    val declared = climatology.first().probability
    val observed = Probabilistic.baseRate(climatology)
    assertTrue(abs(declared - observed) < 0.05)
    assertTrue(abs(Probabilistic.brierSkillScore(climatology)) < 0.05)
  }

  @Test
  fun `le stagioni sono etichettate`() {
    val outcome = Replayer().replay(predictableWorld())
    val seasons = outcome.cells.getValue("persistenza").getValue("0-1h").map { it.season }.toSet()
    assertTrue(seasons.isNotEmpty())
    assertTrue(seasons.all { it in setOf("DJF", "MAM", "JJA", "SON") })
  }
}
