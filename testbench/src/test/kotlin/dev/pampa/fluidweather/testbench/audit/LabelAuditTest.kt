package dev.pampa.fluidweather.testbench.audit

import dev.pampa.fluidweather.nowcast.truth.RainWindows
import dev.pampa.fluidweather.nowcast.truth.TruthPanel
import dev.pampa.fluidweather.testbench.data.BenchLocation
import dev.pampa.fluidweather.testbench.metrics.Contingency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * L'aritmetica dell'audit su un mini-dataset sintetico, verificata a mano: 240 ore da venerdi'
 * 2025-11-28 (l'ora 72 e' il primo dicembre), con pochi slot bagnati in posti noti.
 *
 * Con uno slot bagnato (1 mm) in S, la finestra 0-1h e' bagnata per l'emissione S-1, la 1-3h per
 * S-2 e S-3, la 3-6h per S-4, S-5 e S-6: da qui si contano a mano tutte le tabelle.
 */
class LabelAuditTest {

  private val start = Instant.parse("2025-11-28T00:00:00Z").toEpochMilli()
  private val grid = HourGrid(start, 240)
  private val place = BenchLocation("prova", 43.83, 11.20, "fixture")

  private val mf = TruthPanel.MODELS[0]
  private val ukmo = TruthPanel.MODELS[1]
  private val gem = TruthPanel.MODELS[2]

  private fun series(wet: Set<Int>, holes: Set<Int> = emptySet(), size: Int = grid.size, g: HourGrid = grid) =
    DenseSeries(
      g,
      DoubleArray(size) { i ->
        when {
          i in holes -> Double.NaN
          i in wet -> 1.0
          else -> 0.0
        }
      },
    )

  private fun flat(g: HourGrid = grid, hpa: Double = 1013.0) = DenseSeries(g, DoubleArray(g.size) { hpa })

  /**
   * ERA5 bagnato agli slot 50, 101, 150; i giudici: mf 50/100/101/180, ukmo 50/100/101/200,
   * gem 50/101/200 (la loro mediana: 50, 100, 101, 200); best_match come ERA5, icon e gfs asciutti.
   */
  private fun mainInput(gemHoles: Set<Int> = emptySet()) = LocationInput(
    location = place,
    grid = grid,
    era5 = series(setOf(50, 101, 150)),
    members = mapOf(
      mf to series(setOf(50, 100, 101, 180)),
      ukmo to series(setOf(50, 100, 101, 200)),
      gem to series(setOf(50, 101, 200), holes = gemHoles),
    ),
    memberMsl = flat(),
    context = series(setOf(50, 101, 150)),
    providers = mapOf(LabelAudit.ICON to series(emptySet()), LabelAudit.GFS to series(emptySet())),
  )

  private val test = AuditPeriod(LabelAudit.TEST, grid.timeAt(120), grid.timeAt(233))
  private val full = AuditPeriod(LabelAudit.FULL, grid.timeAt(0), grid.timeAt(233))

  private fun audit(input: LocationInput = mainInput(), testPeriod: AuditPeriod = test, fullPeriod: AuditPeriod = full) =
    LabelAudit.auditLocation(input, testPeriod, fullPeriod)

  private fun LocationResult.window(label: String, period: String = LabelAudit.FULL): WindowStats =
    periods.getValue(period).windows.getValue(label)

  @Test
  fun `le etichette contano a mano, pannello e vecchio consenso contro ERA5`() {
    val result = audit()

    // 234 emissioni (0..233) e tutte giudicabili da tutte le sorgenti: l'insieme comune le prende tutte.
    val w01 = result.window("0-1h")
    assertEquals(234, w01.issues)
    assertEquals(234, w01.common)

    // 0-1h: pannello bagnato alle emissioni 49, 99, 100, 199; ERA5 a 49, 100, 149.
    assertEquals(Contingency(hits = 2, misses = 1, falseAlarms = 2, correctNegatives = 229), w01.panelVsEra)
    // 1-3h: pannello 47, 48, 97, 98, 99, 197, 198; ERA5 47, 48, 98, 99, 147, 148.
    assertEquals(Contingency(4, 2, 3, 225), result.window("1-3h").panelVsEra)
    // 3-6h: pannello 10 emissioni, ERA5 9, in comune 6.
    assertEquals(Contingency(6, 3, 4, 221), result.window("3-6h").panelVsEra)

    val c = w01.panelVsEra!!
    assertEquals(231.0 / 234.0, c.agreement, 1e-12)
    assertEquals(2.0 / 3.0, c.pod, 1e-12)
    assertEquals(0.5, c.far, 1e-12)
    assertEquals(0.4, c.csi, 1e-12)

    // Il vecchio consenso e' la mediana di QUATTRO serie (best_match, icon, gfs, meteofrance; ecmwf manca):
    // con due voti bagnati su quattro la mediana e' 0,5 mm (bagnato), con uno solo e' 0 (asciutto).
    // Slot bagnati: 50 (best_match + mf) e 101 (best_match + mf); 100 (solo mf) e 150 (solo best_match) no.
    assertEquals(Contingency(2, 1, 0, 231), w01.oldVsEra)
    assertEquals(Contingency(4, 2, 0, 228), result.window("1-3h").oldVsEra)
    assertEquals(Contingency(6, 3, 0, 225), result.window("3-6h").oldVsEra)
  }

