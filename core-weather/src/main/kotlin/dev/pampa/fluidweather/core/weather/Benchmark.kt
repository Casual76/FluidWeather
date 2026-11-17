package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.FusedForecast
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket

/** Il punteggio di un provider su una variabile: MAE decaduto, quante verifiche, se pesa. */
data class ProviderScore(
  val providerId: String,
  val decayedMae: Double,
  val count: Int,
  /** Vero quando ha superato la soglia della cascata: il suo peso e' appreso, non un prior. */
  val learned: Boolean,
)

/** La riga della classifica generale. */
data class ProviderRank(
  val providerId: String,
  /** Actual mean contribution to the latest forecast across all its linear hour/variable cells. */
  val share: Double,
  val verifications: Int,
  /** Le variabili in cui e' il migliore della zona. */
  val bestAt: List<String>,
  /** MAE per variabile (fascia 0-6 ore), per la riga espansa. */
  val maeByVariable: Map<String, ProviderScore>,
)

data class DailyError(val epochDay: Long, val mae: Double, val count: Int)

/**
 * Local historical peer-analysis errors and the actual latest forecast contribution shares.
 * A contributing provider may have no history yet: its forecast uses regional priors.
 */
data class BenchmarkReport(
  val ranking: List<ProviderRank>,
  /** variabile -> provider dal migliore al peggiore (fascia 0-6 ore). */
  val byVariable: Map<String, List<ProviderScore>>,
  /** providerId -> variabile -> errore giorno per giorno (ultimi [Benchmark.DAYS] giorni). */
  val dailyError: Map<String, Map<String, List<DailyError>>>,
  val totalVerifications: Int,
  val firstVerificationMillis: Long?,
)

/**
 * Error eligibility and decay are shared with ProviderScoreboard. Shares come from the saved
 * fusion; historical errors cannot identify current coverage, contributing providers or overrides.
 */
object Benchmark {

  const val DAYS = 14
  const val MIN_VERIFICATIONS = LocalProviderLearning.MIN_VERIFICATIONS
  const val HALF_LIFE_DAYS = LocalProviderLearning.HALF_LIFE_DAYS

  /**
   * [stored] e' quello che il magazzino restituisce: ci sono anche le righe `rain_event_*` di quando
   * la pioggia si giudicava qui. Restano nel database ma non sono piu' una verifica di nessuno (la
   * pioggia ha la sua classifica, [RainBoard]): contarle gonfierebbe "verifiche raccolte" e
   * nasconderebbe la pagina vuota a chi ha solo quelle.
   */
  fun build(
    stored: List<ForecastVerification>, nowMillis: Long,
    latitude: Double? = null, longitude: Double? = null, fused: FusedForecast? = null,
  ): BenchmarkReport {
    val verifications = LocalProviderLearning.eligible(stored, latitude, longitude, nowMillis)
      .filter { it.variable in FusionVariables.verified }

    fun scores(variable: String): List<ProviderScore> =
      verifications
        .filter { it.variable == variable && it.horizonBucket == HorizonBucket.SHORT }
        .groupBy { it.providerId }
        .mapNotNull { (providerId, own) ->
          val stats = LocalProviderLearning.statistics(own, nowMillis)
          if (!stats.mae.isFinite()) return@mapNotNull null
          ProviderScore(
            providerId = providerId,
            decayedMae = stats.mae,
            count = stats.count,
            learned = stats.learned,
          )
        }
        .sortedBy { it.decayedMae }

    val byVariable = FusionVariables.verified.associateWith { scores(it) }.filterValues { it.isNotEmpty() }

    val ranking = actualShares(fused).map { (providerId, share) ->
      ProviderRank(
        providerId = providerId,
        share = share,
        verifications = verifications.count { it.providerId == providerId },
        bestAt = byVariable.filter { (_, scores) -> scores.firstOrNull()?.providerId == providerId }.keys.toList(),
        maeByVariable = byVariable.mapNotNull { (variable, scores) ->
          scores.firstOrNull { it.providerId == providerId }?.let { variable to it }
        }.toMap(),
      )
    }.sortedByDescending { it.share }

    val since = nowMillis - DAYS * 86_400_000L
    val dailyError = verifications
      .filter { it.verifiedAtMillis >= since && it.horizonBucket == HorizonBucket.SHORT }
      .groupBy { it.providerId }
      .mapValues { (_, own) ->
        own.groupBy { it.variable }.mapValues { (_, list) ->
          list.groupBy { Math.floorDiv(it.verifiedAtMillis, 86_400_000L) }
            .map { (day, errors) -> DailyError(day, errors.map { it.absoluteError }.average(), errors.size) }
            .sortedBy { it.epochDay }
        }
      }

    return BenchmarkReport(
      ranking = ranking,
      byVariable = byVariable,
      dailyError = dailyError,
      totalVerifications = verifications.size,
      firstVerificationMillis = verifications.minOfOrNull { it.verifiedAtMillis },
    )
  }

  private fun actualShares(fused: FusedForecast?): Map<String, Double> {
    val totals = mutableMapOf<String, Double>()
    var cells = 0
    for (value in fused?.hours.orEmpty().flatMap { it.values.values }) {
      if (!value.value.isFinite()) continue
      val contributions = value.contributions.filter { it.weight.isFinite() && it.weight > 0.0 }
      val total = contributions.sumOf { it.weight }
      if (!total.isFinite() || total <= 0.0) continue
      cells++
      for (contribution in contributions) {
        totals[contribution.providerId] = (totals[contribution.providerId] ?: 0.0) + contribution.weight / total
      }
    }
    // All providers share a denominator: absence from a cell contributes zero.
    return if (cells == 0) emptyMap() else totals.mapValues { it.value / cells }
  }
}
