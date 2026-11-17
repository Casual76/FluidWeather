package dev.pampa.fluidweather.core.sensor

import dev.pampa.fluidweather.core.model.CalibrationOutcome
import dev.pampa.fluidweather.core.model.CalibrationRecord
import dev.pampa.fluidweather.core.model.PendingCalibrationBurst
import dev.pampa.fluidweather.core.model.PressureSample
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationMath
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationReferenceSample
import dev.pampa.fluidweather.nowcast.cleaning.CalibrationSegments
import kotlinx.coroutines.CancellationException

/** Persistence boundary for the retry also used at startup: a failed read is never empty state. */
internal class PendingCalibrationEstimator(
  private val pending: suspend () -> PendingCalibrationBurst?,
  private val current: suspend () -> CalibrationRecord?,
  private val samples: suspend (String) -> List<PressureSample>,
  private val reference: suspend () -> CalibrationReferenceSample?,
  private val save: suspend (CalibrationRecord) -> Unit,
  private val clearPending: suspend () -> Unit,
  private val outcome: suspend (CalibrationOutcome) -> Unit,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  suspend fun retry(): Boolean = try {
    retryEstimate()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (_: Exception) {
    // Preserve pending work on storage or network failure; never report an unsaved bias as OK.
    false
  }

  private suspend fun retryEstimate(): Boolean {
    val burst = pending() ?: return false
    if (current() != null) {
      clearPending()
      return false
    }
    val archived = samples(burst.burstId)
    if (archived.isEmpty()) {
      clearPending()
      return false
    }
    val segments = CalibrationSegments.of(archived)
    if (segments.isEmpty()) {
      outcome(CalibrationOutcome.TOO_MUCH_MOVEMENT)
      clearPending()
      return false
    }
    val target = reference() ?: return false
    val record = CalibrationMath.estimateSegments(segments, listOf(target), clock())
    if (record == null) {
      // No historical-reference service exists here. A current pressure cannot repair yesterday.
      // Keep the raw burst for exports, but stop automatic retries and invite a new calibration.
      val tooOld = segments.all {
        kotlin.math.abs(target.atMillis - (it.fromMillis + (it.toMillis - it.fromMillis) / 2)) >
          CalibrationReferenceSample.MAX_SEPARATION_MINUTES * 60_000
      }
      outcome(if (tooOld) CalibrationOutcome.REFERENCE_EXPIRED else CalibrationOutcome.NO_REFERENCE)
      if (tooOld) clearPending()
      return false
    }
    save(record)
    outcome(CalibrationOutcome.OK)
    clearPending()
    return true
  }
}
