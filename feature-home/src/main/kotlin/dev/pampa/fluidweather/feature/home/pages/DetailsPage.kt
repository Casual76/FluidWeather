package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.ApparentTemperature
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.ui.PageCharts
import dev.pampa.fluidweather.feature.home.HomeUiState
import kotlin.math.abs
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.strings.compassPoint
import androidx.compose.ui.platform.LocalContext

/**
 * Ogni misura del widget Dettagli, estesa: il valore adesso, la sua curva nelle prossime 24
 * ore, e la spiegazione di cosa significa. In fondo, il confronto fra la pressione al mare
 * dei provider e quella del barometro del telefono: lo scarto e' l'indizio del bias del
 * dispositivo che la taratura stima per bene.
 */
@Composable
internal fun DetailsPage(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val units = rememberUnitFormatter()
  val context = LocalContext.current
  val current = state.fusedHours.minByOrNull { abs(it.timestampMillis - now) }
  if (current == null) {
    PageNote(stringResource(R.string.common_waiting_providers))
    return
  }
  val next24 = state.fusedHours.filter { it.timestampMillis >= now - 30 * 60_000L }.take(24)
  val labels = timeLabels(next24.map { it.timestampMillis })
  fun value(variable: String): Double? = current.values[variable]?.value
  fun series(variable: String): List<Double> = next24.mapNotNull { it.values[variable]?.value }

  // ------------------------------------------------------------------------------- vento
  PageSection(stringResource(R.string.common_wind))
  val wind = value(FusionVariables.WIND_SPEED)
  val gust = value(FusionVariables.WIND_GUST)
  val direction = current.windDirectionDeg
  Row(verticalAlignment = Alignment.CenterVertically) {
    if (direction != null) {
      PageCharts.Compass(
        directionFromDeg = direction,
        ringColor = White,
        arrowColor = PageBlue,
        modifier = Modifier.size(84.dp),
      )
      Spacer(Modifier.width(16.dp))
    }
    Column {
      BigStat(
        wind?.let { units.windValue(it) } ?: "—",
        units.windSymbol(),
        direction?.let { stringResource(R.string.details_wind_from, compassPoint(context.resources, it), it.toInt()) } ?: stringResource(R.string.details_no_direction),
      )
      if (gust != null) StatRow(stringResource(R.string.details_gusts), units.wind(gust))
    }
  }
  if (series(FusionVariables.WIND_SPEED).size >= 2) {
    Spacer(Modifier.height(6.dp))
    CurveWithLabels(units.windSeries(series(FusionVariables.WIND_SPEED)), labels, PageBlue, " " + units.windSymbol(), height = 70.dp)
  }
  PageNote(stringResource(R.string.details_wind_note))

  // ------------------------------------------------------------------- umidita' e rugiada
  PageSection(stringResource(R.string.details_humidity_title))
  val humidity = value(FusionVariables.HUMIDITY)
  val dewPoint = value(FusionVariables.DEW_POINT)
  val apparent = if (state.temperatureC != null && humidity != null && wind != null) {
    ApparentTemperature.celsius(state.temperatureC, humidity, wind)
  } else {
    null
  }
  StatGrid(
    listOf(
      stringResource(R.string.details_rh) to (humidity?.let { "${it.toInt()}%" } ?: "—"),
      stringResource(R.string.details_dew_point) to (dewPoint?.let { units.degrees(it) } ?: "—"),
      stringResource(R.string.common_feels_like) to (apparent?.let { units.degrees(it) } ?: "—"),
      stringResource(R.string.common_temperature) to (state.temperatureC?.let { units.degrees(it) } ?: "—"),
    ),
  )
  if (dewPoint != null) {
    Spacer(Modifier.height(6.dp))
    PageNote(
      stringResource(R.string.details_dew_prefix) + when {
        dewPoint < 10 -> stringResource(R.string.details_dew_dry)
        dewPoint < 16 -> stringResource(R.string.details_dew_comfortable)
        dewPoint < 21 -> stringResource(R.string.details_dew_humid)
        else -> stringResource(R.string.details_dew_muggy)
      } + stringResource(R.string.details_dew_explain),
    )
  }
  if (series(FusionVariables.HUMIDITY).size >= 2) {
    CurveWithLabels(series(FusionVariables.HUMIDITY), labels, PageBlue, "%", height = 70.dp)
  }
  if (apparent != null) {
    PageNote(stringResource(R.string.details_feels_note))
  }

  // ------------------------------------------------------------------------------- UV
  PageSection(stringResource(R.string.details_uv))
  val uv = value(FusionVariables.UV_INDEX)
  StatRow(stringResource(R.string.common_now), uv?.let { fmt0(it) } ?: "—", uv?.let { uvLabel(it) })
  val uvPeak = next24.filter { localDate(it.timestampMillis) == localDate(now) }
    .mapNotNull { hour -> hour.values[FusionVariables.UV_INDEX]?.value?.let { hour.timestampMillis to it } }
    .maxByOrNull { it.second }
  if (uvPeak != null) {
    StatRow(stringResource(R.string.details_uv_max), fmt0(uvPeak.second), stringResource(R.string.details_uv_at, fmtTime(uvPeak.first), uvLabel(uvPeak.second)))
  }
  if (series(FusionVariables.UV_INDEX).size >= 2) {
    CurveWithLabels(series(FusionVariables.UV_INDEX), labels, PageAmber, "", height = 60.dp)
  }
  PageNote(stringResource(R.string.details_uv_scale))

  // ------------------------------------------------------------------------ visibilita'
  PageSection(stringResource(R.string.common_visibility))
  val visibility = value(FusionVariables.VISIBILITY)
  StatRow(
    stringResource(R.string.common_now),
    visibility?.let { units.visibilityMeters(it, 1) } ?: "—",
    visibility?.let { visibilityLabel(it) },
  )
  if (series(FusionVariables.VISIBILITY).size >= 2) {
    CurveWithLabels(units.distanceSeries(series(FusionVariables.VISIBILITY).map { it / 1000 }), labels, PageBlue, " " + units.distanceSymbol(), height = 60.dp)
  }

  // ---------------------------------------------------------- pressione: provider vs barometro
  PageSection(stringResource(R.string.details_pressure_title))
  val fusedMsl = value(FusionVariables.PRESSURE_MSL)
  val local = state.cleaning?.latest?.levelHpa
  StatRow(stringResource(R.string.details_pressure_fused), fusedMsl?.let { units.pressure(it, 1) } ?: "—")
  StatRow(stringResource(R.string.details_pressure_local), local?.let { units.pressure(it, 1) } ?: "—")
  if (fusedMsl != null && local != null) {
    StatRow(stringResource(R.string.details_pressure_gap), units.pressureDelta(local - fusedMsl), stringResource(R.string.details_gap_hint))
  }
  if (series(FusionVariables.PRESSURE_MSL).size >= 2) {
    CurveWithLabels(units.pressureSeries(series(FusionVariables.PRESSURE_MSL)), labels, PageAmber, " " + units.pressureSymbol(), height = 70.dp, decimals = units.pressureChartDecimals() + 1)
  }
  PageNote(
    stringResource(R.string.details_bias_note),
  )
}

@Composable
private fun uvLabel(uv: Double): String = when {
  uv < 3 -> stringResource(R.string.uv_low)
  uv < 6 -> stringResource(R.string.uv_moderate)
  uv < 8 -> stringResource(R.string.uv_high)
  uv < 11 -> stringResource(R.string.uv_very_high)
  else -> stringResource(R.string.uv_extreme)
}

@Composable
private fun visibilityLabel(meters: Double): String = when {
  meters >= 10_000 -> stringResource(R.string.visibility_excellent)
  meters >= 4_000 -> stringResource(R.string.visibility_good)
  meters >= 1_000 -> stringResource(R.string.visibility_reduced)
  else -> stringResource(R.string.details_visibility_fog)
}
