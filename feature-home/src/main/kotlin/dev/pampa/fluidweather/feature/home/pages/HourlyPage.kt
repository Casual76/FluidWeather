package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.WeatherKindIcon

/**
 * Ora per ora su tutto l'orizzonte fuso, raggruppato per giorno: temperatura, probabilita' e
 * accumulo di pioggia, vento con direzione, umidita'. Ogni riga e' la fusione pesata dei
 * provider che coprono il punto.
 */
@Composable
internal fun HourlyPage(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val hours = state.fusedHours.filter { it.timestampMillis >= now - 3_600_000L }
  if (hours.isEmpty()) {
    PageNote("In attesa dei provider…")
    return
  }
  PageNote(
    "Ogni riga e' la fusione pesata dei provider che coprono questo punto " +
      "(${state.providersResponding} hanno risposto all'ultimo giro).",
  )
  val today = localDate(now)
  hours.groupBy { localDate(it.timestampMillis) }.toSortedMap().forEach { (date, list) ->
    PageSection(if (date == today) "Oggi" else fmtDate(date))
    HeaderRow()
    list.forEach { HourRow(it) }
  }
}

@Composable
private fun HeaderRow() {
  Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
    Text("ora", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(34.dp))
    Spacer(Modifier.width(26.dp))
    Text("°C", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(44.dp))
    Text("pioggia", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(52.dp))
    Text("mm", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(44.dp))
    Text("vento", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.weight(1f))
    Text("umid.", style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(40.dp))
  }
}

@Composable
private fun HourRow(hour: FusedHour) {
  fun value(variable: String): Double? = hour.values[variable]?.value
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
  ) {
    Text(fmtHour(hour.timestampMillis), style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.width(34.dp))
    WeatherKindIcon(hour.kind, size = 18.dp)
    Spacer(Modifier.width(8.dp))
    Text(
      value(FusionVariables.TEMPERATURE)?.let { "${it.toInt()}°" } ?: "—",
      style = MaterialTheme.typography.titleSmall,
      color = White,
      modifier = Modifier.width(44.dp),
    )
    val pop = value(FusionVariables.PRECIP_PROBABILITY)
    Text(
      if (pop != null && pop >= 5) "${pop.toInt()}%" else "",
      style = MaterialTheme.typography.bodySmall,
      color = PageBlue,
      modifier = Modifier.width(52.dp),
    )
    val mm = value(FusionVariables.PRECIPITATION)
    Text(
      if (mm != null && mm >= 0.05) fmt1(mm) else "",
      style = MaterialTheme.typography.bodySmall,
      color = Dim,
      modifier = Modifier.width(44.dp),
    )
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
      val wind = value(FusionVariables.WIND_SPEED)
      Text(wind?.let { "${it.toInt()} km/h" } ?: "—", style = MaterialTheme.typography.bodySmall, color = Dim)
      val direction = hour.windDirectionDeg
      if (direction != null) {
        Spacer(Modifier.width(4.dp))
        Icon(
          imageVector = Icons.Rounded.Navigation,
          contentDescription = null,
          tint = Faint,
          modifier = Modifier
            .size(12.dp)
            .rotate((direction + 180).toFloat()),
        )
      }
    }
    Text(
      value(FusionVariables.HUMIDITY)?.let { "${it.toInt()}%" } ?: "",
      style = MaterialTheme.typography.bodySmall,
      color = Dim,
      modifier = Modifier.width(40.dp),
    )
  }
}
