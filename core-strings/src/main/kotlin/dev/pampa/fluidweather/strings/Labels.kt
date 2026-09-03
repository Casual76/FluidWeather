package dev.pampa.fluidweather.strings

import android.content.res.Resources
import androidx.annotation.StringRes
import dev.pampa.fluidweather.core.model.AqiBand
import dev.pampa.fluidweather.core.model.GlassLevel
import dev.pampa.fluidweather.core.model.MoonPhase
import dev.pampa.fluidweather.core.model.NotificationChannelKind
import dev.pampa.fluidweather.core.model.ObservedCondition
import dev.pampa.fluidweather.core.model.SampleSource
import dev.pampa.fluidweather.core.model.SamplingMode
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.nowcast.cleaning.RejectionReason
import dev.pampa.fluidweather.nowcast.verdict.AlertLevel

/*
 * La mappa dai modelli alle parole. I modelli (core-model, nowcast) non conoscono le lingue:
 * portano enum e identificativi, e qui ogni valore trova la sua risorsa. Chi mostra un'etichetta
 * passa di qui, cosi' testata, notifiche e impostazioni dicono la stessa cosa nella stessa lingua.
 */

@StringRes
fun WeatherKind.labelRes(): Int? = when (this) {
  WeatherKind.CLEAR -> R.string.kind_clear
  WeatherKind.MOSTLY_CLEAR -> R.string.kind_mostly_clear
  WeatherKind.PARTLY_CLOUDY -> R.string.kind_partly_cloudy
  WeatherKind.CLOUDY -> R.string.kind_cloudy
  WeatherKind.FOG -> R.string.kind_fog
  WeatherKind.DRIZZLE -> R.string.kind_drizzle
  WeatherKind.RAIN -> R.string.kind_rain
  WeatherKind.HEAVY_RAIN -> R.string.kind_heavy_rain
  WeatherKind.SLEET -> R.string.kind_sleet
  WeatherKind.SNOW -> R.string.kind_snow
  WeatherKind.HEAVY_SNOW -> R.string.kind_heavy_snow
  WeatherKind.THUNDERSTORM -> R.string.kind_thunderstorm
  WeatherKind.UNKNOWN -> null
}

/** Il nome della precipitazione che sta arrivando o finendo: "Neve in arrivo", non "Pioggia". */
@StringRes
fun WeatherKind?.precipitationNounRes(): Int = when (this) {
  WeatherKind.SNOW, WeatherKind.HEAVY_SNOW -> R.string.kind_snow
  WeatherKind.SLEET -> R.string.kind_sleet
  WeatherKind.THUNDERSTORM -> R.string.kind_thunderstorm
  WeatherKind.DRIZZLE -> R.string.kind_drizzle
  else -> R.string.kind_rain
}

@StringRes
fun ObservedCondition.labelRes(): Int = when (this) {
  ObservedCondition.CLEAR -> R.string.kind_clear
  ObservedCondition.PARTLY_CLOUDY -> R.string.observed_partly_cloudy
  ObservedCondition.CLOUDY -> R.string.kind_cloudy
  ObservedCondition.FOG -> R.string.kind_fog
  ObservedCondition.DRIZZLE -> R.string.kind_drizzle
  ObservedCondition.RAIN -> R.string.kind_rain
  ObservedCondition.HEAVY_RAIN -> R.string.kind_heavy_rain
  ObservedCondition.THUNDERSTORM -> R.string.kind_thunderstorm
  ObservedCondition.SNOW -> R.string.kind_snow
  ObservedCondition.HAIL -> R.string.observed_hail
}

@StringRes
fun AqiBand.labelRes(): Int = when (this) {
  AqiBand.GOOD -> R.string.aqi_good
  AqiBand.FAIR -> R.string.aqi_fair
  AqiBand.MODERATE -> R.string.aqi_moderate
  AqiBand.POOR -> R.string.aqi_poor
  AqiBand.VERY_POOR -> R.string.aqi_very_poor
  AqiBand.EXTREMELY_POOR -> R.string.aqi_extremely_poor
}

