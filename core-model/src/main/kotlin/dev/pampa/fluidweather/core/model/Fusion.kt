package dev.pampa.fluidweather.core.model

/**
 * Le variabili su cui la fusione pesa i provider. Stringhe stabili, non enum: finiscono nel
 * database delle verifiche e devono sopravvivere ai refactor.
 */
object FusionVariables {
  const val TEMPERATURE = "temperature"
  const val PRESSURE_MSL = "pressure_msl"
  const val PRECIPITATION = "precipitation"
  const val CLOUD_COVER = "cloud_cover"
  const val WIND_SPEED = "wind_speed"
  const val HUMIDITY = "humidity"
  const val DEW_POINT = "dew_point"
  const val PRECIP_PROBABILITY = "precip_probability"
  const val UV_INDEX = "uv_index"
  const val VISIBILITY = "visibility"
  const val WIND_GUST = "wind_gust"

  /**
   * Le variabili che si VERIFICANO: osservabili fisiche con una verita' misurabile. Una
   * probabilita' non ce l'ha (verificarla contro la mediana premierebbe il conformismo, non
   * l'accuratezza), e UV/visibilita' non hanno analisi affidabili nella costellazione.
   */
  val verified: List<String> = listOf(TEMPERATURE, PRESSURE_MSL, PRECIPITATION, CLOUD_COVER, WIND_SPEED)

  /** Le variabili che si FONDONO: tutte quelle lineari. La direzione del vento e' circolare
   * e viaggia a parte (la dice il provider col peso maggiore, come la condizione). */
  val all: List<String> = verified +
    listOf(HUMIDITY, DEW_POINT, PRECIP_PROBABILITY, UV_INDEX, VISIBILITY, WIND_GUST)

  fun of(point: HourlyPoint, variable: String): Double? = when (variable) {
    TEMPERATURE -> point.temperatureC
    PRESSURE_MSL -> point.pressureMslHpa
    PRECIPITATION -> point.precipitationMm
    CLOUD_COVER -> point.cloudCoverPercent
    WIND_SPEED -> point.windSpeedKmh
    HUMIDITY -> point.relativeHumidityPercent
    DEW_POINT -> point.dewPointC
    PRECIP_PROBABILITY -> point.precipitationProbabilityPercent
    UV_INDEX -> point.uvIndex
    VISIBILITY -> point.visibilityMeters
    WIND_GUST -> point.windGustKmh
    else -> null
  }
}

/** Gli orizzonti si pesano a fasce: un provider bravo a +3h puo' essere mediocre a +24h. */
enum class HorizonBucket(val label: String) {
  SHORT("0-6h"),
  MEDIUM("6-24h");

  companion object {
    fun of(horizonHours: Int): HorizonBucket = if (horizonHours <= 6) SHORT else MEDIUM
  }
}

/** Una previsione in attesa di giudizio: cosa ha detto chi, per quando, detto quando. */
data class PendingPrediction(
  val providerId: String,
  val variable: String,
  val targetTimestampMillis: Long,
  val predictedValue: Double,
  val issuedAtMillis: Long,
) {
  val horizonHours: Int
    get() = ((targetTimestampMillis - issuedAtMillis) / 3_600_000L).toInt()
}

/** Il giudizio: errore assoluto contro la verita' di riferimento, con quando e' stato emesso. */
data class ForecastVerification(
  val providerId: String,
  val variable: String,
  val horizonBucket: HorizonBucket,
  val absoluteError: Double,
  val verifiedAtMillis: Long,
)

/**
 * Il magazzino delle verifiche, come interfaccia: Room la implementa sul telefono, una mappa
 * in memoria nei test — e' cio' che rende la matematica dei pesi collaudabile sul computer.
 */
interface VerificationStore {
  suspend fun addPending(predictions: List<PendingPrediction>)
  suspend fun duePending(nowMillis: Long): List<PendingPrediction>
  suspend fun removePending(predictions: List<PendingPrediction>)
  suspend fun addVerifications(verifications: List<ForecastVerification>)
  suspend fun verificationsFor(variable: String, bucket: HorizonBucket): List<ForecastVerification>

  /** Tutte le verifiche da [sinceMillis]: la pagella del Benchmark le legge in un colpo solo. */
  suspend fun allVerifications(sinceMillis: Long): List<ForecastVerification>
}

// ------------------------------------------------------------------------- il risultato fuso

/** Da dove viene un numero fuso: la tracciabilita' promessa dal piano, valore per valore. */
data class Contribution(
  val providerId: String,
  val weight: Double,
  val value: Double,
)

