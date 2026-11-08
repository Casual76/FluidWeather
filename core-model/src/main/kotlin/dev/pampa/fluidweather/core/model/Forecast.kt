package dev.pampa.fluidweather.core.model

/**
 * Il vocabolario comune delle condizioni: ogni provider parla la sua lingua (codici WMO, symbol
 * code, icone), la normalizzazione traduce qui e il resto dell'app non sa piu' chi ha parlato.
 */
enum class WeatherKind {
  CLEAR,
  MOSTLY_CLEAR,
  PARTLY_CLOUDY,
  CLOUDY,
  FOG,
  DRIZZLE,
  RAIN,
  HEAVY_RAIN,
  SLEET,
  SNOW,
  HEAVY_SNOW,
  THUNDERSTORM,
  UNKNOWN,
}

/**
 * Un'ora normalizzata: unita' canoniche (celsius, hPa, mm, %, km/h, gradi, J/kg, metri), campi
 * null quando il provider non serve quella variabile — mai zero al posto di "non lo so".
 */
data class HourlyPoint(
  val timestampMillis: Long,
  val temperatureC: Double? = null,
  val relativeHumidityPercent: Double? = null,
  val dewPointC: Double? = null,
  val pressureMslHpa: Double? = null,
  val precipitationMm: Double? = null,
  val precipitationProbabilityPercent: Double? = null,
  val cloudCoverPercent: Double? = null,
  val windSpeedKmh: Double? = null,
  val windDirectionDeg: Double? = null,
  val windGustKmh: Double? = null,
  val capeJkg: Double? = null,
  val uvIndex: Double? = null,
  val visibilityMeters: Double? = null,
  val kind: WeatherKind? = null,
)

/** Model precipitation over the preceding quarter, ending at timestampMillis; not a measurement. */
data class MinutePoint(
  val timestampMillis: Long,
  val precipitationMm: Double? = null,
  val precipitationProbabilityPercent: Double? = null,
)

/** How the accumulation is positioned relative to the point timestamp. */
enum class AccumulationAnchor { PRECEDING, FOLLOWING, UNKNOWN }

data class PrecipitationAccumulation(
  val durationMillis: Long? = null,
  val anchor: AccumulationAnchor = AccumulationAnchor.UNKNOWN,
) {
  init { require(durationMillis == null || durationMillis > 0) }
}

/** Provider model current. Instantaneous temperature/kind at time; rain over preceding interval. */
data class ProviderCurrent(
  val timestampMillis: Long,
  val temperatureC: Double? = null,
  val kind: WeatherKind? = null,
  val precipitationMm: Double? = null,
  val accumulationIntervalMillis: Long? = null,
  val downloadedAtMillis: Long? = null,
  val possiblyInterpolated: Boolean? = null,
) {
  init {
    require(temperatureC == null || temperatureC.isFinite())
    require(precipitationMm == null || precipitationMm.isFinite() && precipitationMm >= 0)
    require(accumulationIntervalMillis == null || accumulationIntervalMillis > 0)
    require(accumulationIntervalMillis == null || timestampMillis >= Long.MIN_VALUE + accumulationIntervalMillis)
  }
  val evidenceType: WeatherEvidenceType get() = WeatherEvidenceType.MODEL
  val precipitationInterval: WeatherInterval? get() = accumulationIntervalMillis?.let {
    WeatherInterval(timestampMillis - it, timestampMillis)
  }
}

/** Amount only over this actual covered interval; no prorating of boundary quarters. */
data class RainAccumulation(val amountMm: Double, val interval: WeatherInterval) {
  init { require(amountMm.isFinite() && amountMm >= 0) }
}

/**
 * La risposta di un provider per un punto, gia' normalizzata. [hourly] puo' includere ore
 * passate (analisi): servono al contesto del nowcast (pioggia recente, rotazione del vento).
 * [minutely] e' la stessa cosa a quindici minuti, quando il provider la offre.
 * La cadenza della serie non prova una risoluzione nativa; consultare i metadata.
 */