@StringRes
fun NotificationChannelKind.labelRes(): Int = when (this) {
  NotificationChannelKind.NOWCAST_ALERT -> R.string.channel_nowcast_alert
  NotificationChannelKind.PRECIPITATION -> R.string.channel_precipitation
  NotificationChannelKind.OFFICIAL_ALERTS -> R.string.channel_official
  NotificationChannelKind.DAILY_SUMMARY -> R.string.channel_summary
}

@StringRes
fun NotificationChannelKind.descriptionRes(): Int = when (this) {
  NotificationChannelKind.NOWCAST_ALERT -> R.string.channel_nowcast_alert_desc
  NotificationChannelKind.PRECIPITATION -> R.string.channel_precipitation_desc
  NotificationChannelKind.OFFICIAL_ALERTS -> R.string.channel_official_desc
  NotificationChannelKind.DAILY_SUMMARY -> R.string.channel_summary_desc
}

@StringRes
fun SamplingMode.labelRes(): Int = when (this) {
  SamplingMode.MASSIMA -> R.string.sampling_massima
  SamplingMode.BILANCIATA -> R.string.sampling_bilanciata
  SamplingMode.RISPARMIO -> R.string.sampling_risparmio
  SamplingMode.MINIMA -> R.string.sampling_minima
}

@StringRes
fun SamplingMode.descriptionRes(): Int = when (this) {
  SamplingMode.MASSIMA -> R.string.sampling_massima_desc
  SamplingMode.BILANCIATA -> R.string.sampling_bilanciata_desc
  SamplingMode.RISPARMIO -> R.string.sampling_risparmio_desc
  SamplingMode.MINIMA -> R.string.sampling_minima_desc
}

@StringRes
fun GlassLevel.labelRes(): Int = when (this) {
  GlassLevel.FULL -> R.string.glass_full
  GlassLevel.REDUCED -> R.string.glass_reduced
  GlassLevel.OFF -> R.string.glass_off
}

@StringRes
fun GlassLevel.descriptionRes(): Int = when (this) {
  GlassLevel.FULL -> R.string.glass_full_desc
  GlassLevel.REDUCED -> R.string.glass_reduced_desc
  GlassLevel.OFF -> R.string.glass_off_desc
}

@StringRes
fun MoonPhase.labelRes(): Int = when (this) {
  MoonPhase.NEW -> R.string.moon_new
  MoonPhase.WAXING_CRESCENT -> R.string.moon_waxing_crescent
  MoonPhase.FIRST_QUARTER -> R.string.moon_first_quarter
  MoonPhase.WAXING_GIBBOUS -> R.string.moon_waxing_gibbous
  MoonPhase.FULL -> R.string.moon_full
  MoonPhase.WANING_GIBBOUS -> R.string.moon_waning_gibbous
  MoonPhase.LAST_QUARTER -> R.string.moon_last_quarter
  MoonPhase.WANING_CRESCENT -> R.string.moon_waning_crescent
}

@StringRes
fun AlertLevel.labelRes(): Int = when (this) {
  AlertLevel.QUIETE -> R.string.level_quiet
  AlertLevel.SORVEGLIANZA -> R.string.level_watch
  AlertLevel.ALLERTA -> R.string.level_alert
}

@StringRes
fun SampleSource.labelRes(): Int = when (this) {
  SampleSource.PERIODIC -> R.string.sample_source_periodic
  SampleSource.SURVEILLANCE -> R.string.sample_source_surveillance
  SampleSource.MANUAL_BURST -> R.string.sample_source_manual
  SampleSource.CONTINUOUS -> R.string.sample_source_continuous
  SampleSource.CALIBRATION -> R.string.sample_source_calibration
}

@StringRes
fun RejectionReason.labelRes(): Int = when (this) {
  RejectionReason.ANOMALOUS_VARIANCE -> R.string.reject_variance
  RejectionReason.VEHICLE -> R.string.reject_vehicle
  RejectionReason.ALTITUDE_CHANGE -> R.string.reject_altitude
  RejectionReason.NON_WEATHER_JUMP -> R.string.reject_jump
}

