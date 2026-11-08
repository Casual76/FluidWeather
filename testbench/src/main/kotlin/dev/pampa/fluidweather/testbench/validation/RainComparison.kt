package dev.pampa.fluidweather.testbench.validation

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** A zero denominator is unavailable, never a perfect score. */
data class RainRate(val numerator: Int, val denominator: Int) {
  val fraction: Double? get() = if (denominator == 0) null else numerator.toDouble() / denominator
  override fun toString(): String = "$numerator/$denominator (${fraction ?: "N/A"})"
}

data class RainClassification(val hits: Int, val correctDry: Int, val falseDry: Int, val falseAlarm: Int) {
  val scored: Int get() = hits + correctDry + falseDry + falseAlarm
  val falseDryRate: RainRate get() = RainRate(falseDry, hits + falseDry)
  val falseAlarmRatio: RainRate get() = RainRate(falseAlarm, hits + falseAlarm)
  val falsePositiveRate: RainRate get() = RainRate(falseAlarm, correctDry + falseAlarm)
  val accuracy: RainRate get() = RainRate(hits + correctDry, scored)
}

/** Keys make every changed denominator inspectable. Lists are sorted by target key. */
data class RainCoverage(
  val common: List<String>,
  val gained: List<String>,
  val lost: List<String>,
  val neither: List<String>,
) {
  val baseline: Int get() = common.size + lost.size
  val candidate: Int get() = common.size + gained.size
}

data class RainTimingPair(val targetKey: String, val baselineMinutes: Double, val candidateMinutes: Double)

data class RainPairedTiming(
  val eligibleObserved: List<String>,
  val coverage: RainCoverage,
  val pairs: List<RainTimingPair>,
) {
  val baselineMaeMinutes: Double? get() = pairs.takeIf { it.isNotEmpty() }?.map { abs(it.baselineMinutes) }?.average()
  val candidateMaeMinutes: Double? get() = pairs.takeIf { it.isNotEmpty() }?.map { abs(it.candidateMinutes) }?.average()
  /** Candidate minus baseline; defined only on exactly the same transition targets. */
  val maeDeltaMinutes: Double? get() = baselineMaeMinutes?.let { candidateMaeMinutes!! - it }
}

enum class RainComparisonGate { REVIEW_NEEDED }

data class RainComparisonReport(
  val baseline: RainValidationReport,
  val candidate: RainValidationReport,
  val coverage: RainCoverage,
  val commonBaseline: RainClassification,
  val commonCandidate: RainClassification,
  val onset: RainPairedTiming,
  val end: RainPairedTiming,
) {
  // Arithmetic is never evidence authentication or field acceptance, including REAL_LOCAL.
  val gate: RainComparisonGate get() = RainComparisonGate.REVIEW_NEEDED

  fun render(): String = buildString {
    appendLine("Paired rain comparison; evidence=${baseline.evidence}")
    fun classification(label: String, c: RainClassification) {
      appendLine("$label: scored=${c.scored}, hits=${c.hits}, correctDry=${c.correctDry}, falseDry=${c.falseDry}, falseAlarm=${c.falseAlarm}")
      appendLine("  falseDry/observedRain=${c.falseDryRate}; falseAlarm/predictedRain=${c.falseAlarmRatio}; falseAlarm/observedDry=${c.falsePositiveRate}; correct/scored=${c.accuracy}")
    }
    fun run(label: String, r: RainValidationReport) {
      appendLine("$label revision=${r.revision}; slots=${r.slots}; coverage=${RainRate(r.scoredSlots, r.slots)}; unknownForecast=${RainRate(r.unknownForecast, r.slots)}; unknownObservation=${RainRate(r.unknownObservation, r.slots)}")
      classification(label, r.classification())
      appendLine("  raw onset MAE=${r.onset.meanAbsoluteMinutes ?: "N/A"} min, pairs=${RainRate(r.onset.signedMinutes.size, r.slots)}, unpaired=${r.onset.unpaired}")
      appendLine("  raw end MAE=${r.end.meanAbsoluteMinutes ?: "N/A"} min, pairs=${RainRate(r.end.signedMinutes.size, r.slots)}, unpaired=${r.end.unpaired}")
    }
    fun reportCoverage(label: String, c: RainCoverage, denominator: Int) {
      appendLine("$label: common=${RainRate(c.common.size, denominator)} ${c.common}; gained=${c.gained.size} ${c.gained}; lost=${c.lost.size} ${c.lost}; neither=${c.neither.size} ${c.neither}")
    }
    run("Baseline", baseline)
    run("Candidate", candidate)
    reportCoverage("Classifiable coverage", coverage, baseline.slots)
    classification("Common baseline", commonBaseline)
    classification("Common candidate", commonCandidate)
    fun timing(label: String, t: RainPairedTiming) {
      appendLine("$label observed eligible=${RainRate(t.eligibleObserved.size, baseline.slots)} ${t.eligibleObserved}")
      reportCoverage("$label timing coverage (denominator=observed eligible)", t.coverage, t.eligibleObserved.size)
      appendLine("  shared pairs=${t.pairs.size}; baseline MAE=${t.baselineMaeMinutes ?: "N/A"}; candidate MAE=${t.candidateMaeMinutes ?: "N/A"}; candidate-baseline=${t.maeDeltaMinutes ?: "N/A"} min")
      t.pairs.forEach { appendLine("  ${it.targetKey}: baseline=${it.baselineMinutes}, candidate=${it.candidateMinutes} signed minutes (prediction-observation)") }
    }
    timing("Onset", onset)
    timing("End", end)
    appendLine("Raw arithmetic only. Withheld forecasts and lost timing pairs are missing coverage, never improvement; inspect all lost/gained keys before interpreting deltas.")
    appendLine("C2 gate=$gate (OPEN). SYNTHETIC/MODEL_SIMULATION cannot establish C2. REAL_LOCAL is an assertion: independence, preregistration, sample size/event mix and field acceptance require external review.")
  }
}