  @Test
  fun `i tassi base di ogni sorgente sono bagnate su giudicate`() {
    val base = audit().window("0-1h").base
    assertEquals(3.0 / 234.0, base.getValue(LabelAudit.ERA5).rate, 1e-12)
    assertEquals(4.0 / 234.0, base.getValue(LabelAudit.PANEL).rate, 1e-12)
    assertEquals(2.0 / 234.0, base.getValue(LabelAudit.OLD).rate, 1e-12)
    // I giudici da soli: mf e ukmo 4 emissioni, gem 3 (49, 100, 199); best_match come ERA5; icon e gfs mai.
    assertEquals(4, base.getValue(mf).wet)
    assertEquals(4, base.getValue(ukmo).wet)
    assertEquals(3, base.getValue(gem).wet)
    assertEquals(3, base.getValue(LabelAudit.BEST_MATCH).wet)
    assertEquals(0, base.getValue(LabelAudit.ICON).wet)
    assertEquals(0, base.getValue(LabelAudit.GFS).wet)
    assertEquals(234, base.getValue(LabelAudit.ICON).n)
    // ecmwf025 non c'e': la sorgente non compare, non vale zero.
    assertNull(base[LabelAudit.ECMWF])
  }

  @Test
  fun `il pannello vuole tutti e tre i giudici e la copertura lo dice`() {
    // gem senza dato agli slot 60 e 61: lo slot non ha quorum, e ogni finestra che lo contiene resta senza etichetta.
    val result = audit(mainInput(gemHoles = setOf(60, 61)))

    // 0-1h: emissioni 59 e 60. 1-3h: 57, 58, 59. 3-6h: 54..57. Insieme: 54..60 = 7 emissioni senza una finestra.
    assertEquals(232, result.window("0-1h").judged.getValue(LabelAudit.PANEL))
    assertEquals(231, result.window("1-3h").judged.getValue(LabelAudit.PANEL))
    assertEquals(230, result.window("3-6h").judged.getValue(LabelAudit.PANEL))
    // ERA5 le giudica tutte; il confronto si fa solo dove il pannello c'e'.
    assertEquals(234, result.window("0-1h").judged.getValue(LabelAudit.ERA5))
    assertEquals(232, result.window("0-1h").common)

    val stats = result.periods.getValue(LabelAudit.FULL)
    assertEquals(227, stats.completeIssues)
    assertEquals(grid.timeAt(0), stats.firstComplete)
    assertEquals(grid.timeAt(233), stats.lastComplete)

    // Il buco e' nel giudice gem e il rapporto lo nomina.
    assertEquals(2.0 / 240.0, result.gaps.getValue(gem).emptyShare, 1e-12)
    val warnings = LabelAudit.coverageWarnings(LabelAudit.AuditData(grid, test, full, listOf(result), emptyList()))
    assertTrue(warnings.toString(), warnings.any { "COPERTURA prova ${LabelAudit.FULL}" in it && "gem_seamless vuoto" in it })
    // Nel periodo di test i buchi non cadono: nessun avviso.
    assertTrue(warnings.toString(), warnings.none { "COPERTURA prova ${LabelAudit.TEST}" in it })
  }

