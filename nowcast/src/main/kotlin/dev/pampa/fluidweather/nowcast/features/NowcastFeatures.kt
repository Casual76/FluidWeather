package dev.pampa.fluidweather.nowcast.features

import dev.pampa.fluidweather.nowcast.cleaning.CleaningResult
import dev.pampa.fluidweather.nowcast.cleaning.FilteredPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Il contesto sinottico che i provider portano (fase 6) e che il banco pesca dagli archivi.
 * Tutto nullable: il motore deve funzionare anche col solo barometro, e un null diventa
 * "valore neutro" dentro al modello — mai un'invenzione.
 */
data class NowcastContext(
  val relativeHumidityPercent: Double? = null,
  val dewPointSpreadC: Double? = null,
  val cloudCoverPercent: Double? = null,
  val windSpeedKmh: Double? = null,
  val windDirectionDeg: Double? = null,
  val windDirectionDeg3hAgo: Double? = null,
  val rainLastHourMm: Double? = null,
  val rainLast3hMm: Double? = null,
)

/**
 * Stadio 4: dalle serie pulite alle feature, con nomi che un essere umano puo' leggere in un
 * verdetto. L'ordine di [names] E' il contratto: coefficienti, medie e deviazioni del modello
 * addestrato sono indicizzati su di esso.
 *
 * Le feature mancanti valgono NaN qui e vengono imputate al neutro (media di addestramento)
 * dentro al modello: cosi' un telefono senza provider produce comunque un verdetto, e ogni
 * pezzo di contesto in piu' lo affina invece di cambiarne la natura.
 */
object FeatureExtractor {

  val names: List<String> = listOf(
    "tendenza-1h",
    "tendenza-3h",
    "tendenza-6h",
    "tendenza-12h",
    "accelerazione-3h",
    "anomalia-livello",
    "incertezza-tendenza",
    "umidita'",
    "spread-rugiada",
    "copertura",
    "vento",
    "rotazione-vento-3h",
    "pioggia-ultima-ora",
    "pioggia-ultime-3h",
    "ora-sin",
    "ora-cos",
  )

  const val MIN_HISTORY_HOURS = 13.0

  /**
   * Null quando la storia filtrata non copre nemmeno le 13 ore che servono alla tendenza piu'
   * lunga: meglio nessun verdetto che un verdetto costruito sul vuoto.
   */
  fun extract(
    cleaning: CleaningResult,
    context: NowcastContext?,
    /** La normale climatica del punto (media locale di lungo periodo); null = ignota. */
    normalHpa: Double?,
    nowMillis: Long,
  ): DoubleArray? {
    val filtered = cleaning.filtered
    val latest = filtered.lastOrNull() ?: return null
    val spanHours = (latest.timestampMillis - filtered.first().timestampMillis) / 3_600_000.0
    if (spanHours < MIN_HISTORY_HOURS) return null

    val trend1 = slope(filtered, nowMillis, 1.0)
    val trend3 = slope(filtered, nowMillis, 3.0)
    val trend6 = slope(filtered, nowMillis, 6.0)
    val trend12 = slope(filtered, nowMillis, 12.0)

    // Accelerazione: la tendenza a 3 ore di adesso contro quella di 3 ore fa. Un fronte che
    // arriva non e' solo "scende": e' "scende sempre piu' in fretta".
    val previous3 = slopeBetween(filtered, nowMillis - 3 * 3_600_000L, 3.0)
    val acceleration = if (trend3.isNaN() || previous3.isNaN()) Double.NaN else trend3 - previous3

    val anomaly = if (normalHpa == null) Double.NaN else latest.levelHpa - normalHpa

    val rotation = wrapDegrees(context?.windDirectionDeg, context?.windDirectionDeg3hAgo)

    // Ora solare media del posto quando le coordinate ci sono (la convezione pomeridiana e' un
    // fatto solare, non di fuso); UTC come ripiego dichiarato.
    val longitude = cleaning.cleaned.mapNotNull { it.longitude }.sorted().let { sorted ->
      if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
    }
    val utcHours = Math.floorMod(nowMillis, 86_400_000L) / 3_600_000.0
    val solarHours = ((utcHours + longitude / 15.0) % 24.0 + 24.0) % 24.0
    val solarAngle = 2 * PI * solarHours / 24.0

    return doubleArrayOf(
      trend1,
      trend3,
      trend6,
      trend12,
      acceleration,
      anomaly,
      latest.trendSigmaHpaPerHour,
      context?.relativeHumidityPercent ?: Double.NaN,
      context?.dewPointSpreadC ?: Double.NaN,
      context?.cloudCoverPercent ?: Double.NaN,
      context?.windSpeedKmh ?: Double.NaN,
      rotation,
      context?.rainLastHourMm ?: Double.NaN,
      context?.rainLast3hMm ?: Double.NaN,
      sin(solarAngle),
      cos(solarAngle),
    )
  }

  /** Pendenza (hPa/h) fra il livello filtrato "adesso" e quello [lookbackHours] fa. */
  private fun slope(filtered: List<FilteredPoint>, nowMillis: Long, lookbackHours: Double): Double =
    slopeBetween(filtered, nowMillis, lookbackHours)

  private fun slopeBetween(
    filtered: List<FilteredPoint>,
    endMillis: Long,
    lookbackHours: Double,
  ): Double {
    val end = nearest(filtered, endMillis, toleranceHours = lookbackHours / 3) ?: return Double.NaN
    val startMillis = endMillis - (lookbackHours * 3_600_000L).toLong()
    val start = nearest(filtered, startMillis, toleranceHours = lookbackHours / 3) ?: return Double.NaN
    val dtHours = (end.timestampMillis - start.timestampMillis) / 3_600_000.0
    if (dtHours < lookbackHours / 2) return Double.NaN
    return (end.levelHpa - start.levelHpa) / dtHours
  }

  private fun nearest(
    filtered: List<FilteredPoint>,
    targetMillis: Long,
    toleranceHours: Double,
  ): FilteredPoint? = filtered
    .minByOrNull { abs(it.timestampMillis - targetMillis) }
    ?.takeIf { abs(it.timestampMillis - targetMillis) <= toleranceHours * 3_600_000L }

  /** Differenza angolare avvolta in [-180, 180]: una rotazione da 350 a 10 gradi vale +20. */
  private fun wrapDegrees(now: Double?, before: Double?): Double {
    if (now == null || before == null) return Double.NaN
    var delta = now - before
    while (delta > 180) delta -= 360
    while (delta < -180) delta += 360
    return delta
  }
}
