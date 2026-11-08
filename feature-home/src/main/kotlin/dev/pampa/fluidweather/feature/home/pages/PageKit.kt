package dev.pampa.fluidweather.feature.home.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidChip
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.fluidweather.core.model.FusedHour
import dev.pampa.fluidweather.core.ui.BlackSheetNote
import dev.pampa.fluidweather.core.ui.BlackSheetSectionTitle
import dev.pampa.fluidweather.core.ui.Charts
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import dev.pampa.fluidweather.strings.R
import androidx.compose.ui.res.stringResource
import dev.pampa.fluidweather.strings.TimeFormats
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/*
 * Il corredo delle pagine complete (fase 11b): formattazioni, righe di statistica, la curva con
 * le etichette, le pillole di periodo. Tutto in bianco su nero, come il foglio che le ospita.
 */

internal val PageBlue = Color(0xFF8FC7F0)
internal val PageAmber = Color(0xFFF0C060)
internal val PageGreen = Color(0xFF6FD58C)
internal val PageRed = Color(0xFFE06060)

internal val White = Color.White
internal val Dim = Color.White.copy(alpha = 0.7f)
internal val Faint = Color.White.copy(alpha = 0.5f)

internal fun zone(): ZoneId = ZoneId.systemDefault()

internal fun fmtHour(millis: Long): String = TimeFormats.hour(millis, zone())

internal fun fmtTime(millis: Long): String = TimeFormats.time(millis, zone())

internal fun fmtDay(millis: Long): String = TimeFormats.day(millis, zone())

internal fun fmtDate(date: LocalDate): String = TimeFormats.longDate(date)

/** L'ora del giorno come numero: per decidere, non per mostrare (il testo lo fa [fmtHour]). */
internal fun hourOfDay(millis: Long): Int = Instant.ofEpochMilli(millis).atZone(zone()).hour

internal fun fmt1(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

internal fun fmt0(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

internal fun fmtDuration(millis: Long): String = "${millis / 3_600_000L}h ${(millis / 60_000L) % 60}m"

internal fun localDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

@Composable
internal fun PageSection(title: String) = BlackSheetSectionTitle(title)

@Composable
internal fun PageNote(text: String) = BlackSheetNote(text)

/** Progressive disclosure keeps methodology available without displacing the actual metric. */
@Composable
internal fun PageDisclosure(
  title: String = stringResource(R.string.quality_method_sources),
  modifier: Modifier = Modifier,
  content: @Composable ColumnScope.() -> Unit,
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val description = stringResource(if (expanded) R.string.quality_expanded else R.string.quality_collapsed)
  FluidListGroup(modifier = modifier.padding(vertical = 8.dp)) {
    FluidListRow(
      title = title,
      subtitle = "",
      onClick = { expanded = !expanded },
      disclosure = false,
      modifier = Modifier.semantics { stateDescription = description },
      badge = { Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null) },
    )
    if (expanded) Column(
      Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
      content = content,
    )
  }
}

/** Una riga etichetta / valore / dettaglio, allineata come una tabella. */
@Composable
internal fun StatRow(label: String, value: String, detail: String? = null) {
  Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, style = MaterialTheme.typography.bodyMedium, color = Dim, modifier = Modifier.weight(1f))
      Spacer(Modifier.width(8.dp))
      Text(value, style = MaterialTheme.typography.titleSmall, color = White)
    }
    if (detail != null) {
      Text(detail, style = MaterialTheme.typography.bodySmall, color = Faint)
    }
  }
}

/** Un numero grande con la sua unita' e una didascalia sotto. */
@Composable
internal fun BigStat(value: String, unit: String? = null, caption: String? = null, color: Color = White) {
  Column {
    Row(verticalAlignment = Alignment.Bottom) {
      Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light, color = color)
      if (unit != null) {
        Spacer(Modifier.width(6.dp))
        Text(unit, style = MaterialTheme.typography.titleSmall, color = Dim, modifier = Modifier.padding(bottom = 8.dp))
      }
    }
    if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = Faint)
  }
}