  @Test
  fun `una sorgente identica a ERA5 si riconosce`() {
    val identity = audit().identity
    // ERA5 bagnato agli slot 50, 101, 150. best_match e' la stessa serie: tutte e tre le ore bagnate coincidono.
    assertEquals(ValueIdentity(wetHours = 3, identical = 3), identity.getValue(LabelAudit.BEST_MATCH))
    // icon e' asciutto ovunque: le tre ore bagnate di ERA5 non coincidono mai.
    assertEquals(ValueIdentity(3, 0), identity.getValue(LabelAudit.ICON))
    // gem: bagnato a 50, 101, 200; le ore bagnate per almeno uno sono 50, 101, 150, 200 e coincidono 50 e 101.
    assertEquals(ValueIdentity(4, 2), identity.getValue(gem))
    assertEquals(0.5, identity.getValue(gem).share, 1e-12)
    // ERA5, pannello e vecchio consenso non si confrontano con se' stessi.
    assertNull(identity[LabelAudit.ERA5])
    assertNull(identity[LabelAudit.PANEL])
    assertNull(identity[LabelAudit.OLD])
    // Il contesto anno per anno: tutto il dataset e' del 2025 (si parte il 28 novembre), 3 ore bagnate su 3 identiche.
    assertEquals(mapOf(2025 to ValueIdentity(3, 3)), audit().contextIdentityByYear)
    // Due localita' sommano i conteggi.
    val group = AuditGroup("TUTTE (2)", listOf(audit(), audit()))
    assertEquals(ValueIdentity(8, 4), group.identity(gem))
  }

  @Test
  fun `le stagioni si contano dall'ancora dell'emissione`() {
    val w = audit().window("0-1h")
    // L'emissione 72 e' il primo dicembre alle 00:00: 0..71 sono novembre (SON), 72..233 dicembre (DJF).
    // Pannello 0-1h bagnato a 49 (SON) e a 99, 100, 199 (DJF); ERA5 a 49 (SON) e a 100, 149 (DJF).
    assertEquals(listOf(RateCount(162, 3), RateCount(0, 0), RateCount(0, 0), RateCount(72, 1)), w.seasonPanel)
    assertEquals(listOf(RateCount(162, 2), RateCount(0, 0), RateCount(0, 0), RateCount(72, 1)), w.seasonEra)
  }

  @Test
  fun `la climatologia si costruisce solo con la storia prima del test`() {
    // Il pannello piove solo agli slot 150..153, cioe' dopo l'inizio del test (emissione 120).
    val wetAfter = setOf(150, 151, 152, 153)
    val input = LocationInput(
      location = place,
      grid = grid,
      era5 = series(emptySet()),
      members = mapOf(mf to series(wetAfter), ukmo to series(wetAfter), gem to series(wetAfter)),
      memberMsl = flat(),
      context = null,
      providers = emptyMap(),
    )
    val result = audit(input)
    val climate = checkNotNull(result.climate) { "climatologia non costruita" }

    // Prima del test e' tutto asciutto: il tasso costante e ogni cella della climatologia valgono 0.
    assertEquals(0.0, climate.constantRate.getValue("0-1h")!!, 0.0)
    assertEquals(0.0, climate.constantRate.getValue("1-3h")!!, 0.0)
    assertTrue(climate.trainingSamples.getValue("0-1h") > 0)

    // Sul test (114 emissioni, 120..233) la climatologia dice 0: il suo Brier e' la quota di bagnate.
    // 0-1h bagnata alle emissioni 149..152 (4); 1-3h alle 147..151 (5).
    val panelLog = climate.logs.getValue(LabelAudit.PANEL)
    assertEquals(114, panelLog.getValue("0-1h").n)
    assertEquals(4, panelLog.getValue("0-1h").wet)
    assertEquals(4.0 / 114.0, panelLog.getValue("0-1h").brier("clima"), 1e-12)
    assertEquals(4.0 / 114.0, panelLog.getValue("0-1h").brier("costante"), 1e-12)
    assertEquals(4.0 / 114.0, panelLog.getValue("0-1h").brier("sempre-0"), 1e-12)
    assertEquals(5.0 / 114.0, panelLog.getValue("1-3h").brier("clima"), 1e-12)
    // Contro ERA5 (asciutto ovunque) la stessa climatologia non sbaglia mai.
    assertEquals(0.0, climate.logs.getValue(LabelAudit.ERA5).getValue("0-1h").brier("clima"), 0.0)
    // Senza contesto best_match non ci sono baseline da provare.
    assertNull(result.baselines)
  }

  /** 60 giorni di pioggia a blocchi di 6 ore ogni 48, uguale per ERA5, giudici e contesto; test dall'emissione 960. */
  private fun blocksResult(): LocationResult {
    val big = HourGrid(start, 24 * 60)
    val wet = (0 until big.size).filter { it % 48 in 10..15 }.toSet()
    val input = LocationInput(
      location = place,
      grid = big,
      era5 = series(wet, g = big, size = big.size),
      members = TruthPanel.MODELS.associateWith { series(wet, g = big, size = big.size) },
      memberMsl = flat(big),
      context = series(wet, g = big, size = big.size),
      providers = emptyMap(),
    )
    val bigTest = AuditPeriod(LabelAudit.TEST, big.timeAt(960), big.timeAt(1433))
    val bigFull = AuditPeriod(LabelAudit.FULL, big.timeAt(0), big.timeAt(1433))
    return audit(input, bigTest, bigFull)
  }

