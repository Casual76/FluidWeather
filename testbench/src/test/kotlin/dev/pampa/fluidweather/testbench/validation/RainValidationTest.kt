package dev.pampa.fluidweather.testbench.validation

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class RainValidationTest {
  private fun resource(name: String) = requireNotNull(javaClass.getResource("/rain-validation/$name")).readText()
  private fun targets() = RainRecordingTsv.readTargets(resource("targets.tsv"))
  private fun records() = RainRecordingTsv.readRecords(resource("records.tsv"))
  private fun rejected(block: () -> Unit) {
    try { block() } catch (_: RuntimeException) { return }
    fail("Invalid comparison accepted")
  }

  @Test fun `hand counted synthetic results retain unknowns and timing denominators`() {
    val result = RainValidation.evaluate(targets(), records())
    assertEquals(EvidenceKind.SYNTHETIC, result.evidence)
    assertEquals(10, result.slots)
    assertEquals(8, result.scoredSlots)
    assertEquals(4, result.hits)
    assertEquals(1, result.correctDry)
    assertEquals(2, result.falseDry)
    assertEquals(1, result.falseAlarm)
    assertEquals(2, result.unknownForecast)
    assertEquals(0, result.unknownObservation)
    assertEquals(listOf(-2.0), result.onset.signedMinutes)
    assertEquals(listOf(2.0), result.end.signedMinutes)
    assertEquals(9, result.onset.unpaired)
    assertEquals(9, result.end.unpaired)
    assertEquals(2.0, result.onset.meanAbsoluteMinutes!!, 0.0)
    assertEquals(result, RainValidation.evaluate(targets().reversed(), records().reversed()))
  }

  @Test fun `TSV round trip preserves immutable issuance and provenance`() {
    assertEquals(records(), RainRecordingTsv.readRecords(RainRecordingTsv.writeRecords(records())))
  }

  @Test fun `late issue and hindsight are rejected including equality at start`() {
    val original = records()
    for (time in listOf(original[0].target.start, original[0].target.end.plusSeconds(1))) {
      rejected { RainValidation.evaluate(targets(), original.toMutableList().apply { this[0] = this[0].copy(recordedAt = time) }) }
    }
  }

  @Test fun `duplicate missing and extra rows cannot silently change denominators`() {
    val rows = records()
    rejected { RainValidation.evaluate(targets(), rows.drop(1)) }
    rejected { RainValidation.evaluate(targets(), rows.drop(2)) }
    rejected { RainValidation.evaluate(targets(), rows + rows.first()) }
    rejected { RainValidation.evaluate(targets(), rows + rows.first().copy(id = "other-id")) }
    rejected { RainValidation.evaluate(targets() + targets().first(), rows) }
    rejected { RainValidation.evaluate(emptyList(), emptyList()) }
    rejected { RainValidation.evaluate(targets(), rows.toMutableList().apply { this[1] = this[1].copy(id = this[0].id) }) }
    rejected { RainValidation.evaluate(targets(), rows.toMutableList().apply { this[1] = this[0].copy(id = "duplicate-role") }) }
  }

  @Test fun `site interval provenance and reference independence are enforced`() {
    val rows = records()
    val o = rows[1]
    val invalid = listOf(
      o.copy(target = o.target.copy(site = o.target.site.copy(id = "other-place"))),
      o.copy(target = o.target.copy(site = o.target.site.copy(latitude = 44.01))),
      o.copy(target = o.target.copy(end = o.target.end.plusSeconds(1))),
      o.copy(target = o.target.copy(key = "unregistered")),
      o.copy(recordedAt = o.target.end.minusSeconds(1)),
      o.copy(independent = false),
      o.copy(source = rows[0].source),
      o.copy(evidence = EvidenceKind.REAL_LOCAL),
      o.copy(revision = "a".repeat(40)),
      o.copy(revision = "unknown"),
      o.copy(source = ""),
      o.copy(onset = o.target.start.minusSeconds(1)),
      o.copy(end = o.target.end),
      o.copy(onset = o.target.start.plusSeconds(10), end = o.target.start),
      o.copy(state = RainState.DRY, onset = o.target.start),
    )
    for (bad in invalid) rejected {
      RainValidation.evaluate(targets(), rows.toMutableList().apply { this[1] = bad })
    }
  }

  @Test fun `overlap invalid coordinates and reversed intervals rejected`() {
    val t = targets().first()
    for (bad in listOf(t.copy(end = t.start), t.copy(site = t.site.copy(latitude = Double.NaN)),
      t.copy(site = t.site.copy(longitude = 181.0)))) {
      rejected { RainValidation.evaluate(listOf(bad), emptyList()) }
    }
    rejected { RainValidation.evaluate(listOf(t, t.copy(key = "overlap")), records().take(2)) }
  }

  @Test fun `absent transitions produce no invented zero error and unknown reference stays visible`() {
    val rows = records().take(2).map {
      if (it.role == RecordRole.OBSERVATION) it.copy(state = RainState.UNKNOWN) else it
    }
    val result = RainValidation.evaluate(targets().take(1), rows)
    assertEquals(0, result.scoredSlots)
    assertEquals(1, result.unknownObservation)
    assertEquals(1, result.onset.unpaired)
    assertNull(result.onset.meanAbsoluteMinutes)
    assertNull(result.end.meanAbsoluteMinutes)
  }

  @Test fun `simulation and local runs retain explicit evidence class`() {
    for (kind in listOf(EvidenceKind.MODEL_SIMULATION, EvidenceKind.REAL_LOCAL)) {
      val result = RainValidation.evaluate(targets(), records().map { it.copy(evidence = kind) })
      assertEquals(kind, result.evidence) // Schema exercise only: does not turn fixtures into field evidence.
    }
  }

  @Test fun `parser rejects unknown schema malformed rows and absent values`() {
    val text = resource("records.tsv")
    rejected { RainRecordingTsv.readRecords(text.replace("rain-records-v1", "rain-records-v9")) }
    rejected { RainRecordingTsv.readRecords(text + "incomplete\n") }
    rejected { RainRecordingTsv.readRecords(text.replace("SYNTHETIC", "UNLABELLED")) }
    rejected { RainRecordingTsv.readRecords(text.replace("44.0", "")) }
    rejected { RainRecordingTsv.readRecords(text.replace("2026-11-07T10:00:00Z", "yesterday")) }
  }

  @Test fun `seven scenario labels are preserved without asserting future production behavior`() {
    val lines = resource("scenario-inputs.tsv").trimEnd().lines()
    assertEquals(setOf("present", "arrival", "end", "intermittent", "conflict", "stale", "no-coverage"),
      lines.drop(1).map { it.substringBefore('\t') }.toSet())
    assertTrue(lines.drop(1).all { it.split('\t')[1] == "SYNTHETIC" })
    assertEquals(Instant.parse("2026-11-07T10:00:00Z"), records().first().recordedAt)
  }
}