/** Una griglia a due colonne di coppie etichetta / valore. */
@Composable
internal fun StatGrid(items: List<Pair<String, String>>) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    items.chunked(2).forEach { pair ->
      Row(Modifier.fillMaxWidth()) {
        pair.forEach { (label, value) ->
          Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Faint)
            Text(value, style = MaterialTheme.typography.titleMedium, color = White)
          }
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

/**
 * La curva morbida con le etichette dell'asse sotto e il massimo/minimo dichiarati: un grafico
 * senza numeri e' un disegno, e la pagina vuole i numeri.
 */
@Composable
internal fun CurveWithLabels(
  values: List<Double>,
  labels: List<String>,
  color: Color,
  unit: String = "",
  height: Dp = 96.dp,
  markerIndex: Int? = null,
  decimals: Int = 0,
  timestamps: List<Long>? = null,
  maxGapMillis: Long = 30 * 60_000L,
) {
  if (values.size < 2) {
    PageNote(stringResource(R.string.page_no_curve))
    return
  }
  val format = if (decimals == 0) "%.0f" else "%.${decimals}f"
  val maxText = String.format(Locale.getDefault(), format, values.max()) + unit
  val minText = String.format(Locale.getDefault(), format, values.min()) + unit
  // Il grafico e' un disegno: a TalkBack si racconta con i suoi due estremi.
  val description = stringResource(R.string.a11y_curve, minText, maxText)
  Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        stringResource(R.string.common_max_prefix) + maxText,
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
      )
      Text(
        stringResource(R.string.common_min_prefix) + minText,
        style = MaterialTheme.typography.labelSmall,
        color = Faint,
      )
    }
    if (timestamps != null) Charts.TimeLine(values, timestamps, maxGapMillis,
      color = color, modifier = Modifier.fillMaxWidth().height(height))
    else Charts.SmoothLine(
      values = values,
      color = color,
      markerIndex = markerIndex,
      modifier = Modifier
        .fillMaxWidth()
        .height(height),
    )
    val axisLabels = if (timestamps != null && timestamps.size >= 2) {
      val start = timestamps.first()
      val end = timestamps.last()
      listOf(start, start + (end - start) / 2, end).map {
        if (end - start > 36 * 3_600_000L) TimeFormats.shortDate(it, zone()) + "\n" + fmtTime(it)
        else fmtTime(it)
      }
    } else labels
    if (axisLabels.isNotEmpty()) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        axisLabels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = Faint) }
      }
    }
    if (timestamps != null && timestamps.zipWithNext().any { (a, b) -> b - a > maxGapMillis }) {
      PageNote(stringResource(R.string.quality_curve_gaps, (maxGapMillis / 60_000).toInt()))
    }
  }
}

/** Le pillole di scelta (periodo, giorno): una sola accesa. */
@Composable
internal fun ChipRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
  Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    options.forEachIndexed { index, label ->
      FluidChip(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
    }
  }
}

/**
 * I punti di una curva oraria e le loro etichette, presi INSIEME.
 *
 * Filtrare i valori con `mapNotNull` e prendere le etichette da tutte le ore disallineava le due
 * cose: se ai provider manca un'ora il punto salta ma l'etichetta resta, e la prima, la mediana e
 * l'ultima finiscono su ore che la curva non contiene. Qui o si tengono entrambi o non si tiene
 * niente.
 */
internal fun curvePoints(hours: List<FusedHour>, variable: String): Pair<List<Double>, List<String>> {
  val kept = curveSamples(hours, variable)
  return kept.map { it.second } to timeLabels(kept.map { it.first })
}

/** Values and their clocks are selected together, including when a variable has gaps. */
internal fun curveSamples(hours: List<FusedHour>, variable: String): List<Pair<Long, Double>> =
  hours.groupBy { it.timestampMillis }.toSortedMap().mapNotNull { (time, samples) ->
    samples.singleOrNull()?.values?.get(variable)?.value?.takeIf { it.isFinite() }?.let { time to it }
  }

/** Le etichette temporali per una serie: prima, meta' e ultima ora. */
internal fun timeLabels(timestamps: List<Long>): List<String> {
  if (timestamps.isEmpty()) return emptyList()
  if (timestamps.size < 3) return timestamps.map { fmtTime(it) }
  val spanHours = (timestamps.last() - timestamps.first()) / 3_600_000.0
  val format: (Long) -> String = if (spanHours > 36) { { fmtDay(it) } } else { { fmtTime(it) } }
  return listOf(format(timestamps.first()), format(timestamps[timestamps.size / 2]), format(timestamps.last()))
}