  @Test
  fun `le baseline si provano sugli stessi casi e la persistenza batte la climatologia su piogge a blocchi`() {
    // Blocchi di 6 ore bagnate ogni 48: la pioggia persiste, e la persistenza lo sa.
    val result = blocksResult()

    val baselines = checkNotNull(result.baselines) { "baseline non calcolate" }
    // 474 emissioni di test (960..1433), tutte giudicabili e con entrambi gli ingressi.
    for (label in listOf("0-1h", "1-3h", "3-6h")) {
      assertEquals(474, baselines.judged.getValue(label))
      assertEquals(474, baselines.logs.getValue(label).n)
    }
    val log = baselines.logs.getValue("0-1h")
    assertTrue(
      "persistenza ${log.brier("persistenza")} contro clima ${log.brier("clima")}",
      log.brier("persistenza") < 0.85 * log.brier("clima"),
    )
    assertTrue(log.brier("barometrica").isFinite())
    // "sempre 0" sbaglia esattamente la quota di bagnate.
    assertEquals(log.baseRate, log.brier("sempre-0"), 1e-12)
  }

  @Test
  fun `le localita' dove il contesto e' ERA5 restano fuori dal gruppo indipendente`() {
    // Nei blocchi il contesto best_match e' ERA5 stessa: 180 ore bagnate, tutte identiche. Il dataset principale no.
    val circular = blocksResult()
    assertEquals(1.0, circular.identity.getValue(LabelAudit.BEST_MATCH).share, 0.0)

    val groups = LabelAudit.groupsOf(listOf(circular, audit(), audit()))
    assertEquals(listOf("prova", "prova", "prova", "TUTTE (3)", "INDIPENDENTI (2)"), groups.map { it.name })
    assertEquals(2, groups.last().results.size)
    assertTrue(groups.last().results.none { it === circular })

    // Senza localita' circolari non c'e' un gruppo in piu'.
    assertEquals(listOf("prova", "prova", "TUTTE (2)"), LabelAudit.groupsOf(listOf(audit(), audit())).map { it.name })
  }

  @Test
  fun `gli anni in cui il contesto e' ERA5 si dicono anche se il periodo intero resta sotto soglia`() {
    // Come Reykjavik: sul periodo intero best_match coincide con ERA5 nel 40% delle ore bagnate (non
    // circolare), ma nel 2022 nel 100%. La localita' resta fra le indipendenti, e una nota lo dice.
    val base = audit()
    val partly = LocationResult(
      location = BenchLocation("isola", 64.15, -21.94, "fixture"),
      sources = base.sources,
      periods = base.periods,
      gaps = base.gaps,
      identity = base.identity + (LabelAudit.BEST_MATCH to ValueIdentity(1000, 400)),
      contextIdentityByYear = mapOf(2022 to ValueIdentity(150, 150), 2023 to ValueIdentity(850, 250)),
      climate = base.climate,
      baselines = base.baselines,
    )
    assertEquals(listOf("prova", "isola", "TUTTE (2)"), LabelAudit.groupsOf(listOf(base, partly)).map { it.name })

    val report = LabelAudit.render(LabelAudit.AuditData(grid, test, full, listOf(base, partly), emptyList()))
    val note = report.lines().single { "CIRCOLARE IN PARTE isola" in it }
    assertTrue(note, "2022 (100.0%)" in note && "2023" !in note)
    assertTrue(report.lines().none { "CIRCOLARE IN PARTE prova" in it })
  }

  @Test
  fun `sommare le localita' somma i conteggi`() {
    val one = audit()
    val two = audit()
    val group = AuditGroup("TUTTE (2)", listOf(one, two))

    val single = one.window("0-1h")
    val pooled = group.window(LabelAudit.FULL, "0-1h")!!
    assertEquals(2 * single.issues, pooled.issues)
    assertEquals(2 * single.common, pooled.common)
    assertEquals(Contingency(4, 2, 4, 458), pooled.panelVsEra)
    assertEquals(single.base.getValue(LabelAudit.PANEL).rate, pooled.base.getValue(LabelAudit.PANEL).rate, 1e-12)
    assertEquals(2 * single.base.getValue(LabelAudit.PANEL).n, pooled.base.getValue(LabelAudit.PANEL).n)
    assertEquals(2 * one.periods.getValue(LabelAudit.FULL).completeIssues, group.stats(LabelAudit.FULL)!!.completeIssues)

    // I Brier caso per caso si concatenano: stessa media, il doppio dei casi.
    val log = group.climate(LabelAudit.PANEL, "0-1h")!!
    assertEquals(2 * one.climate!!.logs.getValue(LabelAudit.PANEL).getValue("0-1h").n, log.n)
    assertEquals(
      one.climate!!.logs.getValue(LabelAudit.PANEL).getValue("0-1h").brier("clima"),
      log.brier("clima"),
      1e-12,
    )
  }

