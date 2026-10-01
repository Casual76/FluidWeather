package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.tiers.IssueSimulator
import dev.pampa.fluidweather.testbench.tiers.ScenarioMode
import dev.pampa.fluidweather.testbench.train.v3.V3FeatureAssembly
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cio' che un predittore in piu' riceve dal replay: il contesto di sessione e la vista con barometro pulito, normale, slot e baseline. */
class IssueViewTest {

  private val hour = SyntheticWorld.HOUR
  private val day = 24 * hour
  private val start = SyntheticWorld.START_MILLIS
  private val period = TierPeriod("sint", start + 30 * day, start + 32 * day, start)

  private class Recorder : CasePredictor {
    val contexts = Collections.synchronizedList(mutableListOf<Pair<TierKind, SessionContext>>())
    val views = Collections.synchronizedList(mutableListOf<IssueView>())
    override val columns = listOf("registro")
    override fun session(input: LocationInputs, kind: TierKind): CaseSession = error("deve arrivare il contesto")
    override fun session(input: LocationInputs, kind: TierKind, ctx: SessionContext): CaseSession {
      contexts += kind to ctx
      return CaseSession { view ->
        views += view
        arrayOf(DoubleArray(RainWindows.ALL.size) { 0.5 })
      }
    }
  }

  @Test
  fun `le sessioni nascono col contesto e le viste portano barometro, normale, slot e baseline`() {
    val input = SyntheticWorld.inputs("a", days = 40)
    val recorder = Recorder()
    val result = TierReplayer(period, model = null, threads = 1, extras = listOf(recorder)).replay(listOf(input))
    val honest = result.baselines.getValue("a")

    assertEquals(TierKind.entries.size, recorder.contexts.size)
    for ((_, ctx) in recorder.contexts) assertSame(honest, ctx.honest)

    assertTrue(recorder.views.size > 100)
    for (view in recorder.views) {
      assertNotNull(view.cleaning)
      assertSame(honest, view.honest)
      assertTrue(view.cleaning!!.filtered.isNotEmpty())
      if (view.kind.hasContext) {
        val asOf = view.asOf!!
        assertEquals(asOf.context, view.context)
        assertEquals(asOf.slotEndMillis, view.context!!.slotEndMillis)
        assertEquals(asOf.context.rainLastHourMm, view.contextLastHourMm)
        // Lo slot "adesso" del contesto e' chiuso all'emissione e non piu' vecchio delle tredici ore e un'ora.
        assertTrue(view.asOf!!.slotEndMillis <= view.issueMillis)
        assertTrue(view.issueMillis - asOf.slotEndMillis <= 14 * hour)
      } else {
        assertNull(view.asOf)
        assertNull(view.context)
      }
      // La normale c'e' dopo quindici giorni di archivio: il periodo comincia al giorno trenta.
      assertNotNull(view.normalHpa)
    }
  }

  @Test
  fun `le feature della vista si possono rifare con le tabelle e danno lo stesso barometro`() {
    val input = SyntheticWorld.inputs("a", days = 40)
    val recorder = Recorder()
    TierReplayer(period, model = null, threads = 1, extras = listOf(recorder)).replay(listOf(input))
    val view = recorder.views.first { it.kind == TierKind.FRESH }
    val priors = view.honest!!.priors(view.kind.tier)!!
    val v3 = FeatureExtractorV3.extract(view.cleaning!!, view.context, view.normalHpa, view.issueMillis, priors)!!
    // Le prime venti colonne del v3 sono quelle del v2 che la vista porta gia' calcolate.
    for (i in 0 until 20) assertEquals(view.features[i].toRawBits(), v3[i].toRawBits())
  }

  @Test
  fun `la vista basta a rifare le feature del v3, come le rifa' il costruttore di righe`() {
    val input = SyntheticWorld.inputs("a", days = 40)
    val recorder = Recorder()
    TierReplayer(period, model = null, threads = 1, extras = listOf(recorder)).replay(listOf(input))
    val simulator = IssueSimulator(input)
    var checked = 0
    for (view in recorder.views.filter { it.kind.isPrimary }.filterIndexed { i, _ -> i % 5 == 0 }) {
      val loose = V3FeatureAssembly.assemble(
        "a", view.kind, view.anchorMillis, view.issueMillis, view.cleaning!!, view.normalHpa, view.asOf, view.honest!!, ScenarioMode.EVALUATION,
      )!!
      val sim = simulator.simulate(view.anchorMillis)!!
      val viaSim = V3FeatureAssembly.assemble("a", view.kind, sim, view.asOf, view.honest!!, ScenarioMode.EVALUATION)!!
      for (i in loose.features.indices) assertEquals(viaSim.features[i].toRawBits(), loose.features[i].toRawBits())
      checked++
    }
    assertTrue("verificate $checked viste", checked > 8)
  }

  @Test
  fun `senza baseline la sessione non ha tabelle`() {
    val input = SyntheticWorld.inputs("a", days = 40)
    val recorder = Recorder()
    TierReplayer(period, model = null, threads = 1, extras = listOf(recorder), withBaselines = false).replay(listOf(input))
    for ((_, ctx) in recorder.contexts) assertNull(ctx.honest)
    assertTrue(recorder.views.all { it.honest == null })
    assertNotNull(HonestBaselines.buildAll(period, listOf(input)))
  }

  @Test
  fun `un predittore che non conosce il contesto continua a funzionare con la forma di sempre`() {
    val legacy = object : CasePredictor {
      override val columns = listOf("vecchio")
      var created = 0
      override fun session(input: LocationInputs, kind: TierKind): CaseSession {
        created++
        return CaseSession { arrayOf(DoubleArray(RainWindows.ALL.size) { 0.25 }) }
      }
    }
    val result = TierReplayer(period, model = null, threads = 1, extras = listOf(legacy)).replay(listOf(SyntheticWorld.inputs("a", days = 40)))
    assertEquals(TierKind.entries.size, legacy.created)
    val column = result.indexOf("vecchio")
    assertTrue(result.records.all { it.probabilities[column] == 0.25 })
  }
}
