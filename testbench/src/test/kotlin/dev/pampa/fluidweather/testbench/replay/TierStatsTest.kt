package dev.pampa.fluidweather.testbench.replay

import dev.pampa.fluidweather.testbench.baselines.PredictorNames
import dev.pampa.fluidweather.testbench.tiers.TierKind
import dev.pampa.fluidweather.testbench.tiers.TierPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TierStatsTest {

  private val hour = 3_600_000L
  private val day = 24 * hour
  private val start = 1_704_067_200_000L

  private val names = listOf(
    PredictorNames.SEMPRE_0,
    PredictorNames.CLIMATOLOGIA,
    PredictorNames.PERSISTENZA,
    PredictorNames.REGOLA_BAROMETRICA,
    PredictorNames.INFO_PERSISTENZA_FISSA,
    PredictorNames.MODELLO,
  )

  /**
   * Quaranta giorni di casi per un livello e una finestra. Piove un caso su quattro, in modo
   * regolare. Il modello ha il tasso giusto e un pizzico di informazione; la persistenza dice il
   * contrario del vero (un disastro); la regola barometrica sta un filo sopra il tasso; il "fisso" informativo
   * e' perfetto ma non e' una baseline e non deve mai essere la migliore.
   */
  private fun result(kind: TierKind = TierKind.FRESH): TierReplayResult {
    val records = ArrayList<CaseRecord>()
    for (d in 0 until 40) {
      for (h in 0 until 8) {
        val occurred = (d * 8 + h) % 4 == 0
        val o = if (occurred) 1 else 0
        val probabilities = doubleArrayOf(
          0.0, // sempre-0
          0.25, // climatologia
          if (occurred) 0.05 else 0.6, // persistenza al contrario
          0.3, // regola barometrica un filo sopra il tasso
          if (occurred) 1.0 else 0.0, // info perfetta
          if (occurred) 0.6 else 0.2, // modello con un po' di abilita'
        )
        records += CaseRecord(
          location = "a",
          issueMillis = start + d * day + h * 3 * hour,
          anchorMillis = start + d * day + h * 3 * hour,
          kind = kind,
          windowIndex = 1,
          panelOutcome = o,
          era5Outcome = if (h == 0) -1 else o,
          probabilities = probabilities,
        )
      }
    }
    val period = TierPeriod("t", start, start + 40 * day, start)
    return TierReplayResult(period, names, records, emptyList(), emptyMap(), emptyMap())
  }

  @Test
  fun `le righe di punteggio sono quelle calcolate a mano`() {
    val stats = TierStats(result())
    val cases = stats.cases(listOf("a"), TierKind.FRESH, 1, TruthKind.PANEL)
    assertEquals(320, cases.size)
    val rows = stats.rows(cases, TruthKind.PANEL)

    val climatology = stats.byName(rows, PredictorNames.CLIMATOLOGIA)!!
    assertEquals(0.25, climatology.base, 1e-12)
    // Brier della costante 0,25 su un tasso 0,25: 0,25 * 0,75.
    assertEquals(0.1875, climatology.brier, 1e-12)
    assertEquals(0.0, climatology.bss, 1e-12)

    val zero = stats.byName(rows, PredictorNames.SEMPRE_0)!!
    assertEquals(0.25, zero.brier, 1e-12)
    assertEquals(1.0 - 0.25 / 0.1875, zero.bss, 1e-12)
    assertEquals(0.25, zero.mae, 1e-12)

    val model = stats.byName(rows, PredictorNames.MODELLO)!!
    val expected = 0.25 * 0.4 * 0.4 + 0.75 * 0.2 * 0.2
    assertEquals(expected, model.brier, 1e-12)
    assertEquals(1.0 - expected / 0.1875, model.bss, 1e-12)
    assertTrue(model.logLoss > 0.0)
  }

  @Test
  fun `la migliore baseline e' una delle tre del gate, mai un'info ne' sempre-0`() {
    val stats = TierStats(result())
    val rows = stats.rows(stats.cases(listOf("a"), TierKind.FRESH, 1, TruthKind.PANEL), TruthKind.PANEL)
    val best = stats.bestBaseline(rows)!!
    // L'"info" perfetta ha Brier zero ma non concorre; la persistenza al contrario e' la peggiore.
    assertEquals(PredictorNames.CLIMATOLOGIA, best.predictor)
    val perfect = stats.byName(rows, PredictorNames.INFO_PERSISTENZA_FISSA)!!
    assertEquals(0.0, perfect.brier, 1e-12)
  }

  @Test
  fun `ogni verita' giudica i suoi casi`() {
    val stats = TierStats(result())
    val panel = stats.cases(listOf("a"), TierKind.FRESH, 1, TruthKind.PANEL)
    val era5 = stats.cases(listOf("a"), TierKind.FRESH, 1, TruthKind.ERA5)
    // ERA5 non sa giudicare le emissioni h == 0 (40 casi).
    assertEquals(320, panel.size)
    assertEquals(280, era5.size)
    assertTrue(stats.cases(listOf("a"), TierKind.NONE, 1, TruthKind.PANEL).isEmpty())
    assertTrue(stats.cases(listOf("altrove"), TierKind.FRESH, 1, TruthKind.PANEL).isEmpty())
  }

  @Test
  fun `il confronto appaiato ha il segno giusto e un intervallo che lo dice`() {
    val stats = TierStats(result())
    val cases = stats.cases(listOf("a"), TierKind.FRESH, 1, TruthKind.PANEL)
    val paired = stats.pairedAgainstBest(cases, TruthKind.PANEL)
    assertNotNull(paired)
    paired!!
    assertEquals(PredictorNames.CLIMATOLOGIA, paired.baseline)
    val expectedDelta = (0.25 * 0.16 + 0.75 * 0.04) - 0.1875
    assertEquals(expectedDelta, paired.delta.mean, 1e-12)
    assertTrue("il modello vince: IC ${paired.delta.low}..${paired.delta.high}", paired.delta.high < 0.0)
    assertEquals(40, paired.delta.days)
    assertEquals(320, paired.delta.count)

    // Il contrario: la persistenza al contrario contro la climatologia perde, con l'intervallo tutto sopra zero.
    val loses = stats.paired(
      cases, TruthKind.PANEL,
      stats.result.indexOf(PredictorNames.PERSISTENZA), stats.result.indexOf(PredictorNames.CLIMATOLOGIA),
    )!!
    assertTrue(loses.low > 0.0)
  }

  @Test
  fun `senza modello il confronto appaiato non c'e'`() {
    val full = result()
    val noModel = TierReplayResult(
      full.period, full.predictorNames.dropLast(1),
      full.records.map {
        CaseRecord(it.location, it.issueMillis, it.anchorMillis, it.kind, it.windowIndex,
          it.outcome(TruthKind.PANEL), it.outcome(TruthKind.ERA5), it.probabilities.copyOf(5))
      },
      emptyList(), emptyMap(), emptyMap(),
    )
    val stats = TierStats(noModel)
    val cases = stats.cases(listOf("a"), TierKind.FRESH, 1, TruthKind.PANEL)
    assertNull(stats.pairedAgainstBest(cases, TruthKind.PANEL))
  }

  @Test
  fun `le righe srotolate portano i due esiti e saltano i predittori che non si applicano`() {
    val base = result(TierKind.NONE)
    // Nei livelli senza contesto la persistenza non c'e': NaN.
    val noPersistence = TierReplayResult(
      base.period, base.predictorNames,
      base.records.take(3).map {
        it.probabilities[2] = Double.NaN
        it.probabilities[4] = Double.NaN
        it
      },
      emptyList(), emptyMap(), emptyMap(),
    )
    val rows = noPersistence.predictionRows().toList()
    assertEquals(3 * (names.size - 2), rows.size)
    assertTrue(rows.none { it.predictor == PredictorNames.PERSISTENZA })
    val first = rows.first()
    assertEquals("a", first.location)
    assertEquals("1-3h", first.window)
    assertEquals(TierKind.NONE, first.kind)
    assertEquals(true, first.panelOutcome) // il primo caso e' un evento
    assertEquals(null, first.era5Outcome) // h == 0: ERA5 non lo sa giudicare
  }
}
