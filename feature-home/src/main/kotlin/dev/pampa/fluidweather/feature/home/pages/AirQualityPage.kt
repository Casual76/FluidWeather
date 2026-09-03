package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.pampa.fluidweather.core.model.AqiBand
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.aqiColor
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.strings.labelRes

/**
 * La pagina della qualita' dell'aria: l'indice europeo con la sua scala, gli inquinanti uno a
 * uno col sotto-indice sulla LORO scala (e' cosi' che si capisce chi domina), la previsione
 * dell'indice ora per ora, e i pollini per specie.
 */
@Composable
internal fun AirQualityPage(state: HomeUiState) {
  val air = state.airQuality
  if (air == null) {
    PageNote(stringResource(R.string.aq_unavailable_point))
    return
  }

  PageSection(stringResource(R.string.aq_index_title))
  BigStat("${air.europeanAqi}", null, stringResource(air.band.labelRes()), color = aqiColor(air.band))
  Spacer(Modifier.height(10.dp))
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    AqiBand.entries.forEach { band ->
      Box(
        Modifier
          .weight(1f)
          .height(if (band == air.band) 12.dp else 8.dp)
          .background(aqiColor(band).copy(alpha = if (band == air.band) 1f else 0.45f), ContinuousCornerShape(4.dp)),
      )
    }
  }
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(stringResource(R.string.aqi_good), style = MaterialTheme.typography.labelSmall, color = Faint)
    Text(stringResource(R.string.aqi_moderate), style = MaterialTheme.typography.labelSmall, color = Faint)
    Text(stringResource(R.string.aqi_extremely_poor), style = MaterialTheme.typography.labelSmall, color = Faint)
  }
  PageNote(
    stringResource(R.string.aq_explain),
  )

  PageSection(stringResource(R.string.aq_pollutants_now))
  if (air.pollutants.isEmpty()) {
    PageNote(stringResource(R.string.aq_no_concentrations))
  } else {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
      Text(stringResource(R.string.aq_col_pollutant), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.weight(1f))
      Text("µg/m³", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(64.dp))
      Text(stringResource(R.string.aq_col_subindex), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(84.dp))
      Text(stringResource(R.string.aq_col_band), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(88.dp))
    }
    air.pollutants.sortedByDescending { it.subIndex }.forEach { pollutant ->
      Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
          pollutant.name + if (pollutant.name == air.dominantPollutant) stringResource(R.string.aq_dominant_suffix) else "",
          style = MaterialTheme.typography.bodyMedium,
          color = White,
          modifier = Modifier.weight(1f),
        )
        Text(fmt1(pollutant.valueUgm3), style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.width(64.dp))
        Text(fmt0(pollutant.subIndex), style = MaterialTheme.typography.bodyMedium, color = White, modifier = Modifier.width(84.dp))
        Text(
          stringResource(pollutant.band.labelRes()),
          style = MaterialTheme.typography.bodySmall,
          color = aqiColor(pollutant.band),
          modifier = Modifier.width(88.dp),
        )
      }
    }
    PageNote(stringResource(R.string.aq_averaging_note))
  }

  PageSection(stringResource(R.string.aq_forecast))
  val forecast = air.forecast
  if (forecast.size >= 2) {
    CurveWithLabels(
      values = forecast.map { it.europeanAqi.toDouble() },
      labels = timeLabels(forecast.map { it.timestampMillis }),
      color = PageAmber,
      height = 80.dp,
    )
    val worst = forecast.maxByOrNull { it.europeanAqi }
    if (worst != null) {
      StatRow(stringResource(R.string.aq_worst_ahead), "${worst.europeanAqi} · ${stringResource(AqiBand.of(worst.europeanAqi).labelRes())}", fmtDay(worst.timestampMillis) + " " + fmtTime(worst.timestampMillis))
    }
  } else {
    PageNote(stringResource(R.string.aq_no_forecast))
  }

  PageSection(stringResource(R.string.aq_pollen))
  val pollen = air.pollen
  if (pollen == null) {
    PageNote(stringResource(R.string.aq_pollen_europe))
  } else if (!pollen.any) {
    PageNote(stringResource(R.string.aq_no_pollen))
  } else {
    listOf(
      stringResource(R.string.pollen_alder) to pollen.alder,
      stringResource(R.string.pollen_birch) to pollen.birch,
      stringResource(R.string.pollen_grass) to pollen.grass,
      stringResource(R.string.pollen_olive) to pollen.olive,
      stringResource(R.string.pollen_ragweed) to pollen.ragweed,
    ).forEach { (name, value) ->
      if (value != null) {
        StatRow(name, stringResource(R.string.aq_grains, value.toInt()), pollenLevel(value))
      }
    }
    PageNote(stringResource(R.string.aq_pollen_thresholds))
  }
}

@Composable
private fun pollenLevel(value: Double): String = when {
  value <= 0.0 -> stringResource(R.string.pollen_none)
  value < 10 -> stringResource(R.string.pollen_low)
  value < 50 -> stringResource(R.string.pollen_moderate)
  value < 100 -> stringResource(R.string.pollen_high)
  else -> stringResource(R.string.pollen_very_high)
}
