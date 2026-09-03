package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.AppNotification
import dev.pampa.fluidweather.core.model.DataAge
import dev.pampa.fluidweather.core.model.DataFreshness
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.nowcast.verdict.NowcastVerdict
import java.time.Instant
import java.time.ZoneId

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
    texts: NotificationTexts,
    /**
     * Quando risale il giro dei provider da cui viene questo riepilogo.
     *
     * Ha un default perche' i test lo omettono: il riepilogo dichiara l'eta' solo quando c'e'
     * qualcosa da dichiarare. Senza rete da stanotte il riepilogo esce lo stesso — le temperature
     * di ieri sera per oggi valgono ancora qualcosa — ma non finge che siano di stamattina.
     */
    dataAtMillis: Long? = null,
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
    texts.kindLabel(kind)?.let { parts += it }
    if (temperatures.isNotEmpty()) {
      parts += texts.summaryTemperatures(temperatures.max(), temperatures.min())
    }
    if (rainProbability != null && rainProbability >= 30 && rainiest != null) {
      parts += texts.summaryRain(rainProbability.toInt(), texts.hour(rainiest.timestampMillis, zone))
    }
    if (parts.isEmpty()) return null

    val barometer = buildString {
      if (pressureTrendHpaPerHour != null) append(texts.summaryBarometer(pressureTrendHpaPerHour))
      if (verdict != null && verdict.level != AlertLevel.QUIETE) {
        if (isNotEmpty()) append(" · ")
        val strongest = verdict.windows.maxByOrNull { it.probability }
        append(if (verdict.level == AlertLevel.ALLERTA) texts.summaryLocalAlert() else texts.summaryWatch())
        if (strongest != null) append(texts.summaryRainWindow((strongest.probability * 100).toInt(), strongest.window))
      }
    }

    val text = parts.joinToString(" · ")
    val age = dataAtMillis
      ?.takeIf { DataAge.of(it, nowMillis) != DataFreshness.FRESH }
      ?.let { texts.summaryDataAge(it, zone) }
    val tail = listOfNotNull(barometer.takeIf { it.isNotBlank() }, age).joinToString("\n")
    return AppNotification(
      channel = NotificationChannelKind.DAILY_SUMMARY,
      id = AlertPolicy.SUMMARY_ID,
      title = texts.summaryTitle(locationName),
      text = text,
      bigText = if (tail.isNotBlank()) "$text\n$tail" else text,
    )
  }
}
