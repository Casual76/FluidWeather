package dev.pampa.fluidweather.feature.home.pages

import android.content.res.Resources
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.model.WeatherKind
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter
import dev.pampa.fluidweather.feature.home.HomeUiState
import dev.pampa.fluidweather.feature.home.WeatherKindIcon
import dev.pampa.fluidweather.feature.home.isNightAt
import dev.pampa.fluidweather.strings.R
import dev.pampa.fluidweather.strings.UnitFormatter
import dev.pampa.fluidweather.strings.compassPoint

/**
 * Una riga oraria **gia' scritta**: qui non c'e' niente da calcolare, solo da disporre.
 *
 * Prima ogni riga formattava per conto suo — orario, temperatura, probabilita', millimetri, vento,
 * punto cardinale — e ogni `fmtHour` costruiva un `DateTimeFormatter` nuovo passando per ICU. Con
 * 246 righe erano 246 formattatori e 246 letture di risorse per ogni composizione della pagina.
 */
internal class HourlyRow(
  val timestampMillis: Long,
  val hour: String,
  val kind: WeatherKind?,
  /** Di notte il sereno ha la luna, non il sole coi raggi. */
  val night: Boolean,
  val temperature: String,
  val probability: String,
  val precipitation: String,
  val wind: String,
  val windDirectionDeg: Double?,
  val windDescription: String?,
  val humidity: String,
)

/** Un giorno dell'orizzonte, col titolo gia' deciso. */
internal class HourlyDay(val label: String, val rows: List<HourlyRow>)

/**
 * L'orario, precalcolato una volta per ora invece che a ogni ricomposizione. Il filtro, il
 * raggruppamento per giorno e tutte le stringhe si fanno qui, fuori dalla composizione.
 */
internal class HourlyModel(val days: List<HourlyDay>, val providers: Int) {

  companion object {
    fun of(
      state: HomeUiState,
      nowMillis: Long,
      units: UnitFormatter,
      resources: Resources,
      todayLabel: String,
    ): HourlyModel? {
      val hours = state.fusedHours.filter { it.timestampMillis >= nowMillis - 3_600_000L }
      if (hours.isEmpty()) return null
      val today = localDate(nowMillis)
      val days = hours
        .groupBy { localDate(it.timestampMillis) }
        .toSortedMap()
        .map { (date, list) ->
          HourlyDay(
            label = if (date == today) todayLabel else fmtDate(date),
            rows = list.map { hour ->
              fun value(variable: String): Double? = hour.values[variable]?.value
              val pop = value(FusionVariables.PRECIP_PROBABILITY)
              val mm = value(FusionVariables.PRECIPITATION)
              val direction = hour.windDirectionDeg
              HourlyRow(
                timestampMillis = hour.timestampMillis,
                hour = fmtHour(hour.timestampMillis),
                kind = hour.kind,
                night = isNightAt(hour.timestampMillis, state.latitude, state.longitude),
                temperature = value(FusionVariables.TEMPERATURE)?.let { units.degrees(it) } ?: "—",
                probability = if (pop != null && pop >= 5) "${pop.toInt()}%" else "",
                precipitation = if (mm != null && mm >= 0.05) units.precipitationValue(mm) else "",
                wind = value(FusionVariables.WIND_SPEED)?.let { units.wind(it) } ?: "—",
                windDirectionDeg = direction,
                // "da NE": la freccia punta dove va il vento, il nome cardinale dice da dove
                // viene, e senza la preposizione le due cose si contraddicono.
                windDescription = direction?.let {
                  resources.getString(R.string.a11y_wind_from, compassPoint(resources, it))
                },
                humidity = value(FusionVariables.HUMIDITY)?.let { "${it.toInt()}%" } ?: "",
              )
            },
          )
        }
      return HourlyModel(days, state.providersResponding)
    }
  }
}

/**
 * Ora per ora su tutto l'orizzonte fuso, raggruppato per giorno: temperatura, probabilita' e
 * accumulo di pioggia, vento con direzione, umidita'. Ogni riga e' la fusione pesata dei
 * provider che coprono il punto.
 *
 * E' una **lista pigra** e non una colonna, a differenza delle altre pagine, perche' e' l'unica
 * lunga: fino a 246 ore per una decina di nodi l'una. In una `Column` con `verticalScroll` quei
 * ~2.400 nodi si componevano tutti all'apertura e venivano ri-posizionati tutti a ogni fotogramma
 * di scorrimento — misurato su un Galaxy S25, 89 ms per fotogramma in apertura e 85 scorrendo.
 */
internal fun LazyListScope.hourlyPage(model: HourlyModel?) {
  if (model == null) {
    item(key = "hourly-empty") { PageNote(stringResource(R.string.common_waiting_providers)) }
    return
  }
  item(key = "hourly-note") { PageNote(stringResource(R.string.hourly_note, model.providers)) }
  model.days.forEach { day ->
    item(key = "hourly-day-${day.label}") {
      PageSection(day.label)
      HeaderRow()
    }
    items(day.rows, key = { it.timestampMillis }) { HourRow(it) }
  }
}

/** Il modello della pagina, ricalcolato quando cambiano le ore, le unita' o l'ora corrente. */
@Composable
internal fun rememberHourlyModel(state: HomeUiState): HourlyModel? {
  val units = rememberUnitFormatter()
  val resources = LocalContext.current.resources
  val todayLabel = stringResource(R.string.common_today)
  // L'ora al passo dell'ora: `System.currentTimeMillis()` in composizione rendeva la chiave
  // sempre nuova, e con lei si rifaceva tutto.
  val hourStamp = remember(state.fusedHours) { System.currentTimeMillis() / 3_600_000L }
  return remember(state.fusedHours, state.providersResponding, units, hourStamp, todayLabel) {
    HourlyModel.of(state, hourStamp * 3_600_000L, units, resources, todayLabel)
  }
}

@Composable
private fun HeaderRow() {
  val units = rememberUnitFormatter()
  Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
    Text(stringResource(R.string.hourly_col_hour), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(34.dp))
    Spacer(Modifier.width(26.dp))
    Text(units.temperatureSymbol(), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(44.dp))
    Text(stringResource(R.string.hourly_col_rain), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(52.dp))
    Text(units.precipitationSymbol(), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(44.dp))
    Text(stringResource(R.string.hourly_col_wind), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.weight(1f))
    Text(stringResource(R.string.hourly_humidity_short), style = MaterialTheme.typography.labelSmall, color = Faint, modifier = Modifier.width(40.dp))
  }
}

@Composable
private fun HourRow(row: HourlyRow) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
  ) {
    Text(row.hour, style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.width(34.dp))
    WeatherKindIcon(row.kind, size = 18.dp, night = row.night)
    Spacer(Modifier.width(8.dp))
    Text(row.temperature, style = MaterialTheme.typography.titleSmall, color = White, modifier = Modifier.width(44.dp))
    Text(row.probability, style = MaterialTheme.typography.bodySmall, color = PageBlue, modifier = Modifier.width(52.dp))
    Text(row.precipitation, style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.width(44.dp))
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
      Text(row.wind, style = MaterialTheme.typography.bodySmall, color = Dim)
      if (row.windDirectionDeg != null) {
        Spacer(Modifier.width(4.dp))
        Icon(
          imageVector = Icons.Rounded.Navigation,
          contentDescription = row.windDescription,
          tint = Faint,
          modifier = Modifier
            .size(12.dp)
            .rotate((row.windDirectionDeg + 180).toFloat()),
        )
      }
    }
    Text(row.humidity, style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.width(40.dp))
  }
}
