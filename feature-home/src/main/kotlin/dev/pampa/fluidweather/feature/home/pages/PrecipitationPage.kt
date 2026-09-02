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

private val HourColumn = 26.dp

/**
 * La pagina delle precipitazioni: le prossime sei ore (col barometro accanto ai provider), la
 * probabilita' ora per ora su tutto l'orizzonte in una striscia scorrevole, e l'accumulo atteso
 * giorno per giorno.
 */
@Composable
internal fun PrecipitationPage(state: HomeUiState) {
  val now = System.currentTimeMillis()
  val upcoming = state.fusedHours.filter { it.timestampMillis >= now }
  if (upcoming.isEmpty()) {
    PageNote("In attesa dei provider…")
    return
  }

  PageSection("Prossime 6 ore")
  val next6 = upcoming.take(6)
  val pops6 = next6.map { it.values[FusionVariables.PRECIP_PROBABILITY]?.value ?: 0.0 }
  val mm6 = next6.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
  BigStat(if (mm6 >= 0.1) "~${fmt1(mm6)}" else "0", "mm", "attesi dai provider nelle prossime sei ore")
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
      StatRow(windowLabel(window.window), "${(window.probability * 100).toInt()}%", "barometro locale")
    }
    PageNote("Le finestre sono il verdetto del barometro del telefono: indipendente dai provider, e per questo interessante quando non e' d'accordo.")
  }

  PageSection("Probabilita' ora per ora, tutto l'orizzonte")
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
        val label = fmtHour(hour.timestampMillis)
        Column(Modifier.width(HourColumn), horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            if (label.toInt() % 3 == 0) label else " ",
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
          )
        }
      }
    }
    Row {
      upcoming.forEach { hour ->
        val label = fmtHour(hour.timestampMillis)
        Column(Modifier.width(HourColumn)) {
          if (label == "00" || hour === upcoming.first()) {
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
    "Scorri per vedere tutto l'orizzonte (${upcoming.size} ore). Le barre sono la probabilita' media pesata dei provider.",
    style = MaterialTheme.typography.bodySmall,
    color = Faint,
  )

  PageSection("Accumulo per giorno")
  val byDay = upcoming.groupBy { localDate(it.timestampMillis) }.toSortedMap()
  var total = 0.0
  byDay.forEach { (date, hours) ->
    val mm = hours.sumOf { it.values[FusionVariables.PRECIPITATION]?.value ?: 0.0 }
    val popMax = hours.mapNotNull { it.values[FusionVariables.PRECIP_PROBABILITY]?.value }.maxOrNull()
    total += mm
    StatRow(
      fmtDate(date),
      if (mm >= 0.05) "${fmt1(mm)} mm" else "—",
      popMax?.let { "max ${it.toInt()}%" },
    )
  }
  StatRow("Totale sull'orizzonte", "${fmt1(total)} mm", "${byDay.size} giorni")
  PageNote("L'accumulo e' la somma delle ore fuse; la probabilita' e' la media pesata dei provider che la dichiarano.")
}