@StringRes
fun RejectionReason.shortLabelRes(): Int = when (this) {
  RejectionReason.ANOMALOUS_VARIANCE -> R.string.reject_short_variance
  RejectionReason.VEHICLE -> R.string.reject_short_vehicle
  RejectionReason.ALTITUDE_CHANGE -> R.string.reject_short_altitude
  RejectionReason.NON_WEATHER_JUMP -> R.string.reject_short_jump
}

/** Le finestre del verdetto ("0-1h", "1-3h", "3-6h") come titolo: "Entro un'ora". */
@StringRes
fun windowLabelRes(window: String): Int = when (window) {
  "0-1h" -> R.string.window_0_1
  "1-3h" -> R.string.window_1_3
  "3-6h" -> R.string.window_3_6
  else -> R.string.window_other
}

/** Le stesse finestre dentro una frase: "pioggia probabile entro un'ora". */
@StringRes
fun windowPhraseRes(window: String): Int = when (window) {
  "0-1h" -> R.string.window_phrase_0_1
  "1-3h" -> R.string.window_phrase_1_3
  "3-6h" -> R.string.window_phrase_3_6
  else -> R.string.window_phrase_other
}

/**
 * I nomi delle feature del modello ([dev.pampa.fluidweather.nowcast.features.FeatureExtractor.names])
 * sono il contratto del modello addestrato e non cambiano; qui diventano parole.
 */
@StringRes
fun featureLabelRes(name: String): Int = when (name) {
  "tendenza-1h" -> R.string.feature_trend_1h
  "tendenza-3h" -> R.string.feature_trend_3h
  "tendenza-6h" -> R.string.feature_trend_6h
  "tendenza-12h" -> R.string.feature_trend_12h
  "accelerazione-3h" -> R.string.feature_acceleration_3h
  "anomalia-livello" -> R.string.feature_level_anomaly
  "incertezza-tendenza" -> R.string.feature_trend_uncertainty
  "umidita'" -> R.string.feature_humidity
  "spread-rugiada" -> R.string.feature_dew_spread
  "copertura" -> R.string.feature_cloud_cover
  "vento" -> R.string.feature_wind
  "rotazione-vento-3h" -> R.string.feature_wind_rotation_3h
  "pioggia-ultima-ora" -> R.string.feature_rain_last_hour
  "pioggia-ultime-3h" -> R.string.feature_rain_last_3h
  "ora-sin", "ora-cos" -> R.string.feature_hour
  else -> R.string.feature_unknown
}

/** Il nome del provider dove non c'e' un descrittore: il barometro locale della pagella. */
const val LOCAL_BAROMETER_ID: String = "barometro"

/** I sedici punti della rosa dei venti nella lingua corrente, da dove il vento VIENE. */
fun compassPoint(resources: Resources, degrees: Double): String {
  val names = resources.getString(R.string.compass_points).split(',')
  val index = ((degrees % 360 + 360) % 360 / 22.5 + 0.5).toInt() % 16
  return names.getOrElse(index) { "" }
}

/** La fascia dell'indice UV secondo la scala OMS: le stesse soglie della pagina dei dettagli. */
@StringRes
fun uvLabelRes(uv: Double): Int = when {
  uv < 3 -> R.string.uv_low
  uv < 6 -> R.string.uv_moderate
  uv < 8 -> R.string.uv_high
  uv < 11 -> R.string.uv_very_high
  else -> R.string.uv_extreme
}

/** La visibilita' a parole: le stesse soglie della pagina dei dettagli. */
@StringRes
fun visibilityLabelRes(meters: Double): Int = when {
  meters >= 10_000 -> R.string.visibility_excellent
  meters >= 4_000 -> R.string.visibility_good
  meters >= 1_000 -> R.string.visibility_reduced
  else -> R.string.details_visibility_fog
}

/** Come si sente il punto di rugiada: secco, comodo, umido, afoso. */
@StringRes
fun dewPointLabelRes(dewPointC: Double): Int = when {
  dewPointC < 10 -> R.string.details_dew_dry
  dewPointC < 16 -> R.string.details_dew_comfortable
  dewPointC < 21 -> R.string.details_dew_humid
  else -> R.string.details_dew_muggy
}