data class FusedValue(
  val value: Double,
  val contributions: List<Contribution>,
)

data class FusedHour(
  val timestampMillis: Long,
  val values: Map<String, FusedValue>,
  val kind: WeatherKind?,
  /** Circolare: non si media — la dice il provider col peso maggiore, come [kind]. */
  val windDirectionDeg: Double? = null,
)

data class FusedForecast(
  val hours: List<FusedHour>,
  /** providerId -> peso normalizzato usato (mediato sulle variabili): per la diagnostica. */
  val providerWeights: Map<String, Double>,
)

/**
 * Un'ora e mezza: la distanza oltre la quale un'ora prevista non e' piu' "adesso" per chi deve
 * decidere qualcosa da solo — una notifica di pioggia, una transizione. Chi invece mostra e
 * dichiara l'eta' del dato (la testata della home) non ha bisogno di questo tetto.
 */
const val NOW_WINDOW_MILLIS: Long = 90 * 60_000L

/**
 * L'ora piu' vicina a [nowMillis], e **quanto dista**. Null se non c'e' nessuna ora.
 *
 * Era scritta a mano in otto punti, e in tre di quegli otto c'era un tetto di 90 minuti e negli
 * altri cinque no: la stessa schermata poteva scrivere "—" al posto della temperatura (tagliata dal
 * tetto) e "Sereno" appena sotto (non tagliato), leggendo la stessa istantanea.
 *
 * La distanza torna insieme al valore proprio perche' la decisione "e' ancora attuale?" e' di chi
 * guarda, non di chi cerca: una notifica di pioggia non puo' nascere da un'ora di ieri, ma una
 * testata che dichiara l'eta' dei dati puo' benissimo mostrarla.
 */
fun List<FusedHour>.nearestHour(nowMillis: Long): Pair<FusedHour, Long>? =
  minByOrNull { kotlin.math.abs(it.timestampMillis - nowMillis) }
    ?.let { it to kotlin.math.abs(it.timestampMillis - nowMillis) }

/** L'ora piu' vicina, solo se dista meno di [withinMillis]: per chi non puo' sbagliare ora. */
fun List<FusedHour>.hourAround(nowMillis: Long, withinMillis: Long = NOW_WINDOW_MILLIS): FusedHour? =
  nearestHour(nowMillis)?.takeIf { it.second <= withinMillis }?.first

fun FusedForecast.nearestHour(nowMillis: Long): Pair<FusedHour, Long>? = hours.nearestHour(nowMillis)

fun FusedForecast.hourAround(nowMillis: Long, withinMillis: Long = NOW_WINDOW_MILLIS): FusedHour? =
  hours.hourAround(nowMillis, withinMillis)

/**
 * Minima e massima del **giorno locale**, non delle prossime ventiquattro ore.
 *
 * Sono due cose diverse e la seconda inganna: alle undici di sera "massima nelle prossime 24 ore"
 * sarebbe quella di domani. La testata della home dice il giorno, e il widget deve dire lo stesso
 * numero — per questo la funzione sta qui e non in due copie.
 *
 * Null quando la previsione non copre oggi: meglio niente che il massimo di un altro giorno.
 */
fun List<FusedHour>.todayRange(
  nowMillis: Long,
  zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): Pair<Double?, Double?> {
  val today = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
  val temperatures = this
    .filter { java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() == today }
    .mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
  if (temperatures.isEmpty()) return null to null
  return temperatures.min() to temperatures.max()
}

fun FusedForecast.todayRange(nowMillis: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) =
  hours.todayRange(nowMillis, zone)

/**
 * Quante ore avanti guarda la probabilita' di pioggia riassunta in un numero solo: sei.
 *
 * E' la stessa finestra della tessera Precipitazioni dentro l'app, e non e' un caso: due superfici
 * che dicono "40%" e "70%" nello stesso momento sono peggio di una che non lo dice affatto.
 */
const val RAIN_OUTLOOK_HOURS: Int = 6

/**
 * La probabilita' di pioggia piu' alta nelle prossime [withinHours] ore, o null se non si sa.
 *
 * Il massimo e non la media: quello che serve sapere e' se **a un certo punto** conviene prendere
 * l'ombrello, e una media di sei ore annacqua un temporale di un'ora fino a farlo sparire.
 */
fun List<FusedHour>.rainProbabilityPercent(nowMillis: Long, withinHours: Int = RAIN_OUTLOOK_HOURS): Int? = this
  .filter { it.timestampMillis >= nowMillis }
  .take(withinHours)
  .mapNotNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value }
  .maxOrNull()
  ?.toInt()
