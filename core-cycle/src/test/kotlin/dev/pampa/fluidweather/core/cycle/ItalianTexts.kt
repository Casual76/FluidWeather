package dev.pampa.fluidweather.core.cycle

import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Le parole italiane della fase 11, fissate per i test: la politica non deve cambiare frase. */
object ItalianTexts : NotificationTexts {

  private fun windowPhrase(window: String) = when (window) {
    "0-1h" -> "entro un'ora"
    "1-3h" -> "fra una e tre ore"
    "3-6h" -> "fra tre e sei ore"
    else -> "nelle prossime ore"
  }

  private fun noun(kind: WeatherKind?) = when (kind) {
    WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> "Neve"
    WeatherKind.SLEET -> "Pioggia gelata"
    WeatherKind.THUNDERSTORM -> "Temporale"
    WeatherKind.DRIZZLE -> "Pioviggine"
    else -> "Pioggia"
  }

  override fun nowcastTitle() = "Allerta del barometro"
  override fun nowcastText(window: String, probabilityPercent: Int) = "Pioggia probabile ${windowPhrase(window)}: $probabilityPercent%"
  override fun nowcastBigText(window: String, probabilityPercent: Int, lowPercent: Int, highPercent: Int, factors: String?) =
    "Il barometro del telefono vede arrivare la pioggia ${windowPhrase(window)} (probabilita' $probabilityPercent%, banda $lowPercent-$highPercent%)." +
      (factors?.let { "\nFattori: $it." } ?: "")
  override fun factorLabel(name: String) = name
  override fun onsetTitle(kind: WeatherKind?) = "${noun(kind)} in arrivo"
  override fun onsetText(time: String) = "Inizia verso le $time dove sei."
  override fun endTitle(kind: WeatherKind?) = "${noun(kind)} in esaurimento"
  override fun endText(time: String) = "Smette verso le $time."
  override fun officialArea() = "Area: "
  override fun officialSource() = "Fonte: "
  override fun officialVerbatim() = "\nTesto riportato com'e' stato emesso, senza reinterpretazione."
  override fun summaryTitle(locationName: String?) = if (locationName != null) "Oggi a $locationName" else "Il tempo di oggi"
  override fun kindLabel(kind: WeatherKind?): String? = when (kind) {
    WeatherKind.PARTLY_CLOUDY -> "Parzialmente nuvoloso"
    WeatherKind.CLEAR -> "Sereno"
    WeatherKind.RAIN -> "Pioggia"
    null, WeatherKind.UNKNOWN -> null
    else -> kind.name
  }
  override fun summaryTemperatures(maxCelsius: Double, minCelsius: Double) = "max ${maxCelsius.toInt()}° min ${minCelsius.toInt()}°"
  override fun summaryRain(probabilityPercent: Int, hour: String) = "pioggia $probabilityPercent% verso le $hour"
  override fun summaryBarometer(trendHpaPerHour: Double): String {
    val trend = when {
      trendHpaPerHour <= -0.5 -> "in calo"
      trendHpaPerHour >= 0.5 -> "in aumento"
      else -> "stabile"
    }
    return "Barometro $trend (${String.format(Locale.ROOT, "%+.1f", trendHpaPerHour)} hPa/h)"
  }
  override fun summaryLocalAlert() = "allerta locale"
  override fun summaryWatch() = "sorveglianza"
  override fun summaryRainWindow(probabilityPercent: Int, window: String) = ": pioggia $probabilityPercent% ${windowPhrase(window)}"
  override fun time(millis: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(millis))
  override fun hour(millis: Long, zone: ZoneId): String = DateTimeFormatter.ofPattern("HH").withZone(zone).format(Instant.ofEpochMilli(millis))
  override fun cycleSkipped() = "tick saltato"
  override fun cycleNoPosition() = "nessuna posizione"
  override fun cycleNote(
    nowMillis: Long,
    zone: ZoneId,
    trigger: CycleTrigger,
    providersResponding: Int?,
    providersTotal: Int?,
    fromSnapshot: Boolean,
    level: AlertLevel?,
    delivered: Int,
  ) = "$trigger · $providersResponding/$providersTotal · $level · $delivered"
}
