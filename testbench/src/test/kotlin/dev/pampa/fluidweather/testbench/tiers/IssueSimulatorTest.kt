package dev.pampa.fluidweather.testbench.tiers

import dev.pampa.fluidweather.nowcast.cleaning.CleaningPipeline
import dev.pampa.fluidweather.nowcast.features.FeatureExtractor
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.testbench.replay.SampleSynthesizer
import dev.pampa.fluidweather.testbench.replay.SamplingProfile
import dev.pampa.fluidweather.testbench.replay.TierReplayer
import dev.pampa.fluidweather.testbench.train.RecordContext
import dev.pampa.fluidweather.testbench.train.TemperatureTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueSimulatorTest {

  private val hour = SyntheticWorld.HOUR
  private val day = 24 * hour
  private val start = SyntheticWorld.START_MILLIS
  private val input = SyntheticWorld.inputs("sesto-fiorentino", days = 40)

  /** Il telefono finto com'era scritto dentro `TierReplayer.replayLocation` prima dell'estrazione: il metro del test. */
  private class InlineReference(input: LocationInputs) {
    val dataset = input.dataset
    val synthesizer = SampleSynthesizer(dataset, SamplingProfile.TELEFONO)
    val recordContext = RecordContext(dataset)
    val temperatures = TemperatureTrack(dataset)
    val pipeline = CleaningPipeline()

    fun features(name: String, t0: Long): DoubleArray? {
      val issue = TierScenarios.issueMillis(name, t0)
      val samples = TierReplayer.phoneHistory(synthesizer, issue)
      if (samples.size < TierReplayer.MIN_HISTORY_SAMPLES) return null
      val cleaning = pipeline.process(
        samples,
        temperatureCelsius = temperatures.at(issue),
        referenceAltitudeMeters = dataset.elevationMeters,
      )
      return FeatureExtractor.extract(cleaning, null, recordContext.normal(issue), issue)
    }
  }

  @Test
  fun `il telefono finto e' quello di sempre, bit per bit`() {
    val simulator = IssueSimulator(input)
    val reference = InlineReference(input)
    val trendIndex = FeatureExtractor.names.indexOf(TierReplayer.TREND_FEATURE)
    var compared = 0
    var t0 = start + 2 * day
    while (t0 < start + 12 * day) {
      val sim = simulator.simulate(t0)
      val expected = reference.features("sesto-fiorentino", t0)
      if (expected == null) {
        assertNull(sim)
      } else {
        assertNotNull(sim)
        for (i in expected.indices) assertEquals("feature $i a $t0", expected[i].toRawBits(), sim!!.barometerOnly[i].toRawBits())
        assertEquals(TierScenarios.issueMillis("sesto-fiorentino", t0), sim!!.issueMillis)
        assertEquals(t0, sim.t0Millis)
        assertEquals(RainWindows.anchorOf(sim.issueMillis), t0)
        assertEquals(expected[trendIndex].takeUnless { it.isNaN() }, sim.trendHpaPerHour)
        assertEquals(sim.cleaning.latest?.trendHpaPerHour, sim.kalmanTrendHpaPerHour)
        compared++
      }
      t0 += 3 * hour
    }
    assertTrue("confrontate solo $compared emissioni", compared > 40)
  }

  @Test
  fun `senza abbastanza storia non c'e' un telefono`() {
    val simulator = IssueSimulator(input)
    // Nelle prime ore dell'archivio non ci sono ne' 24 campioni ne' 13 ore di storia pulita.
    assertNull(simulator.simulate(start + hour))
    assertNull(simulator.simulate(start + 6 * hour))
    assertNotNull(simulator.simulate(start + 2 * day))
  }

  @Test
  fun `la normale c'e' solo dopo quindici giorni di archivio`() {
    val simulator = IssueSimulator(input)
    assertNull(simulator.simulate(start + 5 * day)!!.normalHpa)
    assertNotNull(simulator.simulate(start + 20 * day)!!.normalHpa)
  }

  @Test
  fun `il contesto e' quello di ContextSources all'eta' dello scenario, e manca nei livelli senza contesto`() {
    val simulator = IssueSimulator(input)
    val t0 = start + 10 * day
    val issue = simulator.issueMillis(t0)
    for (kind in listOf(TierKind.FRESH, TierKind.STALE, TierKind.STALE_6H)) {
      val age = TierScenarios.contextAgeMillis(kind, "sesto-fiorentino", t0)!!
      val expected = ContextSources.contextAtAge(input.contextReader, issue, age)!!
      val actual = simulator.context(kind, t0, issue)!!
      assertEquals(expected.context, actual.context)
      assertEquals(expected.slotEndMillis, actual.slotEndMillis)
      assertEquals(actual.context.slotEndMillis, actual.slotEndMillis)
    }
    assertNull(simulator.context(TierKind.NONE, t0, issue))
    assertNull(simulator.context(TierKind.NONE_NOCLIMA, t0, issue))
  }

  @Test
  fun `il replay rifattorizzato da' le stesse probabilita' del modello di un calcolo diretto`() {
    val period = TierPeriod("sint", start + 20 * day, start + 23 * day, start)
    val model = NowcastModel.trained()
    val result = TierReplayer(period, model = model, threads = 1).replay(listOf(input))
    val column = result.indexOf("nowcast-spedito")
    val simulator = IssueSimulator(input)
    var checked = 0
    for (record in result.records) {
      val sim = simulator.simulate(record.anchorMillis)!!
      val context = simulator.context(record.kind, record.anchorMillis, record.issueMillis)
      val features = if (context == null) {
        sim.barometerOnly
      } else {
        FeatureExtractor.extract(sim.cleaning, context.context, sim.normalHpa, record.issueMillis)!!
      }
      val expected = model.verdict(features).forWindow(record.window.label)!!.probability.coerceIn(0.0, 1.0)
      assertEquals(expected, record.probabilities[column], 0.0)
      checked++
    }
    assertTrue(checked > 100)
  }

  @Test
  fun `l'ancora dell'emissione resta t0 per ogni localita' e ogni ora`() {
    val simulator = IssueSimulator(input)
    var t0 = start + 3 * day
    repeat(200) {
      assertEquals(t0, RainWindows.anchorOf(simulator.issueMillis(t0)))
      t0 += hour
    }
  }
}
