package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastLearning
import dev.pampa.fluidweather.core.model.ForecastVerification
import kotlin.math.pow

/** One eligibility/decay policy for both fusion and the local historical score report. */
internal object LocalProviderLearning {
  const val MIN_VERIFICATIONS = 20
  const val HALF_LIFE_DAYS = 14.0
  const val MAE_EPSILON = 0.3

  fun eligible(rows: List<ForecastVerification>, latitude: Double?, longitude: Double?, now: Long): List<ForecastVerification> {
    if (!ForecastLearning.validPoint(latitude, longitude)) return emptyList()
    return rows.filter {
      it.semanticsVersion == ForecastLearning.CURRENT_SEMANTICS &&
        ForecastLearning.validPoint(it.latitude, it.longitude) &&
        it.absoluteError.isFinite() && it.absoluteError >= 0.0 &&
        it.verifiedAtMillis in 0L..now &&
        haversineKm(latitude!!, longitude!!, it.latitude!!, it.longitude!!) <= ForecastLearning.LOCAL_RADIUS_KM
    }
  }

  data class Statistics(val mae: Double, val count: Int, val learned: Boolean)

  fun statistics(
    rows: List<ForecastVerification>, now: Long,
    minVerifications: Int = MIN_VERIFICATIONS, halfLifeDays: Double = HALF_LIFE_DAYS,
  ): Statistics {
    fun decay(row: ForecastVerification) = 0.5.pow((now - row.verifiedAtMillis) / 86_400_000.0 / halfLifeDays)
    val mass = rows.sumOf(::decay)
    val mae = if (mass > 0.0) rows.sumOf { decay(it) * it.absoluteError } / mass else Double.NaN
    return Statistics(mae, rows.size, rows.size >= minVerifications && mass > 1.0 && mae.isFinite())
  }
}
