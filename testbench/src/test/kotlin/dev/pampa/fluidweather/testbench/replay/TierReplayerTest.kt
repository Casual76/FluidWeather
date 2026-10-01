package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.verdict.NowcastModel
import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.tiers.LocationInputs
import dev.pampa.fluidweather.testbench.tiers.SyntheticWorld
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import dev.pampa.fluidweather.testbench.tiers.TierScenarios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TierReplayerTest {

  private val hour = SyntheticWorld.HOUR
  private val day = 24 * hour
  private val start = SyntheticWorld.START_MILLIS

  /** Trenta giorni di storia, sei di periodo, in un mondo di quaranta giorni. */
  private val period = TierPeriod("sint", start + 30 * day, start + 36 * day, start)

  private fun world(name: String, phase: Int = 0) = SyntheticWorld.inputs(name, days = 40, phase = phase)

  private fun replay(
    inputs: List<LocationInputs>,
    model: NowcastModel? = null,
    threads: Int = 1,
    period: TierPeriod = this.period,
  ) = TierReplayer(period, model = model, threads = threads).replay(inputs)

  @Test
  fun `ogni caso porta i predittori che si applicano al suo livello`() {
    val result = replay(listOf(world("sesto-fiorentino")))
    assertTrue(result.records.size > 500)
    assertEquals(7, result.predictorNames.size)

    val persistence = result.indexOf(PredictorNames.PERSISTENZA)
    val infoPersistence = result.indexOf(PredictorNames.INFO_PERSISTENZA_FISSA)
    for (record in result.records) {
      val p = record.probabilities
      assertEquals(result.predictorNames.size, p.size)
      assertEquals(0.0, p[result.indexOf(PredictorNames.SEMPRE_0)], 0.0)
      assertTrue(p[result.indexOf(PredictorNames.CLIMATOLOGIA)] in 0.0..1.0)
      assertTrue(p[result.indexOf(PredictorNames.REGOLA_BAROMETRICA)] in 0.0..1.0)
      // La persistenza esiste solo dove "piove adesso" e' noto.
      assertEquals(record.kind.hasContext, !p[persistence].isNaN())
      assertEquals(record.kind.hasContext, !p[infoPersistence].isNaN())
    }
    // Tutti i livelli e tutte le finestre sono rappresentati, e nessuna emissione e' stata scartata senza dirlo.
    assertEquals(TierKind.entries.toSet(), result.records.map { it.kind }.toSet())
    assertEquals(RainWindows.ALL.indices.toSet(), result.records.map { it.windowIndex }.toSet())
    val coverage = result.coverage.single()
    assertEquals(period.issueAnchors().size, coverage.anchors)
    assertEquals(coverage.anchors, coverage.issues + coverage.noTruth + coverage.noBarometer)
    assertEquals(result.records.size, coverage.records)
  }

  @Test
  fun `il modello entra in classifica accanto alle baseline`() {
    val result = replay(listOf(world("sesto-fiorentino")), model = NowcastModel.trained())
    assertEquals(8, result.predictorNames.size)
    val model = result.indexOf(PredictorNames.MODELLO)
    assertEquals(7, model)
    assertTrue(result.records.all { it.probabilities[model] in 0.0..1.0 })
    // Stessi casi con o senza modello: il modello non cambia l'insieme giudicato.
    val without = replay(listOf(world("sesto-fiorentino")))
    assertEquals(without.records.size, result.records.size)
  }

  @Test
  fun `l'emissione sta nell'ora prima di t0 e l'ancora e' t0`() {
    val result = replay(listOf(world("a")))
    for (record in result.records) {
      assertEquals(0L, record.anchorMillis % hour)
      assertEquals(record.anchorMillis, RainWindows.anchorOf(record.issueMillis))
      assertTrue(record.anchorMillis - record.issueMillis in 0 until hour)
    }
    // E non tutte alla stessa ora piena.
    assertTrue(result.records.map { record -> record.issueMillis % hour }.toSet().size > 10)
  }

  @Test
  fun `gli esiti sono quelli di RainWindows sulle due verita'`() {
    val input = world("a")
    val result = replay(listOf(input))
    for (record in result.records.filterIndexed { index, _ -> index % 17 == 0 }) {
      val window = RainWindows.ALL[record.windowIndex]
      val panel = input.panelTruth.outcome(record.issueMillis, window)
      val era5 = input.era5Truth.outcome(record.issueMillis, window)
      assertEquals(panel, record.outcome(TruthKind.PANEL).let { if (it < 0) null else it == 1 })
      assertEquals(era5, record.outcome(TruthKind.ERA5).let { if (it < 0) null else it == 1 })
    }
    val rows = result.predictionRows().toList()
    assertTrue(rows.isNotEmpty())
    assertTrue(rows.all { it.window in RainWindows.ALL.map { w -> w.label } })
    // Le righe srotolate non hanno i NaN: ogni riga e' un predittore che risponde.
    assertTrue(rows.none { it.probability.isNaN() })
  }

  @Test
  fun `la storia del barometro finisce all'emissione, anche a meta' di una raffica`() {
    // Revisione P3b: la raffica parte al quarto d'ora e dura cinque secondi; un'emissione due secondi
    // dopo l'inizio del giro si portava dietro tre campioni del futuro.
    val synthesizer = SampleSynthesizer(world("a").dataset, SamplingProfile.TELEFONO)
    var cut = 0
    for (k in 0 until 200) {
      val issue = start + 5 * day + k * 15 * 60_000L + 2_000L
      val history = TierReplayer.phoneHistory(synthesizer, issue)
      assertTrue(history.isNotEmpty())
      assertTrue(history.all { it.timestampMillis <= issue && it.timestampMillis >= issue - TierReplayer.HISTORY_MILLIS })
      val raw = synthesizer.samplesBetween(issue - TierReplayer.HISTORY_MILLIS, issue)
      if (raw.any { it.timestampMillis > issue }) cut++
    }
    assertTrue("il sintetizzatore doveva produrre campioni oltre l'emissione: $cut", cut > 50)
  }

  @Test
  fun `il barometro causale non dipende dall'ora che si chiude dopo il campione`() {
    // L'interpolazione (il default) mette nel campione delle 10:50 l'ERA5 delle 11:00; la variante
    // causale della misura di sensibilita' no. Due mondi uguali tranne la pressione di un'ora.
    val dataset = world("a").dataset
    val target = start + 10 * day + 11 * hour
    val shifted = dev.pampa.fluidweather.testbench.data.StationDataset(
      dataset.location,
      dataset.elevationMeters,
      dataset.records.map { if (it.timestampMillis == target) it.copy(surfacePressureHpa = it.surfacePressureHpa!! + 5.0) else it },
    )
    val from = target - hour + 1
    val until = target - 1
    fun samples(d: dev.pampa.fluidweather.testbench.data.StationDataset, causal: Boolean) =
      SampleSynthesizer(d, SamplingProfile.TELEFONO, causalPressure = causal).samplesBetween(from, until).map { it.pressureHpa }
    assertTrue(samples(dataset, causal = true).isNotEmpty())
    assertEquals(samples(dataset, causal = true), samples(shifted, causal = true))
    assertNotEquals(samples(dataset, causal = false), samples(shifted, causal = false))
  }

  @Test
  fun `il risultato non dipende dal numero di thread`() {
    val inputs = listOf(world("sesto-fiorentino"), world("milano", phase = 11), world("genova", phase = 40))
    val model = NowcastModel.trained()
    val single = replay(inputs, model = model, threads = 1)
    val many = replay(inputs, model = model, threads = 3)
    assertEquals(single.locations, many.locations)
    assertEquals(single.predictionRows().toList(), many.predictionRows().toList())
    assertEquals(
      single.coverage.map { it.records to it.barometricFallbacks },
      many.coverage.map { it.records to it.barometricFallbacks },
    )
  }

  @Test
  fun `le probabilita' delle baseline non cambiano se cambia il futuro, gli esiti si`() {
    val real = replay(listOf(world("a")))
    val deluge = replay(
      listOf(SyntheticWorld.inputs("a", days = 40, panelOverrideAfterMillis = period.firstMillis)),
    )
    assertEquals(real.records.size, deluge.records.size)

    val honest = listOf(
      PredictorNames.SEMPRE_0,
      PredictorNames.CLIMATOLOGIA,
      PredictorNames.PERSISTENZA,
      PredictorNames.REGOLA_BAROMETRICA,
      PredictorNames.INFO_PERSISTENZA_FISSA,
      PredictorNames.INFO_REGOLA_FISSA,
    ).map { real.indexOf(it) }
    var differentOutcomes = 0
    for ((a, b) in real.records.zip(deluge.records)) {
      assertEquals(a.issueMillis, b.issueMillis)
      assertEquals(a.kind, b.kind)
      for (k in honest) assertEquals(a.probabilities[k], b.probabilities[k], 0.0)
      if (a.outcome(TruthKind.PANEL) != b.outcome(TruthKind.PANEL)) differentOutcomes++
    }
    assertTrue("il diluvio doveva cambiare gli esiti: $differentOutcomes", differentOutcomes > 100)

    // La riga "clima col futuro", invece, il futuro lo vede: e' il suo difetto dichiarato.
    val future = real.indexOf(PredictorNames.INFO_CLIMA_FUTURO)
    assertNotEquals(real.records.first().probabilities[future], deluge.records.first().probabilities[future], 1e-6)
  }

  @Test
  fun `senza contesto scaricato i livelli che lo richiedono scartano il caso, gli altri no`() {
    // Il contesto comincia a meta' del periodo: prima, FRESH e STALE non hanno un "adesso".
    val contextFrom = period.firstMillis + 3 * day
    val late = SyntheticWorld.inputs("a", days = 40, contextFromMillis = contextFrom)
    val result = replay(listOf(late))

    val byKind = result.records.groupBy { it.kind }
    for (kind in TierKind.entries.filter { it.hasContext }) {
      assertTrue("${kind.label} doveva perdere casi", result.coverage.single().droppedNoContext.getValue(kind) > 0)
      // Nessun caso di contesto con un riferimento dentro la parte mancante: il contesto non si inventa.
      for (record in byKind.getValue(kind)) {
        val age = TierScenarios.contextAgeMillis(kind, "a", record.anchorMillis)!!
        assertTrue(RainWindows.lastClosedSlotEnd(record.issueMillis - age) >= contextFrom)
      }
    }
    // NONE e NONE_NOCLIMA non hanno bisogno del contesto: sono in tutte le emissioni.
    assertEquals(byKind.getValue(TierKind.NONE).size, byKind.getValue(TierKind.NONE_NOCLIMA).size)
    assertTrue(byKind.getValue(TierKind.NONE).size > byKind.getValue(TierKind.FRESH).size)
  }

  @Test
  fun `una localita' senza storia del pannello non si gioca e lo dice`() {
    // Un periodo che comincia dopo la fine dei dati: prima del suo inizio il pannello non ha un solo slot.
    val later = start + 70 * day
    val noHistory = TierPeriod("vuoto", later, later + 6 * day, later)
    val result = replay(listOf(world("a")), period = noHistory)
    assertTrue(result.records.isEmpty())
    assertEquals(setOf("a"), result.skipped.keys)
    assertTrue(result.coverage.isEmpty())
  }

  @Test
  fun `il rapporto si scrive e contiene le sezioni promesse`() {
    val result = replay(listOf(world("sesto-fiorentino"), world("milano", phase = 5)), model = NowcastModel.trained())
    val text = TierReportWriter(result, "replay-tiers sint").render()
    for (heading in listOf("COPERTURA", "SINTESI D1", "TABELLE", "STALE A ETA' FISSA", "AFFIDABILITA'", "SECONDARIA", "NOTE")) {
      assertTrue("manca la sezione $heading", text.contains(heading))
    }
    assertTrue(text.contains("EUROPA (2)"))
    assertTrue(text.contains("TUTTE (2)"))
    assertTrue(text.contains("nowcast-spedito"))
    assertTrue(text.contains("FRESH"))
    assertTrue(text.contains("NONE_NOCLIMA"))
    assertFalse(text.contains("NaN"))

    val baselinesOnly = TierReportWriter(replay(listOf(world("sesto-fiorentino"))), "baselines sint").render()
    assertTrue(baselinesOnly.contains("BASELINE ONESTE"))
    assertFalse(baselinesOnly.contains("nowcast-spedito:"))
    assertFalse(baselinesOnly.contains("AFFIDABILITA'"))
  }
}
