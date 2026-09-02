package dev.pampa.fluidweather.core.weather

import dev.pampa.fluidweather.core.model.ForecastVerification
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.HorizonBucket
import kotlin.math.pow

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
  /** La quota media di peso appreso sulle variabili verificate, in [0,1]. */
  val share: Double,
  val verifications: Int,
  /** Le variabili in cui e' il migliore della zona. */
  val bestAt: List<String>,
  /** MAE per variabile (fascia 0-6 ore), per la riga espansa. */
  val maeByVariable: Map<String, ProviderScore>,
)

data class DailyError(val epochDay: Long, val mae: Double, val count: Int)

/**
 * La pagella della zona, tutta dalle verifiche vere: nessun numero qui e' un'opinione
 * editoriale. Chi non ha ancora verifiche non compare: la pagina lo dice, non lo inventa.
 */
data class BenchmarkReport(
  val ranking: List<ProviderRank>,
  /** variabile -> provider dal migliore al peggiore (fascia 0-6 ore). */
  val byVariable: Map<String, List<ProviderScore>>,
  /** finestra (variabile rain_event_*) -> classifica, barometro compreso. */
  val rainEvent: Map<String, List<ProviderScore>>,
  /** providerId -> variabile -> errore giorno per giorno (ultimi [Benchmark.DAYS] giorni). */
  val dailyError: Map<String, Map<String, List<DailyError>>>,
  val totalVerifications: Int,
  val firstVerificationMillis: Long?,
)

/**
 * Costruisce la pagella con la STESSA matematica del tabellone della fusione (MAE decaduto con
 * dimezzamento a 14 giorni, peso 1/(MAE+0,3)², soglia di 20 verifiche): la classifica che si
 * legge e' la classifica che comanda i pesi, non una sua parafrasi.
 */
object Benchmark {

  const val DAYS = 14
  const val MIN_VERIFICATIONS = 20
  const val HALF_LIFE_DAYS = 14.0
  private const val MAE_EPSILON = 0.3

  fun build(verifications: List<ForecastVerification>, nowMillis: Long): BenchmarkReport {
    fun decay(v: ForecastVerification): Double =
      0.5.pow((nowMillis - v.verifiedAtMillis) / 86_400_000.0 / HALF_LIFE_DAYS)

    fun scores(variable: String): List<ProviderScore> =
      verifications
        .filter { it.variable == variable && it.horizonBucket == HorizonBucket.SHORT }
        .groupBy { it.providerId }
        .mapNotNull { (providerId, own) ->
          val weights = own.sumOf { decay(it) }
          if (weights <= 0.0) return@mapNotNull null
          ProviderScore(
            providerId = providerId,
            decayedMae = own.sumOf { decay(it) * it.absoluteError } / weights,
            count = own.size,
            learned = own.size >= MIN_VERIFICATIONS && weights > 1.0,
          )
        }
        .sortedBy { it.decayedMae }

    val byVariable = FusionVariables.verified.associateWith { scores(it) }.filterValues { it.isNotEmpty() }
    val rainEvent = RainEvent.windows.associate { it.variable to scores(it.variable) }.filterValues { it.isNotEmpty() }

    // La quota per variabile e' il peso appreso normalizzato fra chi ha verifiche; la classifica
    // generale e' la media delle quote sulle variabili in cui il provider e' stato giudicato.
    val shares = mutableMapOf<String, MutableList<Double>>()
    byVariable.forEach { (_, list) ->
      val weights = list.associate { it.providerId to 1.0 / (it.decayedMae + MAE_EPSILON).pow(2) }
      val total = weights.values.sum()
      weights.forEach { (providerId, weight) ->
        shares.getOrPut(providerId) { mutableListOf() } += weight / total
      }
    }
    val ranking = shares.map { (providerId, list) ->
      ProviderRank(
        providerId = providerId,
        share = list.average(),
        verifications = verifications.count { it.providerId == providerId && !RainEvent.isRainEvent(it.variable) },
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
      rainEvent = rainEvent,
      dailyError = dailyError,
      totalVerifications = verifications.size,
      firstVerificationMillis = verifications.minOfOrNull { it.verifiedAtMillis },
    )
  }
}
