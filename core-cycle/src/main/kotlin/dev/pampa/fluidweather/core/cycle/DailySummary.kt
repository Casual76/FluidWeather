package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.WeatherKindLabels
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Il riepilogo giornaliero: il tempo di oggi in una riga, dal fuso, piu' quello che il
 * barometro ha da dire. Puro: il ciclo lo chiama all'ora scelta e lo consegna.
 */
object DailySummary {

  fun compose(
    nowMillis: Long,
    zone: ZoneId,
    hours: List<FusedHour>,
    verdict: NowcastVerdict?,
    pressureTrendHpaPerHour: Double?,
    locationName: String?,
  ): AppNotification? {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val todayHours = hours.filter { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalDate() == today }
    if (todayHours.isEmpty()) return null

    val temperatures = todayHours.mapNotNull { it.values[FusionVariables.TEMPERATURE]?.value }
    val daytime = todayHours.filter { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).hour in 8..20 }
      .ifEmpty { todayHours }
    val kind = daytime.mapNotNull { it.kind }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    val rainiest = todayHours
      .filter { it.timestampMillis >= nowMillis - 3_600_000L }
      .maxByOrNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value ?: 0.0 }
    val rainProbability = rainiest?.values?.get(FusionVariables.PRECIP_PROBABILITY)?.value

    val parts = mutableListOf<String>()
    WeatherKindLabels.of(kind)?.let { parts += it }
    if (temperatures.isNotEmpty()) {
      parts += "max ${temperatures.max().toInt()}° min ${temperatures.min().toInt()}°"
    }
    if (rainProbability != null && rainProbability >= 30 && rainiest != null) {
      val hour = HourFormatter.withZone(zone).format(Instant.ofEpochMilli(rainiest.timestampMillis))
      parts += "pioggia ${rainProbability.toInt()}% verso le $hour"
    }
    if (parts.isEmpty()) return null

    val barometer = buildString {
      if (pressureTrendHpaPerHour != null) {
        val trend = when {
          pressureTrendHpaPerHour <= -0.5 -> "in calo"
          pressureTrendHpaPerHour >= 0.5 -> "in aumento"
          else -> "stabile"
        }
        append("Barometro $trend (${String.format(Locale.ROOT, "%+.1f", pressureTrendHpaPerHour)} hPa/h)")
      }
      if (verdict != null && verdict.level != AlertLevel.QUIETE) {
        if (isNotEmpty()) append(" · ")
        val strongest = verdict.windows.maxByOrNull { it.probability }
        append(if (verdict.level == AlertLevel.ALLERTA) "allerta locale" else "sorveglianza")
        if (strongest != null) append(": pioggia ${(strongest.probability * 100).toInt()}% ${AlertPolicy.windowPhrase(strongest.window)}")
      }
    }

    val text = parts.joinToString(" · ")
    return AppNotification(
      channel = NotificationChannelKind.DAILY_SUMMARY,
      id = AlertPolicy.SUMMARY_ID,
      title = if (locationName != null) "Oggi a $locationName" else "Il tempo di oggi",
      text = text,
      bigText = if (barometer.isNotBlank()) "$text\n$barometer" else text,
    )
  }

  private val HourFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")
}
