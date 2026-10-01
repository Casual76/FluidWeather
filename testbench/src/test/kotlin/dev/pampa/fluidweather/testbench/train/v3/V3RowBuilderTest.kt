package dev.pampa.fluidweather.testbench.train.v3

import dev.pampa.fluidweather.nowcast.features.ContextSlots
import dev.pampa.fluidweather.nowcast.features.ContextTier
import dev.pampa.fluidweather.nowcast.features.FeatureExtractorV3
import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.testbench.baselines.HonestBaselines
import dev.pampa.fluidweather.testbench.replay.TruthKind
import dev.pampa.fluidweather.testbench.tiers.IssueSimulator
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.ScenarioMode
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V3RowBuilderTest {

  private val hour = SyntheticWorld.HOUR
  private val day = 24 * hour
  private val start = SyntheticWorld.START_MILLIS

  /** Y1 venti giorni, Y2 venti, Y3 venti, TEST dal giorno sessanta: il piano compresso sul mondo sintetico. */
  private val plan = JackknifePlan(start, start + 20 * day, start + 40 * day, start + 60 * day)

  private val trainPeriod = TierPeriod("train", start + 2 * day, plan.y3StartMillis, start)
  private val valPeriod = TierPeriod("val", plan.y3StartMillis, plan.testStartMillis, start)

  private fun world(name: String, phase: Int = 0, panelFrom: Long = start): LocationInputs =
    SyntheticWorld.inputs(name, days = 65, phase = phase, panelFromMillis = panelFrom)

  private val inputs: List<LocationInputs> = listOf(world("a"), world("b", phase = 7))

  private fun builder(inputs: List<LocationInputs> = this.inputs, threads: Int = 2) =
    V3RowBuilder(inputs, JackknifeSlices(plan, inputs), threads)

  private fun train(stage: V3Stage, builder: V3RowBuilder = builder(), step: Long = 3 * hour) =
    builder.build(V3RowSpec(trainPeriod, step, ScenarioMode.TRAINING, stage))

  // ------------------------------------------------------------------ le righe

  @Test
  fun `una emissione fa quattro righe, una per livello, in ordine deterministico`() {
    val b = builder()
    val rows = train(V3Stage.TUNE, b)
    assertTrue(rows.size > 1000)
    for (tier in ContextTier.entries) assertTrue("nessuna riga $tier", rows.count(tier) > 100)
    // Ordine: localita', poi t0, poi livello; dentro un t0 i livelli sono nell'ordine di TierKind.PRIMARY.
    var previous: Triple<Int, Long, Int>? = null
    for (row in 0 until rows.size) {
      val key = Triple(rows.locationIndex[row].toInt(), rows.t0[row], rows.tier[row].toInt())
      if (previous != null) assertTrue("ordine rotto alla riga $row", compareValues(previous.first, key.first) < 0 || (previous.first == key.first && (previous.second < key.second || (previous.second == key.second && previous.third < key.third))))
      previous = key
    }
    // Tutte e quattro le righe di una stessa emissione condividono t0 e istante di emissione.
    val first = rows.t0[0]
    val sameIssue = (0 until rows.size).filter { rows.t0[it] == first && rows.locationIndex[it] == rows.locationIndex[0] }
    assertEquals(setOf(rows.issue[sameIssue.first()]), sameIssue.map { rows.issue[it] }.toSet())
  }

  @Test
  fun `stesso risultato con un thread o con quattro`() {
    val one = train(V3Stage.TUNE, builder(threads = 1))
    val four = train(V3Stage.TUNE, builder(threads = 4))
    assertEquals(one.size, four.size)
    assertTrue(one.features.contentEquals(four.features))
    assertTrue(one.panel.contentEquals(four.panel))
    assertTrue(one.t0.contentEquals(four.t0))
    assertTrue(one.tier.contentEquals(four.tier))
    assertTrue(one.flags.contentEquals(four.flags))
  }

  @Test
  fun `le etichette sono quelle di RainWindows sulle due verita'`() {
    val rows = train(V3Stage.REFIT)
    val byName = inputs.associateBy { it.location.name }
    var checked = 0
    for (row in (0 until rows.size step 11)) {
      val input = byName.getValue(rows.locationOf(row))
      for ((w, window) in RainWindows.ALL.withIndex()) {
        val cut = RainWindows.lastSlotEnd(rows.issue[row], window) > plan.labelCutoffMillis(V3Stage.REFIT, plan.regionOf(rows.t0[row])!!)
        val expectedPanel = if (cut) -1 else when (input.panelTruth.outcome(rows.issue[row], window)) { null -> -1; false -> 0; true -> 1 }
        val expectedEra5 = if (cut) -1 else when (input.era5Truth.outcome(rows.issue[row], window)) { null -> -1; false -> 0; true -> 1 }
        assertEquals(expectedPanel, rows.label(row, w, TruthKind.PANEL))
        assertEquals(expectedEra5, rows.label(row, w, TruthKind.ERA5))
        checked++
      }
    }
    assertTrue(checked > 200)
  }

  @Test
  fun `un'etichetta che si chiude oltre il limite della fase sparisce solo per quella finestra`() {
    // TUNE: il limite delle righe di addestramento e' l'inizio di VALIDATION. Emissione a 2 ore dal confine.
    val period = TierPeriod("bordo", plan.y3StartMillis - 2 * hour, plan.y3StartMillis, start)
    val rows = builder().build(V3RowSpec(period, hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    val atEdge = (0 until rows.size).filter { rows.t0[it] == plan.y3StartMillis - 2 * hour }
    assertTrue(atEdge.isNotEmpty())
    for (row in atEdge) {
      assertTrue("0-1h dentro il limite (si chiude a t0+1h)", rows.label(row, 0) >= 0)
      assertEquals("1-3h si chiude a t0+3h, oltre VALIDATION", -1, rows.label(row, 1))
      assertEquals("3-6h", -1, rows.label(row, 2))
      assertEquals(-1, rows.label(row, 1, TruthKind.ERA5))
    }
    // Le finestre tagliate sono contate.
    assertTrue(builderStats(period).labelsCutByPhase > 0)
    // In REFIT il limite e' TEST, lontano: le stesse righe hanno tutte e tre le etichette.
    val refit = builder().build(V3RowSpec(period, hour, ScenarioMode.TRAINING, V3Stage.REFIT))
    val refitEdge = (0 until refit.size).filter { refit.t0[it] == plan.y3StartMillis - 2 * hour }
    for (row in refitEdge) for (w in 0..2) assertTrue(refit.label(row, w) >= 0)
  }

  private fun builderStats(period: TierPeriod): V3BuildStats {
    val b = builder()
    b.build(V3RowSpec(period, hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    return b.lastStats
  }

  // ------------------------------------------------------------------ le estrazioni casuali degli scenari

  @Test
  fun `in addestramento le estrazioni escono ai tassi dichiarati`() {
    val b = builder()
    val rows = train(V3Stage.TUNE, b, step = hour)
    fun rate(tier: ContextTier, flag: Int): Double {
      val n = rows.count(tier)
      return (0 until rows.size).count { rows.tierOf(it) == tier && rows.flags[it].toInt() and flag != 0 }.toDouble() / n
    }
    for (tier in listOf(ContextTier.FRESH, ContextTier.STALE, ContextTier.NONE)) {
      assertEquals("storia $tier", 0.8, rate(tier, V3Rows.FLAG_HISTORY_KNOWN), 0.04)
    }
    assertEquals("tabelle FRESH", 0.9, rate(ContextTier.FRESH, V3Rows.FLAG_LOCAL_PRIORS), 0.04)
    assertEquals("tabelle STALE", 0.9, rate(ContextTier.STALE, V3Rows.FLAG_LOCAL_PRIORS), 0.04)
    assertEquals("tabelle NONE", 1.0, rate(ContextTier.NONE, V3Rows.FLAG_LOCAL_PRIORS), 0.0)
    assertEquals("storia NONE_NOCLIMA", 0.0, rate(ContextTier.NONE_NOCLIMA, V3Rows.FLAG_HISTORY_KNOWN), 0.0)
    assertEquals("tabelle NONE_NOCLIMA", 0.0, rate(ContextTier.NONE_NOCLIMA, V3Rows.FLAG_LOCAL_PRIORS), 0.0)
    // Le statistiche dicono le stesse cose.
    val stats = b.lastStats
    assertEquals(rows.count(ContextTier.FRESH), stats.rowsByTier.getValue(ContextTier.FRESH))
    assertEquals(0, stats.historyKnownByTier.getValue(ContextTier.NONE_NOCLIMA))
  }

  @Test
  fun `le estrazioni si vedono nelle colonne, normale assente e tabelle di tutti i posti`() {
    val rows = train(V3Stage.TUNE, step = hour)
    val normalMissing = FeatureExtractorV3.NORMAL_MISSING
    val localTables = FeatureExtractorV3.LOCAL_TABLES
    var checkedHistory = 0
    for (row in 0 until rows.size) {
      assertEquals(if (rows.localPriors(row)) 1f else 0f, rows.feature(row, localTables), 0f)
      if (!rows.historyKnown(row)) {
        // Storia non estratta: la normale non c'e', mai.
        assertEquals(1f, rows.feature(row, normalMissing), 0f)
        assertTrue(rows.feature(row, 5).isNaN())
        checkedHistory++
      }
      if (rows.tierOf(row) == ContextTier.NONE_NOCLIMA) assertTrue(rows.feature(row, 5).isNaN())
    }
    assertTrue(checkedHistory > 300)
    // Dopo i quindici giorni di archivio una riga con la storia estratta ha la normale.
    val late = (0 until rows.size).filter { rows.t0[it] > start + 17 * day && rows.historyKnown(it) }
    assertTrue(late.isNotEmpty())
    for (row in late) assertEquals(0f, rows.feature(row, normalMissing), 0f)
  }

  @Test
  fun `in valutazione il telefono ha tutto quello che il livello puo' avere`() {
    val rows = builder().build(V3RowSpec(valPeriod, 3 * hour, ScenarioMode.EVALUATION, V3Stage.TUNE))
    assertTrue(rows.size > 200)
    for (row in 0 until rows.size) {
      when (rows.tierOf(row)) {
        ContextTier.NONE_NOCLIMA -> {
          assertFalse(rows.historyKnown(row))
          assertFalse(rows.localPriors(row))
        }

        else -> {
          assertTrue(rows.historyKnown(row))
          assertTrue(rows.localPriors(row))
        }
      }
    }
  }

  // ------------------------------------------------------------------ tabelle mancanti

  @Test
  fun `una localita senza pannello nell'anno delle tabelle perde NONE e ripiega sul riferimento in FRESH e STALE`() {
    // "c": il pannello comincia con Y2 (come fuori Europa). In TUNE le righe di Y2 hanno le tabelle di Y1: vuote per "c".
    val c = world("c", phase = 3, panelFrom = plan.y2StartMillis)
    val all = listOf(world("a"), c)
    val b = builder(all)
    val rows = b.build(V3RowSpec(TierPeriod("y2", plan.y2StartMillis + day, plan.y3StartMillis, start), 3 * hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    val cIndex = 1
    fun cRows(tier: ContextTier) = (0 until rows.size).filter { rows.locationIndex[it].toInt() == cIndex && rows.tierOf(it) == tier }
    assertTrue("NONE di c dovrebbe sparire", cRows(ContextTier.NONE).isEmpty())
    assertTrue(b.lastStats.droppedNoTables.getValue(ContextTier.NONE) > 0)
    assertTrue(cRows(ContextTier.FRESH).isNotEmpty())
    assertTrue(cRows(ContextTier.NONE_NOCLIMA).isNotEmpty())
    // FRESH e STALE di c hanno il riferimento di tutti i posti: clima-locale = 0.
    for (tier in listOf(ContextTier.FRESH, ContextTier.STALE)) {
      for (row in cRows(tier)) assertEquals(0f, rows.feature(row, FeatureExtractorV3.LOCAL_TABLES), 0f)
    }
    // La localita' "a" ha tutti e quattro i livelli con le sue tabelle.
    assertTrue((0 until rows.size).any { rows.locationIndex[it].toInt() == 0 && rows.tierOf(it) == ContextTier.NONE })
    // E in REFIT, dove Y2 legge anche Y3, "c" ha di nuovo le tabelle locali.
    val refit = b.build(V3RowSpec(TierPeriod("y2", plan.y2StartMillis + day, plan.y3StartMillis, start), 3 * hour, ScenarioMode.TRAINING, V3Stage.REFIT))
    assertTrue((0 until refit.size).any { refit.locationIndex[it].toInt() == cIndex && refit.tierOf(it) == ContextTier.NONE })
  }

  @Test
  fun `senza etichette del pannello in addestramento l'emissione non entra`() {
    // "c" non ha pannello in Y1: nessuna riga di "c" prima di Y2.
    val c = world("c", panelFrom = plan.y2StartMillis)
    val rows = builder(listOf(c)).build(V3RowSpec(trainPeriod, 3 * hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    assertTrue(rows.size > 0)
    assertTrue((0 until rows.size).all { rows.t0[it] >= plan.y2StartMillis - 6 * hour })
  }

  // ------------------------------------------------------------------ train/serve parity

  /** Il pacchetto del telefono: una riga per ora con i valori del contesto, per timestamp esatto. */
  private fun phoneContext(input: LocationInputs, fetchedAt: Long) =
    ContextSlots.contextAt(fetchedAt) { slot, variable -> input.contextReader.value(slot, variable.openMeteo) }

  @Test
  fun `una riga e' uguale alle feature che il telefono calcola con le stesse tabelle`() {
    val rows = builder().build(V3RowSpec(valPeriod, 3 * hour, ScenarioMode.EVALUATION, V3Stage.TUNE))
    // Le tabelle del gate di VALIDATION, costruite per conto proprio dalla storia del periodo.
    val gate = HonestBaselines.buildAll(valPeriod, inputs)
    var checked = 0
    for (row in (0 until rows.size step 7)) {
      val input = inputs.single { it.location.name == rows.locationOf(row) }
      val tier = rows.tierOf(row)
      val kind = TierKind.PRIMARY.single { it.tier == tier }
      val t0 = rows.t0[row]
      val sim = IssueSimulator(input).simulate(t0)!!
      val context = if (kind.hasContext) {
        val age = TierScenarios.contextAgeMillis(kind, input.location.name, t0)!!
        phoneContext(input, sim.issueMillis - age)
      } else {
        null
      }
      val honest = gate.getValue(input.location.name)
      val expected = FeatureExtractorV3.extract(
        cleaning = sim.cleaning,
        context = context,
        normalHpa = if (tier == ContextTier.NONE_NOCLIMA) null else sim.normalHpa,
        nowMillis = sim.issueMillis,
        priors = honest.priors(tier)!!,
        referenceAltitudeKnown = true,
        levelBiasHpa = TierScenarios.levelBiasHpa(input.location.name, sim.issueMillis),
      )!!
      for (i in expected.indices) {
        assertEquals("riga $row ${FeatureExtractorV3.names[i]} ($tier)", expected[i].toFloat().toRawBits(), rows.feature(row, i).toRawBits())
      }
      checked++
    }
    assertTrue("verificate $checked righe", checked > 40)
  }

  @Test
  fun `le righe con contesto hanno tutte la pioggia dell'ultima ora, le altre niente contesto`() {
    val rows = train(V3Stage.TUNE)
    for (row in 0 until rows.size) {
      val raining = rows.feature(row, FeatureExtractorV3.RAINING_NOW)
      val age = rows.feature(row, FeatureExtractorV3.CONTEXT_AGE)
      if (rows.tierOf(row).hasContext) {
        assertFalse(raining.isNaN())
        assertFalse(age.isNaN())
        val kind = TierKind.PRIMARY.single { it.tier == rows.tierOf(row) }
        val expectedAge = TierScenarios.contextAgeMillis(kind, rows.locationOf(row), rows.t0[row])!!
        // L'eta' delle feature e' quella dello scenario piu' il resto dell'ora: fra l'eta' e l'eta' + 1 ora, ritagliata a 14.
        val hours = expectedAge / hour.toDouble()
        assertTrue("eta' ${age} fuori da [$hours, ${hours + 1 + 1}]", age >= hours - 1e-6 && age <= minOf(14.0, hours + 2.0) + 1e-6)
      } else {
        assertTrue(raining.isNaN())
        assertTrue(age.isNaN())
      }
    }
  }

  @Test
  fun `le colonne del contesto e della persistenza sono NaN nei livelli senza contesto`() {
    val rows = train(V3Stage.TUNE)
    for (row in 0 until rows.size) {
      if (rows.tierOf(row).hasContext) continue
      for (i in FeatureExtractorV3.CONTEXT_AGE..FeatureExtractorV3.TEMPERATURE_TREND) assertTrue(rows.feature(row, i).isNaN())
      for (w in 0..2) assertTrue(rows.feature(row, FeatureExtractorV3.PERSISTENCE + w).isNaN())
      // Clima e regola barometrica, mai NaN.
      for (w in 0..2) {
        assertFalse(rows.feature(row, FeatureExtractorV3.CLIMATOLOGY + w).isNaN())
        assertFalse(rows.feature(row, FeatureExtractorV3.BAROMETRIC_RULE + w).isNaN())
      }
    }
    // La finestra 0 di un livello con contesto ha invece sempre la persistenza.
    val withContext = (0 until rows.size).filter { rows.tierOf(it).hasContext }
    assertTrue(withContext.all { !rows.feature(it, FeatureExtractorV3.PERSISTENCE).isNaN() })
  }

  @Test
  fun `la riga non cambia se cambia il suo anno nel mondo, salvo la sua etichetta`() {
    // Le tabelle di una riga di Y2 non vedono Y2: un diluvio nel pannello di Y2 cambia le etichette, non le feature
    // di baseline (le colonne 30-38 e 41: clima, persistenza, regola barometrica, tabelle locali).
    val normal = world("a")
    val flooded = SyntheticWorld.inputs("a", days = 65, panelOverrideAfterMillis = plan.y2StartMillis, panelOverrideUntilMillis = plan.y3StartMillis)
    val period = TierPeriod("y2", plan.y2StartMillis + day, plan.y3StartMillis - day, start)
    fun build(input: LocationInputs) =
      builder(listOf(input)).build(V3RowSpec(period, 3 * hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    val a = build(normal)
    val b = build(flooded)
    assertEquals(a.size, b.size)
    var labelsDiffer = false
    for (row in 0 until a.size) {
      for (column in FeatureExtractorV3.CLIMATOLOGY..FeatureExtractorV3.BAROMETRIC_RULE + 2) {
        assertEquals("riga $row colonna $column", a.feature(row, column).toRawBits(), b.feature(row, column).toRawBits())
      }
      for (w in 0..2) if (a.label(row, w) != b.label(row, w)) labelsDiffer = true
    }
    assertTrue("il diluvio doveva cambiare le etichette", labelsDiffer)
    // E la riga di Y1, che legge Y2, cambia le sue feature di baseline.
    val y1 = TierPeriod("y1", start + 3 * day, plan.y2StartMillis - day, start)
    val c = builder(listOf(normal)).build(V3RowSpec(y1, 3 * hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    val d = builder(listOf(flooded)).build(V3RowSpec(y1, 3 * hour, ScenarioMode.TRAINING, V3Stage.TUNE))
    var baselinesDiffer = false
    for (row in 0 until c.size) {
      for (column in FeatureExtractorV3.CLIMATOLOGY..FeatureExtractorV3.BAROMETRIC_RULE + 2) {
        if (c.feature(row, column) != d.feature(row, column)) baselinesDiffer = true
      }
    }
    assertTrue("le righe di Y1 devono leggere Y2", baselinesDiffer)
    assertNotEquals(0, c.size)
  }

  @Test
  fun `le localita' si tengono nell'ordine dei dati`() {
    val rows = train(V3Stage.TUNE)
    assertEquals(listOf("a", "b"), rows.locations)
    assertEquals(setOf(0, 1), (0 until rows.size).map { rows.locationIndex[it].toInt() }.toSet())
  }

  @Test
  fun `le statistiche non perdono emissioni`() {
    val b = builder()
    train(V3Stage.TUNE, b)
    val stats = b.lastStats
    val anchors = trainPeriod.issueAnchors(3 * hour).size * inputs.size
    assertEquals(anchors, stats.anchors)
    // Ogni emissione che ha il barometro e le etichette produce una riga per livello, meno gli scarti dichiarati.
    val produced = ContextTier.entries.sumOf { stats.rowsByTier.getValue(it) }
    val dropped = ContextTier.entries.sumOf { stats.droppedNoContext.getValue(it) + stats.droppedNoTables.getValue(it) }
    assertEquals((anchors - stats.noTruth - stats.noBarometer) * 4, produced + dropped)
  }
}
