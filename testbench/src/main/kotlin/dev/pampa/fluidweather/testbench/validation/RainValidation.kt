package dev.pampa.fluidweather.testbench.validation

import java.time.Instant
import kotlin.math.abs

enum class EvidenceKind { SYNTHETIC, MODEL_SIMULATION, REAL_LOCAL }
enum class RecordRole { FORECAST, OBSERVATION }
enum class RainState { RAIN, DRY, UNKNOWN }

/** Fixed station identity, not a city name or a moving phone location. */
data class RainSite(val id: String, val latitude: Double, val longitude: Double)

/** Preregistered, non-overlapping verification slot; interval is [start, end). */
data class RainTarget(val key: String, val site: RainSite, val start: Instant, val end: Instant)

/**
 * FORECAST recordedAt = immutable issue time, OBSERVATION recordedAt = log completion time.
 * RAIN means any rain in the slot. Optional transitions refer to one unambiguous episode;
 * omit them for intermittent/ambiguous or boundary-censored events. Never infer them from bins.
 * independent is an explicit capture assertion, not something software can authenticate.
 */
data class RainRecord(
  val role: RecordRole,
  val target: RainTarget,
  val id: String,
  val evidence: EvidenceKind,
  val revision: String,
  val source: String,
  val recordedAt: Instant,
  val independent: Boolean,
  val state: RainState,
  val onset: Instant? = null,
  val end: Instant? = null,
)

data class TimingErrors(val signedMinutes: List<Double>, val unpaired: Int) {
  val meanAbsoluteMinutes: Double?
    get() = signedMinutes.takeIf { it.isNotEmpty() }?.map { abs(it) }?.average()
}

data class RainValidationReport(
  val evidence: EvidenceKind,
  val revision: String,
  val slots: Int,
  val hits: Int,
  val correctDry: Int,
  val falseDry: Int,
  val falseAlarm: Int,
  val unknownForecast: Int,
  val unknownObservation: Int,
  val onset: TimingErrors,
  val end: TimingErrors,
) {
  val scoredSlots: Int get() = hits + correctDry + falseDry + falseAlarm
}

/** Pure JVM, no clock, network, Android, provider adapter or implicit join. Invalid runs fail whole. */
object RainValidation {
  fun evaluate(targets: List<RainTarget>, records: List<RainRecord>): RainValidationReport {
    require(targets.isNotEmpty()) { "Missing preregistered targets" }
    require(targets.map { it.key }.distinct().size == targets.size) { "Duplicate target" }
    targets.forEach(::validateTarget)
    targets.groupBy { it.site.id }.values.forEach { station ->
      require(station.map { it.site }.distinct().size == 1) { "Site coordinates changed" }
      station.sortedBy { it.start }.zipWithNext().forEach { (a, b) ->
        require(a.end <= b.start) { "Overlapping targets" }
      }
    }
    require(records.isNotEmpty()) { "Missing records" }
    require(records.map { it.id }.distinct().size == records.size) { "Duplicate record id" }
    require(records.map { it.role to it.target.key }.distinct().size == records.size) { "Duplicate role/target" }
    require(records.size == targets.size * 2) { "Missing or extra records" }
    val expected = targets.associateBy { it.key }
    records.forEach { r ->
      require(expected[r.target.key] == r.target) { "Unknown target or incongruent site/interval" }
      require(r.id.isNotBlank() && r.source.isNotBlank()) { "Missing record identity/source" }
      require(r.revision.matches(Regex("[0-9a-f]{40}"))) { "Revision must identify a commit or a frozen source manifest by SHA-1" }
      listOfNotNull(r.onset, r.end).forEach {
        require(it >= r.target.start && it < r.target.end) { "Transition outside target" }
      }
      require(r.onset == null || r.end == null || r.onset < r.end) { "Reversed episode" }
      require(r.state == RainState.RAIN || (r.onset == null && r.end == null)) { "Transition without rain" }
      if (r.role == RecordRole.FORECAST) {
        require(r.recordedAt < r.target.start) { "Forecast must precede target and observation" }
        require(!r.independent) { "Forecast is not an independent observation" }
        require(listOfNotNull(r.onset, r.end).all { r.recordedAt < it }) { "Retrospective transition" }
      } else {
        require(r.recordedAt >= r.target.end) { "Observation slot not completed" }
        require(r.independent) { "Reference must be independent of evaluated forecast" }
      }
    }
    require(records.map { it.evidence }.distinct().size == 1) { "Mixed evidence classes" }
    require(records.map { it.revision }.distinct().size == 1) { "Mixed revisions; evaluate separately" }
    val bySlot = records.groupBy { it.target.key }
    var hits = 0; var dry = 0; var misses = 0; var alarms = 0
    var unknownForecast = 0; var unknownObservation = 0
    val onsetErrors = mutableListOf<Double>()
    val endErrors = mutableListOf<Double>()
    for (target in targets.sortedBy { it.key }) {
      val pair = requireNotNull(bySlot[target.key]) { "Missing target records" }
      val f = pair.single { it.role == RecordRole.FORECAST }
      val o = pair.single { it.role == RecordRole.OBSERVATION }
      require(f.source != o.source) { "Forecast reused as reference" }
      if (f.state == RainState.UNKNOWN) unknownForecast++
      if (o.state == RainState.UNKNOWN) unknownObservation++
      if (f.state != RainState.UNKNOWN && o.state != RainState.UNKNOWN) {
        when {
          f.state == RainState.RAIN && o.state == RainState.RAIN -> hits++
          f.state == RainState.DRY && o.state == RainState.DRY -> dry++
          f.state == RainState.DRY -> misses++
          else -> alarms++
        }
      }
      fun error(predicted: Instant?, observed: Instant?, into: MutableList<Double>) {
        if (predicted != null && observed != null) {
          into += java.time.Duration.between(observed, predicted).toMillis() / 60_000.0
        }
      }
      error(f.onset, o.onset, onsetErrors)
      error(f.end, o.end, endErrors)
    }
    return RainValidationReport(records.first().evidence, records.first().revision, targets.size,
      hits, dry, misses, alarms, unknownForecast, unknownObservation,
      TimingErrors(onsetErrors.toList(), targets.size - onsetErrors.size),
      TimingErrors(endErrors.toList(), targets.size - endErrors.size))
  }

