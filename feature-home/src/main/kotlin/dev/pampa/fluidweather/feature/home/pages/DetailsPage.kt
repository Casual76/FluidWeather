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
import java.util.Locale
import kotlin.math.abs

/**
 * Ogni misura del widget Dettagli, estesa: il valore adesso, la sua curva nelle prossime 24
 * ore, e la spiegazione di cosa significa. In fondo, il confronto fra la pressione al mare
 * dei provider e quella del barometro del telefono: lo scarto e' l'indizio del bias del
 * dispositivo che la taratura (fase 15) stimera' per bene.
 */
@Composable
internal fun DetailsPage(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val current = state.fusedHours.minByOrNull { abs(it.timestampMillis - now) }
  if (current == null) {
    PageNote("In attesa dei provider…")
    return
  }
  val next24 = state.fusedHours.filter { it.timestampMillis >= now - 30 * 60_000L }.take(24)
  val labels = timeLabels(next24.map { it.timestampMillis })
  fun value(variable: String): Double? = current.values[variable]?.value
  fun series(variable: String): List<Double> = next24.mapNotNull { it.values[variable]?.value }

  // ------------------------------------------------------------------------------- vento
  PageSection("Vento")
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
        wind?.let { "${it.toInt()}" } ?: "—",
        "km/h",
        direction?.let { "da ${windDirectionName(it)} (${it.toInt()}°)" } ?: "direzione non disponibile",
      )
      if (gust != null) StatRow("Raffiche", "${gust.toInt()} km/h")
    }
  }
  if (series(FusionVariables.WIND_SPEED).size >= 2) {
    Spacer(Modifier.height(6.dp))
    CurveWithLabels(series(FusionVariables.WIND_SPEED), labels, PageBlue, " km/h", height = 70.dp)
  }
  PageNote("La freccia indica dove il vento va; il nome dice da dove viene, come si e' sempre detto.")

  // ------------------------------------------------------------------- umidita' e rugiada
  PageSection("Umidita' e punto di rugiada")
  val humidity = value(FusionVariables.HUMIDITY)
  val dewPoint = value(FusionVariables.DEW_POINT)
  val apparent = if (state.temperatureC != null && humidity != null && wind != null) {
    ApparentTemperature.celsius(state.temperatureC, humidity, wind)
  } else {
    null
  }
  StatGrid(
    listOf(
      "Umidita' relativa" to (humidity?.let { "${it.toInt()}%" } ?: "—"),
      "Punto di rugiada" to (dewPoint?.let { "${it.toInt()}°" } ?: "—"),
      "Percepita" to (apparent?.let { "${it.toInt()}°" } ?: "—"),
      "Temperatura" to (state.temperatureC?.let { "${it.toInt()}°" } ?: "—"),
    ),
  )
  if (dewPoint != null) {
    Spacer(Modifier.height(6.dp))
    PageNote(
      "Punto di rugiada: " + when {
        dewPoint < 10 -> "aria secca e gradevole."
        dewPoint < 16 -> "aria confortevole."
        dewPoint < 21 -> "aria umida, un po' appiccicosa."
        else -> "afa: l'aria e' satura e il sudore non evapora."
      } + " E' la temperatura a cui l'aria si satura: dice quanta acqua c'e' davvero, piu' dell'umidita' relativa.",
    )
  }
  if (series(FusionVariables.HUMIDITY).size >= 2) {
    CurveWithLabels(series(FusionVariables.HUMIDITY), labels, PageBlue, "%", height = 70.dp)
  }
  if (apparent != null) {
    PageNote("Percepita con la formula di Steadman (la stessa del servizio australiano): temperatura, umidita' e vento insieme.")
  }

  // ------------------------------------------------------------------------------- UV
  PageSection("Indice UV")
  val uv = value(FusionVariables.UV_INDEX)
  StatRow("Adesso", uv?.let { fmt0(it) } ?: "—", uv?.let { uvLabel(it) })
  val uvPeak = next24.filter { localDate(it.timestampMillis) == localDate(now) }
    .mapNotNull { hour -> hour.values[FusionVariables.UV_INDEX]?.value?.let { hour.timestampMillis to it } }
    .maxByOrNull { it.second }
  if (uvPeak != null) {
    StatRow("Massimo di oggi", fmt0(uvPeak.second), "alle ${fmtTime(uvPeak.first)} · ${uvLabel(uvPeak.second)}")
  }
  if (series(FusionVariables.UV_INDEX).size >= 2) {
    CurveWithLabels(series(FusionVariables.UV_INDEX), labels, PageAmber, "", height = 60.dp)
  }
  PageNote("Scala OMS: 0-2 basso, 3-5 moderato, 6-7 alto, 8-10 molto alto, 11 e oltre estremo.")

  // ------------------------------------------------------------------------ visibilita'
  PageSection("Visibilita'")
  val visibility = value(FusionVariables.VISIBILITY)
  StatRow(
    "Adesso",
    visibility?.let { String.format(Locale.getDefault(), "%.1f km", it / 1000) } ?: "—",
    visibility?.let { visibilityLabel(it) },
  )
  if (series(FusionVariables.VISIBILITY).size >= 2) {
    CurveWithLabels(series(FusionVariables.VISIBILITY).map { it / 1000 }, labels, PageBlue, " km", height = 60.dp)
  }

  // ---------------------------------------------------------- pressione: provider vs barometro
  PageSection("Pressione al mare: provider e barometro")
  val fusedMsl = value(FusionVariables.PRESSURE_MSL)
  val local = state.cleaning?.latest?.levelHpa
  StatRow("Provider (fusa)", fusedMsl?.let { "${fmt1(it)} hPa" } ?: "—")
  StatRow("Barometro (ridotto al mare)", local?.let { "${fmt1(it)} hPa" } ?: "—")
  if (fusedMsl != null && local != null) {
    StatRow("Scarto", String.format(Locale.ROOT, "%+.1f hPa", local - fusedMsl), "l'indizio del bias del dispositivo")
  }
  if (series(FusionVariables.PRESSURE_MSL).size >= 2) {
    CurveWithLabels(series(FusionVariables.PRESSURE_MSL), labels, PageAmber, " hPa", height = 70.dp, decimals = 1)
  }
  PageNote(
    "Uno scarto costante fra barometro e provider e' il bias del sensore, che la letteratura da' " +
      "di qualche hPa e stabile nel tempo; la taratura iniziale (fase 15) lo stimera' contro la " +
      "stazione piu' vicina. Per il nowcast conta la tendenza, e un offset non la tocca.",
  )
}

private fun uvLabel(uv: Double): String = when {
  uv < 3 -> "basso"
  uv < 6 -> "moderato"
  uv < 8 -> "alto"
  uv < 11 -> "molto alto"
  else -> "estremo"
}

private fun visibilityLabel(meters: Double): String = when {
  meters >= 10_000 -> "ottima"
  meters >= 4_000 -> "buona"
  meters >= 1_000 -> "ridotta"
  else -> "nebbia o foschia"
}
