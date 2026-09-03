package dev.pampa.fluidweather.core.cycle

import android.content.Context
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.TimeFormats
import dev.pampa.fluidweather.strings.UnitFormatter
import dev.pampa.fluidweather.strings.featureLabelRes
import dev.pampa.fluidweather.strings.labelRes
import dev.pampa.fluidweather.strings.precipitationNounRes
import dev.pampa.fluidweather.strings.windowPhraseRes
import java.time.ZoneId

/**
 * Le parole delle notifiche e della nota del ciclo, separate dalla politica: la politica resta
 * pura e si prova sul computer con un'implementazione di prova, il telefono usa le risorse
 * nella lingua e nelle unita' dell'utente (fase 17).
 */
interface NotificationTexts {
  fun nowcastTitle(): String
  fun nowcastText(window: String, probabilityPercent: Int): String
  fun nowcastBigText(window: String, probabilityPercent: Int, lowPercent: Int, highPercent: Int, factors: String?): String
  fun factorLabel(name: String): String
  fun onsetTitle(kind: WeatherKind?): String
  fun onsetText(time: String): String
  fun endTitle(kind: WeatherKind?): String
  fun endText(time: String): String
  fun officialArea(): String
  fun officialSource(): String
  fun officialVerbatim(): String
  fun summaryTitle(locationName: String?): String

  /** "Dati delle 21:40": il riepilogo esce anche su dati vecchi, ma lo dice. */
  fun summaryDataAge(atMillis: Long, zone: ZoneId): String
  fun kindLabel(kind: WeatherKind?): String?
  fun summaryTemperatures(maxCelsius: Double, minCelsius: Double): String
  fun summaryRain(probabilityPercent: Int, hour: String): String
  fun summaryBarometer(trendHpaPerHour: Double): String
  fun summaryLocalAlert(): String
  fun summaryWatch(): String
  fun summaryRainWindow(probabilityPercent: Int, window: String): String
  fun time(millis: Long, zone: ZoneId): String
  fun hour(millis: Long, zone: ZoneId): String
  fun cycleNoPosition(): String
  fun cycleNote(
    nowMillis: Long,
    zone: ZoneId,
    trigger: CycleTrigger,
    providersResponding: Int?,
    providersTotal: Int?,
    fromSnapshot: Boolean,
    level: AlertLevel?,
    delivered: Int,
  ): String
}

/** Le risorse del telefono: lingua di sistema, unita' scelte dall'utente. */
class ResourceNotificationTexts(
  private val context: Context,
  private val units: () -> UnitFormatter,
) : NotificationTexts {

  private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

  override fun nowcastTitle() = s(R.string.notif_nowcast_title)

  override fun nowcastText(window: String, probabilityPercent: Int) =
    s(R.string.notif_nowcast_text, s(windowPhraseRes(window)), probabilityPercent)

  override fun nowcastBigText(window: String, probabilityPercent: Int, lowPercent: Int, highPercent: Int, factors: String?): String =
    s(R.string.notif_nowcast_big, s(windowPhraseRes(window)), probabilityPercent, lowPercent, highPercent) +
      (factors?.takeIf { it.isNotBlank() }?.let { s(R.string.notif_nowcast_factors, it) } ?: "")

  override fun factorLabel(name: String) = s(featureLabelRes(name))

  override fun onsetTitle(kind: WeatherKind?) = s(R.string.notif_onset_title, s(kind.precipitationNounRes()))

  override fun onsetText(time: String) = s(R.string.notif_onset_text, time)

  override fun endTitle(kind: WeatherKind?) = s(R.string.notif_end_title, s(kind.precipitationNounRes()))

  override fun endText(time: String) = s(R.string.notif_end_text, time)

  override fun officialArea() = s(R.string.notif_official_area)

  override fun officialSource() = s(R.string.notif_official_source)

  override fun officialVerbatim() = s(R.string.notif_official_verbatim)

  override fun summaryDataAge(atMillis: Long, zone: ZoneId): String =
    s(R.string.summary_data_age, TimeFormats.time(atMillis, zone))

  override fun summaryTitle(locationName: String?) =
    if (locationName != null) s(R.string.summary_title_at, locationName) else s(R.string.summary_title)

  override fun kindLabel(kind: WeatherKind?): String? = kind?.labelRes()?.let { s(it) }

  override fun summaryTemperatures(maxCelsius: Double, minCelsius: Double): String {
    val formatter = units()
    return s(R.string.summary_temps, formatter.degrees(maxCelsius), formatter.degrees(minCelsius))
  }

  override fun summaryRain(probabilityPercent: Int, hour: String) = s(R.string.summary_rain, probabilityPercent, hour)

  override fun summaryBarometer(trendHpaPerHour: Double): String {
    val trend = when {
      trendHpaPerHour <= -0.5 -> R.string.trend_falling
      trendHpaPerHour >= 0.5 -> R.string.trend_rising
      else -> R.string.trend_steady
    }
    return s(R.string.summary_barometer, s(trend), units().pressureRate(trendHpaPerHour))
  }

  override fun summaryLocalAlert() = s(R.string.summary_local_alert)

  override fun summaryWatch() = s(R.string.summary_watch)

  override fun summaryRainWindow(probabilityPercent: Int, window: String) =
    s(R.string.summary_rain_window, probabilityPercent, s(windowPhraseRes(window)))

  override fun time(millis: Long, zone: ZoneId) = TimeFormats.time(millis, zone)

  override fun hour(millis: Long, zone: ZoneId) = TimeFormats.hour(millis, zone)

  override fun cycleNoPosition() = s(R.string.cycle_no_position)

  override fun cycleNote(
    nowMillis: Long,
    zone: ZoneId,
    trigger: CycleTrigger,
    providersResponding: Int?,
    providersTotal: Int?,
    fromSnapshot: Boolean,
    level: AlertLevel?,
    delivered: Int,
  ): String = buildString {
    append(TimeFormats.time(nowMillis, zone))
    append(" · ").append(
      s(
        when (trigger) {
          CycleTrigger.SAMPLING_PASS -> R.string.trigger_pass
          CycleTrigger.SURVEILLANCE_TICK -> R.string.trigger_surveillance
          CycleTrigger.DAILY_SUMMARY -> R.string.trigger_summary
          CycleTrigger.MANUAL -> R.string.trigger_manual
        },
      ),
    )
    if (providersResponding != null && providersTotal != null) {
      append(s(R.string.cycle_providers, providersResponding, providersTotal))
      if (fromSnapshot) append(s(R.string.cycle_snapshot))
    } else {
      append(s(R.string.cycle_no_weather))
    }
    append(s(R.string.cycle_verdict, level?.let { s(it.labelRes()).lowercase() } ?: s(R.string.cycle_verdict_absent)))
    append(s(R.string.cycle_notifications, delivered))
  }
}
