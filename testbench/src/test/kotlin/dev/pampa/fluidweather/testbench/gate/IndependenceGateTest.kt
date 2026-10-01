package dev.pampa.fluidweather.testbench.gate

import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.replay.CaseRecord
import dev.pampa.fluidweather.testbench.replay.LocationCoverage
import dev.pampa.fluidweather.testbench.replay.PipelineModels
import dev.pampa.fluidweather.testbench.replay.TierGroups
import dev.pampa.fluidweather.testbench.replay.TierReplayResult
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndependenceGateTest {

  private val hour = 3_600_000L
  private val day = 24 * hour
  private val start = 1_704_067_200_000L
  private val column = "v2 +pavimenti"
  private val tag = "v9-prova+panel-1+board-2"

  private val names = listOf(
    PredictorNames.SEMPRE_0,
    PredictorNames.CLIMATOLOGIA,
    PredictorNames.PERSISTENZA,
    PredictorNames.REGOLA_BAROMETRICA,
    column,
  )

  /** Una pipeline che sa: 0,6 quando piove, 0,1 quando no (Brier 0,0475 contro 0,1875 della climatologia). */
  private val good: (TierKind, Int, Int, Boolean) -> Double = { _, _, _, occurred -> if (occurred) 0.6 else 0.1 }

  /** Una pipeline che non sa niente e lo dice male: 0,5 sempre (Brier 0,25). */
  private val bad: (TierKind, Int, Int, Boolean) -> Double = { _, _, _, _ -> 0.5 }

  /**
   * Quaranta giorni di casi per localita', livello e finestra; piove un caso su quattro. Le baseline
   * dicono il tasso (climatologia e regola 0,25; persistenza 0,3 dove c'e' contesto): la migliore e'
   * la climatologia, Brier 0,1875. [pipeline] riceve (livello, finestra, giorno, esito).
   */
  private fun result(
    locations: List<String>,
    pipelineOf: (String) -> (TierKind, Int, Int, Boolean) -> Double = { good },
  ): TierReplayResult {
    val records = ArrayList<CaseRecord>()
    for (location in locations) {
      val pipeline = pipelineOf(location)
      for (d in 0 until 40) {
        for (h in 0 until 8) {
          val occurred = (d * 8 + h) % 4 == 0
          val issue = start + d * day + h * 3 * hour
          for (kind in TierKind.PRIMARY) {
            for (w in 0 until 3) {
              records += CaseRecord(
                location = location,
                issueMillis = issue,
                anchorMillis = issue,
                kind = kind,
                windowIndex = w,
                panelOutcome = if (occurred) 1 else 0,
                era5Outcome = -1,
                probabilities = doubleArrayOf(
                  0.0,
                  0.25,
                  if (kind.hasContext) 0.3 else Double.NaN,
                  0.25,
                  pipeline(kind, w, d, occurred),
                ),
              )
            }
          }
        }
      }
    }
    val coverage = locations.map { LocationCoverage(it, 320, 0, 0, 320, emptyMap(), 0, 0) }
    return TierReplayResult(TierPeriod("t", start, start + 40 * day, start), names, records, coverage, emptyMap(), emptyMap())
  }

  private val all = TierGroups.EUROPA + TierGroups.WARN_ONLY

  @Test
  fun `una pipeline che batte tutte le baseline ovunque passa`() {
    val gate = IndependenceGate.evaluate(result(all), column, tag)
    assertTrue(gate.passed)
    assertEquals("GATE: PASS $tag", gate.finalLine())
    // Due insiemi, sei europee dure, quattro solo avviso; quattro livelli per tre finestre.
    assertEquals((2 + 6 + 4) * 4 * 3, gate.cells.size)
    assertTrue(gate.cells.all { it.passed && it.bestBaseline == PredictorNames.CLIMATOLOGIA })
    assertEquals(0.0475 - 0.1875, gate.cells.first().margin, 1e-12)
    // La persistenza entra solo dove c'e' contesto: nei livelli NONE la migliore non puo' esserlo.
    assertTrue(gate.warnings.isEmpty())
  }

  @Test
  fun `una cella europea peggiore della migliore baseline boccia il gate e si conta`() {
    val milanoBadInFresh01: (TierKind, Int, Int, Boolean) -> Double = { kind, w, d, occurred ->
      if (kind == TierKind.FRESH && w == 0) 0.5 else good(kind, w, d, occurred)
    }
    val gate = IndependenceGate.evaluate(result(all) { if (it == "milano") milanoBadInFresh01 else good }, column, tag)
    assertFalse(gate.passed)
    // Solo la cella di milano: negli insiemi le altre localita' la coprono.
    val failure = gate.failures.single()
    assertEquals("milano", failure.group)
    assertEquals(TierKind.FRESH, failure.kind)
    assertEquals("0-1h", failure.window)
    assertEquals(0.25 - 0.1875, failure.margin, 1e-12)
    assertEquals("GATE: FAIL $tag (1 celle)", gate.finalLine())
  }

  @Test
  fun `fuori dall'Europa si avvisa e non si boccia`() {
    val gate = IndependenceGate.evaluate(result(all) { if (it == "singapore") bad else good }, column, tag)
    assertTrue(gate.passed)
    assertEquals(12, gate.warnings.size)
    assertTrue(gate.warnings.all { it.group == "singapore" && it.scope == GateScope.WARN && it.verdict == "avviso" })
    // L'insieme TUTTE contiene singapore ma resta sotto: 9 pipeline buone e una cattiva.
    assertTrue(gate.cells.filter { it.group.startsWith("TUTTE") }.all { it.passed })
  }

  @Test
  fun `una localita' solo avviso non boccia il gate nemmeno passando dall'insieme di tutte`() {
    // Revisione P3b: con TUTTE dura, una singapore abbastanza cattiva bocciava il gate da sola (a TEST
    // la cella TUTTE FRESH 0-1h falliva per lei, con l'Europa che passava). Altrove una pipeline appena
    // meglio della climatologia (Brier 0,1801 contro 0,1875), a singapore una che dice il contrario di
    // cio' che succede (Brier 1): TUTTE va sotto, ma e' un avviso.
    val barely: (TierKind, Int, Int, Boolean) -> Double = { _, _, _, occurred -> if (occurred) 0.26 else 0.24 }
    val inverted: (TierKind, Int, Int, Boolean) -> Double = { _, _, _, occurred -> if (occurred) 0.0 else 1.0 }
    val gate = IndependenceGate.evaluate(result(all) { if (it == "singapore") inverted else barely }, column, tag)
    val everything = gate.cells.filter { it.group.startsWith("TUTTE") }
    assertTrue(everything.all { !it.passed && it.scope == GateScope.WARN && it.verdict == "avviso" })
    assertTrue(gate.passed)
    assertTrue(gate.cells.filter { it.group.startsWith("EUROPA") }.all { it.scope == GateScope.HARD && it.passed })

    // Se si giocano solo localita' dure, l'insieme di tutte e' l'Europa: resta duro.
    val onlyEurope = IndependenceGate.evaluate(result(TierGroups.EUROPA) { if (it == "milano") bad else good }, column, tag)
    assertTrue(onlyEurope.cells.filter { it.group.startsWith("TUTTE") }.all { it.scope == GateScope.HARD })
  }

  @Test
  fun `gli argomenti del gate sono periodo e modello, e un refuso non gioca il modello di default`() {
    val plain = IndependenceGate.parseArguments(listOf("test"))!!
    assertEquals(TierPeriods.TEST, plain.period)
    assertEquals(PipelineModels.V2, plain.model)
    assertEquals(PipelineModels.V2R, IndependenceGate.parseArguments(listOf("validation", "--model", "v2r"))!!.model)
    for (wrong in listOf(
      listOf("test", "--modle", "v2r"),
      listOf("test", "v2r"),
      listOf("test", "--model"),
      listOf("test", "--model", "v4"),
      listOf("test", "--model", "v2", "--family", "gbm"),
      listOf("test", "--model", "v3", "--family", "alberi"),
      listOf("test", "--family", "gbm"),
      listOf("test", "--model", "v2r", "--model", "v2"),
      listOf("train"),
      emptyList(),
    )) {
      assertNull("doveva rifiutare $wrong", IndependenceGate.parseArguments(wrong))
    }
  }

  @Test
  fun `il v3 si chiama per nome, con la famiglia facoltativa, senza toccare il disco`() {
    val candidate = IndependenceGate.parseArguments(listOf("validation", "--model", "v3-candidate", "--family", "gbm"))!!
    assertEquals(GateSubjects.V3_CANDIDATE, candidate.subjectName)
    assertEquals(dev.pampa.fluidweather.nowcast.verdict.ModelFamily.GBM, candidate.family)
    assertNull(candidate.model)
    assertEquals("gate-validation-v3-candidate-gbm.txt", IndependenceGate.reportName(candidate))
    val shipped = IndependenceGate.parseArguments(listOf("test", "--family", "logistica", "--model", "v3"))!!
    assertEquals(GateSubjects.V3, shipped.subjectName)
    assertEquals("gate-test-v3-logistica.txt", IndependenceGate.reportName(shipped))
    assertEquals("gate-test.txt", IndependenceGate.reportName(IndependenceGate.parseArguments(listOf("test"))!!))
  }

  @Test
  fun `TEST e' solo per l'artefatto compilato, una volta per versione, con la sua famiglia`() {
    val reports = java.nio.file.Files.createTempDirectory("gate-ledger").toFile()
    try {
      fun refusal(vararg args: String) = IndependenceGate.refusal(IndependenceGate.parseArguments(args.toList())!!, reports)
      assertTrue(refusal("test", "--model", "v3-candidate")!!.contains("candidato"))
      assertTrue(refusal("test", "--model", "v3", "--family", "gbm")!!.contains("--family"))
      assertNull(refusal("test", "--model", "v3"))
      assertNull(refusal("validation", "--model", "v3-candidate"))
      assertNull(refusal("test", "--model", "v2r"))
      // Dopo una riga di TEST per la stessa versione, il v3 non si rigioca.
      val tag = dev.pampa.fluidweather.nowcast.verdict.ModelVersions.tag(model = dev.pampa.fluidweather.nowcast.verdict.TrainedNowcastV3.VERSION)
      java.io.File(reports, IndependenceGate.LEDGER_NAME).writeText("2026-09-30T00:00:00Z test $tag FAIL 3 abcdef0123 GBM\n")
      val again = refusal("test", "--model", "v3")!!
      assertTrue(again.contains("gia' stato giocato") && again.contains("abcdef0123"))
      assertNull(refusal("validation", "--model", "v3"))
    } finally {
      reports.deleteRecursively()
    }
  }

  @Test
  fun `il rapporto dice quando il modello e' in campione e con quale impronta di mappe`() {
    val gate = IndependenceGate.evaluate(result(all), column, tag)
    val validation = IndependenceGate.render(gate, PipelineModels.V2R, TierPeriods.VALIDATION, runNumber = 2, skipped = emptyMap(), fingerprintRunNumber = 1)
    assertTrue(validation.contains("IN CAMPIONE"))
    assertTrue(validation.contains("n. 1 per questa impronta delle mappe (${PipelineModels.V2R.fingerprint})"))
    val test = IndependenceGate.render(gate, PipelineModels.V2, TierPeriods.TEST, runNumber = 1, skipped = emptyMap())
    assertFalse(test.contains("IN CAMPIONE"))
    assertFalse(test.contains("impronta"))
  }

  @Test
  fun `una localita' europea non giocata boccia, non si certifica cio' che non si e' guardato`() {
    val gate = IndependenceGate.evaluate(result(all - "bergen"), column, tag)
    assertFalse(gate.passed)
    assertEquals(12, gate.failures.size)
    assertTrue(gate.failures.all { it.group == "bergen" && it.missing && it.verdict == "MANCA" })
    assertTrue(gate.cells.any { it.group == "EUROPA (5/6)" })
  }

  @Test
  fun `un intervallo che attraversa lo zero e' margine incerto, non un fallimento`() {
    // La pipeline dice la climatologia tranne il giorno 0, in cui e' perfetta: meglio in media, ma
    // un ricampionamento senza il giorno 0 da' differenza zero.
    val barelyBetter: (TierKind, Int, Int, Boolean) -> Double = { _, _, d, occurred ->
      if (d == 0) (if (occurred) 1.0 else 0.0) else 0.25
    }
    val gate = IndependenceGate.evaluate(result(all) { barelyBetter }, column, tag)
    assertTrue(gate.passed)
    assertTrue(gate.cells.all { it.uncertain && it.passed && it.verdict == "passa (margine incerto)" })

    // Il contrario: peggio solo il giorno 0. Si boccia sul valore puntuale, con l'incertezza scritta accanto.
    val barelyWorse: (TierKind, Int, Int, Boolean) -> Double = { _, _, d, occurred ->
      if (d == 0) (if (occurred) 0.0 else 1.0) else 0.25
    }
    val failing = IndependenceGate.evaluate(result(all) { if (it == "genova") barelyWorse else good }, column, tag)
    assertFalse(failing.passed)
    assertTrue(failing.failures.all { it.group == "genova" && it.verdict == "FALLISCE (margine incerto)" })
  }

  @Test
  fun `il rapporto finisce con la riga del verdetto e porta l'etichetta`() {
    val gate = IndependenceGate.evaluate(result(all) { if (it == "reykjavik") bad else good }, column, tag)
    val text = IndependenceGate.render(gate, PipelineModels.V2, TierPeriod("t", start, start + 40 * day, start), runNumber = 3, skipped = emptyMap())
    assertEquals("GATE: FAIL $tag (12 celle)", text.trimEnd().lines().last())
    assertTrue(text.contains("Esecuzione n. 3"))
    assertTrue(text.contains("CELLE DURE CHE NON PASSANO (12)"))
    assertFalse(text.contains("NaN"))
  }
}
