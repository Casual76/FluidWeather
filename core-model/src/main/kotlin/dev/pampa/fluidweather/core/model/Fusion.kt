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

  val all: List<String> = listOf(TEMPERATURE, PRESSURE_MSL, PRECIPITATION, CLOUD_COVER, WIND_SPEED)

  fun of(point: HourlyPoint, variable: String): Double? = when (variable) {
    TEMPERATURE -> point.temperatureC
    PRESSURE_MSL -> point.pressureMslHpa
    PRECIPITATION -> point.precipitationMm
    CLOUD_COVER -> point.cloudCoverPercent
    WIND_SPEED -> point.windSpeedKmh
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
)

data class FusedForecast(
  val hours: List<FusedHour>,
  /** providerId -> peso normalizzato usato (mediato sulle variabili): per la diagnostica. */
  val providerWeights: Map<String, Double>,
)
