package dev.pampa.fluidweather.testbench.validation

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RainComparisonTest {
  private val baselineRevision = "a".repeat(40)
  private val candidateRevision = "b".repeat(40)
  private val start = Instant.parse("2026-11-07T10:00:00Z")
  private val site = RainSite("synthetic-station", 44.0, 11.0)
  private fun targets() = (0..3).map {
    RainTarget("t$it", site, start.plusSeconds(it * 3600L), start.plusSeconds((it + 1) * 3600L))
  }
  // All values invented. Two unambiguous rain episodes, one dry slot, one unknown reference.
  private fun baseline() = targets().flatMapIndexed { i, t ->
    val state = when (i) { 2 -> RainState.DRY; 3 -> RainState.UNKNOWN; else -> RainState.RAIN }
    val onset = if (i < 2) t.start.plusSeconds(600) else null
    val end = if (i < 2) t.start.plusSeconds(1800) else null
    listOf(
      RainRecord(RecordRole.FORECAST, t, "f$i", EvidenceKind.SYNTHETIC, baselineRevision,
        "forecast", t.start.minusSeconds(300), false, state,
        onset?.minusSeconds(120), end?.plusSeconds(240)),
      RainRecord(RecordRole.OBSERVATION, t, "o$i", EvidenceKind.SYNTHETIC, baselineRevision,
        "independent-script", t.end.plusSeconds(60), true, state, onset, end),
    )
  }
  private fun candidate(rows: List<RainRecord> = baseline()) = rows.map { it.copy(revision = candidateRevision) }
  private fun forecast(rows: List<RainRecord>, key: String, change: (RainRecord) -> RainRecord) = rows.map {
    if (it.role == RecordRole.FORECAST && it.target.key == key) change(it) else it
  }
  private fun rejected(block: () -> Unit) {
    try { block() } catch (_: IllegalArgumentException) { return }
    fail("Invalid paired comparison accepted")
  }

  @Test fun `paired arithmetic retains counts explicit rates and signed timing`() {
    val c = forecast(candidate(), "t0") { it.copy(onset = it.onset!!.plusSeconds(180), end = it.end!!.minusSeconds(180)) }
    val r = RainComparison.evaluate(targets(), baseline(), c)
    assertEquals(3, r.baseline.scoredSlots)
    assertEquals(RainClassification(2, 1, 0, 0), r.commonBaseline)
    assertEquals(RainRate(0, 2), r.commonCandidate.falseDryRate)
    assertEquals(RainRate(0, 1), r.commonCandidate.falsePositiveRate)
    assertEquals(listOf("t0", "t1", "t2"), r.coverage.common)
    assertEquals(listOf("t3"), r.coverage.neither)
    assertEquals(RainTimingPair("t0", -2.0, 1.0), r.onset.pairs.first())
    assertEquals(2.0, r.onset.baselineMaeMinutes!!, 0.0)
    assertEquals(1.5, r.onset.candidateMaeMinutes!!, 0.0)
    assertEquals(-0.5, r.onset.maeDeltaMinutes!!, 0.0)
    assertEquals(4.0, r.end.baselineMaeMinutes!!, 0.0)
    assertEquals(2.5, r.end.candidateMaeMinutes!!, 0.0)
    assertEquals(r, RainComparison.evaluate(targets().reversed(), baseline().reversed(), c.reversed()))
    assertTrue(r.render().contains("unknownObservation=1/4"))
    assertTrue(r.render().contains("falseAlarm/predictedRain=0/2"))
  }

  @Test fun `every observation field except run revision is immutable`() {
    val c = candidate()
    val o = c[1]
    val changes = listOf(
      o.copy(id = "changed"), o.copy(state = RainState.DRY, onset = null, end = null),
      o.copy(source = "another-independent-script"), o.copy(recordedAt = o.recordedAt.plusSeconds(1)),
      o.copy(independent = false), o.copy(onset = o.onset!!.plusSeconds(60)),
      o.copy(end = o.end!!.plusSeconds(60)), o.copy(evidence = EvidenceKind.REAL_LOCAL),
      o.copy(target = o.target.copy(site = site.copy(latitude = 45.0))),
      o.copy(target = o.target.copy(end = o.target.end.minusSeconds(60))),
      o.copy(target = o.target.copy(key = "other-target")),
    )
    changes.forEach { bad -> rejected {
      RainComparison.evaluate(targets(), baseline(), c.toMutableList().apply { this[1] = bad })
    } }
  }

  @Test fun `strict validator rejects missing duplicate extra late or mixed revision rows on either side`() {
    val b = baseline()
    val invalid = listOf(b.drop(1), b + b.first(), b + b.first().copy(id = "extra"),
      b.toMutableList().apply { this[0] = this[0].copy(recordedAt = this[0].target.start) },
      b.toMutableList().apply { this[0] = this[0].copy(revision = candidateRevision) })
    invalid.forEach { bad ->
      rejected { RainComparison.evaluate(targets(), bad, candidate()) }
      rejected { RainComparison.evaluate(targets(), b, bad.map { it.copy(revision = if (it.revision == baselineRevision) candidateRevision else baselineRevision) }) }
    }
    rejected { RainComparison.evaluate(targets() + targets().first(), b, candidate()) }
    rejected { RainComparison.evaluate(targets(), b, b) }
    rejected { RainComparison.evaluate(targets(), b, candidate().map { it.copy(revision = "working-tree") }) }
  }

  @Test fun `withholding an erroneous forecast loses coverage without improving common counts`() {
    val b = forecast(baseline(), "t0") { it.copy(state = RainState.DRY, onset = null, end = null) }
    val c = forecast(candidate(b), "t0") { it.copy(state = RainState.UNKNOWN) }
    val r = RainComparison.evaluate(targets(), b, c)
    assertEquals(1, r.baseline.falseDry)
    assertEquals(0, r.candidate.falseDry)
    assertEquals(listOf("t0"), r.coverage.lost)
    assertEquals(r.commonBaseline, r.commonCandidate)
    assertEquals(1, r.baseline.unknownForecast)
    assertEquals(2, r.candidate.unknownForecast)
    assertTrue(r.render().contains("never improvement"))
  }

  @Test fun `gained coverage is reported separately from shared targets`() {
    val b = forecast(baseline(), "t2") { it.copy(state = RainState.UNKNOWN) }
    val r = RainComparison.evaluate(targets(), b, candidate())
    assertEquals(listOf("t2"), r.coverage.gained)
    assertEquals(2, r.commonCandidate.scored)
    assertEquals(3, r.candidate.scoredSlots)
  }

  @Test fun `lost timing pairs cannot lower paired MAE by dropping the harder case`() {
    val validB = forecast(baseline(), "t1") { it.copy(onset = it.target.start.plusSeconds(120)) }
    val c = forecast(candidate(validB), "t1") { it.copy(onset = null) }
    val r = RainComparison.evaluate(targets(), validB, c)
    assertEquals(listOf("t1"), r.onset.coverage.lost)
    assertEquals(5.0, r.baseline.onset.meanAbsoluteMinutes!!, 0.0)
    assertEquals(2.0, r.candidate.onset.meanAbsoluteMinutes!!, 0.0)
    assertEquals(0.0, r.onset.maeDeltaMinutes!!, 0.0)
    assertEquals(1, r.onset.pairs.size)
    assertEquals(2, r.end.pairs.size)
    val noTiming = c.map { if (it.role == RecordRole.FORECAST) it.copy(onset = null, end = null) else it }
    val absent = RainComparison.evaluate(targets(), validB, noTiming)
    assertNull(absent.onset.candidateMaeMinutes)
    assertNull(absent.onset.baselineMaeMinutes)
    assertNull(absent.onset.maeDeltaMinutes)
    assertEquals(2, absent.end.coverage.lost.size)
    assertTrue(absent.render().contains("candidate-baseline=N/A"))
  }

  @Test fun `evidence mismatch and no common classifiable targets fail explicitly`() {
    rejected { RainComparison.evaluate(targets(), baseline(), candidate().map { it.copy(evidence = EvidenceKind.MODEL_SIMULATION) }) }
    val unknown = candidate().map {
      if (it.role == RecordRole.FORECAST) it.copy(state = RainState.UNKNOWN, onset = null, end = null) else it
    }
    rejected { RainComparison.evaluate(targets(), baseline(), unknown) }
    val b = forecast(forecast(baseline(), "t0") { it.copy(state = RainState.UNKNOWN, onset = null, end = null) }, "t1") {
      it.copy(state = RainState.UNKNOWN, onset = null, end = null)
    }
    val c = forecast(candidate(), "t2") { it.copy(state = RainState.UNKNOWN) }
    // Both runs score something, but they score disjoint targets.
    rejected { RainComparison.evaluate(targets(), b, c) }
    assertNull(RainRate(0, 0).fraction)
  }

  @Test fun `gained timing does not change the paired transition denominator`() {
    val b = forecast(baseline(), "t1") { it.copy(onset = null) }
    val r = RainComparison.evaluate(targets(), b, candidate())
    assertEquals(listOf("t1"), r.onset.coverage.gained)
    assertEquals(listOf("t0"), r.onset.coverage.common)
    assertEquals(listOf("t0", "t1"), r.onset.eligibleObserved)
    assertEquals(1, r.onset.pairs.size)
    assertEquals(0.0, r.onset.maeDeltaMinutes!!, 0.0)
  }

  @Test fun `empty and header only TSV inputs fail rather than returning a report`() {
    rejected { RainComparison.compareTsv("", "", "") }
    rejected { RainComparison.compareTsv(RainRecordingTsv.TARGET_HEADER, RainRecordingTsv.RECORD_HEADER, RainRecordingTsv.RECORD_HEADER) }
    val t = RainRecordingTsv.TARGET_HEADER + "\n" + targets().joinToString("\n") {
      "${it.key}\t${site.id}\t44.0\t11.0\t${it.start}\t${it.end}"
    }
    rejected { RainComparison.compareTsv(t, RainRecordingTsv.RECORD_HEADER, RainRecordingTsv.writeRecords(candidate())) }
    val r = RainComparison.compareTsv(t, RainRecordingTsv.writeRecords(baseline()), RainRecordingTsv.writeRecords(candidate()))
    assertEquals(4, r.baseline.slots)
  }

  @Test fun `no evidence label automatically approves C2 including perfect invented local data`() {
    EvidenceKind.values().forEach { kind ->
      val r = RainComparison.evaluate(targets(), baseline().map { it.copy(evidence = kind) }, candidate().map { it.copy(evidence = kind) })
      assertEquals(RainComparisonGate.REVIEW_NEEDED, r.gate)
      assertTrue(r.render().contains("(OPEN)"))
      assertTrue(r.render().contains("require external review"))
    }
  }
}