  @Test
  fun `il punteggio caso per caso media e confronta`() {
    val log = ScoreLog(listOf("a", "b"))
    // Giorno 1: a dice 0,9 e piove, b dice 0,5. Giorno 2: a dice 0,1 e non piove, b dice 0,5.
    log.add(1, true, doubleArrayOf(0.9, 0.5))
    log.add(2, false, doubleArrayOf(0.1, 0.5))
    assertEquals(2, log.n)
    assertEquals(1, log.wet)
    assertEquals(0.5, log.baseRate, 1e-12)
    assertEquals(0.01, log.brier("a"), 1e-12)
    assertEquals(0.25, log.brier("b"), 1e-12)

    val delta = log.delta("a", "b")
    assertEquals(-0.24, delta.mean, 1e-12)
    assertTrue("intervallo ${delta.low}..${delta.high}", delta.high < 0.0)
    assertEquals(2, delta.days)

    val merged = ScoreLog.merge(listOf(log, log))!!
    assertEquals(4, merged.n)
    assertEquals(0.01, merged.brier("a"), 1e-12)
    assertNull(ScoreLog.merge(emptyList()))
  }

  @Test
  fun `la griglia e le serie cercano solo istanti esatti`() {
    assertEquals(0, grid.indexOf(start))
    assertEquals(5, grid.indexOf(start + 5 * RainWindows.HOUR_MILLIS))
    assertEquals(-1, grid.indexOf(start + 1_800_000L))
    assertEquals(-1, grid.indexOf(start - RainWindows.HOUR_MILLIS))
    assertEquals(-1, grid.indexOf(grid.timeAt(240)))
    assertEquals(2, grid.ceilIndex(start + 1 * RainWindows.HOUR_MILLIS + 1))
    assertEquals(1, grid.floorIndex(start + 1 * RainWindows.HOUR_MILLIS + 1))

    val s = series(setOf(3), holes = setOf(4))
    assertEquals(1.0, s.at(grid.timeAt(3))!!, 0.0)
    assertNull(s.at(grid.timeAt(4)))
    assertNull(s.at(start + 90 * 60_000L))
    assertEquals(3, s.toMap(untilMillis = grid.timeAt(2)).size)
    assertEquals(grid.timeAt(0), s.firstPresentMillis(0, 239))
    assertEquals(1.0 / 240.0, s.emptyShare(0, 239), 1e-12)
  }

  @Test
  fun `il rapporto mostra le tabelle, i numeri e gli avvisi`() {
    val clean = audit()
    val holed = audit(mainInput(gemHoles = setOf(60, 61)))
    val report = LabelAudit.render(
      LabelAudit.AuditData(grid, test, full, listOf(clean, holed), warnings = listOf("prova: manca best_match")),
    )

    for (heading in listOf(
      "LABEL AUDIT", "SINTESI", "COPERTURA, periodo TEST", "COPERTURA, periodo INTERO", "BUCHI DEI GIUDICI", "IDENTITA' CON ERA5", "IDENTITA' DEL CONTESTO best_match",
      "TASSI BASE, finestra 0-1h, periodo INTERO", "ACCORDO E POD/FAR/CSI CONTRO ERA5, finestra 1-3h",
      "STAGIONI, periodo TEST", "CLIMATOLOGIA del pannello", "BASELINE LOCALI", "AVVISI E NOTE", "TUTTE (2)",
    )) {
      assertTrue("manca '$heading' nel rapporto", heading in report)
    }
    // Il tasso base del pannello a 0-1h sulla localita' pulita: 4/234 = 0,017; ERA5 3/234 = 0,013.
    assertTrue(report.lines().any { it.startsWith("  prova") && "0.017" in it && "0.013" in it })
    assertTrue("prova: manca best_match" in report)
    assertTrue(report.contains("COPERTURA prova INTERO"))
    // Solo ASCII: lo stesso testo va sul terminale e su file senza doppie codifiche.
    assertTrue(report.all { it.code < 128 })
  }
}
