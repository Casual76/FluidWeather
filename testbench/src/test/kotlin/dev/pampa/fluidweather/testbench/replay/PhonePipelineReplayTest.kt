package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.core.model.NowcastIssueRecord
import dev.pampa.fluidweather.core.model.NowcastOutcomeRecord
import dev.pampa.fluidweather.nowcast.learning.LearningState
import dev.pampa.fluidweather.nowcast.learning.PlattParams
import dev.pampa.fluidweather.nowcast.learning.RainObservation
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.nowcast.verdict.RecalibrationV2r
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePipelineReplayTest {

  private val hour = SyntheticWorld.HOUR
  private val day = 24 * hour
  private val start = SyntheticWorld.START_MILLIS

  /** Trenta giorni di storia, venti di periodo: abbastanza perche' Platt si accenda (30 coppie, esiti dopo un giorno). */
  private val period = TierPeriod("sint", start + 30 * day, start + 50 * day, start)

  private fun world(name: String = "sesto-fiorentino", phase: Int = 0) = SyntheticWorld.inputs(name, days = 52, phase = phase)

  private fun replay(
    inputs: List<LocationInputs>,
    extras: List<CasePredictor>,
    threads: Int = 1,
    kinds: List<TierKind> = TierKind.PRIMARY,
  ) = TierReplayer(period, model = PipelineModels.V2.nowcast, kinds = kinds, threads = threads, extras = extras).replay(inputs)

  // ------------------------------------------------------------------ la finalita'

  @Test
  fun `la coda consegna un esito solo quando il pannello lo considera definitivo`() {
    val queue = FinalityQueue()
    val issue = start + 10 * hour - 20 * 60_000L // 09:40, ancora 10:00
    queue.enqueue(issue, intArrayOf(1, 0, -1))
    assertEquals(2, queue.size)

    val delivered = mutableListOf<FinalOutcome>()
    val firstFinal = start + 11 * hour + TruthPanel.FINALITY_MILLIS // 0-1h: ultimo slot 11:00
    queue.release(firstFinal - 1) { delivered += it }
    assertTrue(delivered.isEmpty())
    queue.release(firstFinal) { delivered += it }
    assertEquals(listOf(RainWindows.ZERO_ONE), delivered.map { it.window })
    assertTrue(delivered.single().rained)

    // La 1-3h finisce alle 13:00; la 3-6h era ingiudicabile e non arriva mai.
    queue.release(start + 13 * hour + TruthPanel.FINALITY_MILLIS) { delivered += it }
    assertEquals(listOf(RainWindows.ZERO_ONE, RainWindows.ONE_THREE), delivered.map { it.window })
    assertEquals(0, queue.size)
  }

  /** Una sessione che annota ogni esito ricevuto e l'emissione a cui e' arrivato. */
  private class Spy : CasePredictor {
    val log = java.util.Collections.synchronizedList(mutableListOf<Pair<Long, FinalOutcome>>())
    val issues = java.util.Collections.synchronizedList(mutableListOf<Long>())
    override val columns = listOf("spia")
    override fun session(input: LocationInputs, kind: TierKind): CaseSession = object : CaseSession {
      var now = Long.MIN_VALUE
      override fun predict(view: IssueView): Array<DoubleArray> {
        now = view.issueMillis
        if (kind == TierKind.FRESH) issues += view.issueMillis
        return arrayOf(DoubleArray(RainWindows.ALL.size) { 0.5 })
      }
      override fun onFinalOutcome(outcome: FinalOutcome) {
        if (kind == TierKind.FRESH) log += now to outcome
      }
    }
  }

  @Test
  fun `nessun predittore vede un esito prima della sua finalita', e ognuno arriva una volta`() {
    val input = world()
    val spy = Spy()
    replay(listOf(input), listOf(spy))

    assertTrue("servivano esiti consegnati", spy.log.size > 100)
    // "now" e' l'emissione precedente a quella che riceve l'esito: la consegna avviene prima di predict,
    // quindi l'esito e' definitivo all'emissione successiva a "now", e mai a "now" stesso.
    val issues = spy.issues.toList()
    for ((previousIssue, outcome) in spy.log) {
      val receivingIssue = issues.first { it > previousIssue }
      assertTrue(outcome.finalAtMillis <= receivingIssue)
      assertTrue(outcome.finalAtMillis > previousIssue)
      assertEquals(RainWindows.lastSlotEnd(outcome.issueMillis, outcome.window) + TruthPanel.FINALITY_MILLIS, outcome.finalAtMillis)
      assertEquals(input.panelTruth.outcome(outcome.issueMillis, outcome.window), outcome.rained)
    }
    assertEquals(spy.log.size, spy.log.map { it.second.issueMillis to it.second.window }.toSet().size)
  }

  // ------------------------------------------------------------------ la pipeline

  @Test
  fun `apprendimento vuoto senza pavimenti e' esattamente il modello grezzo`() {
    val pipeline = PhonePipeline("nudo", PipelineModels.V2, EmptyLearning, NoFloors)
    val result = replay(listOf(world()), listOf(pipeline))
    val raw = result.indexOf(PredictorNames.MODELLO)
    val bare = result.indexOf("nudo")
    assertTrue(result.records.isNotEmpty())
    for (record in result.records) assertEquals(record.probabilities[raw], record.probabilities[bare], 1e-12)
  }

  @Test
  fun `i pavimenti alzano e basta, e solo dove il contesto dice che piove`() {
    val input = world()
    val result = replay(
      listOf(input),
      listOf(PhonePipeline("pav", PipelineModels.V2, EmptyLearning, ContextHourFloors)),
    )
    val raw = result.indexOf(PredictorNames.MODELLO)
    val floored = result.indexOf("pav")
    var raised = 0
    for (record in result.records) {
      val p = record.probabilities[floored]
      val q = record.probabilities[raw]
      assertTrue(p >= q - 1e-12)
      if (!record.kind.hasContext) assertEquals(q, p, 1e-12)
      if (p > q + 1e-12) {
        raised++
        // Alzata = al pavimento dichiarato di quella finestra, non oltre.
        assertEquals(RainObservation.FLOORS_WHEN_RAINING.getValue(record.window.label), p, 1e-12)
      }
    }
    assertTrue("nel mondo sintetico piove sei ore ogni quattro giorni: qualche pavimento doveva scattare", raised > 0)
  }

  @Test
  fun `il telefono di oggi impara durante il periodo, e le mappe le stima il codice del telefono`() {
    val diagnostics = PipelineDiagnostics()
    val phone = PhonePipeline("oggi", PipelineModels.V2, TodayLearning, ContextHourFloors, diagnostics)
    val fresh = PhonePipeline("appena-installato", PipelineModels.V2, EmptyLearning, ContextHourFloors)
    val result = replay(listOf(world()), listOf(phone, fresh), kinds = listOf(TierKind.FRESH, TierKind.NONE))

    val stats = diagnostics.of("sesto-fiorentino", TierKind.FRESH)!!
    assertTrue(stats.issues > 100)
    assertTrue(stats.outcomes > 0)
    assertTrue("Platt doveva accendersi", stats.refits > 0)
    assertTrue(stats.plattActive.all { it > 0 })
    assertEquals(RainWindows.ALL.map { it.label }.toSet(), stats.finalPlatt.keys)

    // Il primo giorno i due telefoni dicono la stessa cosa (nessun esito ancora); alla fine no.
    val a = result.indexOf("oggi")
    val b = result.indexOf("appena-installato")
    val firstDay = result.records.filter { it.issueMillis < period.firstMillis + day }
    assertTrue(firstDay.isNotEmpty())
    for (record in firstDay) assertEquals(record.probabilities[b], record.probabilities[a], 1e-12)
    val lastDays = result.records.filter { it.issueMillis > period.endExclusiveMillis - 3 * day }
    assertTrue(lastDays.any { kotlin.math.abs(it.probabilities[a] - it.probabilities[b]) > 1e-6 })
  }

  @Test
  fun `la pipeline non dipende dal numero di thread`() {
    val inputs = listOf(world("sesto-fiorentino"), world("milano", phase = 11), world("genova", phase = 40))
    fun columns() = listOf(
      PhonePipeline("oggi", PipelineModels.V2, TodayLearning, ContextHourFloors),
      PhonePipeline("v2r", PipelineModels.V2R, EmptyLearning, ContextHourFloors),
    )
    val single = replay(inputs, columns(), threads = 1)
    val many = replay(inputs, columns(), threads = 3)
    assertEquals(single.predictionRows().toList(), many.predictionRows().toList())
  }

  @Test
  fun `v2r senza pavimenti e' la mappa generata applicata al grezzo`() {
    val result = replay(listOf(world()), listOf(PhonePipeline("v2r", PipelineModels.V2R, EmptyLearning, NoFloors)))
    val raw = result.indexOf(PredictorNames.MODELLO)
    val v2r = result.indexOf("v2r")
    for (record in result.records) {
      val expected = RecalibrationV2r.apply(record.window.label, record.kind.hasContext, record.probabilities[raw])
      assertEquals(expected, record.probabilities[v2r], 1e-9)
    }
  }

  @Test
  fun `due mappe di Platt in fila sono una mappa di Platt`() {
    val inner = PlattParams(0.6, -0.4)
    val outer = PlattParams(1.3, 0.2)
    val composed = PipelineModel.compose(outer, inner)
    for (p in listOf(0.01, 0.1, 0.3, 0.5, 0.8, 0.97)) {
      assertEquals(outer.apply(inner.apply(p)), composed.apply(p), 1e-9)
    }
  }

  @Test
  fun `l'apprendimento e' intercambiabile, e vede solo cio' che il telefono iscrive`() {
    val recorded = java.util.Collections.synchronizedList(mutableListOf<NowcastIssueRecord>())
    val outcomes = java.util.Collections.synchronizedList(mutableListOf<NowcastOutcomeRecord>())
    // Una politica che non impara ma impone sempre la stessa mappa: la finale deve essere quella mappa sul grezzo.
    val fixed = PlattParams(1.0, 1.0)
    val policy = object : LearningPolicy {
      override val name = "fissa"
      override fun newSession() = object : LearningSession {
        override fun stateAt(nowMillis: Long) = LearningState(platt = RainWindows.ALL.associate { it.label to fixed })
        override fun recordIssue(issue: NowcastIssueRecord, nowMillis: Long) {
          assertEquals(nowMillis, issue.issuedAtMillis)
          recorded += issue
        }
        override fun recordOutcome(outcome: NowcastOutcomeRecord) {
          outcomes += outcome
        }
      }
    }
    val result = replay(listOf(world()), listOf(PhonePipeline("fissa", PipelineModels.V2, policy, NoFloors)), kinds = listOf(TierKind.FRESH))
    val raw = result.indexOf(PredictorNames.MODELLO)
    val column = result.indexOf("fissa")
    for (record in result.records) assertEquals(fixed.apply(record.probabilities[raw]), record.probabilities[column], 1e-9)

    // L'emissione iscritta porta le probabilita' grezze del modello, come sul telefono di oggi.
    val byIssue = result.records.groupBy { it.issueMillis }
    for (issue in recorded) {
      val records = byIssue[issue.issuedAtMillis] ?: continue
      val zeroOne = records.first { it.window == RainWindows.ZERO_ONE }
      assertEquals(zeroOne.probabilities[raw], issue.rawProbability01, 1e-12)
    }
    assertTrue(outcomes.isNotEmpty())
  }

  @Test
  fun `l'impronta dice quali mappe sono state giudicate, non solo la versione`() {
    assertEquals("-", PipelineModels.V2.fingerprint)
    val v2r = PipelineModels.V2R.fingerprint
    assertTrue(v2r.matches(Regex("[0-9a-f]{10}")))
    // Stessa versione, una mappa appena diversa: l'impronta cambia (la versione no).
    val refit = PipelineModel(
      name = "v2r",
      version = RecalibrationV2r.VERSION,
      recalibration = { window, hasContext ->
        val c = RecalibrationV2r.coefficients(window, hasContext)
        PlattParams(c.a, if (window == "3-6h" && !hasContext) c.b + 1e-6 else c.b)
      },
    )
    assertEquals(PipelineModels.V2R.tag, refit.tag)
    assertTrue(refit.fingerprint != v2r)
    assertEquals(v2r, PipelineModels.V2R.fingerprint)
  }
}
