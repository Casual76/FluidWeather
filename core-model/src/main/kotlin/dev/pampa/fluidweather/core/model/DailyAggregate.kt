package dev.pampa.fluidweather.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Un giorno visto da lontano: la condizione che domina di giorno, l'escursione, l'acqua, il vento, il sole. */
data class DaySummary(
  val date: LocalDate,
  /** La condizione modale fra le 8 e le 20 (o su tutte le ore se il giorno e' monco). */
  val kind: WeatherKind?,
  val minC: Double,
  val maxC: Double,
  val precipitationProbabilityMaxPercent: Double?,
  val precipitationMm: Double?,
  val windMaxKmh: Double?,
  val windGustMaxKmh: Double?,
  val sunriseMillis: Long?,
  val sunsetMillis: Long?,
  /** Le ore fuse del giorno, nell'ordine: chi vuole il dettaglio le ha gia' in mano. */
  val hours: List<FusedHour>,
  val rainHours: List<FusedHour> = emptyList(),
  val precipitationSampleSumMm: Double? = precipitationMm,
  val precipitationSamples: Int = 0,
  val precipitationExpectedSamples: Int = 0,
)

/**
 * L'aggregato giornaliero delle ore fuse, uno solo per tutta l'app: lo disegnava la pagina del
 * giornaliero (fase 9) e adesso lo legge anche l'assistente (fase 19), e due copie della stessa
 * aritmetica sarebbero due modi di sbagliare. Puro: il fuso e le coordinate arrivano da fuori.
 */
object DailyAggregate {

  /** Le ore "di giorno" che decidono la condizione prevalente: chi guarda il cielo alle 3 dorme. */
  val DAYTIME_HOURS: IntRange = 8..20

  fun of(
    hours: List<FusedHour>,
    zone: ZoneId,
    latitude: Double?,
    longitude: Double?,
    fromDate: LocalDate,
    maxDays: Int = 10,
  ): List<DaySummary> {
    val byDay = hours
      .groupBy { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() }
      .toSortedMap()
      .entries
      .filter { it.key >= fromDate }
      .take(maxDays)
    return byDay.mapNotNull { (date, dayHours) ->
      val sorted = dayHours.sortedBy { it.timestampMillis }
      val temps = sorted.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
      if (temps.isEmpty()) return@mapNotNull null
      val daytime = sorted.filter { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).hour in DAYTIME_HOURS }.ifEmpty { sorted }
      val kind = daytime.mapNotNull { it.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
      val sun = if (latitude != null && longitude != null) {
        SunTimes.forDay(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), latitude, longitude)
      } else {
        null
      }
      val rain = hours.precipitationSummary(date.atStartOfDay(zone).toInstant().toEpochMilli(),
        date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
      DaySummary(
        date = date,
        kind = kind,
        minC = temps.min(),
        maxC = temps.max(),
        precipitationProbabilityMaxPercent = rain.probabilityMaxPercent,
        precipitationMm = rain.amountMm,
        windMaxKmh = sorted.mapNotNull { it.values[FusionVariables.WIND_SPEED]?.value }.maxOrNull(),
        windGustMaxKmh = sorted.mapNotNull { it.values[FusionVariables.WIND_GUST]?.value }.maxOrNull(),
        sunriseMillis = sun?.sunriseMillis,
        sunsetMillis = sun?.sunsetMillis,
        hours = sorted,
        rainHours = rain.hours,
        precipitationSampleSumMm = rain.sampleAmountMm,
        precipitationSamples = rain.availableAmountSamples,
        precipitationExpectedSamples = rain.expectedSamples,
      )
    }
  }

  /** Le quattro fasce di un giorno (notte 0-6, mattina 6-12, pomeriggio 12-18, sera 18-24). */
  enum class DayPart(val hours: IntRange) { NIGHT(0..5), MORNING(6..11), AFTERNOON(12..17), EVENING(18..23) }

  data class PartSummary(
    val part: DayPart,
    val kind: WeatherKind?,
    val minC: Double?,
    val maxC: Double?,
    val precipitationProbabilityMaxPercent: Double?,
    val precipitationMm: Double?,
    val windMaxKmh: Double?,
    val rainHours: List<FusedHour> = emptyList(),
    val precipitationSampleSumMm: Double? = precipitationMm,
    val precipitationSamples: Int = 0,
    val precipitationExpectedSamples: Int = 0,
  )

  fun parts(day: DaySummary, zone: ZoneId): List<PartSummary> = DayPart.entries.mapNotNull { part ->
    val hours = day.hours.filter { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).hour in part.hours }
    val start = day.date.atTime(part.hours.first, 0).atZone(zone).toInstant().toEpochMilli()
    val end = if (part == DayPart.EVENING) day.date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
      else day.date.atTime(part.hours.last + 1, 0).atZone(zone).toInstant().toEpochMilli()
    val rain = day.rainHours.precipitationSummary(start, end)
    if (hours.isEmpty() && rain.hours.isEmpty()) return@mapNotNull null
    val temps = hours.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
    PartSummary(
      part = part,
      kind = hours.mapNotNull { it.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
      minC = temps.minOrNull(),
      maxC = temps.maxOrNull(),
      precipitationProbabilityMaxPercent = rain.probabilityMaxPercent,
      precipitationMm = rain.amountMm,
      windMaxKmh = hours.mapNotNull { it.values[FusionVariables.WIND_SPEED]?.value }.maxOrNull(),
      rainHours = rain.hours,
      precipitationSampleSumMm = rain.sampleAmountMm,
      precipitationSamples = rain.availableAmountSamples,
      precipitationExpectedSamples = rain.expectedSamples,
    )
  }
}
