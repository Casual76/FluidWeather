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

/**
 * Un quarto d'ora di precipitazione: la risoluzione a cui "sta piovendo adesso" e' una domanda
 * con risposta. L'ora, per quella domanda, e' un'eternita'.
 */
data class MinutePoint(
  val timestampMillis: Long,
  val precipitationMm: Double? = null,
  val precipitationProbabilityPercent: Double? = null,
)

/**
 * La risposta di un provider per un punto, gia' normalizzata. [hourly] puo' includere ore
 * passate (analisi): servono al contesto del nowcast (pioggia recente, rotazione del vento).
 * [minutely] e' la stessa cosa a quindici minuti, quando il provider la offre.
 */
data class ForecastBundle(
  val providerId: String,
  val fetchedAtMillis: Long,
  val latitude: Double,
  val longitude: Double,
  val hourly: List<HourlyPoint>,
  val minutely: List<MinutePoint> = emptyList(),
) {

  fun at(timestampMillis: Long): HourlyPoint? =
    hourly.minByOrNull { kotlin.math.abs(it.timestampMillis - timestampMillis) }
      ?.takeIf { kotlin.math.abs(it.timestampMillis - timestampMillis) <= 90 * 60_000L }

  /**
   * Quanta pioggia e' caduta nell'ultimo quarto d'ora, secondo il quarto d'ora appena concluso.
   *
   * Open-Meteo dichiara la precipitazione come somma dell'intervallo *precedente* al timestamp:
   * il punto delle 15:15 copre 15:00-15:15. Si prende quindi il primo punto il cui intervallo si
   * e' gia' chiuso, e lo si accetta solo se e' recente — un dato di due ore fa non e' "adesso".
   */
  fun rainNowMm(nowMillis: Long, maxAgeMillis: Long = 45 * 60_000L): Double? = minutely
    .filter { it.timestampMillis <= nowMillis && nowMillis - it.timestampMillis <= maxAgeMillis }
    .maxByOrNull { it.timestampMillis }
    ?.precipitationMm

  /**
   * La pioggia dell'ultima ora vera, sommando i quarti d'ora appena conclusi.
   *
   * Non e' la stessa cosa di [rainNowMm] moltiplicato per quattro, ed e' importante che non lo
   * sia: il modello e' addestrato su *accumuli orari*, e un rovescio da mezzo millimetro in un
   * quarto d'ora vale 2 mm/h come tasso ma 0,5 mm come ora. Passare il tasso al posto
   * dell'accumulo gonfierebbe la feature col peso piu' alto di tutte.
   *
   * Null se i quarti d'ora non coprono l'ora: meglio la riga oraria del provider che una somma
   * di pezzi mancanti.
   */
  fun rainLastHourMm(nowMillis: Long): Double? {
    val window = minutely.filter { it.timestampMillis > nowMillis - 3_600_000L && it.timestampMillis <= nowMillis }
    if (window.size < QUARTERS_IN_HOUR) return null
    return window.sumOf { it.precipitationMm ?: return null }
  }

  /** La pioggia attesa da adesso a [withinMillis]: il nowcast sub-orario del provider. */
  fun rainSoonMm(nowMillis: Long, withinMillis: Long): Double? = minutely
    .filter { it.timestampMillis > nowMillis && it.timestampMillis <= nowMillis + withinMillis }
    .takeIf { it.isNotEmpty() }
    ?.sumOf { it.precipitationMm ?: 0.0 }

  val horizonMillis: Long get() = hourly.lastOrNull()?.timestampMillis ?: 0L

  private companion object {
    const val QUARTERS_IN_HOUR = 4
  }
}