private fun RainValidationReport.classification() = RainClassification(hits, correctDry, falseDry, falseAlarm)

/** Offline paired arithmetic. No observation field except the required per-run revision is normalized. */
object RainComparison {
  fun compareTsv(targets: String, baseline: String, candidate: String): RainComparisonReport = evaluate(
    RainRecordingTsv.readTargets(targets), RainRecordingTsv.readRecords(baseline), RainRecordingTsv.readRecords(candidate),
  )

  fun evaluate(targets: List<RainTarget>, baseline: List<RainRecord>, candidate: List<RainRecord>): RainComparisonReport {
    val b = RainValidation.evaluate(targets, baseline)
    val c = RainValidation.evaluate(targets, candidate)
    require(b.revision != c.revision) { "Comparison requires distinct frozen revisions" }
    require(b.evidence == c.evidence) { "Incomparable evidence classes" }
    fun List<RainRecord>.byRole(role: RecordRole) = filter { it.role == role }.associateBy { it.target.key }
    val bo = baseline.byRole(RecordRole.OBSERVATION)
    val co = candidate.byRole(RecordRole.OBSERVATION)
    val bf = baseline.byRole(RecordRole.FORECAST)
    val cf = candidate.byRole(RecordRole.FORECAST)
    val keys = targets.map { it.key }.sorted()
    keys.forEach { key ->
      require(bo.getValue(key).copy(revision = c.revision) == co.getValue(key)) {
        "Changed observation for target $key; only per-run revision may differ"
      }
    }
    fun coverageOf(universe: List<String>, bKeys: Set<String>, cKeys: Set<String>) = RainCoverage(
      universe.filter { it in bKeys && it in cKeys }, universe.filter { it !in bKeys && it in cKeys },
      universe.filter { it in bKeys && it !in cKeys }, universe.filter { it !in bKeys && it !in cKeys },
    )
    fun classifiable(f: Map<String, RainRecord>) = keys.filter {
      f.getValue(it).state != RainState.UNKNOWN && bo.getValue(it).state != RainState.UNKNOWN
    }.toSet()
    val coverage = coverageOf(keys, classifiable(bf), classifiable(cf))
    require(coverage.common.isNotEmpty()) { "Incomparable runs: no common classifiable targets" }
    fun common(records: List<RainRecord>): RainClassification = RainValidation.evaluate(
      targets.filter { it.key in coverage.common }, records.filter { it.target.key in coverage.common },
    ).classification()
    fun timing(event: (RainRecord) -> Instant?): RainPairedTiming {
      val eligible = keys.filter { event(bo.getValue(it)) != null }
      val bc = eligible.filter { event(bf.getValue(it)) != null }.toSet()
      val cc = eligible.filter { event(cf.getValue(it)) != null }.toSet()
      val tc = coverageOf(eligible, bc, cc)
      fun error(key: String, f: Map<String, RainRecord>): Double =
        Duration.between(event(bo.getValue(key))!!, event(f.getValue(key))!!).toMillis() / 60_000.0
      return RainPairedTiming(eligible, tc, tc.common.map { RainTimingPair(it, error(it, bf), error(it, cf)) })
    }
    return RainComparisonReport(b, c, coverage, common(baseline), common(candidate), timing { it.onset }, timing { it.end })
  }
}