  private fun validateTarget(t: RainTarget) {
    require(t.key.isNotBlank() && t.site.id.isNotBlank()) { "Missing target/site" }
    require(t.site.latitude.isFinite() && t.site.latitude in -90.0..90.0)
    require(t.site.longitude.isFinite() && t.site.longitude in -180.0..180.0)
    require(t.start < t.end) { "Empty/reversed target interval" }
  }
}

/** Strict versioned TSV. '-' means absent transition only; no defaults or guessed timestamps. */
object RainRecordingTsv {
  const val TARGET_HEADER = "rain-targets-v1\tkey\tsite\tlatitude\tlongitude\tstart\tend"
  const val RECORD_HEADER = "rain-records-v1\trole\tkey\tid\tevidence\trevision\tsource\tsite\tlatitude\tlongitude\tstart\tend\trecordedAt\tindependent\tstate\tonset\tendEvent"

  fun readTargets(text: String): List<RainTarget> = rows(text, TARGET_HEADER, 6).map { c ->
    target(c[0], c[1], c[2], c[3], c[4], c[5])
  }

  fun readRecords(text: String): List<RainRecord> = rows(text, RECORD_HEADER, 16).map { c ->
    RainRecord(RecordRole.valueOf(c[0]), target(c[1], c[6], c[7], c[8], c[9], c[10]),
      c[2], EvidenceKind.valueOf(c[3]), c[4], c[5], Instant.parse(c[11]),
      when (c[12]) { "true" -> true; "false" -> false; else -> error("Invalid independent flag") },
      RainState.valueOf(c[13]), event(c[14]), event(c[15]))
  }

  fun writeRecords(records: List<RainRecord>): String = RECORD_HEADER + "\n" + records.joinToString("\n") { r ->
    val t = r.target
    listOf(r.role, t.key, r.id, r.evidence, r.revision, r.source, t.site.id,
      t.site.latitude, t.site.longitude, t.start, t.end, r.recordedAt,
      r.independent, r.state, r.onset ?: "-", r.end ?: "-").joinToString("\t") { value ->
        value.toString().also { require(it.isNotEmpty() && it.none { ch -> ch == '\t' || ch == '\n' || ch == '\r' }) }
      }
  } + "\n"

  private fun target(key: String, site: String, lat: String, lon: String, start: String, end: String) =
    RainTarget(key, RainSite(site, lat.toDouble(), lon.toDouble()), Instant.parse(start), Instant.parse(end))

  private fun event(value: String): Instant? = if (value == "-") null else Instant.parse(value)

  private fun rows(text: String, header: String, count: Int): List<List<String>> {
    val lines = text.lineSequence().toList().dropLastWhile { it.isEmpty() }
    require(lines.firstOrNull() == header) { "Unknown TSV schema/header" }
    return lines.drop(1).mapIndexed { index, line ->
      line.split('\t').also { cells ->
        require(cells.size == count && cells.none { it.isBlank() }) { "Malformed row ${index + 2}" }
      }
    }
  }
}