data class ForecastBundle(
  val providerId: String,
  val fetchedAtMillis: Long,
  val latitude: Double,
  val longitude: Double,
  val hourly: List<HourlyPoint>,
  val minutely: List<MinutePoint> = emptyList(),
  /** Provider's IANA zone; unixtime points remain absolute UTC instants. */
  val zoneId: String? = null,
  val current: ProviderCurrent? = null,
  val hourlyPrecipitation: PrecipitationAccumulation? = null,
  val minutelyPrecipitation: PrecipitationAccumulation? = null,
  /** True means native sub-hourly resolution is NOT established; null means unknown. */
  val minutelyPossiblyInterpolated: Boolean? = null,
) {

  fun at(timestampMillis: Long): HourlyPoint? =
    hourly.minByOrNull { kotlin.math.abs(it.timestampMillis - timestampMillis) }
      ?.takeIf { kotlin.math.abs(it.timestampMillis - timestampMillis) <= 90 * 60_000L }

  /** Latest unique closed model quarter, with finite/nonnegative amount and explicit age gate. */
  fun rainNowMm(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): Double? =
    rainNow(nowMillis, maxAgeMillis)?.amountMm

  fun rainNow(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): RainAccumulation? {
    if (!quarterProduct() || maxAgeMillis < 0) return null
    val end = minutely.filter { it.timestampMillis <= nowMillis }.maxOfOrNull { it.timestampMillis } ?: return null
    if (nowMillis - end > maxAgeMillis || Math.floorMod(end, QUARTER) != 0L) return null
    return quarters(end - QUARTER, end)
  }

  /** One complete hour ending at the latest actually available closed quarter, age <= maxAgeMillis. */
  fun rainLastHourMm(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): Double? =
    rainLastHour(nowMillis, maxAgeMillis)?.amountMm

  fun rainLastHour(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): RainAccumulation? {
    val last = rainNow(nowMillis, maxAgeMillis) ?: return null
    return quarters(last.interval.endMillis - 3_600_000L, last.interval.endMillis)
  }

  /** Only whole quarters inside the requested future window. Actual coverage is returned explicitly. */
  fun rainSoon(nowMillis: Long, withinMillis: Long): RainAccumulation? {
    if (!quarterProduct() || withinMillis <= 0 || nowMillis > Long.MAX_VALUE - withinMillis) return null
    val remainder = Math.floorMod(nowMillis, QUARTER)
    val start = if (remainder == 0L) nowMillis else nowMillis + QUARTER - remainder
    val requestedEnd = nowMillis + withinMillis
    val end = requestedEnd - Math.floorMod(requestedEnd, QUARTER)
    return quarters(start, end)
  }

  /** Compatibility API: a numeric total is returned only for exact coverage of now..now+horizon. */
  fun rainSoonMm(nowMillis: Long, withinMillis: Long): Double? = rainSoon(nowMillis, withinMillis)
    ?.takeIf { it.interval.startMillis == nowMillis && it.interval.endMillis == nowMillis + withinMillis }
    ?.amountMm

  private fun quarterProduct(): Boolean = minutelyPrecipitation == null ||
    (minutelyPrecipitation.durationMillis == QUARTER && minutelyPrecipitation.anchor == AccumulationAnchor.PRECEDING)

  private fun quarters(start: Long, end: Long): RainAccumulation? {
    if (!quarterProduct() || end <= start || (end - start) % QUARTER != 0L) return null
    val points = minutely.filter { it.timestampMillis > start && it.timestampMillis <= end }.sortedBy { it.timestampMillis }
    if (points.size.toLong() != (end - start) / QUARTER) return null
    var total = 0.0
    points.forEachIndexed { i, point ->
      if (point.timestampMillis != start + (i + 1L) * QUARTER) return null
      val mm = point.precipitationMm?.takeIf { it.isFinite() && it >= 0 } ?: return null
      total += mm
    }
    return total.takeIf { it.isFinite() }?.let { RainAccumulation(it, WeatherInterval(start, end)) }
  }

  val horizonMillis: Long get() = hourly.lastOrNull()?.timestampMillis ?: 0L

  private companion object {
    const val QUARTER = 900_000L
  }
}
