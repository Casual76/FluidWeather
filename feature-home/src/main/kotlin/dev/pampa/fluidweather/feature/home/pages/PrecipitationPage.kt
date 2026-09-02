package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pampa.fluidweather.core.model.FusionVariables
import dev.pampa.fluidweather.core.ui.Charts
import dev.pampa.fluidweather.core.ui.PageCharts
import dev.pampa.fluidweather.feature.home.HomeUiState
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.core.ui.rememberUnitFormatter

private val HourColumn = 26.dp

/**
 * La pagina delle precipitazioni: le prossime sei ore (col barometro accanto ai provider), la
 * probabilita' ora per ora su tutto l'orizzonte in una striscia scorrevole, e l'accumulo atteso
 * giorno per giorno.
 */
@Composable
internal fun PrecipitationPage(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val units = rememberUnitFormatter()
  val upcoming = state.fusedHours.filter { it.timestampMillis >= now }
  if (upcoming.isEmpty()) {
    PageNote(stringResource(R.string.common_waiting_providers))
    return
  }

  PageSection(stringResource(R.string.precip_next_6h))
  val next6 = upcoming.take(6)
  val pops6 = next6.map { it.values[FusionVariables.PRECIP_PROBABILITY]?.value ?: 0.0 }
  val mm6 = next6.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
  BigStat(if (mm6 >= 0.1) "~" + units.precipitationValue(mm6) else "0", units.precipitationSymbol(), stringResource(R.string.precip_expected_6h))
  Spacer(Modifier.height(8.dp))
  Charts.ProbabilityBars(
    percentages = pops6,
    color = PageBlue,
    modifier = Modifier
      .fillMaxWidth()
      .height(60.dp),
  )
  Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    next6.forEach { hour ->
      Text(fmtHour(hour.timestampMillis), style = MaterialTheme.typography.labelSmall, color = Faint)
    }
  }
  val verdict = state.verdict
  if (verdict != null) {
    Spacer(Modifier.height(6.dp))
    verdict.windows.forEach { window ->
      StatRow(windowLabel(window.window), "${(window.probability * 100).toInt()}%", stringResource(R.string.precip_local_barometer))
    }
    PageNote(stringResource(R.string.precip_windows_note))
  }

  PageSection(stringResource(R.string.precip_hourly_title))
  val pops = upcoming.map { it.values[FusionVariables.PRECIP_PROBABILITY]?.value ?: 0.0 }
  val dayFormatter = DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())
  Column(Modifier.horizontalScroll(rememberScrollState())) {
    PageCharts.Bars(
      values = pops,
      color = PageBlue,
      maxValue = 100.0,
      modifier = Modifier
        .width(HourColumn * upcoming.size)
        .height(70.dp),
    )
    Row {
      upcoming.forEach { hour ->
        Column(Modifier.width(HourColumn), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            if (hourOfDay(hour.timestampMillis) % 3 == 0) fmtHour(hour.timestampMillis) else " ",
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
          )
        }
      }
    }
    Row {
      upcoming.forEach { hour ->
        Column(Modifier.width(HourColumn)) {
          if (hourOfDay(hour.timestampMillis) == 0 || hour === upcoming.first()) {
            Text(
              dayFormatter.format(localDate(hour.timestampMillis)).replaceFirstChar { it.uppercase() },
              style = MaterialTheme.typography.labelSmall,
              color = Dim,
              maxLines = 1,
              softWrap = false,
            )
          }
        }
      }
    }
  }
  Text(
    stringResource(R.string.precip_scroll_note, upcoming.size),
    style = MaterialTheme.typography.bodySmall,
    color = Faint,
  )

  PageSection(stringResource(R.string.precip_daily_title))
  val byDay = upcoming.groupBy { localDate(it.timestampMillis) }.toSortedMap()
  var total = 0.0
  byDay.forEach { (date, hours) ->
    val mm = hours.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
    val popMax = hours.mapNotNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value }.maxOrNull()
    total += mm
    StatRow(
      fmtDate(date),
      if (mm >= 0.05) units.precipitation(mm) else "—",
      popMax?.let { stringResource(R.string.precip_max_pct, it.toInt()) },
    )
  }
  StatRow(stringResource(R.string.precip_total), units.precipitation(total), stringResource(R.string.common_days_count, byDay.size))
  PageNote(stringResource(R.string.precip_method_note))
}
