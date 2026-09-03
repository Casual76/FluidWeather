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
  val precipitationMm: Double,
  val windMaxKmh: Double?,
  val windGustMaxKmh: Double?,
  val sunriseMillis: Long?,
  val sunsetMillis: Long?,
  /** Le ore fuse del giorno, nell'ordine: chi vuole il dettaglio le ha gia' in mano. */
  val hours: List<FusedHour>,
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
      DaySummary(
        date = date,
        kind = kind,
        minC = temps.min(),
        maxC = temps.max(),
        precipitationProbabilityMaxPercent = sorted.mapNotNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value }.maxOrNull(),
        precipitationMm = sorted.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 },
        windMaxKmh = sorted.mapNotNull { it.values[FusionVariables.WIND_SPEED]?.value }.maxOrNull(),
        windGustMaxKmh = sorted.mapNotNull { it.values[FusionVariables.WIND_GUST]?.value }.maxOrNull(),
        sunriseMillis = sun?.sunriseMillis,
        sunsetMillis = sun?.sunsetMillis,
        hours = sorted,
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
    val precipitationMm: Double,
    val windMaxKmh: Double?,
  )

  fun parts(day: DaySummary, zone: ZoneId): List<PartSummary> = DayPart.entries.mapNotNull { part ->
    val hours = day.hours.filter { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).hour in part.hours }
    if (hours.isEmpty()) return@mapNotNull null
    val temps = hours.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
    PartSummary(
      part = part,
      kind = hours.mapNotNull { it.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
      minC = temps.minOrNull(),
      maxC = temps.maxOrNull(),
      precipitationProbabilityMaxPercent = hours.mapNotNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value }.maxOrNull(),
      precipitationMm = hours.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 },
      windMaxKmh = hours.mapNotNull { it.values[FusionVariables.WIND_SPEED]?.value }.maxOrNull(),
    )
  }
}
